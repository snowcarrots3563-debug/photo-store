package dev.local.artistgallery.data.importing

import dev.local.artistgallery.model.ImportBatchSummary
import dev.local.artistgallery.model.ImportItemStatus
import dev.local.artistgallery.model.ImportItemSummary
import org.junit.Assert.*
import org.junit.Test

class ImportNotificationRulesTest {
    @Test fun completedAndReusedItemsAreSuccessfulCounts() {
        val result = batch(ImportItemStatus.COMPLETE, ImportItemStatus.REUSED).toImportCompletion()
        assertEquals(1, result.added); assertEquals(1, result.reused)
        assertEquals("导入完成", result.title)
        assertTrue(result.shouldNotify(false))
    }

    @Test fun failureAlongsideSuccessIsPartial() {
        val result = batch(ImportItemStatus.COMPLETE, ImportItemStatus.FAILED).toImportCompletion()
        assertEquals("导入部分完成", result.title); assertEquals(1, result.unresolved)
    }

    @Test fun allFailuresAreUnfinished() {
        val result = batch(ImportItemStatus.FAILED).toImportCompletion()
        assertEquals("导入未完成", result.title); assertEquals(0, result.added)
    }

    @Test fun pendingStatusesAreCountedAsUnresolved() {
        val result = batch(ImportItemStatus.QUEUED, ImportItemStatus.COPYING, ImportItemStatus.STAGED, ImportItemStatus.FINALIZED).toImportCompletion()
        assertEquals(4, result.unresolved); assertEquals("导入未完成", result.title)
    }

    @Test fun skippedItemsAreNotReportedAsAddedSuccess() {
        val result = batch(ImportItemStatus.SKIPPED).toImportCompletion()
        assertEquals(0, result.added + result.reused); assertEquals(1, result.skipped)
        assertEquals("导入部分完成", result.title)
    }

    @Test fun cancelledItemsNeverNotify() {
        val result = batch(ImportItemStatus.COMPLETE, ImportItemStatus.CANCELLED).toImportCompletion()
        assertTrue(result.cancelled); assertFalse(result.shouldNotify(false))
        assertEquals(0, result.unresolved)
    }

    @Test fun cancelledBatchWithoutCancelledItemsNeverNotifies() {
        val result = batch(ImportItemStatus.COMPLETE).copy(status = "PARTIAL_CANCELLED").toImportCompletion()
        assertFalse(result.shouldNotify(false))
    }

    @Test fun foregroundSuppressesOtherwiseSuccessfulNotification() {
        assertFalse(batch(ImportItemStatus.COMPLETE).toImportCompletion().shouldNotify(true))
    }

    @Test fun tagsDistinguishKindAndBatchButRetryKeepsTag() {
        val first = batch(ImportItemStatus.COMPLETE).toImportCompletion()
        assertEquals(first.notificationTag, first.copy(added = 2).notificationTag)
        assertNotEquals(first.notificationTag, first.copy(batchId = "other").notificationTag)
        assertNotEquals(first.notificationTag, first.copy(kind = ImportResultKind.Archive).notificationTag)
    }

    @Test fun emptyResultIsNotSuccessfulAndContainsOnlyCounts() {
        val result = batch().toImportCompletion()
        assertEquals("导入未完成", result.title)
        assertEquals("新增 0 · 复用 0 · 跳过 0 · 失败或待恢复 0", result.message)
    }

    @Test fun newApplicationStartsInBackground() {
        assertFalse(ImportAppVisibility().isVisible)
    }

    @Test fun finalStoppedActivityMarksApplicationBackground() {
        val visibility = ImportAppVisibility()
        visibility.started(); assertTrue(visibility.isVisible)
        visibility.stopped(false); assertFalse(visibility.isVisible)
    }

    @Test fun visibleSecondActivityKeepsApplicationForeground() {
        val visibility = ImportAppVisibility()
        visibility.started(); visibility.started()
        visibility.stopped(false); assertTrue(visibility.isVisible)
        visibility.stopped(false); assertFalse(visibility.isVisible)
    }

    @Test fun configurationGapKeepsForegroundUntilReplacementStops() {
        val visibility = ImportAppVisibility()
        visibility.started(); visibility.stopped(true)
        assertTrue(visibility.isVisible)
        visibility.started(); assertTrue(visibility.isVisible)
        visibility.stopped(false); assertFalse(visibility.isVisible)
    }

    @Test fun anotherWindowStoppingCannotClearAnExistingConfigurationGap() {
        val visibility = ImportAppVisibility()
        visibility.started(); visibility.started()
        visibility.stopped(true); visibility.stopped(false)
        assertTrue(visibility.isVisible)
        visibility.started(); visibility.stopped(false)
        assertFalse(visibility.isVisible)
    }

    private fun batch(vararg statuses: ImportItemStatus) = ImportBatchSummary("fixture-batch", "COMPLETED",
        statuses.mapIndexed { index, status -> ImportItemSummary("$index", "private-name-$index.png", status, null, false) })
}
