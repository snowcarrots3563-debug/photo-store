package dev.local.artistgallery.data

import org.junit.Assert.assertEquals
import org.junit.Test

class OrganizationOrderTest {
    @Test fun filteredReorderPreservesHiddenPositionsAndIgnoresUnknownOrDuplicateIds() {
        assertEquals(listOf("d", "hidden", "a", "c"),
            reorderVisibleIds(listOf("a", "hidden", "c", "d"), listOf("d", "missing", "d", "a", "c")))
        assertEquals(listOf("a", "b"), reorderVisibleIds(listOf("a", "b"), emptyList()))
    }

    @Test fun savedOrderFiltersDeletedIdsAndAppendsNewItemsInTheirOriginalOrder() {
        assertEquals(listOf("c", "a", "new1", "new2"),
            orderedEntities(listOf("a", "new1", "c", "new2"), listOf("deleted", "c", "a")) { it })
    }
}
