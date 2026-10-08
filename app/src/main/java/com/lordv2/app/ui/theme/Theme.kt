package com.lordv2.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

@Immutable
data class LordColors(
    val bg: Color,
    val surface: Color,
    val card: Color,
    val cardHi: Color,
    val stroke: Color,
    val text: Color,
    val muted: Color,
    val faint: Color,
    val primary: Color,
    val cyan: Color,
    val success: Color,
    val warning: Color,
    val danger: Color,
    val isDark: Boolean,
)

val DarkLord = LordColors(
    bg = Color(0xFF060B16), surface = Color(0xFF0A1222), card = Color(0xFF0E192C), cardHi = Color(0xFF14223A),
    stroke = Color(0xFF1B2A44), text = Color(0xFFF1F5FB), muted = Color(0xFF8C9BB6), faint = Color(0xFF3F5070),
    primary = Color(0xFF3AA8FF), cyan = Color(0xFF23D5E8), success = Color(0xFF35D49A),
    warning = Color(0xFFF5B946), danger = Color(0xFFFF6B7A), isDark = true,
)

val AmoledLord = DarkLord.copy(
    bg = Color(0xFF000000), surface = Color(0xFF04070D), card = Color(0xFF080D18), cardHi = Color(0xFF0E1626),
    stroke = Color(0xFF141E32),
)

val LightLord = LordColors(
    bg = Color(0xFFF2F6FB), surface = Color(0xFFFFFFFF), card = Color(0xFFFFFFFF), cardHi = Color(0xFFF6F9FD),
    stroke = Color(0xFFDCE4EF), text = Color(0xFF0B1424), muted = Color(0xFF5B6B86), faint = Color(0xFFB4C0D2),
    primary = Color(0xFF1C8CEB), cyan = Color(0xFF0BAFC4), success = Color(0xFF15A676),
    warning = Color(0xFFD08C0B), danger = Color(0xFFE5485B), isDark = false,
)

val LocalLord = staticCompositionLocalOf { DarkLord }

object Lord {
    val colors: LordColors
        @Composable @ReadOnlyComposable get() = LocalLord.current
}

@Composable
fun resolveColors(theme: String): LordColors {
    val sysDark = isSystemInDarkTheme()
    return when (theme) {
        "light" -> LightLord
        "amoled" -> AmoledLord
        "system" -> if (sysDark) DarkLord else LightLord
        else -> DarkLord
    }
}

@Composable
fun LordTheme(colors: LordColors, content: @Composable () -> Unit) {
    val c = colors
    val scheme = if (c.isDark) darkColorScheme(
        primary = c.primary, onPrimary = Color.White, secondary = c.cyan, onSecondary = Color(0xFF00121A),
        background = c.bg, onBackground = c.text, surface = c.surface, onSurface = c.text,
        surfaceVariant = c.card, onSurfaceVariant = c.muted, outline = c.stroke, outlineVariant = c.stroke,
        error = c.danger, surfaceContainer = c.card, surfaceContainerHigh = c.cardHi,
        surfaceContainerHighest = c.cardHi, surfaceContainerLow = c.surface, surfaceContainerLowest = c.bg,
    ) else lightColorScheme(
        primary = c.primary, onPrimary = Color.White, secondary = c.cyan, onSecondary = Color.White,
        background = c.bg, onBackground = c.text, surface = c.surface, onSurface = c.text,
        surfaceVariant = c.cardHi, onSurfaceVariant = c.muted, outline = c.stroke, outlineVariant = c.stroke,
        error = c.danger, surfaceContainer = c.card, surfaceContainerHigh = c.cardHi,
        surfaceContainerHighest = c.cardHi, surfaceContainerLow = c.surface, surfaceContainerLowest = c.bg,
    )
    CompositionLocalProvider(LocalLord provides c) {
        MaterialTheme(colorScheme = scheme, typography = Typography(), content = content)
    }
}
