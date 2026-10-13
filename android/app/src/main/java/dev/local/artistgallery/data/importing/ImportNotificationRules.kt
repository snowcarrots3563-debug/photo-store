package dev.local.artistgallery.data.importing

import dev.local.artistgallery.model.ImportBatchSummary
import dev.local.artistgallery.model.ImportItemStatus

enum class ImportResultKind { Media, Archive }

data class ImportResultTarget(val kind: ImportResultKind, val batchId: String)

data class ImportCompletion(
    val kind: ImportResultKind,
    val batchId: String,
    val added: Int,
    val reused: Int,
    val skipped: Int,
    val unresolved: Int,
    val cancelled: Boolean = false,
) {
    val target get() = ImportResultTarget(kind, batchId)
    val notificationTag get() = "import-result:${kind.name}:$batchId"
    val title: String get() = when {
        added + reused == 0 && (unresolved > 0 || skipped == 0) -> "导入未完成"
        unresolved > 0 || skipped > 0 -> "导入部分完成"
        else -> "导入完成"
    }
    val message get() = "新增 $added · 复用 $reused · 跳过 $skipped · 失败或待恢复 $unresolved"
    fun shouldNotify(isVisible: Boolean) = !isVisible && !cancelled && batchId.isNotBlank()
}

fun ImportBatchSummary.toImportCompletion() = ImportCompletion(
    kind = ImportResultKind.Media,
    batchId = id,
    added = items.count { it.status == ImportItemStatus.COMPLETE },
    reused = items.count { it.status == ImportItemStatus.REUSED },
    skipped = items.count { it.status == ImportItemStatus.SKIPPED },
    unresolved = items.count { it.status !in setOf(ImportItemStatus.COMPLETE, ImportItemStatus.REUSED, ImportItemStatus.SKIPPED, ImportItemStatus.CANCELLED) },
    cancelled = items.any { it.status == ImportItemStatus.CANCELLED } || status.endsWith("CANCELLED"),
)

/** Started activities include visible multi-window activities, even when not resumed. */
internal class ImportAppVisibility {
    private var startedCount = 0
    private var changingConfiguration = false
    @Volatile var isVisible = false
        private set

    @Synchronized fun started() {
        startedCount++
        changingConfiguration = false
        isVisible = true
    }

    @Synchronized fun stopped(isChangingConfigurations: Boolean) {
        startedCount = (startedCount - 1).coerceAtLeast(0)
        if (isChangingConfigurations) changingConfiguration = true
        isVisible = startedCount > 0 || changingConfiguration
    }
}
