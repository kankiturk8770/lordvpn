package com.lordv2.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp

/** The Lord V2 mark: a crown whose body hides a "V" chevron. Drawn in a 108-unit grid (same as the launcher icon). */
@Composable
fun LordLogo(size: Dp, modifier: Modifier = Modifier, withBackground: Boolean = true) {
    Canvas(modifier.size(size)) {
        val s = this.size.minDimension / 108f
        fun o(x: Float, y: Float) = Offset(x * s, y * s)
        if (withBackground) {
            drawRoundRect(
                Brush.linearGradient(listOf(Color(0xFF0D1E3A), Color(0xFF050B16)), o(0f, 0f), o(108f, 108f)),
                cornerRadius = CornerRadius(30f * s),
            )
        }
        val crown = Path().apply {
            moveTo(28f * s, 38f * s)
            lineTo(42f * s, 50f * s)
            lineTo(54f * s, 30f * s)
            lineTo(66f * s, 50f * s)
            lineTo(80f * s, 38f * s)
            lineTo(72f * s, 74f * s)
            cubicTo(64f * s, 81f * s, 44f * s, 81f * s, 36f * s, 74f * s)
            close()
        }
        drawPath(crown, Brush.linearGradient(listOf(Color(0xFF63C9FF), Color(0xFF23D5E8)), o(28f, 30f), o(80f, 80f)))
        val v = Path().apply {
            moveTo(43f * s, 54f * s)
            lineTo(54f * s, 69f * s)
            lineTo(65f * s, 54f * s)
        }
        drawPath(v, Color(0xFF07101F), style = Stroke(width = 5.5f * s, cap = StrokeCap.Round, join = StrokeJoin.Round))
        drawCircle(Color(0xFFA5F1FA), radius = 3.8f * s, center = o(54f, 22f))
    }
}
