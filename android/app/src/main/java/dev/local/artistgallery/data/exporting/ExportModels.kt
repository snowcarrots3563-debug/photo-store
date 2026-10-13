package dev.local.artistgallery.data.exporting

import dev.local.artistgallery.data.backup.BackupAsset
import dev.local.artistgallery.data.backup.BackupManifest

sealed interface ExportScope {
    data class Assets(val ids: List<String>) : ExportScope
    data class Collections(val ids: List<String>) : ExportScope
    data class Artists(val ids: List<String>) : ExportScope
    data object Library : ExportScope
}

data class GallerySnapshot(val manifest: BackupManifest, val storageKeys: Map<String, String>)
data class ExportEntry(val path: String, val assetId: String? = null)
data class ExportPlan(
    val label: String,
    val entries: List<ExportEntry>,
    val assets: List<BackupAsset>,
    val storageKeys: Map<String, String>,
    val manifest: BackupManifest? = null,
) {
    val fileCount: Int get() = entries.count { it.assetId != null }
    val totalBytes: Long get() { val sizes = assets.associate { it.id to it.byteSize }; return entries.fold(0L) { sum, entry -> Math.addExact(sum, entry.assetId?.let { sizes.getValue(it) } ?: 0L) } }
}

data class ExportOutcome(val fileCount: Int, val byteSize: Long, val sha256: String)
enum class TransferKind { Images, Collections, Artists, Library, Backup, Restore }
enum class TransferStep { Scope, Selection, Preparing, Preview, Destination, Working, RestorePick, RestorePreview, Result }
data class TransferChoice(val id: String, val title: String, val subtitle: String)
data class LocalTransferState(
    val visible: Boolean = false,
    val kind: TransferKind = TransferKind.Library,
    val step: TransferStep = TransferStep.Scope,
    val choices: List<TransferChoice> = emptyList(),
    val selected: Set<String> = emptySet(),
    val label: String = "",
    val fileName: String = "",
    val assetCount: Int = 0,
    val fileCount: Int = 0,
    val artistCount: Int = 0,
    val collectionCount: Int = 0,
    val referenceCount: Int = 0,
    val totalBytes: Long = 0,
    val progress: String = "",
    val message: String? = null,
    val success: Boolean = false,
    val canCancel: Boolean = true,
    val warning: String? = null,
)
