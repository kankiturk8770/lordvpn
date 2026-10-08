package com.lordv2.app.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.PointMode
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import com.lordv2.app.data.Countries
import com.lordv2.app.ui.theme.Lord
import com.lordv2.app.vpn.ConnState
import kotlin.math.hypot
import kotlin.math.min

// Equirectangular land mask (96 x 40), latitude 78N .. 56S, generated from Natural Earth 110m.
private val MASK = arrayOf(
    "...............#.#...#.#..#..##############......................#........####..................",
    "...............###.#.##.##.#.....#########....................#......#############..............",
    ".....######..#.#.####..#.#.###...#########...........####.......#.#.########################.##.",
    "###..####################...###...#####....#.......######.######################################",
    "....####################.....##...###.............###.##########################################",
    ".....##....############....##....................####...#################################..#....",
    ".....#.......############...####..............#...##..###############################.....#.....",
    "..............############.######..............#.#####################################..........",
    "..............################..##..............#####################################...........",
    "...............################.................########..###.#######################...........",
    "...............##############.................###.##.##....##.######################.#..........",
    "...............#############..................##..#.##.######.####################...#..........",
    "................############..................#.###.......######################..#.##..........",
    ".................##########...................######.....#######################................",
    ".................######......................####################################...............",
    "...................###....#.................#############.###..#################................",
    "....................##....#.................####################...############.................",
    "......#.............##..#...................##############.####....####..###.#..................",
    "......................##....................##############.###......##...####...................",
    ".........................#..................###############.........#.....###...................",
    "............................####............#################.......##.........#.#..............",
    "...........................######............###.############....................#..............",
    "...........................#######.................#########..............##..#.................",
    "...........................########...............#########................#.##....#............",
    "..........................############.............########................#....#..####.........",
    "...........................############............#######..................##.......##.........",
    "...........................###########..............#######.....................................",
    "............................##########.............########........................#............",
    ".............................#########.............#######..#....................####.#.........",
    ".............................########...............#####...#..................#########........",
    ".............................#######................#####...#.................##########........",
    ".............................######.................#####.....................###########.......",
    ".............................#####...................###.......................##########.......",
    ".............................###.#...................#.........................#.....###........",
    "............................#####....................................................###........",
    "............................###........................................................#........",
    "............................###..............................................................#..",
    "............................##..................................................................",
    "............................##..................................................................",
    ".............................#.................................................................."
)
private const val COLS = 96
private const val ROWS = 40
private const val LAT_TOP = 78f
private const val LAT_SPAN = 134f

private fun project(lat: Float, lon: Float, w: Float, h: Float) =
    Offset((lon + 180f) / 360f * w, (LAT_TOP - lat) / LAT_SPAN * h)

private fun bezier(a: Offset, m: Offset, b: Offset, t: Float): Offset {
    val u = 1 - t
    return Offset(u * u * a.x + 2 * u * t * m.x + t * t * b.x, u * u * a.y + 2 * u * t * m.y + t * t * b.y)
}

/**
 * Minimal dotted world map. The land layer is cached (drawWithCache) and only the small
 * overlay animates, so it stays cheap on mid-range phones.
 */
@Composable
fun WorldMap(
    modifier: Modifier = Modifier,
    servers: List<String>,
    selected: String?,
    home: String?,
    state: ConnState,
    animate: Boolean,
    style: String = "dots",
) {
    val c = Lord.colors
    val cells = remember {
        val list = ArrayList<Pair<Int, Int>>()
        MASK.forEachIndexed { r, row -> row.forEachIndexed { col, ch -> if (ch == '#') list.add(col to r) } }
        list
    }
    val lit by animateFloatAsState(
        targetValue = when (state) { ConnState.CONNECTED -> 1f; ConnState.CONNECTING -> 0.55f; else -> 0f },
        animationSpec = tween(if (animate) 900 else 0),
        label = "lit",
    )
    val minimal = style == "minimal"
    val serverSet = remember(servers) { servers.filter { it != "UN" }.distinct() }
    val sel = Countries.get(selected).takeIf { it.code != "UN" }
    val homeC = Countries.get(home).takeIf { it.code != "UN" && it.code != sel?.code }

    Box(modifier.aspectRatio(COLS / ROWS.toFloat())) {
        Spacer(
            Modifier.fillMaxSize().drawWithCache {
                val cw = size.width / COLS
                val ch = size.height / ROWS
                val dot = min(cw, ch) * (if (minimal) 0.36f else 0.52f)
                val pts = cells.filterIndexed { i, _ -> !minimal || i % 2 == 0 }
                    .map { (x, y) -> Offset((x + 0.5f) * cw, (y + 0.5f) * ch) }
                onDrawBehind {
                    val alpha = (if (c.isDark) 0.30f else 0.55f) + 0.25f * lit
                    drawPoints(pts, PointMode.Points, c.faint.copy(alpha = alpha), strokeWidth = dot, cap = StrokeCap.Round)
                    if (lit > 0f && sel != null) {
                        val p = project(sel.lat, sel.lon, size.width, size.height)
                        drawCircle(
                            Brush.radialGradient(listOf(c.cyan.copy(alpha = 0.28f * lit), Color.Transparent), center = p, radius = size.width * 0.22f),
                            radius = size.width * 0.22f, center = p,
                        )
                    }
                    serverSet.forEach { code ->
                        val co = Countries.get(code)
                        val p = project(co.lat, co.lon, size.width, size.height)
                        drawCircle(c.primary.copy(alpha = 0.75f), radius = dot * 1.1f, center = p)
                    }
                    if (sel != null && homeC != null) {
                        val a = project(homeC.lat, homeC.lon, size.width, size.height)
                        val b = project(sel.lat, sel.lon, size.width, size.height)
                        val d = hypot(b.x - a.x, b.y - a.y)
                        val m = Offset((a.x + b.x) / 2f, (a.y + b.y) / 2f - d * 0.28f)
                        val path = Path().apply {
                            moveTo(a.x, a.y)
                            cubicTo(
                                a.x + (m.x - a.x) * 2f / 3f, a.y + (m.y - a.y) * 2f / 3f,
                                b.x + (m.x - b.x) * 2f / 3f, b.y + (m.y - b.y) * 2f / 3f,
                                b.x, b.y,
                            )
                        }
                        drawPath(
                            path,
                            Brush.linearGradient(listOf(c.muted.copy(alpha = 0.35f + 0.2f * lit), c.cyan.copy(alpha = 0.45f + 0.5f * lit)), a, b),
                            style = Stroke(width = dot * 0.55f, cap = StrokeCap.Round, pathEffect = if (lit < 0.99f) PathEffect.dashPathEffect(floatArrayOf(dot * 1.6f, dot * 1.6f)) else null),
                        )
                        drawCircle(c.text.copy(alpha = 0.85f), radius = dot * 0.9f, center = a)
                    }
                    if (sel != null) {
                        val p = project(sel.lat, sel.lon, size.width, size.height)
                        drawCircle(c.cyan, radius = dot * (1.5f + 0.4f * lit), center = p)
                        drawCircle(Color.White.copy(alpha = 0.9f), radius = dot * 0.55f, center = p)
                    }
                }
            }
        )
        if (animate && sel != null && (state == ConnState.CONNECTING || state == ConnState.CONNECTED)) {
            PulseLayer(sel.lat, sel.lon, homeC?.lat, homeC?.lon, state == ConnState.CONNECTING)
        }
    }
}

@Composable
private fun PulseLayer(lat: Float, lon: Float, homeLat: Float?, homeLon: Float?, connecting: Boolean) {
    val c = Lord.colors
    val t = rememberInfiniteTransition(label = "pulse")
    val pulse by t.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(if (connecting) 1100 else 2400, easing = LinearEasing), RepeatMode.Restart),
        label = "p",
    )
    Spacer(
        Modifier.fillMaxSize().drawWithCache {
            val dot = min(size.width / COLS, size.height / ROWS) * 0.52f
            val p = project(lat, lon, size.width, size.height)
            val a = if (homeLat != null && homeLon != null) project(homeLat, homeLon, size.width, size.height) else null
            val m = if (a != null) {
                val d = hypot(p.x - a.x, p.y - a.y)
                Offset((a.x + p.x) / 2f, (a.y + p.y) / 2f - d * 0.28f)
            } else null
            onDrawBehind {
                val r = dot * (2f + 7f * pulse)
                drawCircle(c.cyan.copy(alpha = (1f - pulse) * 0.55f), radius = r, center = p, style = Stroke(width = dot * 0.35f))
                if (connecting && a != null && m != null) {
                    drawCircle(c.cyan, radius = dot * 0.8f, center = bezier(a, m, p, pulse))
                }
            }
        }
    )
}
