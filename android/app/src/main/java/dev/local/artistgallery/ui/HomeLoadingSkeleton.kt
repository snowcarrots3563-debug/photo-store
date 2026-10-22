package dev.local.artistgallery.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

@Composable
internal fun rememberHomeLoadingPhase(): State<Float> {
    val transition = rememberInfiniteTransition(label = "homeLoading")
    return transition.animateFloat(-1f, 2f,
        infiniteRepeatable(tween(1200, easing = LinearEasing), RepeatMode.Restart), label = "homeLoadingSweep")
}

@Composable
internal fun HomeLoadingSkeleton(phase: State<Float>) {
    DGroup(Modifier.testTag("artistHomeLoading").semantics(mergeDescendants = true) {
        contentDescription = "正在读取图库"
        progressBarRangeInfo = ProgressBarRangeInfo.Indeterminate
    }) {
        repeat(5) { index ->
            if (index > 0) DDivider()
            // Match DRow's cover and insets so fractional densities round the same way.
            HomeLoadingSkeletonRow(phase, Modifier.fillMaxWidth().testTag("artistHomeLoadingRow:$index")
                .heightIn(min = 76.dp).padding(vertical = 18.dp).height(56.dp))
        }
    }
}

@Composable
internal fun HomeLoadingSkeletonRow(phase: State<Float>, modifier: Modifier) {
    Canvas(modifier) {
        drawRect(DColors.Panel)
        val shift = (size.height - 92.dp.toPx()) / 2f
        val base = Color(0xFFE8EAEE)
        val x = size.width * phase.value
        val brush = Brush.linearGradient(listOf(base, Color(0xFFF5F6F8), base),
            start = Offset(x, shift), end = Offset(x + size.width * .45f, shift + 92.dp.toPx()))
        drawRoundRect(brush, Offset(18.dp.toPx(), shift + 18.dp.toPx()), Size(56.dp.toPx(), 56.dp.toPx()), CornerRadius(18.dp.toPx()))
        val textLeft = 88.dp.toPx()
        val textWidth = (size.width - 138.dp.toPx()).coerceAtLeast(0f)
        drawRoundRect(brush, Offset(textLeft, shift + 26.dp.toPx()), Size(textWidth * .55f, 16.dp.toPx()), CornerRadius(5.dp.toPx()))
        drawRoundRect(brush, Offset(textLeft, shift + 51.dp.toPx()), Size(textWidth * .9f, 12.dp.toPx()), CornerRadius(4.dp.toPx()))
        drawRoundRect(brush, Offset(size.width - 31.dp.toPx(), shift + 38.dp.toPx()), Size(8.dp.toPx(), 16.dp.toPx()), CornerRadius(4.dp.toPx()))
    }
}
