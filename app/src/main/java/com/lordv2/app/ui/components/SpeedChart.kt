package com.lordv2.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import com.lordv2.app.ui.theme.Lord
import com.lordv2.app.vpn.SpeedSample

/** Lightweight line chart for download / upload speed (last 60 seconds). */
@Composable
fun SpeedChart(samples: List<SpeedSample>, modifier: Modifier = Modifier) {
    val c = Lord.colors
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        for (i in 1..3) {
            val y = h * i / 4f
            drawLine(c.stroke.copy(alpha = 0.6f), Offset(0f, y), Offset(w, y), strokeWidth = 1f)
        }
        if (samples.size < 2) return@Canvas
        val maxV = (samples.maxOf { maxOf(it.rx, it.tx) }).coerceAtLeast(1024L).toFloat() * 1.15f
        val step = w / 59f
        val offset = (60 - samples.size) * step
        fun line(sel: (SpeedSample) -> Long): Path = Path().apply {
            samples.forEachIndexed { i, s ->
                val x = offset + i * step
                val y = h - (sel(s) / maxV) * h
                if (i == 0) moveTo(x, y) else lineTo(x, y)
            }
        }
        val rx = line { it.rx }
        val fill = Path().apply {
            addPath(rx)
            lineTo(offset + (samples.size - 1) * step, h)
            lineTo(offset, h)
            close()
        }
        drawPath(fill, Brush.verticalGradient(listOf(c.cyan.copy(alpha = 0.28f), Color.Transparent)))
        drawPath(rx, c.cyan, style = Stroke(width = 5f, cap = StrokeCap.Round, join = StrokeJoin.Round))
        drawPath(line { it.tx }, c.primary.copy(alpha = 0.9f), style = Stroke(width = 4f, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}
