package dev.local.artistgallery.ui

import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.updateTransition
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

internal val LocalDDialogActive = compositionLocalOf { true }

/** Keeps only the last presentation during exit. Business hide/dismiss always happens immediately. */
@Composable
internal fun <T : Any> DAnimatedDialog(
    value: T?,
    onDismissRequest: () -> Unit,
    properties: DialogProperties = DialogProperties(decorFitsSystemWindows = false),
    centered: Boolean = false,
    content: @Composable (T, Modifier, Float) -> Unit,
) {
    val parentActive = LocalDDialogActive.current
    val active = value != null && parentActive
    var lastValue by remember { mutableStateOf(value) }
    if (value != null && parentActive) lastValue = value
    val visibility = remember { MutableTransitionState(false) }
    visibility.targetState = active
    val transition = updateTransition(visibility, label = "dialogVisibility")
    val fraction by transition.animateFloat(transitionSpec = { tween(400, easing = LinearOutSlowInEasing) }, label = "dialogFraction") { if (it) 1f else 0f }
    if (!visibility.currentState && !visibility.targetState && visibility.isIdle) {
        lastValue = null
        return
    }
    val presentation = lastValue ?: return
    val currentDismiss by rememberUpdatedState(onDismissRequest)
    Dialog(onDismissRequest = { if (active) currentDismiss() }, properties = properties) {
        val focus = LocalFocusManager.current
        val keyboard = LocalSoftwareKeyboardController.current
        LaunchedEffect(active) {
            if (!active) { focus.clearFocus(force = true); keyboard?.hide() }
        }
        CompositionLocalProvider(LocalDDialogActive provides active) {
            Box(Modifier.fillMaxSize().then(if (active) Modifier else Modifier
                .clearAndSetSemantics { }
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
                    }
                })) {
                content(presentation, Modifier.graphicsLayer {
                    alpha = fraction
                    if (centered) {
                        scaleX = .96f + .04f * fraction
                        scaleY = scaleX
                    } else translationY = 24.dp.toPx() * (1f - fraction)
                }, fraction)
            }
        }
    }
}
