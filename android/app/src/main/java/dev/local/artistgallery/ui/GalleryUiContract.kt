package dev.local.artistgallery.ui

import dev.local.artistgallery.model.*
import dev.local.artistgallery.data.ArtistImageLayout
import java.time.ZoneId

enum class LoadState { Loading, Ready, Failed }
enum class ArtistTab { Images, Collections }
enum class ComicDirection { NameAscending, NameDescending }
enum class EntitySelectionKind { Artists, Collections }
sealed interface GalleryRoute {
    data object Home : GalleryRoute
    data class Artist(val id: String) : GalleryRoute
    data class Collection(val id: String, val artistId: String) : GalleryRoute
    data object Manage : GalleryRoute
    data object Unassigned : GalleryRoute
}
sealed interface GalleryOverlay {
    data object None : GalleryOverlay
    data class CardViewer(val assetId: String) : GalleryOverlay
    data class VideoPlayer(val assetId: String) : GalleryOverlay
    data object ComicReader : GalleryOverlay
}
sealed interface GalleryDialog {
    data object None : GalleryDialog
    data object AddMenu : GalleryDialog
    data class AddArtist(val draft: String = "", val fromArchiveImport: Boolean = false) : GalleryDialog
    data object ArtistMenu : GalleryDialog
    data object CollectionMenu : GalleryDialog
    data class CreateCollection(val artistId: String, val draft: String = "") : GalleryDialog
    data class RenameCollection(val id: String, val draft: String) : GalleryDialog
    data class RenameArtist(val id: String, val draft: String) : GalleryDialog
    data class DeleteArtist(val id: String, val name: String) : GalleryDialog
    data class DeleteArtists(val ids: List<String>) : GalleryDialog
    data class DeleteCollections(val ids: List<String>) : GalleryDialog
    data object ImageDisplayMenu : GalleryDialog
    data object ArtistImageSort : GalleryDialog
    data object CollectionMediaSort : GalleryDialog
    data class Unavailable(val feature: String, val phase: Int) : GalleryDialog
    data object About : GalleryDialog
    data object UpdateHistory : GalleryDialog
    data class ImportTarget(val artistId: String?, val collectionId: String?, val toUnassigned: Boolean = false, val fromFiles: Boolean = false, val allowDefault: Boolean = false) : GalleryDialog
    data object ImportPreview : GalleryDialog
    data object ImportProgress : GalleryDialog
    data class ImportResult(val batchId: String) : GalleryDialog
    data class RemoveLastReference(val assetId: String, val collectionId: String) : GalleryDialog
    data class DeleteMedia(val assetId: String, val name: String) : GalleryDialog
    data class DeleteSelectedMedia(val assetIds: List<String>) : GalleryDialog
    data class ArtistCover(val artistId: String, val selectedAssetId: String? = null) : GalleryDialog
}
data class GalleryScrollPosition(val index: Int = 0, val offset: Int = 0)

// Keep snapshot comparison constant-time when StateFlow publishes on Main.
class PreparedMedia(
    val items: List<MediaAssetSummary>,
    val sort: MediaSort,
    val layout: ArtistImageLayout,
    val zoneId: ZoneId,
    val gridRows: List<ArtistGridRow>,
)

data class GalleryUiState(
    val loadState: LoadState = LoadState.Loading,
    val route: GalleryRoute = GalleryRoute.Home,
    val overlay: GalleryOverlay = GalleryOverlay.None,
    val comicDirection: ComicDirection = ComicDirection.NameAscending,
    val searchQuery: String = "",
    val artistTab: ArtistTab = ArtistTab.Images,
    val artists: List<ArtistSummary> = emptyList(),
    val artistDetail: ArtistDetail? = null,
    val artistHeader: ArtistHeader? = null,
    val collection: CollectionSummary? = null,
    val dialog: GalleryDialog = GalleryDialog.None,
    val isSaving: Boolean = false,
    val isDeleting: Boolean = false,
    val fieldError: String? = null,
    val preparedMedia: PreparedMedia? = null,
    val mediaLoadState: LoadState = LoadState.Loading,
    val scrollPositions: Map<String, GalleryScrollPosition> = emptyMap(),
    val importTargetCollections: List<CollectionSummary> = emptyList(),
    val importCandidates: List<ImportCandidate> = emptyList(),
    val importProgress: ImportProgress? = null,
    val importResult: ImportBatchSummary? = null,
    val pendingImportTargetCollectionId: String? = null,
    val pendingImportDefaultArtistId: String? = null,
    val pendingAddAssetIds: List<String> = emptyList(),
    val recentImportBatches: List<ImportBatchHistorySummary> = emptyList(),
    val selectedMediaIds: Set<String> = emptySet(),
    val mediaSelectionMode: Boolean = false,
    val entitySelectionKind: EntitySelectionKind? = null,
    val selectedEntityIds: Set<String> = emptySet(),
    val coverItems: List<MediaAssetSummary> = emptyList(),
    val coverLoadState: LoadState = LoadState.Loading,
    val browseOptions: dev.local.artistgallery.data.BrowseOptions = dev.local.artistgallery.data.BrowseOptions(),
) {
    val mediaItems: List<MediaAssetSummary> get() = preparedMedia?.items.orEmpty()
}
sealed interface GalleryEvent {
    data class SearchChanged(val query: String) : GalleryEvent
    data object ClearSearch : GalleryEvent
    data class OpenArtist(val id: String) : GalleryEvent
    data class OpenCollection(val id: String, val artistId: String) : GalleryEvent
    data class OpenMedia(val assetId: String) : GalleryEvent
    data object CloseOverlay : GalleryEvent
    data class StepViewer(val direction: Int) : GalleryEvent
    data object OpenComic : GalleryEvent
    data object ToggleComicDirection : GalleryEvent
    data object AddViewerMediaToCollection : GalleryEvent
    data class SwitchArtistTab(val tab: ArtistTab) : GalleryEvent
    data class OpenDialog(val dialog: GalleryDialog) : GalleryEvent
    data class DraftChanged(val draft: String) : GalleryEvent
    data object Submit : GalleryEvent
    data object DismissDialog : GalleryEvent
    data object NavigateBack : GalleryEvent
    data object OpenManage : GalleryEvent
    data object OpenHome : GalleryEvent
    data object RetryLoad : GalleryEvent
    data object OpenUnassigned : GalleryEvent
    data object OpenAddMediaTarget : GalleryEvent
    data class OpenImportResult(val batchId: String) : GalleryEvent
    data class OpenImportTarget(val artistId: String? = null, val collectionId: String? = null, val toUnassigned: Boolean = false, val fromFiles: Boolean = false) : GalleryEvent
    data class ImportArtistSelected(val artistId: String) : GalleryEvent
    data class ImportCollectionSelected(val collectionId: String?) : GalleryEvent
    data object ToggleUnassignedImportTarget : GalleryEvent
    data object ConfirmImportTarget : GalleryEvent
    data class MediaPicked(val uris: List<String>) : GalleryEvent
    data object ConfirmImport : GalleryEvent
    data class RetryImport(val batchId: String) : GalleryEvent
    data class ToggleMediaSelection(val assetId: String) : GalleryEvent
    data class StartMediaSelection(val assetId: String) : GalleryEvent
    data object ExitMediaSelection : GalleryEvent
    data object OpenDeleteSelectedMedia : GalleryEvent
    data object ConfirmDeleteSelectedMedia : GalleryEvent
    data class OpenRemoveMedia(val assetId: String, val collectionId: String) : GalleryEvent
    data object KeepLastReference : GalleryEvent
    data object DeleteLastReference : GalleryEvent
    data class OpenDeleteMedia(val assetId: String) : GalleryEvent
    data object ConfirmDeleteMedia : GalleryEvent
    data object ConfirmDeleteArtist : GalleryEvent
    data class StartEntitySelection(val kind: EntitySelectionKind, val id: String) : GalleryEvent
    data class ToggleEntitySelection(val id: String) : GalleryEvent
    data object ExitEntitySelection : GalleryEvent
    data object OpenDeleteSelectedEntities : GalleryEvent
    data object ConfirmDeleteSelectedEntities : GalleryEvent
    data class ReorderArtists(val ids: List<String>) : GalleryEvent
    data class ReorderCollections(val artistId: String, val ids: List<String>) : GalleryEvent
    data object CycleImageLayout : GalleryEvent
    data object DismissStorageNotice : GalleryEvent
    data class RememberScrollPosition(val key: String, val index: Int, val offset: Int) : GalleryEvent
    data class SetImageLayout(val layout: dev.local.artistgallery.data.ArtistImageLayout) : GalleryEvent
    data class SetArtistImageSort(val sort: MediaSort) : GalleryEvent
    data class SetCollectionMediaSort(val sort: MediaSort) : GalleryEvent
    data class OpenArtistCover(val artistId: String) : GalleryEvent
    data class SelectArtistCover(val assetId: String) : GalleryEvent
    data object ConfirmArtistCover : GalleryEvent
    data class SetCardEffects(val enabled: Boolean) : GalleryEvent
    data class SetPlayGifs(val enabled: Boolean) : GalleryEvent
    data class SetSwipeDelete(val enabled: Boolean) : GalleryEvent
    data class SetAutoPlayVideos(val enabled: Boolean) : GalleryEvent
}
sealed interface GalleryFeedback {
    data class Saved(val message: String, val scrollToArtistId: String? = null) : GalleryFeedback
    data class Deleted(val count: Int) : GalleryFeedback
}

sealed interface GalleryEffect {
    data object ResumeArchiveArtistSelection : GalleryEffect
    data class LaunchMediaPicker(val targetCollectionId: String?, val fromFiles: Boolean = false) : GalleryEffect
}
