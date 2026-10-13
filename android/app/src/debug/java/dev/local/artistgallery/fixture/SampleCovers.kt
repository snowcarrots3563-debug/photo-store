package dev.local.artistgallery.fixture

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import dev.local.artistgallery.model.SampleCoverKey
import kotlin.math.min

/** 从 D 原创 SVG 参数绘制，保持完整比例；只编入 debug。 */
@Composable
fun SampleCover(key: SampleCoverKey) {
    val w = if (key == SampleCoverKey.Scenery2) 720f else 480f
    val h = if (key == SampleCoverKey.Scenery3) 1080f else 640f
    val sky = when (key) {
        SampleCoverKey.Scenery1 -> Color(0xFFDCE9F2)
        SampleCoverKey.Scenery2 -> Color(0xFFF0E7DC)
        SampleCoverKey.Scenery3 -> Color(0xFFDDE7DF)
    }
    Canvas(Modifier.fillMaxSize()) {
        val factor = min(size.width / w, size.height / h)
        withTransform({ translate((size.width - w * factor) / 2, (size.height - h * factor) / 2); scale(factor, factor, Offset.Zero) }) {
            drawRect(sky, size = Size(w, h))
            drawCircle(Color(0xFFFCF9E8), 44f, Offset(.76f*w, .18f*h))
            drawPath(Path().apply {
                moveTo(0f,.58f*h); quadraticBezierTo(.25f*w,.34f*h,.55f*w,.61f*h)
                quadraticBezierTo(.85f*w,.88f*h,w,.49f*h); lineTo(w,h); lineTo(0f,h); close()
            }, Color(0xFFA5BCB7))
            drawPath(Path().apply {
                moveTo(0f,.75f*h); quadraticBezierTo(.28f*w,.45f*h,.58f*w,.72f*h)
                quadraticBezierTo(.88f*w,.99f*h,w,.64f*h); lineTo(w,h); lineTo(0f,h); close()
            }, Color(0xFF6F9697))
            drawPath(Path().apply {
                moveTo(.25f*w,h); quadraticBezierTo(.65f*w,.81f*h,.52f*w,.59f*h)
                quadraticBezierTo(.44f*w,.78f*h,.1f*w,h); close()
            }, Color(0xFFD6E4DD))
            drawPath(Path().apply {
                moveTo(.16f*w,.65f*h); lineTo(.16f*w,.38f*h)
                moveTo(.16f*w,.51f*h); lineTo(.09f*w,.42f*h)
                moveTo(.16f*w,.455f*h); lineTo(.24f*w,.385f*h)
            }, Color(0xFF456E70), style = Stroke(7f, cap = StrokeCap.Round))
            withTransform({ translate(.65f*w,.73f*h) }) {
                drawPath(Path().apply { moveTo(-11f,8f); quadraticBezierTo(0f,-7f,11f,8f); lineTo(17f,46f); lineTo(-17f,46f); close() },Color(0xFFECE5CE))
                drawCircle(Color(0xFFF0CDB4),10f,Offset(0f,-4f))
                drawPath(Path().apply { moveTo(-10f,-8f); quadraticBezierTo(0f,-24f,10f,-8f); close() },Color(0xFF405F61))
                drawLine(Color(0xFF405F61),Offset(-7f,46f),Offset(-7f,65f),5f,StrokeCap.Round)
                drawLine(Color(0xFF405F61),Offset(7f,46f),Offset(7f,65f),5f,StrokeCap.Round)
            }
            drawRect(Color.White.copy(alpha=7f/15f),Offset(20f,20f),Size(w-40f,h-40f),style=Stroke(2f))
        }
    }
}
