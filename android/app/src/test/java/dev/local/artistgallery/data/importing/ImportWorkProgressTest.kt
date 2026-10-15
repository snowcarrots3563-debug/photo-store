package dev.local.artistgallery.data.importing

import org.junit.Assert.*
import org.junit.Test

class ImportWorkProgressTest {
    @Test fun preparingAndEmptyTotalsAreIndeterminate() {
        assertTrue(progress(4, null).isIndeterminate)
        assertTrue(progress(4, 0).isIndeterminate)
        assertTrue(progress(4, -1).isIndeterminate)
    }

    @Test fun knownTotalsClampCountsWithoutClaimingSuccess() {
        assertEquals(0L, progress(-3, 10).processed)
        assertEquals(10L, progress(15, 10).processed)
        assertEquals(10, progress(15, 10).barValue)
        assertEquals(4, progress(4, 10).barValue)
        assertEquals(10, progress(4, 10).barMaximum)
        assertFalse(progress(4, 10).isIndeterminate)
    }

    @Test fun hugeTotalsCannotOverflowOrRoundUnfinishedWorkUpToFull() {
        val unfinished = progress(Long.MAX_VALUE - 1, Long.MAX_VALUE)
        assertEquals(Int.MAX_VALUE, unfinished.barMaximum)
        assertTrue(unfinished.barValue in 0 until unfinished.barMaximum)
        assertEquals(Int.MAX_VALUE, progress(Long.MAX_VALUE, Long.MAX_VALUE).barValue)
    }

    private fun progress(completed: Long, total: Long?) = ImportWorkProgress("导入中", "处理中", completed, total)
}
