package dev.trueform.player.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import dev.trueform.player.R
import dev.trueform.player.settings.ThemeMode

/** The design tokens (docs/plan/04-ui-ux.md section 2; docs/design/mockup.html). */
@Immutable
data class TfColors(
    val bg: Color,
    val surface1: Color,
    val surface2: Color,
    val surface3: Color,
    val outline: Color,
    val outlineStrong: Color,
    val text: Color,
    val textSecondary: Color,
    val textTertiary: Color,
    val accent: Color,
    val onAccent: Color,
    val accentDim: Color,
    val waveLow: Color,
    val waveMid: Color,
    val waveHigh: Color,
    val positive: Color,
    val danger: Color,
    val limit: Color,
    val isDark: Boolean,
    /** Overlay alpha for the pressed state (16% dark, 12% light). */
    val pressedAlpha: Float,
)

val DarkColors = TfColors(
    bg = Color(0xFF0B0C0F), surface1 = Color(0xFF13151A), surface2 = Color(0xFF1B1E25), surface3 = Color(0xFF252933),
    outline = Color(0xFF2E333D), outlineStrong = Color(0xFF454B57), text = Color(0xFFF3F4F6),
    textSecondary = Color(0xFFA8AEBB), textTertiary = Color(0xFF737A88), accent = Color(0xFFFFB547),
    onAccent = Color(0xFF1C1200), accentDim = Color(0xFF3A2A0C), waveLow = Color(0xFFFF5E4D), waveMid = Color(0xFFFFC24B),
    waveHigh = Color(0xFF7CE4FF), positive = Color(0xFF5BD68A), danger = Color(0xFFFF6B6B), limit = Color(0xFFFF8A3D),
    isDark = true, pressedAlpha = 0.16f,
)

val LightColors = TfColors(
    bg = Color(0xFFFAFAF7), surface1 = Color(0xFFFFFFFF), surface2 = Color(0xFFF1F1EC), surface3 = Color(0xFFE6E6DF),
    outline = Color(0xFFD9D9D0), outlineStrong = Color(0xFFB8B8AD), text = Color(0xFF14161A),
    textSecondary = Color(0xFF4A505C), textTertiary = Color(0xFF6B7280), accent = Color(0xFFA85F00),
    onAccent = Color(0xFFFFFFFF), accentDim = Color(0xFFFFE9C7), waveLow = Color(0xFFD93A2B), waveMid = Color(0xFFC98A00),
    waveHigh = Color(0xFF008FB3), positive = Color(0xFF1E8E4E), danger = Color(0xFFC62828), limit = Color(0xFFD2691E),
    isDark = false, pressedAlpha = 0.12f,
)

val BlackColors = DarkColors.copy(
    bg = Color(0xFF000000), surface1 = Color(0xFF0C0D10), surface2 = Color(0xFF15171C), surface3 = Color(0xFF20232B),
    outline = Color(0xFF262A33), outlineStrong = Color(0xFF3D434F), accentDim = Color(0xFF2A1E08),
)

val InterFamily = FontFamily(
    listOf(400, 500, 600, 700).map { w ->
        Font(R.font.inter, FontWeight(w), variationSettings = FontVariation.Settings(FontVariation.weight(w)))
    }
)

@Immutable
data class TfType(
    val display: TextStyle,
    val title: TextStyle,
    val rowTitle: TextStyle,
    val body: TextStyle,
    val label: TextStyle,
    val caption: TextStyle,
    val numeric: TextStyle,
)

private fun style(size: Int, line: Int, weight: Int, tnum: Boolean = false) = TextStyle(
    fontFamily = InterFamily, fontSize = size.sp, lineHeight = line.sp, fontWeight = FontWeight(weight),
    fontFeatureSettings = if (tnum) "tnum" else null,
)

val Type = TfType(
    display = style(22, 28, 600),
    title = style(20, 26, 600),
    rowTitle = style(16, 22, 500),
    body = style(14, 20, 400),
    label = style(13, 18, 500),
    caption = style(12, 16, 500),
    numeric = style(13, 16, 500, tnum = true),
)

val LocalTf = staticCompositionLocalOf { DarkColors }

/** Shortcut: `Tf.colors.accent`, `Tf.type.title`. */
object Tf {
    val colors: TfColors @Composable get() = LocalTf.current
    val type: TfType get() = Type
}

@Composable
fun TrueformTheme(mode: ThemeMode, content: @Composable () -> Unit) {
    val colors = when (mode) {
        ThemeMode.DARK -> DarkColors
        ThemeMode.LIGHT -> LightColors
        ThemeMode.BLACK -> BlackColors
        ThemeMode.SYSTEM -> if (isSystemInDarkTheme()) DarkColors else LightColors
    }
    val scheme = if (colors.isDark) darkColorScheme(
        primary = colors.accent, onPrimary = colors.onAccent, background = colors.bg, surface = colors.surface1,
        surfaceVariant = colors.surface2, onSurface = colors.text, onSurfaceVariant = colors.textSecondary,
        outline = colors.outline, surfaceContainer = colors.surface1, surfaceContainerHigh = colors.surface2,
        surfaceContainerLow = colors.surface1, error = colors.danger,
    ) else lightColorScheme(
        primary = colors.accent, onPrimary = colors.onAccent, background = colors.bg, surface = colors.surface1,
        surfaceVariant = colors.surface2, onSurface = colors.text, onSurfaceVariant = colors.textSecondary,
        outline = colors.outline, surfaceContainer = colors.surface1, surfaceContainerHigh = colors.surface2,
        surfaceContainerLow = colors.surface1, error = colors.danger,
    )
    CompositionLocalProvider(LocalTf provides colors) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}

/** WCAG contrast ratio of two opaque colours (ThemeContrastTest). */
fun contrast(a: Color, b: Color): Double {
    fun lum(c: Color): Double {
        fun ch(v: Float) = if (v <= 0.03928f) v / 12.92 else Math.pow(((v + 0.055) / 1.055), 2.4)
        return 0.2126 * ch(c.red) + 0.7152 * ch(c.green) + 0.0722 * ch(c.blue)
    }
    val la = lum(a); val lb = lum(b)
    return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
}
