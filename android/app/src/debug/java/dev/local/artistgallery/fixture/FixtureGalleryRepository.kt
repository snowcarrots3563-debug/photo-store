package dev.local.artistgallery.fixture

import dev.local.artistgallery.data.GalleryRepository
import dev.local.artistgallery.data.NameRules
import dev.local.artistgallery.model.*
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** 数据仅用于 D 对照；图片数由独立中性样例引用推导，不能作为真实导入证据。 */
class FixtureGalleryRepository(scenario: String = "baseline") : GalleryRepository {
    private data class Artist(val id: String, val name: String)
    private data class Collection(val id: String, val artistId: String, val name: String, val temporary: Boolean = false, val assets: List<String> = emptyList())
    private data class Asset(val video: Boolean, val cover: SampleCoverKey?)
    private data class Snapshot(val artists: List<Artist>, val collections: List<Collection>)
    private val assets = mapOf(
        "p1" to Asset(false, SampleCoverKey.Scenery1), "p2" to Asset(false, SampleCoverKey.Scenery2),
        "p10" to Asset(false, SampleCoverKey.Scenery3), "cover" to Asset(false, SampleCoverKey.Scenery2),
        "gif" to Asset(false, null), "video" to Asset(true, null),
    )
    private val snapshots = MutableStateFlow(if (scenario == "empty" || scenario == "save-error") Snapshot(emptyList(), emptyList()) else Snapshot(
        listOf(Artist("a1", "墨岚"), Artist("a2", "纸月"), Artist("a3", "把日子画成很长很长的散步日记")),
        listOf(
            Collection("c1", "a1", "山间来信", assets = listOf("p10", "p1", "p2", "gif", "video")),
            Collection("c2", "a1", "沿途收藏", assets = listOf("cover", "p1")),
            Collection("c3", "a2", "一起经过的地方", assets = listOf("cover")),
            Collection("c4", "a2", "未完成的散步"),
        ),
    ))
    private val mutex = Mutex()
    private var failRead = scenario == "read-error"
    private var failSave = scenario == "save-error"
    private fun cover(ids: List<String>): CoverRef = ids.firstNotNullOfOrNull { assets[it]?.cover }?.let(CoverRef::Sample) ?: CoverRef.None
    private fun imageCount(ids: List<String>) = ids.distinct().count { assets[it]?.video == false }
    private fun Collection.summary() = CollectionSummary(id, artistId, name, temporary, imageCount(assets), assets.count { this@FixtureGalleryRepository.assets[it]?.video == true }, cover(assets))
    private fun Snapshot.artistSummary(artist: Artist): ArtistSummary {
        val groups = collections.filter { it.artistId == artist.id }
        val references = groups.flatMap { it.assets }.distinct()
        return ArtistSummary(artist.id, artist.name, groups.size, imageCount(references), cover(references))
    }
    override fun observeArtists(): Flow<List<ArtistSummary>> = flow {
        if (failRead) { failRead = false; throw IOException("调试验收：一次读取失败") }
        emitAll(snapshots.map { s -> s.artists.map { s.artistSummary(it) } })
    }
    override fun observeArtist(id: String): Flow<ArtistDetail?> = snapshots.map { s ->
        s.artists.find { it.id == id }?.let { ArtistDetail(s.artistSummary(it), s.collections.filter { it.artistId == id }.map { it.summary() }) }
    }
    override fun observeCollection(id: String): Flow<CollectionSummary?> = snapshots.map { s -> s.collections.find { it.id == id }?.summary() }
    private suspend fun save(block: () -> SaveResult): SaveResult = mutex.withLock {
        if (failSave) { failSave = false; SaveResult.StorageFailure } else block()
    }
    override suspend fun createArtist(name: String): SaveResult = save {
        val normalized = name.trim()
        if (normalized.isEmpty()) SaveResult.InvalidName else {
            val id = UUID.randomUUID().toString()
            snapshots.value = snapshots.value.let { it.copy(artists = it.artists + Artist(id, normalized)) }
            SaveResult.Saved(id)
        }
    }
    override suspend fun renameArtist(id: String, name: String): SaveResult = save {
        val s = snapshots.value
        if (s.artists.none { it.id == id }) return@save SaveResult.NotFound
        val normalized = NameRules.normalize(name)
        if (normalized.isEmpty()) return@save SaveResult.InvalidName
        snapshots.value = s.copy(artists = s.artists.map { if (it.id == id) it.copy(name = normalized) else it })
        SaveResult.Saved(id)
    }
    override suspend fun createCollection(artistId: String, name: String): SaveResult = save {
        val s = snapshots.value
        if (s.artists.none { it.id == artistId }) return@save SaveResult.NotFound
        val existing = s.collections.filter { it.artistId == artistId }.map { it.name }
        val resolved = name.trim().ifEmpty { NameRules.nextTemporaryName(existing) }
        if (resolved in existing) return@save SaveResult.DuplicateCollectionName
        val id = UUID.randomUUID().toString()
        snapshots.value = s.copy(collections = s.collections + Collection(id, artistId, resolved, name.isBlank()))
        SaveResult.Saved(id)
    }
    override suspend fun renameCollection(id: String, name: String): SaveResult = save {
        val s = snapshots.value
        val current = s.collections.find { it.id == id } ?: return@save SaveResult.NotFound
        val sameArtist = s.collections.filter { it.artistId == current.artistId }
        val resolved = name.trim().ifEmpty { NameRules.nextTemporaryName(sameArtist.map { it.name }) }
        if (sameArtist.any { it.id != id && it.name == resolved }) return@save SaveResult.DuplicateCollectionName
        snapshots.value = s.copy(collections = s.collections.map { if (it.id == id) it.copy(name = resolved, temporary = name.isBlank()) else it })
        SaveResult.Saved(id)
    }
}
