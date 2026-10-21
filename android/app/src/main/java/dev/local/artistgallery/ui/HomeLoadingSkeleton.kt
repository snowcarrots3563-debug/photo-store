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
import androidx.compose.runtime.Composable
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
internal fun HomeLoadingSkeleton() {
    val transition = rememberInfiniteTransition(label = "homeLoading")
    val phase = transition.animateFloat(-1f, 2f,
        infiniteRepeatable(tween(1200, easing = LinearEasing), RepeatMode.Restart), label = "homeLoadingSweep")
    DGroup(Modifier.testTag("artistHomeLoading").semantics(mergeDescendants = true) {
        contentDescription = "正在读取图库"
        progressBarRangeInfo = ProgressBarRangeInfo.Indeterminate
    }) {
        repeat(5) { index ->
            if (index > 0) DDivider()
            // DRow's 56dp cover and two 18dp vertical insets retain the existing 92dp row.
            Canvas(Modifier.fillMaxWidth().height(92.dp).testTag("artistHomeLoadingRow:$index")) {
                val base = Color(0xFFE8EAEE)
                val x = size.width * phase.value
                val brush = Brush.linearGradient(listOf(base, Color(0xFFF5F6F8), base),
                    start = Offset(x, 0f), end = Offset(x + size.width * .45f, size.height))
                drawRoundRect(brush, Offset(18.dp.toPx(), 18.dp.toPx()), Size(56.dp.toPx(), 56.dp.toPx()), CornerRadius(18.dp.toPx()))
                val textLeft = 88.dp.toPx()
                val textWidth = (size.width - 138.dp.toPx()).coerceAtLeast(0f)
                drawRoundRect(brush, Offset(textLeft, 26.dp.toPx()), Size(textWidth * .55f, 16.dp.toPx()), CornerRadius(5.dp.toPx()))
                drawRoundRect(brush, Offset(textLeft, 51.dp.toPx()), Size(textWidth * .9f, 12.dp.toPx()), CornerRadius(4.dp.toPx()))
                drawRoundRect(brush, Offset(size.width - 31.dp.toPx(), 38.dp.toPx()), Size(8.dp.toPx(), 16.dp.toPx()), CornerRadius(4.dp.toPx()))
            }
        }
    }
}
