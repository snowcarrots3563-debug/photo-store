package dev.local.artistgallery.data.backup

import dev.local.artistgallery.data.ArtistEntity
import dev.local.artistgallery.data.BrowseOptions
import dev.local.artistgallery.data.CollectionEntity
import dev.local.artistgallery.data.CollectionMediaEntity
import java.io.File
import java.io.IOException
import java.util.UUID

data class BackupAsset(
    val id: String,
    val originalName: String,
    val mediaType: String,
    val byteSize: Long,
    val width: Int?,
    val height: Int?,
    val sha256: String,
    val createdAt: Long,
) {
    val entryPath: String get() = "media/$id"
}

data class BackupManifest(
    val version: Int = 1,
    val createdAt: Long,
    val assets: List<BackupAsset>,
    val artists: List<ArtistEntity>,
    val collections: List<CollectionEntity>,
    val references: List<CollectionMediaEntity>,
    val settings: BrowseOptions,
) {
    val totalBytes: Long get() = assets.fold(0L) { sum, asset -> Math.addExact(sum, asset.byteSize) }
}

data class StagedRestoreAsset(val assetId: String, val stagingKey: String, val storageKey: String)
data class PreparedRestore(val manifest: BackupManifest, val stagedAssets: Map<String, StagedRestoreAsset>, val archiveFile: File)
data class RestoreOutcome(val artistCount: Int, val collectionCount: Int, val assetCount: Int, val referenceCount: Int, val warning: String? = null)

object BackupValidation {
    const val MANIFEST_NAME = "gallery-backup.json"
    const val MAX_MANIFEST_BYTES = 16 * 1024 * 1024
    const val MAX_RECORDS = 100_000

    fun validate(manifest: BackupManifest) {
        requireBackup(manifest.version == 1, "备份版本不兼容，请使用支持此版本的应用。")
        requireBackup(listOf(manifest.assets.size, manifest.artists.size, manifest.collections.size, manifest.references.size).all { it <= MAX_RECORDS }, "备份记录过多。")
        fun ids(values: List<String>): Set<String> {
            requireBackup(values.all { runCatching { UUID.fromString(it).toString() == it }.getOrDefault(false) }, "备份中的标识无效。")
            requireBackup(values.toSet().size == values.size, "备份中存在重复标识。")
            return values.toSet()
        }
        fun name(value: String) = requireBackup(value.isNotBlank() && value.length <= 4096 && '\u0000' !in value, "备份名称无效。")
        val assetIds = ids(manifest.assets.map { it.id })
        val artistIds = ids(manifest.artists.map { it.id })
        val collectionIds = ids(manifest.collections.map { it.id })
        manifest.assets.forEach {
            name(it.originalName)
            requireBackup(it.mediaType in setOf("IMAGE", "GIF", "VIDEO") && it.byteSize > 0 && it.sha256.matches(Regex("[0-9a-f]{64}")), "备份媒体信息无效。")
            requireBackup((it.width == null || it.width > 0) && (it.height == null || it.height > 0), "备份图片尺寸无效。")
        }
        runCatching { manifest.totalBytes }.getOrElse { throw IOException("备份大小无效。") }
        manifest.artists.forEach { name(it.name); requireBackup(it.coverAssetId == null || it.coverAssetId in assetIds, "备份封面引用无效。") }
        val defaultArtists = HashSet<String>()
        manifest.collections.forEach {
            name(it.name); requireBackup(it.artistId in artistIds, "备份作品集归属无效。")
            requireBackup(!it.isDefault || defaultArtists.add(it.artistId), "同一画师不能有多个默认图集。")
        }
        val pairs = HashSet<Pair<String, String>>()
        manifest.references.forEach {
            name(it.sourceName)
            requireBackup(it.collectionId in collectionIds && it.assetId in assetIds && it.manualOrder >= 0 && pairs.add(it.collectionId to it.assetId), "备份图片引用或排序无效。")
        }
        val collectionArtists = manifest.collections.associate { it.id to it.artistId }
        val imageIds = manifest.assets.filter { it.mediaType != "VIDEO" }.map { it.id }.toSet()
        val covers = manifest.references.map { collectionArtists[it.collectionId] to it.assetId }.toSet()
        manifest.artists.forEach { requireBackup(it.coverAssetId == null || (it.id to it.coverAssetId) in covers && it.coverAssetId in imageIds, "备份封面不属于对应画师。") }
        requireBackup(manifest.settings.artistOrder.distinct().size == manifest.settings.artistOrder.size && manifest.settings.artistOrder.all { it in artistIds }, "备份画师排序无效。")
        requireBackup(manifest.settings.artistLayouts.keys.all { it in artistIds }, "备份视图归属无效。")
        requireBackup(manifest.settings.artistImageSorts.keys.all { it in artistIds }, "备份图片排序归属无效。")
        requireBackup(manifest.settings.collectionMediaSorts.keys.all { it in collectionIds }, "备份图集内排序归属无效。")
        manifest.settings.collectionOrders.forEach { (id, order) ->
            requireBackup(id in artistIds && order.distinct().size == order.size && order.all { collectionArtists[it] == id }, "备份作品集排序无效。")
        }
    }

    private fun requireBackup(condition: Boolean, message: String) { if (!condition) throw IOException(message) }
}
