package dev.local.artistgallery.ui

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
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
internal class ReorderRowsState(ids: List<String>, private val listState: LazyListState? = null) {
    var order by mutableStateOf(ids)
        private set
    var draggedId by mutableStateOf<String?>(null)
        private set
    var settlingId by mutableStateOf<String?>(null)
        private set
    var settleGeneration by mutableIntStateOf(0)
        private set
    private var releasedTop = 0f
    private var settleFraction by mutableFloatStateOf(0f)
    private var draggedTop by mutableFloatStateOf(0f)
    var pointerY = 0f
        private set
    var viewport = Rect.Zero
    val rowBounds = mutableStateMapOf<String, Rect>()
    private val itemKeys = mutableMapOf<String, Any>()
    private val visualTops = mutableMapOf<String, Float>()
    var beforeOrderChange: () -> Unit = {}
    private var originalOrder = ids

    fun removeRow(id: String) {
        rowBounds.remove(id)
        itemKeys.remove(id)
        visualTops.remove(id)
        if (draggedId == id) draggedId = null
        if (settlingId == id) clearSettle()
    }

    fun measure(id: String, itemKey: Any, bounds: Rect) {
        itemKeys[id] = itemKey
        visualTops[id] = bounds.top
        rowBounds[id] = slotBounds(id, bounds) ?: bounds
    }

    private fun slotBounds(id: String, measured: Rect? = rowBounds[id]): Rect? {
        val bounds = measured ?: return null
        val layout = listState?.layoutInfo ?: return bounds
        val item = layout.visibleItemsInfo.firstOrNull { it.key == itemKeys[id] }
            ?: return if (id == draggedId || id == settlingId) bounds else null
        val top = viewport.top + layout.beforeContentPadding + item.offset
        return Rect(bounds.left, top, bounds.right, top + item.size)
    }

    fun update(ids: List<String>, enabled: Boolean) {
        if (!enabled || ids.toSet() != order.toSet()) { cancel(); clearSettle() }
        if (draggedId == null) order = ids
    }

    fun start(id: String, fingerY: Float) {
        val bounds = slotBounds(id) ?: return
        val top = (visualTops[id] ?: bounds.top) + translation(id)
        clearSettle()
        originalOrder = order.toList()
        draggedId = id
        draggedTop = top
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
        val bounds = slotBounds(id) ?: return
        val center = draggedTop + bounds.height / 2f
        val index = order.indexOf(id)
        if (index < 0) return
        var target = index
        for (otherId in rowBounds.keys) {
            val otherBounds = slotBounds(otherId) ?: continue
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

    fun translation(id: String): Float = when (id) {
        draggedId -> draggedTop - (slotBounds(id)?.top ?: draggedTop)
        settlingId -> (releasedTop - (slotBounds(id)?.top ?: releasedTop)) * settleFraction
        else -> 0f
    }

    private fun beginSettle() {
        val id = draggedId ?: return
        releasedTop = draggedTop
        settleFraction = 1f
        settlingId = id
        settleGeneration++
        draggedId = null
    }

    fun settle(generation: Int, fraction: Float) {
        if (generation == settleGeneration && settlingId != null) settleFraction = fraction
    }

    fun completeSettle(generation: Int) {
        if (generation == settleGeneration) clearSettle()
    }

    private fun clearSettle() {
        if (settlingId != null) settleGeneration++
        settlingId = null
        settleFraction = 0f
    }

    fun finish(commit: (List<String>) -> Unit) {
        val changed = draggedId != null && order != originalOrder
        beginSettle()
        if (changed) commit(order.toList())
    }

    fun cancel() {
        if (draggedId != null && order != originalOrder) {
            beforeOrderChange()
            order = originalOrder
        }
        beginSettle()
    }
}

@Composable
internal fun rememberReorderRows(ids: List<String>, enabled: Boolean, listState: LazyListState): ReorderRowsState {
    val state = remember(listState) { ReorderRowsState(ids, listState) }
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
    LaunchedEffect(state.settleGeneration) {
        if (state.settlingId == null) return@LaunchedEffect
        val generation = state.settleGeneration
        // Let the newly ordered slots be placed before the release starts moving.
        withFrameNanos { }
        animate(1f, 0f, animationSpec = spring(stiffness = Spring.StiffnessMediumLow, dampingRatio = .85f)) { value, _ ->
            state.settle(generation, value)
        }
        state.completeSettle(generation)
    }
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
internal fun ReorderRow(state: ReorderRowsState, id: String, modifier: Modifier = Modifier, itemKey: Any = id, content: @Composable () -> Unit) {
    val active = state.draggedId == id || state.settlingId == id
    val lift = animateFloatAsState(if (active) 1f else 0f, spring(stiffness = Spring.StiffnessMediumLow), label = "reorderLift")
    DisposableEffect(state, id) { onDispose { state.removeRow(id) } }
    Box(modifier.onGloballyPositioned {
        val position = it.positionInRoot()
        state.measure(id, itemKey, Rect(position.x, position.y, position.x + it.size.width, position.y + it.size.height))
    }.zIndex(if (active) 1f else 0f)) {
        Box(Modifier.graphicsLayer {
            translationY = state.translation(id)
            shadowElevation = 8.dp.toPx() * lift.value
            shape = RoundedCornerShape(20.dp * lift.value.coerceIn(0f, 1f))
            clip = lift.value > 0f
        }) { content() }
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
