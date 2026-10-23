package dev.local.artistgallery.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.updateTransition
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

internal val LocalDDialogActive = compositionLocalOf { true }
internal val DPopupSlide = SemanticsPropertyKey<Float>("PopupSlideDp")
internal val DPopupOpacity = SemanticsPropertyKey<Float>("PopupOpacity")

/** Keeps only the last presentation during exit. Business hide/dismiss always happens immediately. */
@Composable
internal fun <T : Any> DAnimatedDialog(
    value: T?,
    onDismissRequest: () -> Unit,
    properties: DialogProperties = DialogProperties(decorFitsSystemWindows = false),
    centered: Boolean = false,
    contentKey: Any? = Unit,
    content: @Composable (T, Modifier, Float) -> Unit,
) {
    val parentActive = LocalDDialogActive.current
    val active = value != null && parentActive
    var lastValue by remember { mutableStateOf(value) }
    var shownKey by remember { mutableStateOf(contentKey) }
    val replacing = active && lastValue != null && shownKey != contentKey
    if (active) { lastValue = value; shownKey = contentKey }
    val visibility = remember { MutableTransitionState(false) }
    visibility.targetState = active
    if (!visibility.currentState && !visibility.targetState && visibility.isIdle) {
        lastValue = null
        return
    }
    val entry = CubicBezierEasing(.215f, .61f, .355f, 1f)
    val quickEntry = CubicBezierEasing(.2f, .8f, .2f, 1f)
    val exit = CubicBezierEasing(.23f, 1f, .32f, 1f)
    val presentation = lastValue ?: return
    val page = remember(shownKey) { Animatable(if (replacing) 0f else 1f) }
    LaunchedEffect(page) { if (page.value < 1f) page.animateTo(1f, tween(240, easing = entry)) }
    val currentDismiss by rememberUpdatedState(onDismissRequest)
    Dialog(onDismissRequest = { if (active) currentDismiss() }, properties = properties) {
        val focus = LocalFocusManager.current
        val keyboard = LocalSoftwareKeyboardController.current
        LaunchedEffect(active) {
            if (!active) { focus.clearFocus(force = true); keyboard?.hide() }
        }
        CompositionLocalProvider(LocalDDialogActive provides active) {
            BoxWithConstraints(Modifier.fillMaxSize().then(if (active) Modifier else Modifier
                .clearAndSetSemantics { }
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
                    }
                })) {
                val transition = updateTransition(visibility, label = "dialogVisibility")
                val opacity = transition.animateFloat(transitionSpec = { if (targetState) tween(if (centered) 400 else 0, easing = entry) else tween(180, easing = exit) }, label = "dialogOpacity") { if (it) 1f else 0f }
                val slide = transition.animateFloat(transitionSpec = { if (targetState) tween(if (centered) 400 else 220, easing = if (centered) entry else quickEntry) else tween(180, easing = exit) }, label = "dialogSlide") {
                    if (it) 0f else if (active) maxHeight.value else 55f
                }
                val scrim = transition.animateFloat(transitionSpec = { tween(if (targetState) { if (centered) 200 else 120 } else 160, easing = exit) }, label = "dialogScrim") { if (it) 1f else 0f }
                content(presentation, Modifier.graphicsLayer {
                    alpha = opacity.value * page.value
                    if (centered) {
                        scaleX = .96f + .04f * opacity.value
                        scaleY = scaleX
                    } else translationY = (slide.value + 12f * (1f - page.value)).dp.toPx()
                }.semantics {
                    this[DPopupSlide] = if (centered) 0f else slide.value + 12f * (1f - page.value)
                    this[DPopupOpacity] = opacity.value * page.value
                }, scrim.value)
            }
        }
    }
}
