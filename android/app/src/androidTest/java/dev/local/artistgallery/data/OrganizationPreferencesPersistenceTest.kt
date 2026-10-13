package dev.local.artistgallery.data

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.local.artistgallery.model.MediaSort
import java.util.UUID
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OrganizationPreferencesPersistenceTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val name = "organization-preferences-test-${UUID.randomUUID()}"
    private val isolated = object : ContextWrapper(context) {
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
            check(name == "browse")
            return context.getSharedPreferences(this@OrganizationPreferencesPersistenceTest.name, mode)
        }
    }
    @After fun cleanup() { assertTrue(context.deleteSharedPreferences(name)) }

    @Test fun allOrganizationPreferencesRoundTripWithoutChangingLegacyOptions() {
        val expected = BrowseOptions(true, false, ArtistImageLayout.Medium, false,
            listOf("画师a", "id,with punctuation"), mapOf("画师a" to listOf("图集c", "图集b")),
            mapOf("画师a" to ArtistImageLayout.List, "画师b" to ArtistImageLayout.Large), true)
        val prefs = LocalBrowsePreferences(isolated)
        prefs.save(expected)
        assertEquals(expected, LocalBrowsePreferences(isolated).options.value)
        prefs.save(expected.copy(playGifs = true))
        assertEquals(expected.copy(playGifs = true), LocalBrowsePreferences(isolated).options.value)
    }

    @Test fun oldPreferencesUseEmptyOrdersAndLayoutsWithVisibleStorageNotice() {
        assertTrue(context.getSharedPreferences(name, Context.MODE_PRIVATE).edit()
            .putString("imageLayout", ArtistImageLayout.Medium.name).putBoolean("cardEffects", true).commit())
        val options = LocalBrowsePreferences(isolated).options.value
        assertEquals(BrowseOptions(cardEffects = true, imageLayout = ArtistImageLayout.Medium), options)
        assertEquals("小图标", ArtistImageLayout.Medium.label)
    }

    @Test fun everyArtistSortRoundTripsWithoutReplacingLegacyOptions() {
        val expected = BrowseOptions(cardEffects = true, playGifs = false, artistImageSorts =
            MediaSort.entries.associate { "artist-${it.name}" to it })
        LocalBrowsePreferences(isolated).saveDurably(expected)
        assertEquals(expected, LocalBrowsePreferences(isolated).options.value)
    }

    @Test fun missingArtistSortFieldDefaultsToEmptyMap() {
        assertTrue(context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().putBoolean("playGifs", false).commit())
        assertEquals(BrowseOptions(playGifs = false), LocalBrowsePreferences(isolated).options.value)
    }

    @Test fun unknownLocalSortIsIgnoredWithoutLosingKnownArtistSort() {
        assertTrue(context.getSharedPreferences(name, Context.MODE_PRIVATE).edit()
            .putString("artistImageSorts", "{\"known\":\"NameAscending\",\"unknown\":\"FutureSort\"}").commit())
        assertEquals(mapOf("known" to MediaSort.NameAscending), LocalBrowsePreferences(isolated).options.value.artistImageSorts)
    }

    @Test fun everyCollectionMediaSortRoundTripsIndependentlyOfArtistChoices() {
        val expected = BrowseOptions(cardEffects = true, playGifs = false, artistImageSorts = mapOf("artist" to MediaSort.NameAscending),
            collectionMediaSorts = MediaSort.entries.associate { "collection-${it.name}" to it })
        LocalBrowsePreferences(isolated).saveDurably(expected)
        assertEquals(expected, LocalBrowsePreferences(isolated).options.value)
    }

    @Test fun missingCollectionMediaSortFieldPreservesExistingArtistChoice() {
        assertTrue(context.getSharedPreferences(name, Context.MODE_PRIVATE).edit()
            .putString("artistImageSorts", "{\"artist\":\"AddedDescending\"}").commit())
        assertEquals(BrowseOptions(artistImageSorts = mapOf("artist" to MediaSort.AddedDescending)), LocalBrowsePreferences(isolated).options.value)
    }

    @Test fun unknownLocalCollectionSortIsIgnoredWithoutLosingKnownChoice() {
        assertTrue(context.getSharedPreferences(name, Context.MODE_PRIVATE).edit()
            .putString("collectionMediaSorts", "{\"known\":\"SizeDescending\",\"unknown\":\"FutureSort\"}").commit())
        assertEquals(mapOf("known" to MediaSort.SizeDescending), LocalBrowsePreferences(isolated).options.value.collectionMediaSorts)
    }
}
