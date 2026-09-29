package dev.jellyflix.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import dev.jellyflix.data.AppSettings
import dev.jellyflix.data.ThemeMode

/** A theme = an accent pair + a tinted dark surface family. Add entries here to ship new themes. */
data class Palette(
    val id: String,
    val name: String,
    val primary: Color,
    val secondary: Color,
    val darkBg: Color,
    val darkSurface: Color,
    val lightBg: Color = Color(0xFFFAF7FF),
)

val Palettes = listOf(
    Palette("jellyfin", "Jellyfin", Color(0xFFAA5CC3), Color(0xFF00A4DC), Color(0xFF0F0B1A), Color(0xFF1B1530)),
    Palette("ocean", "Ocean", Color(0xFF38B6FF), Color(0xFF2DD4BF), Color(0xFF07131F), Color(0xFF0F2436)),
    Palette("forest", "Forest", Color(0xFF4ADE80), Color(0xFFA3E635), Color(0xFF0A130D), Color(0xFF14261A)),
    Palette("sunset", "Sunset", Color(0xFFFF7A59), Color(0xFFFFC145), Color(0xFF160B0A), Color(0xFF2A1613)),
    Palette("rose", "Rose", Color(0xFFF472B6), Color(0xFFC084FC), Color(0xFF150A12), Color(0xFF2A1524)),
    Palette("mono", "Mono", Color(0xFFE5E5E5), Color(0xFF9CA3AF), Color(0xFF0A0A0A), Color(0xFF1A1A1A)),
)

fun palette(id: String) = Palettes.firstOrNull { it.id == id } ?: Palettes.first()

val LocalIsTv = staticCompositionLocalOf { false }

private fun scheme(p: Palette, dark: Boolean, amoled: Boolean, accent: Color?): ColorScheme {
    val primary = accent ?: p.primary
    val onPrimary = if (primary.luminance() > 0.5f) Color.Black else Color.White
    return if (dark) darkColorScheme(
        primary = primary, onPrimary = onPrimary,
        secondary = accent?.let { p.secondary } ?: p.secondary,
        background = if (amoled) Color.Black else p.darkBg,
        surface = if (amoled) Color.Black else p.darkBg,
        surfaceVariant = if (amoled) Color(0xFF111111) else p.darkSurface,
        surfaceContainer = if (amoled) Color(0xFF0B0B0B) else p.darkSurface,
        surfaceContainerHigh = if (amoled) Color(0xFF151515) else p.darkSurface,
        onBackground = Color(0xFFF2EEF9), onSurface = Color(0xFFF2EEF9), onSurfaceVariant = Color(0xFFB9B2C9),
    ) else lightColorScheme(
        primary = primary, onPrimary = onPrimary, secondary = p.secondary,
        background = p.lightBg, surface = p.lightBg,
    )
}

@Composable
fun JellyflixTheme(settings: AppSettings, content: @Composable () -> Unit) {
    val dark = when (settings.themeMode) {
        ThemeMode.System -> isSystemInDarkTheme()
        ThemeMode.Light -> false
        ThemeMode.Dark -> true
    }
    val ctx = LocalContext.current
    val colors = if (settings.dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
    } else scheme(palette(settings.paletteId), dark, settings.amoled && dark, settings.customAccent?.let { Color(it) })
    MaterialTheme(colorScheme = colors, typography = MaterialTheme.typography, content = content)
}
