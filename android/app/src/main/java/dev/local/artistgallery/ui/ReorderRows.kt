package dev.local.artistgallery.ui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.layout.LocalPinnableContainer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import kotlinx.coroutines.CancellationException

/** Keeps the dragged row under the finger while its measured, variable-height slots move. */
internal class ReorderRowsState(ids: List<String>) {
    var order by mutableStateOf(ids)
        private set
    var draggedId by mutableStateOf<String?>(null)
        private set
    private var draggedTop by mutableFloatStateOf(0f)
    var pointerY = 0f
        private set
    var viewport = Rect.Zero
    val rowBounds = mutableStateMapOf<String, Rect>()
    var beforeOrderChange: () -> Unit = {}
    private var originalOrder = ids

    fun removeRow(id: String) { rowBounds.remove(id) }

    fun update(ids: List<String>, enabled: Boolean) {
        if (!enabled || ids.toSet() != order.toSet()) cancel()
        if (draggedId == null) order = ids
    }

    fun start(id: String, fingerY: Float) {
        val bounds = rowBounds[id] ?: return
        originalOrder = order.toList()
        draggedId = id
        draggedTop = bounds.top
        pointerY = fingerY
    }

    fun move(delta: Float) {
        if (draggedId == null) return
        pointerY += delta
        draggedTop += delta
        reorder()
    }

    fun reorder() {
        val id = draggedId ?: return
        val bounds = rowBounds[id] ?: return
        val center = draggedTop + bounds.height / 2f
        val index = order.indexOf(id)
        if (index < 0) return
        var target = index
        for ((otherId, otherBounds) in rowBounds) {
            if (otherId == id || otherBounds.bottom <= viewport.top || otherBounds.top >= viewport.bottom) continue
            val otherIndex = order.indexOf(otherId)
            if (center > bounds.center.y && otherIndex > target && center > otherBounds.center.y) target = otherIndex
            if (center < bounds.center.y && otherIndex in 0 until target && center < otherBounds.center.y) target = otherIndex
        }
        if (target == index) return
        val moved = order.toMutableList()
        moved.add(target, moved.removeAt(index))
        beforeOrderChange()
        order = moved
    }

    fun translation(id: String): Float = if (id == draggedId) draggedTop - (rowBounds[id]?.top ?: draggedTop) else 0f

    fun finish(commit: (List<String>) -> Unit) {
        val changed = draggedId != null && order != originalOrder
        draggedId = null
        if (changed) commit(order.toList())
    }

    fun cancel() {
        if (draggedId != null && order != originalOrder) {
            beforeOrderChange()
            order = originalOrder
        }
        draggedId = null
    }
}

@Composable
internal fun rememberReorderRows(ids: List<String>, enabled: Boolean, listState: LazyListState): ReorderRowsState {
    val state = remember { ReorderRowsState(ids) }
    SideEffect {
        state.beforeOrderChange = {
            // A dragged first-visible key must not pull the viewport along with its new index.
            listState.requestScrollToItem(listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset)
        }
    }
    val edge = with(LocalDensity.current) { 56.dp.toPx() }
    val speed = with(LocalDensity.current) { 800.dp.toPx() }
    // Both lists reserve this space for the floating bottom navigation.
    val navigationSpace = with(LocalDensity.current) { 126.dp.toPx() }
    LaunchedEffect(ids, enabled) { state.update(ids, enabled) }
    LaunchedEffect(state.draggedId) {
        if (state.draggedId == null) return@LaunchedEffect
        var previous = withFrameNanos { it }
        while (state.draggedId != null) {
            val now = withFrameNanos { it }
            val seconds = ((now - previous) / 1_000_000_000f).coerceAtMost(.05f)
            previous = now
            val viewport = state.viewport
            val direction = when {
                state.pointerY < viewport.top + edge -> -((viewport.top + edge - state.pointerY) / edge).coerceAtMost(1f)
                state.pointerY > viewport.bottom - navigationSpace - edge -> ((state.pointerY - viewport.bottom + navigationSpace + edge) / edge).coerceAtMost(1f)
                else -> 0f
            }
            if (direction != 0f && viewport.height > 0f) listState.scrollBy(direction * speed * seconds)
            state.reorder()
        }
    }
    return state
}

@Composable
internal fun ReorderRow(state: ReorderRowsState, id: String, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    DisposableEffect(state, id) { onDispose { state.removeRow(id) } }
    Box(modifier.onGloballyPositioned {
        val position = it.positionInRoot()
        state.rowBounds[id] = Rect(position.x, position.y, position.x + it.size.width, position.y + it.size.height)
    }.zIndex(if (state.draggedId == id) 1f else 0f)) {
        Box(Modifier.graphicsLayer { translationY = state.translation(id); shadowElevation = if (state.draggedId == id) 8.dp.toPx() else 0f }) { content() }
    }
}

@Composable
internal fun ReorderHandle(state: ReorderRowsState, id: String, name: String, enabled: Boolean, onCommit: (List<String>) -> Unit) {
    var bounds by remember { mutableStateOf(Rect.Zero) }
    val currentCommit by rememberUpdatedState(onCommit)
    val pinnableContainer = LocalPinnableContainer.current
    Box(Modifier.size(48.dp).testTag("reorder:$id").semantics { contentDescription = "长按拖动排序：$name" }
        .onGloballyPositioned {
            val position = it.positionInRoot()
            bounds = Rect(position.x, position.y, position.x + it.size.width, position.y + it.size.height)
        }
        .pointerInput(state, id, enabled, pinnableContainer) {
            if (!enabled) return@pointerInput
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                // The handle owns the press, so a parent row cannot enter multi-selection too.
                down.consume()
                val press = awaitLongPressOrCancellation(down.id) ?: return@awaitEachGesture
                val pinned = pinnableContainer?.pin()
                try {
                    state.start(id, bounds.top + press.position.y)
                    val released = drag(press.id) { change -> state.move(change.positionChange().y); change.consume() }
                    if (released) state.finish(currentCommit) else state.cancel()
                } catch (cancelled: CancellationException) {
                    state.cancel()
                    throw cancelled
                } finally {
                    pinned?.release()
                }
            }
        }, contentAlignment = Alignment.Center) {
        DIconImage(DIcon.Drag, if (enabled) DColors.Muted else DColors.Muted.copy(alpha = .4f))
    }
}
