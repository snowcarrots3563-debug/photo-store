package dev.local.artistgallery.ui

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import dev.local.artistgallery.data.ArtistImageLayout
import dev.local.artistgallery.data.BrowseOptions
import dev.local.artistgallery.data.BrowsePreferences
import dev.local.artistgallery.data.GalleryRepository
import dev.local.artistgallery.data.backup.BackupManifest
import dev.local.artistgallery.data.backup.RestoreRules
import dev.local.artistgallery.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class GalleryVideoPreferencesTest {
    private val dispatcher = StandardTestDispatcher()
    private val store = ViewModelStore()
    private var serial = 0
    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun cleanup() { store.clear(); Dispatchers.resetMain() }

    @Test fun newAndOldPositionalOptionsDefaultVideoAutoPlayToEnabled() {
        assertTrue(BrowseOptions().autoPlayVideos)
        val old = BrowseOptions(true, false, ArtistImageLayout.Medium, false)
        assertTrue(old.autoPlayVideos)
        assertTrue(old.cardEffects)
        assertFalse(old.playGifs)
        assertFalse(old.swipeDelete)
    }

    @Test fun videoSettingSurvivesViewModelRecreationAndPreservesEveryOtherOption() = runTest(dispatcher) {
        val initial = BrowseOptions(true, false, ArtistImageLayout.Medium, false, listOf("a"),
            mapOf("a" to listOf("c")), mapOf("a" to ArtistImageLayout.Large), true,
            mapOf("a" to MediaSort.NameAscending), mapOf("c" to MediaSort.SizeDescending))
        val prefs = Preferences(initial)
        val vm = model(prefs)
        vm.onEvent(GalleryEvent.SetAutoPlayVideos(false))
        runCurrent()
        assertEquals(initial.copy(autoPlayVideos = false), prefs.options.value)
        assertEquals(initial.copy(autoPlayVideos = false), model(prefs).state.value.browseOptions)
        assertEquals(1, prefs.saves)
    }

    @Test fun unrelatedStateAndPreferenceRefreshKeepTheDisabledVideoSetting() = runTest(dispatcher) {
        val prefs = Preferences(BrowseOptions(autoPlayVideos = false))
        val vm = model(prefs)
        vm.onEvent(GalleryEvent.OpenManage)
        vm.onEvent(GalleryEvent.SetPlayGifs(false))
        vm.onEvent(GalleryEvent.SetSwipeDelete(false))
        runCurrent()
        assertFalse(vm.state.value.browseOptions.autoPlayVideos)
        prefs.options.value = prefs.options.value.copy(cardEffects = true)
        runCurrent()
        assertEquals(BrowseOptions(cardEffects = true, playGifs = false, swipeDelete = false, autoPlayVideos = false), vm.state.value.browseOptions)
        vm.onEvent(GalleryEvent.SetAutoPlayVideos(true))
        runCurrent()
        assertEquals(prefs.options.value, model(prefs).state.value.browseOptions)
        assertTrue(vm.state.value.browseOptions.autoPlayVideos)
    }

    @Test fun mergeRestorePreservesTheLocalVideoSettingRatherThanTheBackupsValue() {
        listOf(false, true).forEach { enabled ->
            val local = BrowseOptions(cardEffects = true, playGifs = false, swipeDelete = false, autoPlayVideos = enabled)
            val backup = BackupManifest(createdAt = 0, assets = emptyList(), artists = emptyList(), collections = emptyList(),
                references = emptyList(), settings = BrowseOptions(autoPlayVideos = !enabled))
            assertEquals(local, RestoreRules.preferences(local, backup, emptyMap(), emptyMap(), emptyList(), emptyList()))
        }
    }

    private fun model(prefs: Preferences): GalleryViewModel = GalleryViewModel(object : GalleryRepository {
        override fun observeArtists(): Flow<List<ArtistSummary>> = flowOf(emptyList())
        override fun observeArtist(id: String): Flow<ArtistDetail?> = flowOf(null)
        override fun observeCollection(id: String): Flow<CollectionSummary?> = flowOf(null)
        override suspend fun createArtist(name: String): SaveResult = error("Preferences must not write gallery")
        override suspend fun renameArtist(id: String, name: String): SaveResult = error("Preferences must not write gallery")
        override suspend fun createCollection(artistId: String, name: String): SaveResult = error("Preferences must not write gallery")
        override suspend fun renameCollection(id: String, name: String): SaveResult = error("Preferences must not write gallery")
    }, SavedStateHandle(), browsePreferences = prefs, computationDispatcher = dispatcher).also { store.put("video${serial++}", it) }

    private class Preferences(initial: BrowseOptions) : BrowsePreferences {
        override val options = MutableStateFlow(initial)
        var saves = 0
        override fun save(options: BrowseOptions) { saves++; this.options.value = options }
    }
}
