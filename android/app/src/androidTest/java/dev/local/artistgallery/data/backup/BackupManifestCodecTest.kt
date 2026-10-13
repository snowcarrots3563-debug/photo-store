package dev.local.artistgallery.data.backup

import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.local.artistgallery.data.ArtistEntity
import dev.local.artistgallery.data.ArtistImageLayout
import dev.local.artistgallery.data.BrowseOptions
import dev.local.artistgallery.data.CollectionEntity
import dev.local.artistgallery.data.CollectionMediaEntity
import dev.local.artistgallery.model.MediaSort
import java.io.IOException
import java.util.UUID
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BackupManifestCodecTest {
    private fun fixture(): BackupManifest {
        val a = UUID.randomUUID().toString(); val c = UUID.randomUUID().toString(); val m = UUID.randomUUID().toString()
        return BackupManifest(createdAt = 10,
            assets = listOf(BackupAsset(m, "原始画作.gif", "GIF", 3, null, null, "a".repeat(64), 2)),
            artists = listOf(ArtistEntity(a, "画师 😀", 1, 3, m)),
            collections = listOf(CollectionEntity(c, a, "第1册", false, 2, 4)),
            references = listOf(CollectionMediaEntity(c, m, 5, "第1页.gif", null)),
            settings = BrowseOptions(cardEffects = true, playGifs = false, swipeDelete = false, artistOrder = listOf(a), collectionOrders = mapOf(a to listOf(c)), artistLayouts = mapOf(a to ArtistImageLayout.Large), storageNoticeDismissed = true))
    }
    @Test fun fullManifestRoundTripPreservesRelationshipsNullsAndSettings() { val m = fixture(); assertEquals(m, BackupManifestCodec.decode(BackupManifestCodec.encode(m))) }
    @Test fun wrongFormatAndFutureVersionAreRejected() {
        val json = JSONObject(BackupManifestCodec.encode(fixture()).toString(Charsets.UTF_8))
        json.put("format", "ordinary-export")
        assertThrows(IOException::class.java) { BackupManifestCodec.decode(json.toString().toByteArray()) }
        json.put("format", "artist-gallery-backup").put("version", 2)
        assertThrows(IOException::class.java) { BackupManifestCodec.decode(json.toString().toByteArray()) }
    }
    @Test fun corruptAndOversizedJsonAreRejected() {
        assertThrows(IOException::class.java) { BackupManifestCodec.decode("{broken".toByteArray()) }
        assertThrows(IOException::class.java) { BackupManifestCodec.decode(ByteArray(BackupValidation.MAX_MANIFEST_BYTES + 1)) }
    }
    @Test fun allArtistSortsRoundTripInVersionOne() {
        val m = fixture()
        MediaSort.entries.forEach { sort ->
            val expected = m.copy(settings = m.settings.copy(artistImageSorts = mapOf(m.artists.single().id to sort)))
            assertEquals(expected, BackupManifestCodec.decode(BackupManifestCodec.encode(expected)))
        }
    }
    @Test fun oldVersionOneWithoutArtistSortFieldRemainsReadable() {
        val m = fixture()
        val json = JSONObject(BackupManifestCodec.encode(m).toString(Charsets.UTF_8))
        json.getJSONObject("settings").remove("artistImageSorts")
        assertEquals(m, BackupManifestCodec.decode(json.toString().toByteArray()))
    }
    @Test fun invalidSortValueAndWrongSortMappingTypeAreRejected() {
        val m = fixture()
        val json = JSONObject(BackupManifestCodec.encode(m).toString(Charsets.UTF_8))
        json.getJSONObject("settings").put("artistImageSorts", JSONObject().put(m.artists.single().id, "FutureSort"))
        assertThrows(IOException::class.java) { BackupManifestCodec.decode(json.toString().toByteArray()) }
        json.getJSONObject("settings").put("artistImageSorts", "not-a-map")
        assertThrows(IOException::class.java) { BackupManifestCodec.decode(json.toString().toByteArray()) }
    }

    @Test fun allCollectionMediaSortsRoundTripInVersionOne() {
        val m = fixture()
        MediaSort.entries.forEach { sort ->
            val expected = m.copy(settings = m.settings.copy(collectionMediaSorts = mapOf(m.collections.single().id to sort)))
            assertEquals(expected, BackupManifestCodec.decode(BackupManifestCodec.encode(expected)))
        }
    }

    @Test fun oldVersionOneWithoutCollectionSortFieldKeepsArtistSortReadable() {
        val m = fixture().let { it.copy(settings = it.settings.copy(artistImageSorts = mapOf(it.artists.single().id to MediaSort.NameDescending))) }
        val json = JSONObject(BackupManifestCodec.encode(m).toString(Charsets.UTF_8))
        json.getJSONObject("settings").remove("collectionMediaSorts")
        assertEquals(m, BackupManifestCodec.decode(json.toString().toByteArray()))
    }

    @Test fun invalidCollectionSortValueAndWrongMappingTypeAreRejected() {
        val m = fixture()
        val json = JSONObject(BackupManifestCodec.encode(m).toString(Charsets.UTF_8))
        json.getJSONObject("settings").put("collectionMediaSorts", JSONObject().put(m.collections.single().id, "FutureSort"))
        assertThrows(IOException::class.java) { BackupManifestCodec.decode(json.toString().toByteArray()) }
        json.getJSONObject("settings").put("collectionMediaSorts", "not-a-map")
        assertThrows(IOException::class.java) { BackupManifestCodec.decode(json.toString().toByteArray()) }
    }
}
