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
    Card("M5 3h14v18H5zM8 7h8M8 11h5M15 15l1 2 2 1-2 1-1 2-1-2-2-1 2-1z"),
    Gif("M3 5h18v14H3zM7 9H5v6h3v-3H7M11 9v6M15 15V9h4M15 12h3"),
    Swipe("M3 12h16m-4-4 4 4-4 4M6 5H3v3M6 19H3v-3"),
    Video("M3 5h13v14H3zM16 9l5-3v12l-5-3M7 9l5 3-5 3z"),
    Backup("M4 8l8-5 8 5v11H4zM4 8l8 5 8-5M12 13v6"),
    Restore("M4 9a8 8 0 1 1 1 9M4 4v5h5M12 8v5l3 2"),
    History("M4 3h12l4 4v14H4zM15 3v5h5M8 11h8M8 15h8M8 18h5"),
    Play("M8 5v14l11-7z"), Pause("M8 5v14M16 5v14"),
    SettingsImage("M4 3h16a1 1 0 0 1 1 1v16a1 1 0 0 1-1 1H4a1 1 0 0 1-1-1V4a1 1 0 0 1 1-1Zm-1 13 5-5 5 5 3-3 5 5M14 8h.01"),
    SettingsDownload("M12 3v13m-5-5 5 5 5-5M4 17v4h16v-4"),
    SettingsArchive("M4 4h16v4H4zM6 8v13h12V8M10 12h4"),
    SettingsUpload("M12 16V3m-5 5 5-5 5 5M4 15v6h16v-6"),
    SettingsBackup("M3 11a9 9 0 1 1 2 7M3 5v6h6M12 7v5l3 2"),
    SettingsFolder("M3 7V5a1 1 0 0 1 1-1h5l2 3h9a1 1 0 0 1 1 1v11a1 1 0 0 1-1 1H4a1 1 0 0 1-1-1V7Z"),
    SettingsSpark("m12 3 2.5 6.5L21 12l-6.5 2.5L12 21l-2.5-6.5L3 12l6.5-2.5L12 3Z"),
    SettingsPlay("m9 5 11 7-11 7V5Z"),
    SettingsTrash("M3 6h18M9 6V3h6v3M5 6l1 15h12l1-15M10 10v7M14 10v7"),
    SettingsInfo("M12 16v-4m0-4h.01M3 12a9 9 0 1 0 18 0 9 9 0 1 0-18 0"),
    SettingsFile("M14 3H5v18h14V8l-5-5Zm0 0v5h5M8 12h8M8 16h6"),
    SettingsArtist("M20 21v-2a7 7 0 0 0-14 0v2M9 7a4 4 0 1 0 8 0 4 4 0 1 0-8 0"),
    SettingsEdit("m4 16 11-11 4 4L8 20H4v-4Zm9-9 4 4");
    val vectorPath by lazy { PathParser().parsePathString(path).toPath() }
}

@Composable
fun DIconImage(icon: DIcon, color: Color = DColors.Ink, size: Dp = 24.dp, strokeWidth: Float = 1.8f) {
    Canvas(Modifier.size(size)) {
        scale(this.size.width / 24f, this.size.height / 24f, pivot = androidx.compose.ui.geometry.Offset.Zero) {
            drawPath(icon.vectorPath, color, style = Stroke(strokeWidth, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
    }
}
