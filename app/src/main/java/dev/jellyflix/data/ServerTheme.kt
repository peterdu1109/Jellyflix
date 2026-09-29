package dev.jellyflix.data

import android.graphics.Color as AColor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jellyfin.sdk.api.client.extensions.brandingApi
import org.jellyfin.sdk.api.client.extensions.displayPreferencesApi
import java.net.HttpURLConnection
import java.net.URL

/** Colors the server asks clients to use. Any field can be null when the server doesn't define it. */
data class ServerTheme(val accent: Int? = null, val background: Int? = null, val dark: Boolean? = null)

/**
 * Derives a theme from the two places a Jellyfin server exposes one:
 *  1. the user's web theme (display preferences `appTheme`),
 *  2. the server's custom CSS (Dashboard → Branding), including one level of `@import`.
 * CSS values win over the web theme since admins use them to override it.
 */
class ServerThemeRepository(private val sessions: SessionManager) {

    suspend fun fetch(): ServerTheme {
        val s = sessions.current ?: return ServerTheme()
        val web = runCatching {
            val prefs = s.api.displayPreferencesApi.getDisplayPreferences("usersettings", s.userId, "emby").content
            ServerThemeParser.fromWebTheme(prefs.customPrefs["appTheme"])
        }.getOrNull() ?: ServerTheme()

        val css = runCatching {
            val root = s.api.brandingApi.getBrandingOptions().content.customCss.orEmpty()
            val imports = ServerThemeParser.imports(root, s.account.serverUrl).take(3)
            buildString {
                append(root)
                for (url in imports) append('\n').append(download(url))
            }
        }.getOrDefault("")
        val fromCss = ServerThemeParser.fromCss(css)

        return ServerTheme(
            accent = fromCss.accent ?: web.accent,
            background = fromCss.background ?: web.background,
            dark = fromCss.dark ?: web.dark,
        )
    }

    /** Fetch then persist, so callers don't need a suspend lambda inside the DataStore edit. */
    suspend fun sync(settings: SettingsRepository) {
        val theme = runCatching { fetch() }.getOrNull() ?: return
        settings.update { it.serverTheme(theme) }
    }

    private suspend fun download(url: String): String = withContext(Dispatchers.IO) {
        runCatching {
            val c = URL(url).openConnection() as HttpURLConnection
            c.connectTimeout = 5_000; c.readTimeout = 5_000
            try { c.inputStream.use { it.readNBytes(512 * 1024).decodeToString() } } finally { c.disconnect() }
        }.getOrDefault("")
    }
}

/** Pure functions, unit-tested. */
object ServerThemeParser {
    // Variable names used by the default web client and the popular community themes.
    private val accentNames = listOf(
        "jf-palette-primary-main", "theme-primary-color", "accent-color", "accent", "primary-color", "primary", "main-color", "highlight",
    )
    private val backgroundNames = listOf(
        "jf-palette-background-default", "theme-body-bg", "background-color", "background", "body-bg", "main-background", "bg",
    )

    private val hex = Regex("#([0-9a-fA-F]{8}|[0-9a-fA-F]{6}|[0-9a-fA-F]{3})\\b")
    private val rgb = Regex("rgba?\\(\\s*(\\d{1,3})\\s*[, ]\\s*(\\d{1,3})\\s*[, ]\\s*(\\d{1,3})")

    fun fromWebTheme(name: String?): ServerTheme? = when (name?.lowercase()) {
        "dark" -> ServerTheme(0xFF00A4DC.toInt(), 0xFF101010.toInt(), true)
        "light" -> ServerTheme(0xFF00A4DC.toInt(), 0xFFFFFFFF.toInt(), false)
        "blueradiance" -> ServerTheme(0xFF00A4DC.toInt(), 0xFF011432.toInt(), true)
        "purplehaze" -> ServerTheme(0xFFAA5CC3.toInt(), 0xFF000420.toInt(), true)
        "wmc" -> ServerTheme(0xFF52B54B.toInt(), 0xFF0A1B3D.toInt(), true)
        "appletv" -> ServerTheme(0xFF3478F6.toInt(), 0xFFF5F5F7.toInt(), false)
        else -> null
    }

    fun fromCss(css: String): ServerTheme {
        if (css.isBlank()) return ServerTheme()
        val vars = Regex("--([A-Za-z0-9_-]+)\\s*:\\s*([^;}]+)").findAll(css).associate { it.groupValues[1].lowercase() to it.groupValues[2].trim() }
        fun pick(names: List<String>): Int? {
            for (n in names) vars.entries.firstOrNull { it.key == n || it.key.endsWith("-$n") }?.let { parseColor(it.value)?.let { c -> return c } }
            return null
        }
        val accent = pick(accentNames)
        val bg = pick(backgroundNames)
        // Luminance decides light/dark; without a background we say nothing and keep the user's mode.
        val dark = bg?.let { luminance(it) < 0.5f }
        return ServerTheme(accent, bg, dark)
    }

    /** Absolute URLs of `@import` rules (relative ones are resolved against the server). */
    fun imports(css: String, serverUrl: String): List<String> =
        Regex("@import\\s+(?:url\\(\\s*)?['\"]?([^'\")\\s;]+)").findAll(css).map { it.groupValues[1] }
            .map { if (it.startsWith("http", true)) it else serverUrl.trimEnd('/') + "/" + it.trimStart('/') }
            .filter { it.startsWith("http://", true) || it.startsWith("https://", true) }.distinct().toList()

    fun parseColor(value: String): Int? {
        hex.find(value)?.let { m ->
            var h = m.groupValues[1]
            if (h.length == 3) h = h.map { "$it$it" }.joinToString("")
            if (h.length == 8) h = h.substring(0, 6) // drop alpha, keep RGB
            return (0xFF000000 or h.toLong(16)).toInt()
        }
        rgb.find(value)?.let { m ->
            val (r, g, b) = m.destructured
            return (0xFF shl 24) or (r.toInt().coerceAtMost(255) shl 16) or (g.toInt().coerceAtMost(255) shl 8) or b.toInt().coerceAtMost(255)
        }
        return null
    }

    fun luminance(argb: Int): Float {
        val r = (argb shr 16 and 0xFF) / 255f; val g = (argb shr 8 and 0xFF) / 255f; val b = (argb and 0xFF) / 255f
        return 0.2126f * r + 0.7152f * g + 0.0722f * b
    }
}
