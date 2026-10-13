package dev.local.artistgallery.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

object DColors {
    val Background = Color(0xFFF6F6F7)
    val Panel = Color.White
    val Ink = Color(0xFF17181B)
    val Muted = Color(0xFF696D76)
    val Blue = Color(0xFF4B7FF3)
    val Action = Color(0xFF3265D5)
    val Line = Color(0xFFECEEF2)
    val Soft = Color(0xFFEEF3FF)
    val Error = Color(0xFFB5323F)
}

@Composable
fun DTheme(content: @Composable () -> Unit) {
    val base = TextStyle(fontFamily = FontFamily.SansSerif, color = DColors.Ink)
    MaterialTheme(
        colorScheme = lightColorScheme(primary = DColors.Action, background = DColors.Background, surface = DColors.Panel, onSurface = DColors.Ink, error = DColors.Error),
        typography = Typography(
            headlineLarge = base.copy(fontSize = 32.sp, lineHeight = 40.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.6).sp),
            headlineMedium = base.copy(fontSize = 28.sp, lineHeight = 35.sp, fontWeight = FontWeight.SemiBold),
            headlineSmall = base.copy(fontSize = 22.sp, lineHeight = 31.sp, fontWeight = FontWeight.SemiBold),
            bodyLarge = base.copy(fontSize = 17.sp, lineHeight = 25.5.sp),
            bodyMedium = base.copy(fontSize = 15.sp, lineHeight = 22.5.sp),
            bodySmall = base.copy(fontSize = 13.sp, lineHeight = 20.sp),
        ), content = content,
    )
}
