package dev.local.artistgallery.ui

import androidx.compose.ui.geometry.Rect
import org.junit.Assert.*
import org.junit.Test

class ReorderRowsStateTest {
    private fun rows(): ReorderRowsState = ReorderRowsState(listOf("a", "b", "c", "d")).apply {
        rowBounds["a"] = Rect(0f, 0f, 200f, 60f)
        rowBounds["b"] = Rect(0f, 60f, 200f, 180f)
        rowBounds["c"] = Rect(0f, 180f, 200f, 260f)
        rowBounds["d"] = Rect(0f, 260f, 200f, 360f)
    }

    @Test fun oneDragCrossesMultipleVariableHeightRowsAndCommitsOnce() {
        val state = rows()
        val commits = mutableListOf<List<String>>()
        state.start("a", 30f)
        state.move(300f)
        assertEquals(listOf("b", "c", "d", "a"), state.order)
        state.finish { commits.add(it) }
        state.finish { commits.add(it) }
        assertEquals(listOf(listOf("b", "c", "d", "a")), commits)
        assertNull(state.draggedId)
    }

    @Test fun upwardCrossingAndCancellationRestoreTheOriginalOrder() {
        val state = rows()
        state.start("d", 310f)
        state.move(-300f)
        assertEquals(listOf("d", "a", "b", "c"), state.order)
        state.cancel()
        assertEquals(listOf("a", "b", "c", "d"), state.order)
        assertNull(state.draggedId)
        state.finish { fail("Cancellation must not commit") }
    }

    @Test fun measuredSlotMovementDoesNotMoveTheDraggedRowAwayFromTheFinger() {
        val state = rows()
        state.start("a", 30f)
        state.move(130f)
        assertEquals(listOf("b", "a", "c", "d"), state.order)
        state.rowBounds["a"] = Rect(0f, 120f, 200f, 180f)
        state.rowBounds["b"] = Rect(0f, 0f, 200f, 120f)
        assertEquals(10f, state.translation("a"), .01f)
        assertEquals(160f, state.pointerY, .01f)
        assertEquals(0f, state.translation("b"), .01f)
    }

    @Test fun disablingOrChangingMembershipCancelsTheActiveDrag() {
        val state = rows()
        state.start("a", 30f)
        state.move(300f)
        state.update(listOf("a", "b", "c", "d"), enabled = false)
        assertNull(state.draggedId)
        assertEquals(listOf("a", "b", "c", "d"), state.order)
        state.start("a", 30f)
        state.move(300f)
        state.update(listOf("a", "c", "d"), enabled = true)
        assertNull(state.draggedId)
        assertEquals(listOf("a", "c", "d"), state.order)
    }

    @Test fun scrollingMeasuredRowsReordersWithoutMovingTheFinger() {
        val state = rows()
        state.start("a", 30f)
        state.rowBounds.toMap().forEach { (id, bounds) ->
            state.rowBounds[id] = Rect(bounds.left, bounds.top - 200f, bounds.right, bounds.bottom - 200f)
        }
        state.reorder()
        assertEquals(listOf("b", "c", "a", "d"), state.order)
        assertEquals(30f, state.pointerY, .01f)
        assertEquals(200f, state.translation("a"), .01f)
    }
}
