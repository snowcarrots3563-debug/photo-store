package dev.local.artistgallery.data.backup

import android.content.Context
import android.net.Uri
import android.os.StatFs
import android.system.Os
import android.system.OsConstants
import androidx.room.withTransaction
import dev.local.artistgallery.data.ArtistEntity
import dev.local.artistgallery.data.BrowseOptions
import dev.local.artistgallery.data.BrowsePreferences
import dev.local.artistgallery.data.CollectionEntity
import dev.local.artistgallery.data.GalleryDatabase
import dev.local.artistgallery.data.MediaAssetEntity
import dev.local.artistgallery.data.importing.MediaFileStore
import dev.local.artistgallery.model.MediaSort
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.DigestInputStream
import java.security.DigestOutputStream
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

class BackupRestorer(
    private val context: Context,
    private val database: GalleryDatabase,
    private val files: MediaFileStore,
    private val preferences: BrowsePreferences,
) {
    private val journals = File(context.filesDir, "gallery-restore-journals")
    private val archives get() = File(context.getExternalFilesDir(null) ?: throw IOException("应用专属存储暂不可用。"), ".artistgallery-restores")
    private val dao get() = database.galleryDao()

    suspend fun prepare(uri: Uri, onProgress: (String) -> Unit): PreparedRestore = withContext(Dispatchers.IO) {
        val id = UUID.randomUUID().toString()
        var journal = RestoreJournal(id)
        try {
            writeJournal(journal)
            onProgress("正在读取备份文件…")
            copyArchive(uri, archiveFile(id))
            BackupArchiveReader(archiveFile(id)).use { reader ->
                val job = currentCoroutineContext()
                val manifest = BackupManifestCodec.decode(reader.manifestBytes { job.ensureActive() })
                BackupValidation.validate(manifest)
                reader.validateEntries(manifest)
                if (manifest.totalBytes > availableForFiles()) throw IOException("可用空间不足，无法暂存全部备份原图。")
                val assetIds = RestoreRules.assetIds(manifest.assets.map { it.id }, List(manifest.assets.size) { UUID.randomUUID().toString() })
                val staged = manifest.assets.associate { asset ->
                    asset.id to StagedRestoreAsset(assetIds.getValue(asset.id), UUID.randomUUID().toString(), UUID.randomUUID().toString())
                }
                journal = journal.copy(manifest = manifest, assets = staged)
                // All owned UUIDs are durable before the first media write.
                writeJournal(journal)
                manifest.assets.forEachIndexed { index, asset ->
                    job.ensureActive()
                    onProgress("正在校验原图 ${index + 1} / ${manifest.assets.size}…")
                    if (asset.byteSize > availableForFiles()) throw IOException("可用空间不足，无法暂存备份原图。")
                    val target = staged.getValue(asset.id)
                    if (files.hasStaging(target.stagingKey) || files.hasOriginal(target.storageKey)) throw IOException("恢复暂存文件标识冲突。")
                    val written = files.writeToStaging(target.stagingKey) { output ->
                        reader.extract(asset.entryPath, output) { job.ensureActive() }
                    }
                    if (written.byteSize != asset.byteSize || written.sha256 != asset.sha256) throw IOException("备份原图校验失败：${asset.originalName}")
                    files.validateStagedMedia(target.stagingKey, asset.mediaType)
                }
                PreparedRestore(manifest, staged, archiveFile(id))
            }
        } catch (error: Throwable) {
            withContext(NonCancellable) { runCatching { cleanup(journal, committed = false) }.exceptionOrNull()?.let(error::addSuppressed) }
            throw error
        }
    }

    suspend fun confirm(prepared: PreparedRestore): RestoreOutcome = withContext(Dispatchers.IO) {
        var journal = preparedJournal(prepared)
        val manifest = journal.manifest ?: throw IOException("恢复预览已失效，请重新选择备份。")
        var committed = false
        val promoted = HashSet<String>()
        try {
            if (journal.artists.isNotEmpty() || journal.collections.isNotEmpty()) throw IOException("此备份正在恢复，请重新打开恢复结果。")
            journal = journal.copy(
                artists = manifest.artists.associate { it.id to UUID.randomUUID().toString() },
                collections = manifest.collections.associate { it.id to UUID.randomUUID().toString() },
            )
            writeJournal(journal)
            manifest.assets.forEach { asset ->
                currentCoroutineContext().ensureActive()
                val target = journal.assets.getValue(asset.id)
                val staged = files.inspectStaging(target.stagingKey)
                if (staged.byteSize != asset.byteSize || staged.sha256 != asset.sha256) throw IOException("恢复暂存原图已改变，请重新选择备份。")
                files.promote(target.stagingKey, target.storageKey)
                promoted += target.storageKey
            }
            currentCoroutineContext().ensureActive()
            // Once the commit begins, cancellation cannot report failure after rows were saved.
            withContext(NonCancellable) {
                database.withTransaction {
                    val rows = RestoreRules.rows(manifest, journal.assets, journal.artists, journal.collections, dao.allArtists())
                    rows.artists.forEach { dao.insertArtist(it) }
                    rows.collections.forEach { dao.insertCollection(it) }
                    rows.assets.forEach { dao.insertAsset(it) }
                    rows.references.forEach { dao.insertCollectionMedia(it) }
                }
                committed = true
                finishCommitted(journal)
            }
            RestoreOutcome(manifest.artists.size, manifest.collections.size, manifest.assets.size, manifest.references.size)
        } catch (error: Throwable) {
            if (committed) {
                // The database is already committed; retain its journal for finalization retry.
                return@withContext RestoreOutcome(manifest.artists.size, manifest.collections.size, manifest.assets.size, manifest.references.size,
                    warning = "原图与整理关系已恢复，设置或临时文件清理尚未完成，将在下次启动时重试。")
            }
            withContext(NonCancellable) { runCatching { cleanup(journal, committed = false, promoted = promoted) }.exceptionOrNull()?.let(error::addSuppressed) }
            throw error
        }
    }

    suspend fun discard(prepared: PreparedRestore) = withContext(Dispatchers.IO + NonCancellable) {
        val journal = preparedJournal(prepared)
        if (wasCommitted(journal)) finishCommitted(journal) else cleanup(journal, committed = false)
    }

    suspend fun recover() = withContext(Dispatchers.IO + NonCancellable) {
        journals.listFiles()?.filter { it.name.endsWith(".journal") }?.forEach { file ->
            val id = file.name.removeSuffix(".journal")
            if (!isUuid(id)) return@forEach
            val journal = readJournal(id)
            if (wasCommitted(journal)) finishCommitted(journal) else cleanup(journal, committed = false)
        }
        // A pending journal is never authoritative: no file writes begin before its rename.
        journals.listFiles()?.filter { it.name.endsWith(".pending") && isUuid(it.name.removeSuffix(".pending")) }?.forEach { it.delete() }
    }

    private suspend fun finishCommitted(journal: RestoreJournal) {
        val manifest = journal.manifest ?: return
        val existing = dao.allArtists()
        val collections = dao.allCollections()
        val current = preferences.options.value
        val known = existing.map { it.id }.toSet()
        val restored = journal.artists.values.filter { it in known }
        val knownCollections = collections.map { it.id }.toSet()
        val restoredCollections = journal.collections.values.filter { it in knownCollections }
        if (!restored.all { it in current.artistOrder && it in current.artistLayouts && it in current.artistImageSorts } ||
            !restoredCollections.all { it in current.collectionMediaSorts }) {
            preferences.saveDurably(RestoreRules.preferences(current, manifest, journal.artists, journal.collections, existing, collections))
        }
        cleanup(journal, committed = true)
    }

    private suspend fun wasCommitted(journal: RestoreJournal): Boolean {
        if (journal.artists.values.any { dao.getArtist(it) != null }) return true
        if (journal.collections.values.any { dao.getCollection(it) != null }) return true
        return journal.assets.values.any { target -> dao.getAsset(target.assetId)?.storageKey == target.storageKey }
    }

    private suspend fun cleanup(journal: RestoreJournal, committed: Boolean, promoted: Set<String>? = null) {
        var clean = true
        journal.assets.values.forEach { target ->
            clean = files.deleteStaging(target.stagingKey) && clean
            if (!committed && files.hasOriginal(target.storageKey) && (promoted == null || target.storageKey in promoted)) {
                // Never delete a file currently referenced by any database row.
                if (promoted != null || dao.getAssetByStorageKey(target.storageKey) == null) clean = files.deleteOriginal(target.storageKey) && clean
            }
        }
        val archive = archiveFile(journal.id)
        clean = (!archive.exists() || archive.delete()) && clean
        val pending = File(journals, "${requireUuid(journal.id)}.pending")
        clean = (!pending.exists() || pending.delete()) && clean
        if (clean) {
            val saved = journalFile(journal.id)
            if (saved.exists() && !saved.delete()) throw IOException("恢复临时记录清理失败，将在下次启动重试。")
            syncJournalDirectory()
        } else throw IOException("恢复临时文件清理失败，将在下次启动重试。")
    }

    private suspend fun copyArchive(uri: Uri, target: File) {
        val limit = availableForFiles()
        if (limit <= 0) throw IOException("可用空间不足，无法读取备份。")
        if (!archives.exists() && !archives.mkdirs()) throw IOException("无法创建恢复暂存目录。")
        if (target.exists()) throw IOException("恢复暂存文件标识冲突。")
        val job = currentCoroutineContext()
        val source = context.contentResolver.openInputStream(uri) ?: throw IOException("无法读取所选备份。")
        source.use { input ->
            BufferedInputStream(input, BUFFER_SIZE).use { buffered ->
                FileOutputStream(target).use { output ->
                    BufferedOutputStream(output, BUFFER_SIZE).use { destination ->
                        val buffer = ByteArray(BUFFER_SIZE)
                        var copied = 0L
                        while (true) {
                            job.ensureActive()
                            val read = buffered.read(buffer)
                            if (read < 0) break
                            if (read.toLong() > limit - copied) throw IOException("备份文件超过可用暂存空间。")
                            destination.write(buffer, 0, read)
                            copied += read
                        }
                        destination.flush()
                        output.fd.sync()
                    }
                }
            }
        }
    }

    private fun availableForFiles(): Long = (files.availableBytes() - SPACE_RESERVE).coerceAtLeast(0)
    private fun archiveFile(id: String): File = File(archives, "${requireUuid(id)}.zip")
    private fun journalFile(id: String): File = File(journals, "${requireUuid(id)}.journal")

    private fun preparedJournal(prepared: PreparedRestore): RestoreJournal {
        val id = prepared.archiveFile.name.removeSuffix(".zip")
        if (!isUuid(id) || prepared.archiveFile.canonicalFile != archiveFile(id).canonicalFile) throw IOException("恢复预览已失效。")
        val journal = readJournal(id)
        if (journal.manifest != prepared.manifest || journal.assets != prepared.stagedAssets) throw IOException("恢复预览与暂存记录不一致。")
        return journal
    }

    private fun writeJournal(journal: RestoreJournal) {
        if (!journals.exists() && !journals.mkdirs()) throw IOException("无法保存恢复记录。")
        val manifest = journal.manifest?.let(BackupManifestCodec::encode) ?: byteArrayOf()
        if (manifest.size > BackupValidation.MAX_MANIFEST_BYTES || StatFs(journals.absolutePath).availableBytes < SPACE_RESERVE + manifest.size + journal.assets.size * 180L + (journal.artists.size + journal.collections.size) * 80L) {
            throw IOException("可用空间不足，无法保存恢复记录。")
        }
        val pending = File(journals, "${requireUuid(journal.id)}.pending")
        FileOutputStream(pending).use { file ->
            val digest = MessageDigest.getInstance("SHA-256")
            val hashed = DigestOutputStream(BufferedOutputStream(file), digest)
            DataOutputStream(hashed).use { output ->
                output.writeInt(JOURNAL_VERSION)
                output.writeUTF(journal.id)
                output.writeInt(manifest.size)
                output.write(manifest)
                output.writeInt(journal.assets.size)
                journal.assets.forEach { (oldId, asset) ->
                    output.writeUTF(oldId); output.writeUTF(asset.assetId); output.writeUTF(asset.stagingKey); output.writeUTF(asset.storageKey)
                }
                fun mappings(values: Map<String, String>) {
                    output.writeInt(values.size)
                    values.forEach { (oldId, newId) -> output.writeUTF(oldId); output.writeUTF(newId) }
                }
                mappings(journal.artists); mappings(journal.collections)
                output.flush()
                hashed.on(false)
                output.write(digest.digest())
                output.flush()
                file.fd.sync()
            }
        }
        Files.move(pending.toPath(), journalFile(journal.id).toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        syncJournalDirectory()
    }

    private fun readJournal(id: String): RestoreJournal {
        val file = journalFile(id)
        val maximum = BackupValidation.MAX_MANIFEST_BYTES + BackupValidation.MAX_RECORDS * 340L + 1024L
        if (file.length() !in 1L..maximum) throw IOException("恢复记录大小无效。")
        val digest = MessageDigest.getInstance("SHA-256")
        val hashed = DigestInputStream(BufferedInputStream(FileInputStream(file)), digest)
        return DataInputStream(hashed).use { input ->
            if (input.readInt() != JOURNAL_VERSION || input.readUTF() != id) throw IOException("恢复记录已损坏，未修改现有图库。")
            val size = input.readInt()
            if (size !in 0..BackupValidation.MAX_MANIFEST_BYTES) throw IOException("恢复记录大小无效。")
            val bytes = ByteArray(size).also { input.readFully(it) }
            val manifest = if (size == 0) null else BackupManifestCodec.decode(bytes).also(BackupValidation::validate)
            fun count(): Int = input.readInt().also { if (it !in 0..BackupValidation.MAX_RECORDS) throw IOException("恢复记录数量无效。") }
            val assets = LinkedHashMap<String, StagedRestoreAsset>()
            repeat(count()) {
                val oldId = requireUuid(input.readUTF())
                val asset = StagedRestoreAsset(requireUuid(input.readUTF()), requireUuid(input.readUTF()), requireUuid(input.readUTF()))
                if (assets.put(oldId, asset) != null) throw IOException("恢复记录存在重复文件。")
            }
            fun mappings(): Map<String, String> {
                val values = LinkedHashMap<String, String>()
                repeat(count()) {
                    val oldId = requireUuid(input.readUTF())
                    if (values.put(oldId, requireUuid(input.readUTF())) != null) throw IOException("恢复记录存在重复标识。")
                }
                if (values.values.distinct().size != values.size) throw IOException("恢复记录存在重复标识。")
                return values
            }
            val artists = mappings()
            val collections = mappings()
            hashed.on(false)
            val expectedHash = ByteArray(32).also { input.readFully(it) }
            if (!MessageDigest.isEqual(digest.digest(), expectedHash)) throw IOException("恢复记录校验失败，未修改现有图库。")
            val allFileIds = assets.values.flatMap { listOf(it.assetId, it.stagingKey, it.storageKey) }
            if (allFileIds.distinct().size != allFileIds.size || assets.keys != manifest?.assets?.map { it.id }?.toSet().orEmpty() ||
                artists.isNotEmpty() && artists.keys != manifest?.artists?.map { it.id }?.toSet().orEmpty() ||
                collections.isNotEmpty() && collections.keys != manifest?.collections?.map { it.id }?.toSet().orEmpty() || input.read() != -1) {
                throw IOException("恢复记录与备份清单不一致。")
            }
            RestoreJournal(id, manifest, assets, artists, collections)
        }
    }

    private fun syncJournalDirectory() {
        if (!journals.exists()) return
        val descriptor = Os.open(journals.absolutePath, OsConstants.O_RDONLY, 0)
        try { Os.fsync(descriptor) } finally { Os.close(descriptor) }
    }

    private data class RestoreJournal(
        val id: String,
        val manifest: BackupManifest? = null,
        val assets: Map<String, StagedRestoreAsset> = emptyMap(),
        val artists: Map<String, String> = emptyMap(),
        val collections: Map<String, String> = emptyMap(),
    )

    private companion object {
        const val SPACE_RESERVE = 64L * 1024 * 1024
        const val BUFFER_SIZE = 128 * 1024
        const val JOURNAL_VERSION = 1
        fun isUuid(value: String): Boolean = runCatching { UUID.fromString(value).toString() == value }.getOrDefault(false)
        fun requireUuid(value: String): String = value.also { if (!isUuid(it)) throw IOException("恢复文件标识无效。") }
    }
}

internal data class RestoreRows(
    val artists: List<ArtistEntity>,
    val collections: List<CollectionEntity>,
    val assets: List<MediaAssetEntity>,
    val references: List<dev.local.artistgallery.data.CollectionMediaEntity>,
)

internal object RestoreRules {
    fun assetIds(sourceIds: List<String>, candidates: List<String>): Map<String, String> {
        require(sourceIds.size == candidates.size && candidates.distinct().size == candidates.size)
        // Tied createdAt/manualOrder values fall back to asset ID in the gallery queries.
        return sourceIds.sorted().zip(candidates.sorted()).toMap()
    }

    fun rows(
        manifest: BackupManifest,
        staged: Map<String, StagedRestoreAsset>,
        artistIds: Map<String, String>,
        collectionIds: Map<String, String>,
        currentArtists: List<ArtistEntity>,
    ): RestoreRows {
        val artistNames = currentArtists.map { it.name }.toMutableSet()
        val artists = manifest.artists.map { artist ->
            artist.copy(id = artistIds.getValue(artist.id), name = uniqueName(artist.name, artistNames), coverAssetId = artist.coverAssetId?.let { staged.getValue(it).assetId })
        }
        val collectionNames = HashMap<String, MutableSet<String>>()
        val collections = manifest.collections.map { collection ->
            collection.copy(id = collectionIds.getValue(collection.id), artistId = artistIds.getValue(collection.artistId), name = uniqueName(collection.name, collectionNames.getOrPut(collection.artistId) { HashSet() }))
        }
        val assets = manifest.assets.map { asset ->
            val target = staged.getValue(asset.id)
            MediaAssetEntity(target.assetId, asset.originalName, asset.mediaType, asset.byteSize, asset.width, asset.height, asset.sha256, target.storageKey, "READY", asset.createdAt)
        }
        val references = manifest.references.map { it.copy(collectionId = collectionIds.getValue(it.collectionId), assetId = staged.getValue(it.assetId).assetId) }
        return RestoreRows(artists, collections, assets, references)
    }

    fun preferences(
        current: BrowseOptions,
        manifest: BackupManifest,
        artistIds: Map<String, String>,
        collectionIds: Map<String, String>,
        artists: List<ArtistEntity>,
        collections: List<CollectionEntity>,
    ): BrowseOptions {
        val knownArtists = artists.map { it.id }.toSet()
        val knownCollections = collections.map { it.id }.toSet()
        val restoredArtists = artistIds.values.toSet()
        val currentOrder = (current.artistOrder + artists.sortedWith(compareBy({ it.createdAt }, { it.id })).map { it.id })
            .distinct().filter { it in knownArtists && it !in restoredArtists }
        val restoredOrder = (manifest.settings.artistOrder + manifest.artists.sortedWith(compareBy({ it.createdAt }, { it.id })).map { it.id })
            .distinct().mapNotNull { artistIds[it] }.filter { it in knownArtists }
        val collectionsByArtist = manifest.collections.groupBy { it.artistId }
        val restoredCollections = manifest.artists.mapNotNull { artist ->
            artistIds[artist.id]?.takeIf { it in knownArtists }?.let { newArtist ->
                val order = (manifest.settings.collectionOrders[artist.id].orEmpty() +
                    collectionsByArtist[artist.id].orEmpty().sortedWith(compareBy({ it.createdAt }, { it.id })).map { it.id })
                    .distinct().mapNotNull { collectionIds[it] }.filter { it in knownCollections }
                newArtist to order
            }
        }.toMap()
        val restoredLayouts = manifest.artists.mapNotNull { artist ->
            artistIds[artist.id]?.takeIf { it in knownArtists }?.let { newArtist -> newArtist to (manifest.settings.artistLayouts[artist.id] ?: manifest.settings.imageLayout) }
        }.toMap()
        val restoredSorts = manifest.artists.mapNotNull { artist ->
            artistIds[artist.id]?.takeIf { it in knownArtists }?.let { newArtist -> newArtist to (manifest.settings.artistImageSorts[artist.id] ?: MediaSort.Default) }
        }.toMap()
        val restoredMediaSorts = manifest.collections.mapNotNull { collection ->
            collectionIds[collection.id]?.takeIf { it in knownCollections }?.let { newCollection ->
                newCollection to (manifest.settings.collectionMediaSorts[collection.id] ?: MediaSort.Default)
            }
        }.toMap()
        return current.copy(
            artistOrder = currentOrder + restoredOrder,
            collectionOrders = current.collectionOrders + restoredCollections,
            artistLayouts = current.artistLayouts + restoredLayouts,
            artistImageSorts = current.artistImageSorts + restoredSorts,
            collectionMediaSorts = current.collectionMediaSorts + restoredMediaSorts,
        )
    }

    private fun uniqueName(name: String, used: MutableSet<String>): String {
        if (used.add(name)) return name
        var index = 2
        while (true) {
            val suffix = "（$index）"
            var end = minOf(name.length, 4096 - suffix.length)
            if (end > 0 && Character.isHighSurrogate(name[end - 1])) end--
            val candidate = name.substring(0, end) + suffix
            if (used.add(candidate)) return candidate
            index++
        }
    }
}
