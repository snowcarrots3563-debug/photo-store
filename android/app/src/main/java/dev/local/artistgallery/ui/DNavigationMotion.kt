package dev.local.artistgallery.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.SemanticsPropertyReceiver
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

internal val DMotionFraction = SemanticsPropertyKey<Float>("MotionFraction")
internal var SemanticsPropertyReceiver.motionFraction by DMotionFraction
internal val DMotionDirection = SemanticsPropertyKey<Int>("MotionDirection")
internal var SemanticsPropertyReceiver.motionDirection by DMotionDirection

/** Only the current screen is composed; route changes never retain a second business tree. */
@Composable
internal fun DRouteMotion(route: GalleryRoute, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    var previous by remember { mutableStateOf(route) }
    val (direction, fraction) = remember(route) {
        val direction = if (route == previous) 0 else routeDirection(previous, route)
        previous = route
        direction to Animatable(if (direction == 0) 1f else 0f)
    }
    LaunchedEffect(fraction) { fraction.animateTo(1f, tween(280, easing = FastOutSlowInEasing)) }
    Box(modifier.graphicsLayer {
        translationX = direction * 22.dp.toPx() * (1f - fraction.value)
        alpha = .75f + .25f * fraction.value
    }.semantics { motionFraction = fraction.value; motionDirection = direction }) { content() }
}

private fun routeDirection(from: GalleryRoute, to: GalleryRoute): Int {
    fun depth(route: GalleryRoute): Int = when (route) {
        GalleryRoute.Home, GalleryRoute.Manage -> 0
        is GalleryRoute.Artist, GalleryRoute.Unassigned -> 1
        is GalleryRoute.Collection -> 2
    }
    return if (depth(to) < depth(from) || to == GalleryRoute.Home) -1 else 1
}

/** Kind, rather than asset identity, owns entry; paging never replays the surface animation. */
@Composable
internal fun DOverlaySurfaceMotion(overlay: GalleryOverlay, content: @Composable (Modifier, Modifier) -> Unit) {
    val kind = when (overlay) {
        GalleryOverlay.None -> 0
        is GalleryOverlay.CardViewer -> 1
        is GalleryOverlay.VideoPlayer -> 2
        GalleryOverlay.ComicReader -> 3
    }
    var previousKind by remember { mutableIntStateOf(kind) }
    val fraction = remember(kind) {
        val changed = previousKind != kind
        previousKind = kind
        Animatable(if (changed) 0f else 1f)
    }
    LaunchedEffect(fraction) { fraction.animateTo(1f, tween(280, easing = FastOutSlowInEasing)) }
    val surface = Modifier.graphicsLayer {
        val value = if (kind == 0) fraction.value else 1f
        alpha = .9f + .1f * value
        scaleX = .985f + .015f * value
        scaleY = scaleX
        translationY = 6.dp.toPx() * (1f - value)
    }.semantics { motionFraction = if (kind == 0) fraction.value else 1f }
    val viewer = Modifier.graphicsLayer {
        alpha = .9f + .1f * fraction.value
        scaleX = .98f + .02f * fraction.value
        scaleY = scaleX
        translationY = 12.dp.toPx() * (1f - fraction.value)
    }.semantics { motionFraction = fraction.value }
    content(surface, viewer)
}
