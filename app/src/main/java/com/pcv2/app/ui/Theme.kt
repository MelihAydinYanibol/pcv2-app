package com.pcv2.app.ui

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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** The dashboard's CSS custom properties (:root / dark theme). */
@Immutable
data class Pal(
    val bg: Color,
    val surface: Color,
    val surface2: Color,
    val border: Color,
    val text: Color,
    val text2: Color,
    val text3: Color,
    val accent: Color,
    val accentSoft: Color,
    val accentText: Color,
    val ok: Color,
    val okSoft: Color,
    val warn: Color,
    val warnSoft: Color,
    val danger: Color,
    val dangerSoft: Color,
    val info: Color,
    val infoSoft: Color,
    val track: Color,
    val dark: Boolean,
)

val LightPal = Pal(
    bg = Color(0xFFF4F5F8), surface = Color(0xFFFFFFFF), surface2 = Color(0xFFF8F9FB), border = Color(0xFFE3E6EC),
    text = Color(0xFF1B1F29), text2 = Color(0xFF5B6272), text3 = Color(0xFF8A91A1),
    accent = Color(0xFF4F5BD5), accentSoft = Color(0xFFECEEFD), accentText = Color(0xFFFFFFFF),
    ok = Color(0xFF1F9D63), okSoft = Color(0xFFE3F6EC), warn = Color(0xFFC27C0E), warnSoft = Color(0xFFFDF3E1),
    danger = Color(0xFFD64545), dangerSoft = Color(0xFFFDEAEA), info = Color(0xFF2B7BD6), infoSoft = Color(0xFFE6F0FC),
    track = Color(0xFFE9EBF1), dark = false,
)

val DarkPal = Pal(
    bg = Color(0xFF0F1117), surface = Color(0xFF171A22), surface2 = Color(0xFF1D212B), border = Color(0xFF2A2F3B),
    text = Color(0xFFE8EAF0), text2 = Color(0xFFA5ABBA), text3 = Color(0xFF747B8C),
    accent = Color(0xFF7C86F0), accentSoft = Color(0xFF252A4A), accentText = Color(0xFF0F1117),
    ok = Color(0xFF3CCF8E), okSoft = Color(0xFF173327), warn = Color(0xFFF0B04A), warnSoft = Color(0xFF3A2C13),
    danger = Color(0xFFF07070), dangerSoft = Color(0xFF3D1C1E), info = Color(0xFF64A8F5), infoSoft = Color(0xFF172A42),
    track = Color(0xFF262B36), dark = true,
)

val LocalPal = staticCompositionLocalOf { LightPal }

val pal: Pal
    @Composable @ReadOnlyComposable get() = LocalPal.current

val MonoFamily = FontFamily.Monospace

@Composable
fun PcvTheme(dark: Boolean, content: @Composable () -> Unit) {
    val p = if (dark) DarkPal else LightPal
    val base = if (dark) darkColorScheme() else lightColorScheme()
    val scheme = base.copy(
        primary = p.accent, onPrimary = p.accentText,
        primaryContainer = p.accentSoft, onPrimaryContainer = p.accent,
        secondary = p.accent, onSecondary = p.accentText,
        secondaryContainer = p.accentSoft, onSecondaryContainer = p.accent,
        tertiary = p.ok, onTertiary = p.accentText,
        background = p.bg, onBackground = p.text,
        surface = p.surface, onSurface = p.text,
        surfaceVariant = p.surface2, onSurfaceVariant = p.text2,
        surfaceTint = p.surface,
        surfaceContainerLowest = p.surface, surfaceContainerLow = p.surface,
        surfaceContainer = p.surface, surfaceContainerHigh = p.surface, surfaceContainerHighest = p.surface2,
        surfaceBright = p.surface, surfaceDim = p.surface2,
        outline = p.border, outlineVariant = p.border,
        error = p.danger, onError = Color.White, errorContainer = p.dangerSoft, onErrorContainer = p.danger,
        inverseSurface = p.text, inverseOnSurface = p.bg, inversePrimary = p.accentSoft,
        scrim = Color(0x800A0C14),
    )
    val body = TextStyle(fontSize = 15.sp, lineHeight = 21.sp, color = p.text)
    val typography = Typography(
        bodyLarge = body,
        bodyMedium = body.copy(fontSize = 14.sp, lineHeight = 20.sp),
        bodySmall = body.copy(fontSize = 12.5.sp, lineHeight = 17.sp),
        titleLarge = body.copy(fontSize = 20.sp, fontWeight = FontWeight.Bold, lineHeight = 26.sp),
        titleMedium = body.copy(fontSize = 16.5.sp, fontWeight = FontWeight.SemiBold),
        labelLarge = body.copy(fontSize = 14.sp, fontWeight = FontWeight.SemiBold),
    )
    CompositionLocalProvider(LocalPal provides p) {
        MaterialTheme(colorScheme = scheme, typography = typography, content = content)
    }
}
