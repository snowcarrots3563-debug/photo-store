package dev.local.artistgallery.ui

import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.local.artistgallery.data.GalleryRepository
import dev.local.artistgallery.data.MediaLibraryRepository
import dev.local.artistgallery.data.ArtistImageLayout
import dev.local.artistgallery.data.BrowseOptions
import dev.local.artistgallery.data.orderedEntities
import dev.local.artistgallery.data.reorderVisibleIds
import dev.local.artistgallery.data.deleting.deleteMediaCopies
import dev.local.artistgallery.data.importing.MediaImportCoordinator
import dev.local.artistgallery.data.importing.ImportCompletion
import dev.local.artistgallery.data.importing.toImportCompletion
import dev.local.artistgallery.model.AddReferenceResult
import dev.local.artistgallery.model.ArtistDetail
import dev.local.artistgallery.model.MediaAssetSummary
import dev.local.artistgallery.model.MediaSort
import dev.local.artistgallery.model.sortArtistImages
import dev.local.artistgallery.model.sortCollectionMedia
import dev.local.artistgallery.model.ReferenceRemovalResult
import dev.local.artistgallery.model.SaveResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.currentCoroutineContext
import java.util.UUID

class GalleryViewModel(
    private val repository: GalleryRepository,
    private val savedState: SavedStateHandle,
    private val mediaRepository: MediaLibraryRepository? = null,
    private val mediaImporter: MediaImportCoordinator? = null,
    private val browsePreferences: dev.local.artistgallery.data.BrowsePreferences? = null,
    private val onImportCompleted: (ImportCompletion) -> Unit = {},
) : ViewModel() {
    private var persistedBrowseOptions = browsePreferences?.options?.value ?: BrowseOptions()
    private val mutableState = MutableStateFlow(restore().let { it.copy(browseOptions = displayOptions(it.route)) })
    val state: StateFlow<GalleryUiState> = mutableState.asStateFlow()
    private val messages = MutableSharedFlow<GalleryFeedback>(extraBufferCapacity = 1)
    val feedback: Flow<GalleryFeedback> = messages.asSharedFlow()
    private val mutableEffects = MutableSharedFlow<GalleryEffect>(extraBufferCapacity = 1)
    val effects: Flow<GalleryEffect> = mutableEffects.asSharedFlow()
    private var artistsJob: Job? = null
    private var homeLoadState = LoadState.Loading
    private var detailJob: Job? = null
    private var mediaJob: Job? = null
    private var importJob: Job? = null
    private var importResultJob: Job? = null
    private var pendingImportResultId: String? = null
    private var importPreviewId: String? = null
    private var coverJob: Job? = null
    private data class ArtistReturnState(val id: String, val detail: ArtistDetail, val images: List<MediaAssetSummary>, val tab: ArtistTab)
    private var parentArtist: ArtistReturnState? = null

    init {
        observeArtists()
        observeRoute()
        observeImportHistory()
        (state.value.dialog as? GalleryDialog.ArtistCover)?.let { loadCoverImages(it.artistId) }
        browsePreferences?.let { preferences -> viewModelScope.launch {
            preferences.options.collect { options -> applyBrowseOptions(options) }
        } }
    }

    private fun change(transform: (GalleryUiState) -> GalleryUiState) {
        mutableState.update(transform)
        val s = state.value
        savedState["query"] = s.searchQuery
        savedState["tab"] = s.artistTab.name
        savedState["pendingImportTargetCollectionId"] = s.pendingImportTargetCollectionId
        savedState["mediaSelectionMode"] = s.mediaSelectionMode
        savedState["selectedMediaIds"] = ArrayList(s.selectedMediaIds)
        savedState["entitySelectionKind"] = s.entitySelectionKind?.name
        savedState["selectedEntityIds"] = ArrayList(s.selectedEntityIds)
        savedState["route"] = when (val r = s.route) {
            GalleryRoute.Home -> arrayListOf("home")
            GalleryRoute.Manage -> arrayListOf("manage")
            GalleryRoute.Unassigned -> arrayListOf("unassigned")
            is GalleryRoute.Artist -> arrayListOf("artist", r.id)
            is GalleryRoute.Collection -> arrayListOf("collection", r.id, r.artistId)
        }
        savedState["overlay"] = when (val overlay = s.overlay) {
            GalleryOverlay.None -> arrayListOf("none")
            is GalleryOverlay.CardViewer -> arrayListOf("card", overlay.assetId)
            is GalleryOverlay.VideoPlayer -> arrayListOf("video", overlay.assetId)
            GalleryOverlay.ComicReader -> arrayListOf("comic")
        }
        if (s.route is GalleryRoute.Collection) savedState["comicDirection:${s.route.id}"] = s.comicDirection.name
        savedState["dialog"] = when (val d = s.dialog) {
            GalleryDialog.None -> arrayListOf("none")
            GalleryDialog.AddMenu -> arrayListOf("addMenu")
            is GalleryDialog.AddArtist -> arrayListOf("addArtist", d.draft, d.fromArchiveImport.toString())
            GalleryDialog.ArtistMenu -> arrayListOf("artistMenu")
            GalleryDialog.CollectionMenu -> arrayListOf("collectionMenu")
            is GalleryDialog.CreateCollection -> arrayListOf("create", d.artistId, d.draft)
            is GalleryDialog.RenameCollection -> arrayListOf("rename", d.id, d.draft)
            is GalleryDialog.RenameArtist -> arrayListOf("renameArtist", d.id, d.draft)
            is GalleryDialog.DeleteArtist -> arrayListOf("deleteArtist", d.id, d.name)
            is GalleryDialog.DeleteArtists -> arrayListOf("deleteArtists").apply { addAll(d.ids) }
            is GalleryDialog.DeleteCollections -> arrayListOf("deleteCollections").apply { addAll(d.ids) }
            GalleryDialog.ImageDisplayMenu -> arrayListOf("imageDisplay")
            GalleryDialog.ArtistImageSort -> arrayListOf("artistImageSort")
            GalleryDialog.CollectionMediaSort -> arrayListOf("collectionMediaSort")
            is GalleryDialog.Unavailable -> arrayListOf("unavailable", d.feature, d.phase.toString())
            GalleryDialog.About -> arrayListOf("about")
            GalleryDialog.UpdateHistory -> arrayListOf("updateHistory")
            is GalleryDialog.ImportTarget -> arrayListOf("importTarget", d.artistId.orEmpty(), d.collectionId.orEmpty(), d.toUnassigned.toString(), d.fromFiles.toString())
            is GalleryDialog.DeleteMedia -> arrayListOf("deleteMedia", d.assetId, d.name)
            is GalleryDialog.DeleteSelectedMedia -> arrayListOf("deleteSelectedMedia").apply { addAll(d.assetIds) }
            is GalleryDialog.ArtistCover -> arrayListOf("artistCover", d.artistId, d.selectedAssetId.orEmpty())
            else -> arrayListOf("none")
        }
    }

    private fun changeTransient(transform: (GalleryUiState) -> GalleryUiState) { mutableState.update(transform) }

    private fun restore(): GalleryUiState {
        val r = savedState.get<ArrayList<String>>("route") ?: arrayListOf("home")
        val d = savedState.get<ArrayList<String>>("dialog") ?: arrayListOf("none")
        val overlayState = savedState.get<ArrayList<String>>("overlay") ?: arrayListOf("none")
        val route = when (r[0]) {
            "artist" -> GalleryRoute.Artist(r[1])
            "collection" -> GalleryRoute.Collection(r[1], r[2])
            "manage" -> GalleryRoute.Manage
            "unassigned" -> GalleryRoute.Unassigned
            else -> GalleryRoute.Home
        }
        val tab = if (savedState.get<String>("tab") == "Collections") ArtistTab.Collections else ArtistTab.Images
        val selectionKind = EntitySelectionKind.entries.firstOrNull { it.name == savedState.get<String>("entitySelectionKind") }
            ?.takeIf { (it == EntitySelectionKind.Artists && route == GalleryRoute.Home) ||
                (it == EntitySelectionKind.Collections && route is GalleryRoute.Artist && tab == ArtistTab.Collections) }
        return GalleryUiState(
            searchQuery = savedState["query"] ?: "",
            pendingImportTargetCollectionId = savedState["pendingImportTargetCollectionId"],
            artistTab = tab,
            route = route,
            mediaSelectionMode = selectionKind == null && (savedState["mediaSelectionMode"] ?: false),
            entitySelectionKind = selectionKind,
            selectedEntityIds = if (selectionKind == null) emptySet() else savedState.get<ArrayList<String>>("selectedEntityIds")?.toSet().orEmpty(),
            selectedMediaIds = if (selectionKind != null) emptySet() else savedState.get<ArrayList<String>>("selectedMediaIds")?.toSet().orEmpty(),
            scrollPositions = savedState.keys().filter { it.startsWith("scroll:") }.associate { key ->
                val position = savedState.get<ArrayList<Int>>(key) ?: arrayListOf(0, 0)
                key.removePrefix("scroll:") to GalleryScrollPosition(position[0], position[1])
            },
            overlay = when (overlayState[0]) {
                "card" -> GalleryOverlay.CardViewer(overlayState[1])
                "video" -> GalleryOverlay.VideoPlayer(overlayState[1])
                "comic" -> GalleryOverlay.ComicReader
                else -> GalleryOverlay.None
            },
            comicDirection = if (route is GalleryRoute.Collection && savedState.get<String>("comicDirection:${route.id}") == ComicDirection.NameDescending.name)
                ComicDirection.NameDescending else ComicDirection.NameAscending,
            dialog = when (d[0]) {
                "addMenu" -> GalleryDialog.AddMenu
                "addArtist" -> GalleryDialog.AddArtist(d[1], d.getOrNull(2) == "true")
                "artistMenu" -> GalleryDialog.ArtistMenu
                "collectionMenu" -> GalleryDialog.CollectionMenu
                "create" -> GalleryDialog.CreateCollection(d[1], d[2])
                "rename" -> GalleryDialog.RenameCollection(d[1], d[2])
                "renameArtist" -> GalleryDialog.RenameArtist(d[1], d[2])
                "deleteArtist" -> GalleryDialog.DeleteArtist(d[1], d[2])
                "deleteArtists" -> if (selectionKind == EntitySelectionKind.Artists) GalleryDialog.DeleteArtists(d.drop(1).distinct()) else GalleryDialog.None
                "deleteCollections" -> if (selectionKind == EntitySelectionKind.Collections) GalleryDialog.DeleteCollections(d.drop(1).distinct()) else GalleryDialog.None
                "imageDisplay" -> GalleryDialog.ImageDisplayMenu
                "artistImageSort" -> if (route is GalleryRoute.Artist && tab == ArtistTab.Images &&
                    selectionKind == null && !(savedState.get<Boolean>("mediaSelectionMode") ?: false) &&
                    overlayState[0] == "none") GalleryDialog.ArtistImageSort else GalleryDialog.None
                "collectionMediaSort" -> if (route is GalleryRoute.Collection && selectionKind == null &&
                    !(savedState.get<Boolean>("mediaSelectionMode") ?: false) &&
                    overlayState[0] == "none") GalleryDialog.CollectionMediaSort else GalleryDialog.None
                "unavailable" -> GalleryDialog.Unavailable(d[1], d[2].toInt())
                "about" -> GalleryDialog.About
                "updateHistory" -> GalleryDialog.UpdateHistory
                "importTarget" -> GalleryDialog.ImportTarget(d.getOrNull(1)?.ifEmpty { null }, d.getOrNull(2)?.ifEmpty { null }, d.getOrNull(3) == "true", d.getOrNull(4) == "true")
                "deleteMedia" -> GalleryDialog.DeleteMedia(d[1], d[2])
                "deleteSelectedMedia" -> GalleryDialog.DeleteSelectedMedia(d.drop(1).distinct())
                "artistCover" -> GalleryDialog.ArtistCover(d[1], d.getOrNull(2)?.ifEmpty { null })
                else -> GalleryDialog.None
            },
        )
    }

    private fun displayOptions(route: GalleryRoute): BrowseOptions {
        val artistId = when (route) {
            is GalleryRoute.Artist -> route.id
            is GalleryRoute.Collection -> route.artistId
            else -> null
        }
        return persistedBrowseOptions.copy(imageLayout = artistId?.let { persistedBrowseOptions.artistLayouts[it] } ?: persistedBrowseOptions.imageLayout)
    }

    private fun orderedDetail(detail: ArtistDetail?): ArtistDetail? = detail?.copy(collections =
        orderedEntities(detail.collections, persistedBrowseOptions.collectionOrders[detail.artist.id].orEmpty()) { it.id })

    private fun applyBrowseOptions(options: BrowseOptions) {
        val previousSorts = persistedBrowseOptions.artistImageSorts
        val previousCollectionSorts = persistedBrowseOptions.collectionMediaSorts
        persistedBrowseOptions = options
        parentArtist = parentArtist?.let { cached -> cached.copy(detail = requireNotNull(orderedDetail(cached.detail)),
            images = if (previousSorts[cached.id] != options.artistImageSorts[cached.id])
                sortArtistImages(cached.images, options.artistImageSorts[cached.id] ?: MediaSort.Default) else cached.images) }
        changeTransient { it.copy(browseOptions = displayOptions(it.route),
            artists = orderedEntities(it.artists, options.artistOrder) { artist -> artist.id }, artistDetail = orderedDetail(it.artistDetail),
            mediaItems = when (val route = it.route) {
                is GalleryRoute.Artist -> if (previousSorts[route.id] != options.artistImageSorts[route.id])
                    sortArtistImages(it.mediaItems, options.artistImageSorts[route.id] ?: MediaSort.Default) else it.mediaItems
                is GalleryRoute.Collection -> if (previousCollectionSorts[route.id] != options.collectionMediaSorts[route.id])
                    sortCollectionMedia(it.mediaItems, options.collectionMediaSorts[route.id] ?: MediaSort.Default) else it.mediaItems
                else -> it.mediaItems
            }) }
    }

    private fun saveBrowseOptions(options: BrowseOptions) {
        browsePreferences?.save(options)
        applyBrowseOptions(options)
    }

    private fun setImageLayout(layout: ArtistImageLayout) {
        val artistId = when (val route = state.value.route) {
            is GalleryRoute.Artist -> route.id
            is GalleryRoute.Collection -> route.artistId
            else -> null
        }
        saveBrowseOptions(if (artistId == null) persistedBrowseOptions.copy(imageLayout = layout)
            else persistedBrowseOptions.copy(artistLayouts = persistedBrowseOptions.artistLayouts + (artistId to layout)))
        change { it.copy(dialog = GalleryDialog.None) }
    }

    private fun canSortArtistImages(current: GalleryUiState): Boolean = current.route is GalleryRoute.Artist &&
        current.artistTab == ArtistTab.Images && !current.mediaSelectionMode && current.entitySelectionKind == null &&
        !current.isSaving && current.overlay == GalleryOverlay.None

    private fun setArtistImageSort(sort: MediaSort) {
        val current = state.value
        if (!canSortArtistImages(current)) return
        val artistId = (current.route as GalleryRoute.Artist).id
        saveBrowseOptions(persistedBrowseOptions.copy(artistImageSorts = persistedBrowseOptions.artistImageSorts + (artistId to sort)))
        change { it.copy(dialog = GalleryDialog.None) }
    }

    private fun canSortCollectionMedia(current: GalleryUiState): Boolean = current.route is GalleryRoute.Collection &&
        current.collection?.id == current.route.id && current.loadState == LoadState.Ready && current.mediaLoadState == LoadState.Ready &&
        !current.mediaSelectionMode && current.entitySelectionKind == null && !current.isSaving && current.overlay == GalleryOverlay.None

    private fun setCollectionMediaSort(sort: MediaSort) {
        val current = state.value
        if (!canSortCollectionMedia(current)) return
        val collectionId = (current.route as GalleryRoute.Collection).id
        saveBrowseOptions(persistedBrowseOptions.copy(collectionMediaSorts = persistedBrowseOptions.collectionMediaSorts + (collectionId to sort)))
        change { it.copy(dialog = GalleryDialog.None) }
    }

    private fun entityIds(current: GalleryUiState, kind: EntitySelectionKind): List<String> = when {
        kind == EntitySelectionKind.Artists && current.route == GalleryRoute.Home -> current.artists.map { it.id }
        kind == EntitySelectionKind.Collections && current.route is GalleryRoute.Artist && current.artistTab == ArtistTab.Collections ->
            current.artistDetail?.takeIf { it.artist.id == current.route.id }?.collections.orEmpty().map { it.id }
        else -> emptyList()
    }

    private fun isEntityDeleteDialog(dialog: GalleryDialog): Boolean = dialog is GalleryDialog.DeleteArtists || dialog is GalleryDialog.DeleteCollections

    private fun entityDeleteDialog(kind: EntitySelectionKind, ids: List<String>): GalleryDialog =
        if (kind == EntitySelectionKind.Artists) GalleryDialog.DeleteArtists(ids) else GalleryDialog.DeleteCollections(ids)

    private fun observeArtists() {
        artistsJob?.cancel()
        artistsJob = viewModelScope.launch {
            repository.observeArtists().distinctUntilChanged().catch { e ->
                if (e is CancellationException) throw e
                homeLoadState = LoadState.Failed
                if (state.value.route == GalleryRoute.Home) change { it.copy(loadState = LoadState.Failed) }
            }.collect { artists ->
                homeLoadState = LoadState.Ready
                change { current -> current.copy(artists = orderedEntities(artists, persistedBrowseOptions.artistOrder) { it.id },
                    selectedEntityIds = if (current.entitySelectionKind == EntitySelectionKind.Artists)
                        current.selectedEntityIds.intersect(artists.mapTo(mutableSetOf()) { it.id }) else current.selectedEntityIds,
                    loadState = if (current.route == GalleryRoute.Home) LoadState.Ready else current.loadState) }
            }
        }
    }

    private fun observeRoute() {
        detailJob?.cancel()
        mediaJob?.cancel()
        val route = state.value.route
        observeMediaRoute(route)
        if (route == GalleryRoute.Home) {
            change { it.copy(loadState = homeLoadState) }
            return
        }
        if (route == GalleryRoute.Manage || route == GalleryRoute.Unassigned) {
            change { it.copy(loadState = LoadState.Ready) }
            return
        }
        detailJob = viewModelScope.launch {
            try {
                when (route) {
                    is GalleryRoute.Artist -> repository.observeArtist(route.id).collect { detail ->
                        currentCoroutineContext().ensureActive()
                        if (state.value.route != route) return@collect
                        change { current -> current.copy(artistDetail = orderedDetail(detail),
                            selectedEntityIds = if (current.entitySelectionKind == EntitySelectionKind.Collections)
                                current.selectedEntityIds.intersect(detail?.collections.orEmpty().mapTo(mutableSetOf()) { it.id }) else current.selectedEntityIds,
                            loadState = if (detail == null) LoadState.Failed else LoadState.Ready) }
                    }
                    is GalleryRoute.Collection -> {
                        var loadedCollection = false
                        combine(repository.observeArtist(route.artistId), repository.observeCollection(route.id)) { artist, collection -> artist to collection }
                            .collect { (artist, collection) ->
                                currentCoroutineContext().ensureActive()
                                if (state.value.route != route) return@collect
                                if (loadedCollection && collection == null && artist != null) {
                                    parentArtist = parentArtist?.copy(tab = ArtistTab.Collections)
                                    navigate(GalleryRoute.Artist(route.artistId), ArtistTab.Collections, returningToArtist = true)
                                } else {
                                    if (artist != null && collection != null) loadedCollection = true
                                    change { it.copy(artistDetail = orderedDetail(artist), collection = collection,
                                        loadState = if (artist == null || collection == null) LoadState.Failed else LoadState.Ready) }
                                }
                            }
                    }
                    else -> Unit
                }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) {
                currentCoroutineContext().ensureActive()
                if (state.value.route == route) change { it.copy(loadState = LoadState.Failed) }
            }
        }
    }

    private fun observeMediaRoute(route: GalleryRoute) {
        val source = when {
            mediaRepository == null -> null
            route is GalleryRoute.Collection -> mediaRepository.observeCollectionMedia(route.id)
            route is GalleryRoute.Artist -> mediaRepository.observeArtistImages(route.id)
            route == GalleryRoute.Unassigned -> mediaRepository.observeUnassignedMedia()
            else -> null
        } ?: run {
            change { it.copy(mediaItems = emptyList(), selectedMediaIds = emptySet(), mediaLoadState = LoadState.Ready) }
            return
        }
        mediaJob = viewModelScope.launch {
            source.catch { error ->
                if (error is CancellationException) throw error
                currentCoroutineContext().ensureActive()
                if (state.value.route != route) return@catch
                change { it.copy(loadState = LoadState.Failed, mediaLoadState = LoadState.Failed) }
            }.collect { items ->
                currentCoroutineContext().ensureActive()
                if (state.value.route != route) return@collect
                change { current ->
                    val ids = items.mapTo(mutableSetOf()) { it.id }
                    current.copy(mediaItems = when (route) {
                        is GalleryRoute.Artist -> sortArtistImages(items, persistedBrowseOptions.artistImageSorts[route.id] ?: MediaSort.Default)
                        is GalleryRoute.Collection -> sortCollectionMedia(items, persistedBrowseOptions.collectionMediaSorts[route.id] ?: MediaSort.Default)
                        else -> items
                    },
                        selectedMediaIds = current.selectedMediaIds.intersect(ids), mediaLoadState = LoadState.Ready,
                        loadState = if (route == GalleryRoute.Unassigned) LoadState.Ready else current.loadState)
                }
            }
        }
    }

    private fun navigate(route: GalleryRoute, tab: ArtistTab = state.value.artistTab, returningToArtist: Boolean = false) {
        importResultJob?.cancel()
        coverJob?.cancel()
        val current = state.value
        val parent = if (returningToArtist && route is GalleryRoute.Artist && current.route is GalleryRoute.Collection &&
            current.route.artistId == route.id) {
            parentArtist?.takeIf { it.id == route.id }
        } else null
        parentArtist = if (route is GalleryRoute.Collection && current.route is GalleryRoute.Artist &&
            current.route.id == route.artistId && current.loadState == LoadState.Ready && current.mediaLoadState == LoadState.Ready) {
            current.artistDetail?.takeIf { it.artist.id == route.artistId }?.let {
                ArtistReturnState(route.artistId, it, current.mediaItems, current.artistTab)
            }
        } else null
        val comicDirection = if (route is GalleryRoute.Collection && savedState.get<String>("comicDirection:${route.id}") == ComicDirection.NameDescending.name)
            ComicDirection.NameDescending else ComicDirection.NameAscending
        change { it.copy(route = route, overlay = GalleryOverlay.None, comicDirection = comicDirection, artistTab = parent?.tab ?: tab,
            dialog = GalleryDialog.None, fieldError = null, loadState = if (route == GalleryRoute.Home) homeLoadState else if (parent == null) LoadState.Loading else LoadState.Ready,
            mediaLoadState = if (parent == null) LoadState.Loading else LoadState.Ready,
            artistDetail = parent?.let { cached -> current.artistDetail?.takeIf { it.artist.id == cached.id } ?: cached.detail }, collection = null,
            mediaItems = parent?.images ?: emptyList(), selectedMediaIds = emptySet(), mediaSelectionMode = false,
            entitySelectionKind = null, selectedEntityIds = emptySet(), browseOptions = displayOptions(route),
            pendingAddAssetIds = emptyList(), importCandidates = emptyList(), importResult = null) }
        observeRoute()
    }

    private fun openMedia(assetId: String) {
        val item = state.value.mediaItems.firstOrNull { it.id == assetId } ?: return
        if (state.value.mediaSelectionMode) { onEvent(GalleryEvent.ToggleMediaSelection(assetId)); return }
        if (item.type == dev.local.artistgallery.model.MediaType.VIDEO) {
            if (state.value.route is GalleryRoute.Collection || state.value.route is GalleryRoute.Artist) change { it.copy(overlay = GalleryOverlay.VideoPlayer(assetId)) }
        } else {
            change { it.copy(overlay = GalleryOverlay.CardViewer(assetId)) }
        }
    }

    private fun stepViewer(direction: Int) {
        val current = state.value.overlay as? GalleryOverlay.CardViewer ?: return
        val images = state.value.mediaItems.filter { it.type != dev.local.artistgallery.model.MediaType.VIDEO }
        val index = images.indexOfFirst { it.id == current.assetId }
        if (index < 0) return
        val next = (index + direction).coerceIn(0, images.lastIndex)
        if (next != index) change { it.copy(overlay = GalleryOverlay.CardViewer(images[next].id)) }
    }

    private fun toggleComicDirection() {
        val route = state.value.route as? GalleryRoute.Collection ?: return
        change { current ->
            val next = if (current.comicDirection == ComicDirection.NameAscending) ComicDirection.NameDescending else ComicDirection.NameAscending
            savedState["comicDirection:${route.id}"] = next.name
            current.copy(comicDirection = next)
        }
    }

    private fun addViewerMediaToCollection() {
        val viewer = state.value.overlay as? GalleryOverlay.CardViewer ?: return
        change { it.copy(pendingAddAssetIds = listOf(viewer.assetId), selectedMediaIds = emptySet()) }
        openImportTarget(null, null, false)
    }

    fun onEvent(event: GalleryEvent) {
        if (state.value.isSaving && event !is GalleryEvent.RememberScrollPosition) return
        if (event !is GalleryEvent.OpenImportResult && event !is GalleryEvent.RememberScrollPosition) importResultJob?.cancel()
        when (event) {
            is GalleryEvent.SearchChanged -> change { it.copy(searchQuery = event.query) }
            GalleryEvent.ClearSearch -> change { it.copy(searchQuery = "") }
            is GalleryEvent.OpenArtist -> if (state.value.entitySelectionKind == EntitySelectionKind.Artists)
                onEvent(GalleryEvent.ToggleEntitySelection(event.id)) else navigate(GalleryRoute.Artist(event.id), ArtistTab.Images)
            is GalleryEvent.OpenCollection -> if (state.value.entitySelectionKind == EntitySelectionKind.Collections)
                onEvent(GalleryEvent.ToggleEntitySelection(event.id)) else navigate(GalleryRoute.Collection(event.id, event.artistId))
            is GalleryEvent.OpenMedia -> openMedia(event.assetId)
            GalleryEvent.CloseOverlay -> change { it.copy(overlay = GalleryOverlay.None) }
            is GalleryEvent.StepViewer -> stepViewer(event.direction)
            GalleryEvent.OpenComic -> if (state.value.route is GalleryRoute.Collection) change { it.copy(overlay = GalleryOverlay.ComicReader) }
            GalleryEvent.ToggleComicDirection -> toggleComicDirection()
            GalleryEvent.AddViewerMediaToCollection -> addViewerMediaToCollection()
            is GalleryEvent.SwitchArtistTab -> change { it.copy(artistTab = event.tab, mediaSelectionMode = false, selectedMediaIds = emptySet(),
                entitySelectionKind = null, selectedEntityIds = emptySet(),
                dialog = if (it.dialog is GalleryDialog.DeleteSelectedMedia || isEntityDeleteDialog(it.dialog) || it.dialog == GalleryDialog.ArtistImageSort) GalleryDialog.None else it.dialog,
                fieldError = if (it.dialog is GalleryDialog.DeleteSelectedMedia || isEntityDeleteDialog(it.dialog) || it.dialog == GalleryDialog.ArtistImageSort) null else it.fieldError) }
            is GalleryEvent.OpenDialog -> if (when (event.dialog) {
                GalleryDialog.ArtistImageSort -> canSortArtistImages(state.value)
                GalleryDialog.CollectionMediaSort -> canSortCollectionMedia(state.value)
                else -> true
            })
                change { it.copy(dialog = event.dialog, fieldError = null) }
            is GalleryEvent.DraftChanged -> change { s -> s.copy(dialog = when (val d = s.dialog) {
                is GalleryDialog.AddArtist -> d.copy(draft = event.draft)
                is GalleryDialog.CreateCollection -> d.copy(draft = event.draft)
                is GalleryDialog.RenameCollection -> d.copy(draft = event.draft)
                is GalleryDialog.RenameArtist -> d.copy(draft = event.draft)
                else -> d
            }) }
            GalleryEvent.Submit -> submit()
            GalleryEvent.DismissDialog -> {
                val resumeArchive = (state.value.dialog as? GalleryDialog.AddArtist)?.fromArchiveImport == true
                coverJob?.cancel()
                change { current ->
                    current.copy(dialog = GalleryDialog.None, fieldError = null, coverItems = emptyList(), pendingAddAssetIds = if (current.dialog is GalleryDialog.ImportTarget) emptyList() else current.pendingAddAssetIds)
                }
                if (resumeArchive) mutableEffects.tryEmit(GalleryEffect.ResumeArchiveArtistSelection)
            }
            GalleryEvent.NavigateBack -> if (state.value.dialog != GalleryDialog.None) onEvent(GalleryEvent.DismissDialog)
            else if (state.value.overlay != GalleryOverlay.None) change { it.copy(overlay = GalleryOverlay.None) }
            else if (state.value.mediaSelectionMode) onEvent(GalleryEvent.ExitMediaSelection)
            else if (state.value.entitySelectionKind != null) onEvent(GalleryEvent.ExitEntitySelection)
            else when (val r = state.value.route) {
                is GalleryRoute.Collection -> navigate(GalleryRoute.Artist(r.artistId), ArtistTab.Collections, returningToArtist = true)
                GalleryRoute.Unassigned -> navigate(GalleryRoute.Manage)
                else -> navigate(GalleryRoute.Home)
            }
            GalleryEvent.OpenHome -> navigate(GalleryRoute.Home)
            GalleryEvent.OpenManage -> navigate(GalleryRoute.Manage)
            GalleryEvent.RetryLoad -> {
                change { it.copy(loadState = LoadState.Loading, mediaLoadState = LoadState.Loading) }
                if (state.value.route == GalleryRoute.Home) {
                    homeLoadState = LoadState.Loading
                    observeArtists()
                }
                observeRoute()
            }
            GalleryEvent.OpenUnassigned -> navigate(GalleryRoute.Unassigned)
            GalleryEvent.OpenAddMediaTarget -> openAddMediaTarget()
            is GalleryEvent.OpenImportResult -> openImportResult(event.batchId)
            is GalleryEvent.OpenImportTarget -> openImportTarget(event.artistId, event.collectionId, event.toUnassigned, event.fromFiles)
            is GalleryEvent.ImportArtistSelected -> selectImportArtist(event.artistId)
            is GalleryEvent.ImportCollectionSelected -> change { current ->
                val target = current.dialog as? GalleryDialog.ImportTarget ?: return@change current
                current.copy(dialog = target.copy(collectionId = event.collectionId, toUnassigned = false), fieldError = null)
            }
            GalleryEvent.ToggleUnassignedImportTarget -> change { current ->
                val target = current.dialog as? GalleryDialog.ImportTarget ?: return@change current
                current.copy(dialog = target.copy(collectionId = null, toUnassigned = !target.toUnassigned), fieldError = null)
            }
            GalleryEvent.ConfirmImportTarget -> confirmImportTarget()
            is GalleryEvent.MediaPicked -> onMediaPicked(event.uris.map(Uri::parse))
            GalleryEvent.ConfirmImport -> startImport()
            is GalleryEvent.RetryImport -> retryImport(event.batchId)
            is GalleryEvent.ToggleMediaSelection -> change { current ->
                if (current.entitySelectionKind != null || current.mediaItems.none { it.id == event.assetId }) return@change current
                val selected = current.selectedMediaIds.toMutableSet()
                if (!selected.add(event.assetId)) selected.remove(event.assetId)
                current.copy(selectedMediaIds = selected)
            }
            is GalleryEvent.StartMediaSelection -> change { current ->
                if (!isMediaPage(current) || current.overlay != GalleryOverlay.None || current.dialog != GalleryDialog.None ||
                    current.mediaItems.none { it.id == event.assetId }) return@change current
                current.copy(mediaSelectionMode = true, selectedMediaIds = setOf(event.assetId), entitySelectionKind = null, selectedEntityIds = emptySet())
            }
            GalleryEvent.ExitMediaSelection -> change { it.copy(mediaSelectionMode = false, selectedMediaIds = emptySet(),
                dialog = if (it.dialog is GalleryDialog.DeleteSelectedMedia) GalleryDialog.None else it.dialog, fieldError = null) }
            GalleryEvent.OpenDeleteSelectedMedia -> change { current ->
                if (!current.mediaSelectionMode || !isMediaPage(current)) return@change current
                val ids = current.mediaItems.map { it.id }.filter { it in current.selectedMediaIds }.distinct()
                if (ids.isEmpty()) current else current.copy(dialog = GalleryDialog.DeleteSelectedMedia(ids), fieldError = null)
            }
            is GalleryEvent.OpenRemoveMedia -> removeReference(event.assetId, event.collectionId)
            GalleryEvent.KeepLastReference -> keepLastReference()
            GalleryEvent.DeleteLastReference -> deleteLastReference()
            is GalleryEvent.OpenDeleteMedia -> state.value.mediaItems.firstOrNull { it.id == event.assetId }?.let { media ->
                change { it.copy(dialog = GalleryDialog.DeleteMedia(media.id, media.name), fieldError = null) }
            }
            GalleryEvent.ConfirmDeleteMedia -> if (state.value.dialog is GalleryDialog.DeleteMedia) deleteMedia()
            GalleryEvent.ConfirmDeleteSelectedMedia -> if (state.value.dialog is GalleryDialog.DeleteSelectedMedia) deleteMedia()
            GalleryEvent.ConfirmDeleteArtist -> deleteArtist()
            is GalleryEvent.StartEntitySelection -> change { current ->
                if (current.overlay != GalleryOverlay.None || current.dialog != GalleryDialog.None ||
                    event.id !in entityIds(current, event.kind)) return@change current
                current.copy(entitySelectionKind = event.kind, selectedEntityIds = setOf(event.id),
                    mediaSelectionMode = false, selectedMediaIds = emptySet())
            }
            is GalleryEvent.ToggleEntitySelection -> change { current ->
                val kind = current.entitySelectionKind ?: return@change current
                if (event.id !in entityIds(current, kind)) return@change current
                val selected = current.selectedEntityIds.toMutableSet()
                if (!selected.add(event.id)) selected.remove(event.id)
                current.copy(selectedEntityIds = selected)
            }
            GalleryEvent.ExitEntitySelection -> change { it.copy(entitySelectionKind = null, selectedEntityIds = emptySet(),
                dialog = if (isEntityDeleteDialog(it.dialog)) GalleryDialog.None else it.dialog, fieldError = null) }
            GalleryEvent.OpenDeleteSelectedEntities -> change { current ->
                val kind = current.entitySelectionKind ?: return@change current
                val ids = entityIds(current, kind).filter { it in current.selectedEntityIds }
                if (ids.isEmpty()) current else current.copy(dialog = entityDeleteDialog(kind, ids), fieldError = null)
            }
            GalleryEvent.ConfirmDeleteSelectedEntities -> deleteSelectedEntities()
            is GalleryEvent.ReorderArtists -> if (state.value.route == GalleryRoute.Home) {
                val order = reorderVisibleIds(state.value.artists.map { it.id }, event.ids)
                saveBrowseOptions(persistedBrowseOptions.copy(artistOrder = order))
            }
            is GalleryEvent.ReorderCollections -> {
                val current = state.value
                if (current.route == GalleryRoute.Artist(event.artistId) && current.artistTab == ArtistTab.Collections) {
                    val order = reorderVisibleIds(current.artistDetail?.collections.orEmpty().map { it.id }, event.ids)
                    saveBrowseOptions(persistedBrowseOptions.copy(collectionOrders = persistedBrowseOptions.collectionOrders + (event.artistId to order)))
                }
            }
            GalleryEvent.CycleImageLayout -> {
                val next = when (state.value.browseOptions.imageLayout) {
                    ArtistImageLayout.List -> ArtistImageLayout.Large
                    ArtistImageLayout.Large -> ArtistImageLayout.Medium
                    ArtistImageLayout.Medium -> ArtistImageLayout.List
                }
                setImageLayout(next)
            }
            GalleryEvent.DismissStorageNotice -> saveBrowseOptions(persistedBrowseOptions.copy(storageNoticeDismissed = true))
            is GalleryEvent.RememberScrollPosition -> {
                val position = GalleryScrollPosition(event.index, event.offset)
                savedState["scroll:${event.key}"] = arrayListOf(event.index, event.offset)
                changeTransient { it.copy(scrollPositions = it.scrollPositions + (event.key to position)) }
            }
            is GalleryEvent.SetImageLayout -> setImageLayout(event.layout)
            is GalleryEvent.SetArtistImageSort -> setArtistImageSort(event.sort)
            is GalleryEvent.SetCollectionMediaSort -> setCollectionMediaSort(event.sort)
            is GalleryEvent.OpenArtistCover -> {
                change { it.copy(dialog = GalleryDialog.ArtistCover(event.artistId), coverItems = emptyList(), coverLoadState = LoadState.Loading, fieldError = null) }
                loadCoverImages(event.artistId)
            }
            is GalleryEvent.SelectArtistCover -> change { current ->
                val dialog = current.dialog as? GalleryDialog.ArtistCover ?: return@change current
                current.copy(dialog = dialog.copy(selectedAssetId = event.assetId), fieldError = null)
            }
            GalleryEvent.ConfirmArtistCover -> saveArtistCover()
            is GalleryEvent.SetCardEffects -> saveBrowseOptions(persistedBrowseOptions.copy(cardEffects = event.enabled))
            is GalleryEvent.SetPlayGifs -> saveBrowseOptions(persistedBrowseOptions.copy(playGifs = event.enabled))
            is GalleryEvent.SetSwipeDelete -> saveBrowseOptions(persistedBrowseOptions.copy(swipeDelete = event.enabled))
        }
    }

    fun onMediaPicked(uris: List<Uri>) {
        importResultJob?.cancel()
        if (uris.isEmpty()) {
            change { it.copy(pendingImportTargetCollectionId = null) }
            return
        }
        val importer = mediaImporter ?: return
        viewModelScope.launch {
            try {
                val candidates = importer.previewUris(uris)
                importPreviewId = UUID.randomUUID().toString()
                change { it.copy(importCandidates = candidates, importProgress = null, importResult = null, dialog = GalleryDialog.ImportPreview, fieldError = null) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { change { it.copy(dialog = GalleryDialog.None, fieldError = error.message ?: "读取选择结果失败。") } }
        }
    }

    private fun openImportTarget(artistId: String?, collectionId: String?, toUnassigned: Boolean, fromFiles: Boolean = false) {
        if (importJob?.isActive == true) {
            messages.tryEmit(GalleryFeedback.Saved("图库导入仍在后台进行。"))
            return
        }
        if (mediaImporter == null || mediaRepository == null) {
            change { it.copy(dialog = GalleryDialog.Unavailable(if (fromFiles) "从文件导入" else "从相册导入", 2)) }
            return
        }
        val route = state.value.route
        val selectedArtist = artistId ?: when (route) {
            is GalleryRoute.Artist -> route.id
            is GalleryRoute.Collection -> route.artistId
            else -> null
        }
        val selectedCollection = collectionId ?: (route as? GalleryRoute.Collection)?.id
        change {
            it.copy(
                dialog = GalleryDialog.ImportTarget(selectedArtist, selectedCollection, toUnassigned, fromFiles),
                importTargetCollections = emptyList(),
                fieldError = null,
            )
        }
        if (selectedArtist != null) loadTargetCollections(selectedArtist)
    }

    private fun openAddMediaTarget() {
        val selected = state.value.selectedMediaIds.toList()
        if (selected.isEmpty()) return
        change { it.copy(pendingAddAssetIds = selected) }
        openImportTarget(null, null, false)
    }

    private fun openImportResult(batchId: String) {
        val media = mediaRepository ?: return
        if (importJob?.isActive == true) {
            pendingImportResultId = batchId
            messages.tryEmit(GalleryFeedback.Saved("当前导入结束后查看结果。"))
            return
        }
        importResultJob?.cancel()
        importResultJob = viewModelScope.launch {
            try {
                val batch = media.getImportBatchResult(batchId)
                currentCoroutineContext().ensureActive()
                if (batch == null) messages.emit(GalleryFeedback.Saved("导入记录不存在。"))
                else change { it.copy(importResult = batch, dialog = GalleryDialog.ImportResult(batchId), overlay = GalleryOverlay.None) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { messages.emit(GalleryFeedback.Saved(error.message ?: "读取导入记录失败。")) }
        }
    }

    internal fun importPermissionActionKey(event: GalleryEvent): String? {
        if (importJob?.isActive == true || state.value.isSaving) return null
        val current = state.value
        return when (event) {
            GalleryEvent.ConfirmImport -> importPreviewId?.takeIf { current.dialog == GalleryDialog.ImportPreview && current.importCandidates.isNotEmpty() }
            is GalleryEvent.RetryImport -> event.batchId.takeIf {
                current.dialog == GalleryDialog.ImportResult(it) && current.importResult?.let { result -> result.id == it && result.items.any { item -> item.canRetry } } == true
            }
            else -> null
        }
    }

    internal fun continueImportAfterPermission(event: GalleryEvent, key: String) {
        if (importPermissionActionKey(event) == key) onEvent(event)
    }

    private fun finishImportAttempt() {
        importJob = null
        pendingImportResultId?.let { id -> pendingImportResultId = null; openImportResult(id) }
    }

    private fun observeImportHistory() {
        val media = mediaRepository ?: return
        viewModelScope.launch {
            media.observeLatestImportBatches()
                .catch { error -> if (error is CancellationException) throw error }
                .collect { batches -> changeTransient { current ->
                    val result = current.importResult?.let { old -> batches.firstOrNull { it.id == old.id } ?: old }
                    current.copy(recentImportBatches = batches, importResult = result)
                } }
        }
    }

    private fun selectImportArtist(artistId: String) {
        change { current ->
            val target = current.dialog as? GalleryDialog.ImportTarget ?: return@change current
            current.copy(dialog = target.copy(artistId = artistId, collectionId = null, toUnassigned = false), importTargetCollections = emptyList(), fieldError = null)
        }
        loadTargetCollections(artistId)
    }

    private fun loadTargetCollections(artistId: String) {
        viewModelScope.launch {
            val collections = orderedDetail(repository.observeArtist(artistId).first())?.collections.orEmpty()
            change { current ->
                val target = current.dialog as? GalleryDialog.ImportTarget
                if (target?.artistId == artistId) current.copy(importTargetCollections = collections) else current
            }
        }
    }

    private fun confirmImportTarget() {
        val target = state.value.dialog as? GalleryDialog.ImportTarget ?: return
        val addAssetIds = state.value.pendingAddAssetIds
        if (addAssetIds.isNotEmpty()) {
            val collectionId = target.collectionId
            if (collectionId == null) {
                change { it.copy(fieldError = "请选择一个作品集。") }
                return
            }
            val media = mediaRepository ?: return
            change { it.copy(dialog = GalleryDialog.None, fieldError = null) }
            viewModelScope.launch {
                var added = 0
                var alreadyPresent = 0
                var unavailable = 0
                addAssetIds.forEach { assetId ->
                    when (media.addReference(collectionId, assetId)) {
                        AddReferenceResult.ADDED -> added++
                        AddReferenceResult.ALREADY_LINKED -> alreadyPresent++
                        AddReferenceResult.NOT_FOUND, AddReferenceResult.NOT_READY -> unavailable++
                    }
                }
                change { it.copy(pendingAddAssetIds = emptyList(), selectedMediaIds = emptySet()) }
                messages.emit(GalleryFeedback.Saved("已加入 $added 项，已有 $alreadyPresent 项，无法加入 $unavailable 项。"))
            }
            return
        }
        if (!target.toUnassigned && target.collectionId == null) {
            change { it.copy(fieldError = "请选择作品集，或选择导入到未归组媒体。") }
            return
        }
        val collectionId = if (target.toUnassigned) null else target.collectionId
        change { it.copy(dialog = GalleryDialog.None, pendingImportTargetCollectionId = collectionId, fieldError = null) }
        mutableEffects.tryEmit(GalleryEffect.LaunchMediaPicker(collectionId, target.fromFiles))
    }

    private fun startImport() {
        if (importJob?.isActive == true) return
        val importer = mediaImporter ?: return
        val candidates = state.value.importCandidates
        if (candidates.isEmpty()) return
        val targetCollectionId = state.value.pendingImportTargetCollectionId
        importPreviewId = null
        importResultJob?.cancel()
        change { it.copy(dialog = GalleryDialog.ImportProgress, importProgress = null, importResult = null) }
        importJob = viewModelScope.launch {
            try {
                val result = importer.importUris(candidates.map { Uri.parse(it.uri) }, targetCollectionId) { progress ->
                    changeTransient { it.copy(importProgress = progress) }
                }
                notifyImportCompleted(result)
                val showResult = state.value.dialog == GalleryDialog.ImportProgress
                change { it.copy(dialog = if (showResult) GalleryDialog.ImportResult(result.id) else GalleryDialog.None, importResult = result, importProgress = null, importCandidates = emptyList(), pendingImportTargetCollectionId = null) }
                if (!showResult) messages.emit(GalleryFeedback.Saved(importSummaryText(result)))
                observeRoute()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                change { it.copy(dialog = GalleryDialog.None, importCandidates = emptyList(), importProgress = null, pendingImportTargetCollectionId = null, fieldError = error.message ?: "导入失败。") }
            }
            finally { finishImportAttempt() }
        }
    }

    private fun retryImport(batchId: String) {
        if (importJob?.isActive == true) return
        val importer = mediaImporter ?: return
        importResultJob?.cancel()
        change { it.copy(dialog = GalleryDialog.ImportProgress, importProgress = null) }
        importJob = viewModelScope.launch {
            try {
                val result = importer.retryFailed(batchId) { progress -> changeTransient { it.copy(importProgress = progress) } }
                notifyImportCompleted(result)
                val showResult = state.value.dialog == GalleryDialog.ImportProgress
                change { it.copy(dialog = if (showResult) GalleryDialog.ImportResult(result.id) else GalleryDialog.None, importResult = result, importProgress = null) }
                if (!showResult) messages.emit(GalleryFeedback.Saved(importSummaryText(result)))
                observeRoute()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { change { it.copy(dialog = GalleryDialog.None, fieldError = error.message ?: "重试失败。") } }
            finally { finishImportAttempt() }
        }
    }

    private fun importSummaryText(result: dev.local.artistgallery.model.ImportBatchSummary): String {
        val added = result.items.count { it.status == dev.local.artistgallery.model.ImportItemStatus.COMPLETE }
        val reused = result.items.count { it.status == dev.local.artistgallery.model.ImportItemStatus.REUSED }
        val skipped = result.items.count { it.status == dev.local.artistgallery.model.ImportItemStatus.SKIPPED }
        val unresolved = result.items.count { it.status in setOf(dev.local.artistgallery.model.ImportItemStatus.FAILED, dev.local.artistgallery.model.ImportItemStatus.STAGED, dev.local.artistgallery.model.ImportItemStatus.FINALIZED) }
        return "导入处理结束：新增 $added 项，复用 $reused 项，跳过 $skipped 项，失败或待恢复 $unresolved 项。"
    }

    private fun notifyImportCompleted(result: dev.local.artistgallery.model.ImportBatchSummary) {
        val completion = result.toImportCompletion()
        if (!completion.cancelled) try { onImportCompleted(completion) } catch (_: RuntimeException) { }
    }

    private fun loadCoverImages(artistId: String) {
        coverJob?.cancel()
        val media = mediaRepository ?: run { change { it.copy(coverLoadState = LoadState.Failed) }; return }
        coverJob = viewModelScope.launch {
            media.observeArtistImages(artistId).catch { error ->
                if (error is CancellationException) throw error
                change { it.copy(coverLoadState = LoadState.Failed) }
            }.collect { images -> change { it.copy(coverItems = images.filter { media -> media.type != dev.local.artistgallery.model.MediaType.VIDEO }, coverLoadState = LoadState.Ready) } }
        }
    }

    private fun saveArtistCover() {
        val media = mediaRepository ?: return
        val dialog = state.value.dialog as? GalleryDialog.ArtistCover ?: return
        val assetId = dialog.selectedAssetId ?: return
        if (state.value.coverItems.none { it.id == assetId }) {
            change { it.copy(fieldError = "这张图片已不可用，请重新选择。") }; return
        }
        change { it.copy(isSaving = true, fieldError = null) }
        viewModelScope.launch {
            try {
                if (media.setArtistCover(dialog.artistId, assetId)) {
                    coverJob?.cancel()
                    change { it.copy(dialog = GalleryDialog.None, coverItems = emptyList()) }
                    messages.emit(GalleryFeedback.Saved("画师封面已更新"))
                } else change { it.copy(fieldError = "这张图片已不可用，请重新选择。") }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { change { it.copy(fieldError = "封面保存失败，请重试。") } }
            finally { change { it.copy(isSaving = false) } }
        }
    }

    private fun isMediaPage(current: GalleryUiState): Boolean = current.route is GalleryRoute.Collection ||
        current.route == GalleryRoute.Unassigned || (current.route is GalleryRoute.Artist && current.artistTab == ArtistTab.Images)

    private fun deleteMedia() {
        val media = mediaRepository ?: return
        val importer = mediaImporter ?: return
        val dialog = state.value.dialog
        val bulk = dialog is GalleryDialog.DeleteSelectedMedia
        val ids = when (dialog) {
            is GalleryDialog.DeleteMedia -> listOf(dialog.assetId)
            is GalleryDialog.DeleteSelectedMedia -> {
                if (!state.value.mediaSelectionMode || !isMediaPage(state.value)) return
                dialog.assetIds.distinct()
            }
            else -> return
        }
        if (ids.isEmpty()) return
        change { it.copy(isSaving = true, fieldError = null) }
        viewModelScope.launch {
            val remaining = ids.toMutableSet()
            var completed = 0
            var unavailable = 0
            fun keepRemaining(message: String) {
                change { if (bulk) it.copy(dialog = GalleryDialog.DeleteSelectedMedia(remaining.toList()),
                    mediaSelectionMode = true, selectedMediaIds = remaining.toSet(), fieldError = message)
                    else it.copy(fieldError = message) }
            }
            try {
                deleteMediaCopies(media, ids, importer::deleteOriginal) { deleted, missing ->
                    val finished = deleted + missing
                    completed += deleted.size
                    unavailable += missing.size
                    remaining.removeAll(finished)
                    removeCachedImages(finished)
                }
                if (remaining.isEmpty()) {
                    change { it.copy(dialog = GalleryDialog.None, mediaSelectionMode = if (bulk) false else it.mediaSelectionMode,
                        selectedMediaIds = if (bulk) emptySet() else it.selectedMediaIds) }
                    val message = if (bulk) "已删除 $completed 个图库副本及全部引用，源文件未更改。" +
                        (if (unavailable > 0) "$unavailable 项已不可用。" else "")
                    else if (completed == 0) "这张图片已不可用。" else "已删除图库副本及全部引用，源文件未更改。"
                    messages.emit(GalleryFeedback.Saved(message))
                } else {
                    keepRemaining(if (bulk) "已删除 $completed 个副本，${remaining.size} 个副本删除失败，已保留副本及引用，请重试。"
                        else "图片删除失败，已保留图片及引用，请重试。")
                }
            } catch (cancelled: CancellationException) {
                if (bulk && remaining.isNotEmpty()) keepRemaining("已删除 $completed 个副本，${remaining.size} 个未完成；中断的副本清理会在下次启动时恢复。")
                throw cancelled
            } catch (_: Exception) {
                keepRemaining(if (bulk) "已删除 $completed 个副本，${remaining.size} 个未完成，请重试；中断的副本清理会在下次启动时恢复。"
                    else "删除未完成，请重试；中断任务会在下次启动时恢复。")
            }
            finally { change { it.copy(isSaving = false) } }
        }
    }

    private fun removeCachedImages(ids: Set<String>) {
        if (ids.isEmpty()) return
        parentArtist = parentArtist?.let { cached -> cached.copy(images = cached.images.filterNot { it.id in ids }) }
    }

    private fun forgetEntity(kind: EntitySelectionKind, id: String) {
        if (kind == EntitySelectionKind.Artists) {
            if (parentArtist?.id == id) parentArtist = null
            saveBrowseOptions(persistedBrowseOptions.copy(artistOrder = persistedBrowseOptions.artistOrder - id,
                artistLayouts = persistedBrowseOptions.artistLayouts - id, collectionOrders = persistedBrowseOptions.collectionOrders - id,
                artistImageSorts = persistedBrowseOptions.artistImageSorts - id))
        } else {
            parentArtist = parentArtist?.let { cached -> cached.copy(detail = cached.detail.copy(collections = cached.detail.collections.filterNot { it.id == id })) }
            saveBrowseOptions(persistedBrowseOptions.copy(collectionOrders = persistedBrowseOptions.collectionOrders.mapValues { (_, order) -> order - id },
                collectionMediaSorts = persistedBrowseOptions.collectionMediaSorts - id))
        }
        change { current -> current.copy(
            artists = if (kind == EntitySelectionKind.Artists) current.artists.filterNot { it.id == id } else current.artists,
            artistDetail = if (kind == EntitySelectionKind.Collections) current.artistDetail?.let { detail ->
                detail.copy(collections = detail.collections.filterNot { it.id == id }) } else current.artistDetail,
            selectedEntityIds = current.selectedEntityIds - id) }
    }

    private fun deleteSelectedEntities() {
        val media = mediaRepository ?: return
        val importer = mediaImporter ?: return
        val kind = state.value.entitySelectionKind ?: return
        val ids = when (val dialog = state.value.dialog) {
            is GalleryDialog.DeleteArtists -> if (kind == EntitySelectionKind.Artists) dialog.ids else return
            is GalleryDialog.DeleteCollections -> if (kind == EntitySelectionKind.Collections) dialog.ids else return
            else -> return
        }.distinct()
        if (ids.isEmpty()) return
        change { it.copy(isSaving = true, fieldError = null) }
        viewModelScope.launch {
            val remaining = ids.toMutableSet()
            var completed = 0
            fun keepRemaining(message: String) {
                change { it.copy(dialog = entityDeleteDialog(kind, remaining.toList()), entitySelectionKind = kind,
                    selectedEntityIds = remaining.toSet(), fieldError = message) }
            }
            try {
                for (id in ids) {
                    val deleted = try {
                        val assets = if (kind == EntitySelectionKind.Artists) media.getArtistAssets(id) else media.getCollectionAssets(id)
                        val result = deleteMediaCopies(media, assets.map { it.id }, importer::deleteOriginal) { deleted, missing ->
                            removeCachedImages(deleted + missing)
                        }
                        if (result.failedIds.isNotEmpty()) false else if (kind == EntitySelectionKind.Artists)
                            media.deleteArtistIfEmpty(id) || repository.observeArtist(id).first() == null
                        else media.deleteCollectionIfEmpty(id) || repository.observeCollection(id).first() == null
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { false }
                    if (deleted) {
                        remaining.remove(id)
                        completed++
                        forgetEntity(kind, id)
                    }
                }
                if (remaining.isEmpty()) {
                    change { it.copy(dialog = GalleryDialog.None, entitySelectionKind = null, selectedEntityIds = emptySet()) }
                    messages.emit(GalleryFeedback.Saved("已删除 $completed 个${if (kind == EntitySelectionKind.Artists) "画师" else "作品集"}及图库副本，共享引用已移除，源文件未更改。"))
                } else keepRemaining("已删除 $completed 项，${remaining.size} 项未完成，已保留剩余内容；已删除的副本及共享引用不会恢复，请重试。")
            } catch (cancelled: CancellationException) {
                keepRemaining("已删除 $completed 项，${remaining.size} 项未完成；中断的副本清理会在下次启动时恢复。")
                throw cancelled
            } catch (_: Exception) {
                keepRemaining("已删除 $completed 项，${remaining.size} 项未完成，请重试；中断的副本清理会在下次启动时恢复。")
            } finally { change { it.copy(isSaving = false) } }
        }
    }

    private fun deleteArtist() {
        val media = mediaRepository ?: return
        val importer = mediaImporter ?: return
        val dialog = state.value.dialog as? GalleryDialog.DeleteArtist ?: return
        change { it.copy(isSaving = true, fieldError = null) }
        viewModelScope.launch {
            try {
                val ids = media.getArtistAssets(dialog.id).map { it.id }
                val result = deleteMediaCopies(media, ids, importer::deleteOriginal) { deleted, missing ->
                    removeCachedImages(deleted + missing)
                }
                val failures = result.failedIds.size
                if (failures > 0) {
                    change { it.copy(fieldError = "$failures 个副本删除失败，画师已保留。已删除的副本及共享引用不会恢复，请重试剩余内容。") }
                } else if (media.deleteArtistIfEmpty(dialog.id)) {
                    forgetEntity(EntitySelectionKind.Artists, dialog.id)
                    navigate(GalleryRoute.Home)
                    messages.emit(GalleryFeedback.Saved("已删除画师、作品集及图库副本，共享引用已移除。"))
                } else {
                    change { it.copy(fieldError = "画师内容已变化，删除未完成，请重试。") }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                change { it.copy(fieldError = "删除未完成，画师已保留，请重试；已删除内容不会恢复，中断的副本清理会在下次启动时继续。") }
            } finally { change { it.copy(isSaving = false) } }
        }
    }

    private fun removeReference(assetId: String, collectionId: String) {
        val media = mediaRepository ?: return
        viewModelScope.launch {
            when (media.removeReference(collectionId, assetId)) {
                ReferenceRemovalResult.REMOVED_SHARED -> messages.emit(GalleryFeedback.Saved("已从此作品集移除，共享原图仍保留。"))
                ReferenceRemovalResult.LAST_REFERENCE -> change { it.copy(dialog = GalleryDialog.RemoveLastReference(assetId, collectionId)) }
                ReferenceRemovalResult.NOT_FOUND -> messages.emit(GalleryFeedback.Saved("这项媒体已不在当前作品集中。"))
            }
        }
    }

    private fun keepLastReference() {
        val media = mediaRepository ?: return
        val dialog = state.value.dialog as? GalleryDialog.RemoveLastReference ?: return
        viewModelScope.launch {
            val removed = media.removeLastReferenceAndKeep(dialog.collectionId, dialog.assetId)
            change { it.copy(dialog = GalleryDialog.None) }
            messages.emit(GalleryFeedback.Saved(if (removed) "已移除引用，原图保留在未归组媒体。" else "媒体关系已变化，请刷新后重试。"))
        }
    }

    private fun deleteLastReference() {
        val media = mediaRepository ?: return
        val importer = mediaImporter ?: return
        val dialog = state.value.dialog as? GalleryDialog.RemoveLastReference ?: return
        viewModelScope.launch {
            val asset = media.markLastReferenceForDelete(dialog.collectionId, dialog.assetId)
            if (asset == null) {
                change { it.copy(dialog = GalleryDialog.None) }
                messages.emit(GalleryFeedback.Saved("媒体关系已变化，请刷新后重试。"))
                return@launch
            }
            val deleted = try { importer.deleteOriginal(asset.storageKey) } catch (_: Exception) { false }
            if (deleted) media.completeAssetDelete(asset.id) else media.restoreAssetAfterDeleteFailure(asset.id)
            change { it.copy(dialog = GalleryDialog.None) }
            messages.emit(GalleryFeedback.Saved(if (deleted) "已删除图库副本；系统相册原文件未更改。" else "图库文件删除失败，已保留记录。"))
        }
    }

    private fun submit() {
        val dialog = state.value.dialog
        if (dialog !is GalleryDialog.AddArtist && dialog !is GalleryDialog.CreateCollection && dialog !is GalleryDialog.RenameCollection && dialog !is GalleryDialog.RenameArtist) return
        val artistDraft = when (dialog) {
            is GalleryDialog.AddArtist -> dialog.draft
            is GalleryDialog.RenameArtist -> dialog.draft
            else -> null
        }
        if (artistDraft?.isBlank() == true) {
            change { it.copy(fieldError = "请输入画师名称，不能只填写空格。") }; return
        }
        change { it.copy(isSaving = true, fieldError = null) }
        viewModelScope.launch {
            val result = try { when (dialog) {
                is GalleryDialog.AddArtist -> repository.createArtist(dialog.draft)
                is GalleryDialog.CreateCollection -> repository.createCollection(dialog.artistId, dialog.draft)
                is GalleryDialog.RenameCollection -> repository.renameCollection(dialog.id, dialog.draft)
                is GalleryDialog.RenameArtist -> repository.renameArtist(dialog.id, dialog.draft)
                else -> error("不是保存表单")
            } } catch (e: CancellationException) { throw e }
            catch (_: Exception) { SaveResult.StorageFailure }
            change { it.copy(isSaving = false) }
            when (result) {
                is SaveResult.Saved -> {
                    when (dialog) {
                        is GalleryDialog.AddArtist -> {
                            saveBrowseOptions(persistedBrowseOptions.copy(artistLayouts = persistedBrowseOptions.artistLayouts + (result.id to ArtistImageLayout.List),
                                artistImageSorts = persistedBrowseOptions.artistImageSorts + (result.id to MediaSort.Default)))
                            if (dialog.fromArchiveImport) {
                                change { it.copy(dialog = GalleryDialog.None, fieldError = null) }
                                mutableEffects.emit(GalleryEffect.ResumeArchiveArtistSelection)
                                messages.emit(GalleryFeedback.Saved("已添加画师"))
                            } else {
                                change { it.copy(searchQuery = if (dialog.draft.trim().contains(it.searchQuery.trim())) it.searchQuery else "") }
                                navigate(GalleryRoute.Home)
                                messages.emit(GalleryFeedback.Saved("已添加画师", result.id))
                            }
                        }
                        is GalleryDialog.CreateCollection -> {
                            navigate(GalleryRoute.Collection(result.id, dialog.artistId))
                            messages.emit(GalleryFeedback.Saved("已创建作品集，可随时改名"))
                        }
                        is GalleryDialog.RenameArtist -> {
                            change { it.copy(dialog = GalleryDialog.None, fieldError = null) }
                            messages.emit(GalleryFeedback.Saved("画师名称已更新"))
                        }
                        else -> {
                            change { it.copy(dialog = GalleryDialog.None, fieldError = null) }
                            messages.emit(GalleryFeedback.Saved("作品集名称已更新"))
                        }
                    }
                }
                else -> change { it.copy(fieldError = when (result) {
                    SaveResult.InvalidName -> "请输入画师名称，不能只填写空格。"
                    SaveResult.DuplicateCollectionName -> "该画师已有同名作品集，请换一个名称。"
                    else -> "保存失败，请重试。内容尚未保存。"
                }) }
            }
        }
    }
}
