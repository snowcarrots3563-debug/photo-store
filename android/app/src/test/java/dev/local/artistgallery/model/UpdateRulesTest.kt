package dev.local.artistgallery.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateRulesTest {
    @Test fun missingAcknowledgmentShowsOnlyForAnUpdatedInstallation() {
        assertFalse(UpdateRules.shouldShow(2, null, false))
        assertTrue(UpdateRules.shouldShow(2, null, true))
    }

    @Test fun acknowledgedCurrentOrNewerVersionDoesNotRepeatPopup() {
        assertFalse(UpdateRules.shouldShow(2, 2, true))
        assertFalse(UpdateRules.shouldShow(2, 2, false))
        assertFalse(UpdateRules.shouldShow(2, 3, true))
    }

    @Test fun aNewVersionShowsWhenPreviousVersionWasAcknowledged() {
        assertTrue(UpdateRules.shouldShow(2, 1, true))
        assertTrue(UpdateRules.shouldShow(2, 1, false))
    }

    @Test fun recentReturnsLatestTwoReleasedVersionsInDescendingOrder() {
        val updates = listOf(update(1), update(4), update(2), update(3))
        assertEquals(listOf(update(3), update(2)), UpdateRules.recent(updates, 3))
        assertEquals(listOf(update(2), update(1)), UpdateRules.recent(updates, 2))
        assertEquals(listOf(update(1)), UpdateRules.recent(updates, 1))
    }

    @Test fun recentIsEmptyBeforeTheFirstReleaseOrWithoutHistory() {
        assertEquals(emptyList<VersionUpdate>(), UpdateRules.recent(listOf(update(1), update(2)), 0))
        assertEquals(emptyList<VersionUpdate>(), UpdateRules.recent(emptyList(), 2))
    }

    private fun update(code: Long) = VersionUpdate(code, "0.1.$code", "2026-10-11", listOf("change $code"))
}
