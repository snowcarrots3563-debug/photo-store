package dev.local.artistgallery.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

enum class DIcon(val path: String) {
    Back("M19 12H5m7-7-7 7 7 7"), Next("m9 5 7 7-7 7"), More("M5 12h.01M12 12h.01M19 12h.01"),
    Plus("M12 5v14M5 12h14"), Close("m6 6 12 12M6 18 18 6"), Search("M21 21l-5-5M18 10a8 8 0 1 1-16 0 8 8 0 0 1 16 0"),
    Import("M12 3v12m-5-5 5 5 5-5M5 16v5h14v-5"), Export("M12 16V3m-5 5 5-5 5 5M5 15v6h14v-6"),
    Artist("M16 7a4 4 0 1 1-8 0 4 4 0 0 1 8 0M4 21v-2a8 8 0 0 1 16 0v2"),
    Manage("M4 4h6v6H4zM14 4h6v6h-6zM4 14h6v6H4zM14 14h6v6h-6z"),
    Album("M3 5h7l2 3h9v12H3z"), Image("M3 3h18v18H3zM3 17l6-6 5 5 3-3 4 4M15 7h.01"),
    Sort("M4 6h16M4 12h11M4 18h6"), SortArrows("M22 12a10 10 0 1 1-20 0 10 10 0 0 1 20 0M9 17V7m-3 3 3-3 3 3M15 7v10m-3-3 3 3 3-3"), Drag("M4 6h16M4 12h16M4 18h16"), Trash("M3 6h18M9 6V3h6v3M5 6l1 15h12l1-15M10 10v7M14 10v7"), Comic("M4 3h16v18H4zM4 9h16M4 15h16"),
    Select("M8 3H3v5m13-5h5v5M3 16v5h5m13-5v5h-5m-8-9 3 3 6-6"),
    Info("M12 11v6M12 7h.01M22 12A10 10 0 1 1 2 12a10 10 0 0 1 20 0"),
    Archive("M3 3h18v5H3zM5 8v13h14V8M9 12h6"), Check("M5 12.5 9.5 17 19 7"),
    Play("M8 5v14l11-7z"), Pause("M8 5v14M16 5v14");
    val vectorPath by lazy { PathParser().parsePathString(path).toPath() }
}

@Composable
fun DIconImage(icon: DIcon, color: Color = DColors.Ink, size: Dp = 24.dp) {
    Canvas(Modifier.size(size)) {
        scale(this.size.width / 24f, this.size.height / 24f, pivot = androidx.compose.ui.geometry.Offset.Zero) {
            drawPath(icon.vectorPath, color, style = Stroke(1.8f, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
    }
}
