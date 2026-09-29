package dev.jellyflix.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val Context.store: DataStore<Preferences> by preferencesDataStore("jellyflix")

enum class ThemeMode { System, Light, Dark }

/** Max streaming quality; [bitrate] in bits/s, null = let the server direct play whenever possible. */
enum class QualityCap(val bitrate: Int?) {
    Auto(null), P1080(20_000_000), P720(8_000_000), P480(3_000_000)
}

@Serializable
data class Account(val serverUrl: String, val serverName: String, val userId: String, val userName: String, val token: String) {
    val key get() = "$serverUrl|$userId"
}

data class AppSettings(
    val themeMode: ThemeMode = ThemeMode.System,
    val paletteId: String = "jellyfin",
    val dynamicColor: Boolean = false,
    val amoled: Boolean = false,
    val customAccent: Int? = null,
    val quality: QualityCap = QualityCap.Auto,
    val disabledPlugins: Set<String> = emptySet(),
    /** Follow the theme defined by the Jellyfin server (web theme + branding CSS). */
    val useServerTheme: Boolean = true,
    val serverTheme: ServerTheme = ServerTheme(),
)

class SettingsRepository(private val context: Context) {
    private val json = Json { ignoreUnknownKeys = true }

    private object K {
        val mode = stringPreferencesKey("theme_mode")
        val palette = stringPreferencesKey("palette")
        val dynamic = booleanPreferencesKey("dynamic")
        val amoled = booleanPreferencesKey("amoled")
        val accent = intPreferencesKey("accent")
        val quality = stringPreferencesKey("quality")
        val disabledPlugins = stringSetPreferencesKey("disabled_plugins")
        val accounts = stringPreferencesKey("accounts")
        val current = stringPreferencesKey("current_account")
        val useServerTheme = booleanPreferencesKey("use_server_theme")
        val serverAccent = intPreferencesKey("server_accent")
        val serverBg = intPreferencesKey("server_bg")
        val serverDark = booleanPreferencesKey("server_dark")
    }

    val settings: Flow<AppSettings> = context.store.data.map { p ->
        AppSettings(
            themeMode = p[K.mode]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } ?: ThemeMode.System,
            paletteId = p[K.palette] ?: "jellyfin",
            dynamicColor = p[K.dynamic] ?: false,
            amoled = p[K.amoled] ?: false,
            customAccent = p[K.accent],
            quality = p[K.quality]?.let { runCatching { QualityCap.valueOf(it) }.getOrNull() } ?: QualityCap.Auto,
            disabledPlugins = p[K.disabledPlugins] ?: emptySet(),
            useServerTheme = p[K.useServerTheme] ?: true,
            serverTheme = ServerTheme(p[K.serverAccent], p[K.serverBg], p[K.serverDark]),
        )
    }

    suspend fun update(block: (MutablePrefs) -> Unit) = context.store.edit { block(MutablePrefs(it)) }

    class MutablePrefs(private val p: androidx.datastore.preferences.core.MutablePreferences) {
        fun themeMode(v: ThemeMode) { p[K.mode] = v.name }
        fun palette(v: String) { p[K.palette] = v }
        fun dynamic(v: Boolean) { p[K.dynamic] = v }
        fun amoled(v: Boolean) { p[K.amoled] = v }
        fun accent(v: Int?) { if (v == null) p.remove(K.accent) else p[K.accent] = v }
        fun quality(v: QualityCap) { p[K.quality] = v.name }
        fun disabledPlugins(v: Set<String>) { p[K.disabledPlugins] = v }
        fun useServerTheme(v: Boolean) { p[K.useServerTheme] = v }
        fun serverTheme(t: ServerTheme) {
            t.accent?.let { p[K.serverAccent] = it } ?: p.remove(K.serverAccent)
            t.background?.let { p[K.serverBg] = it } ?: p.remove(K.serverBg)
            t.dark?.let { p[K.serverDark] = it } ?: p.remove(K.serverDark)
        }
    }

    val accounts: Flow<List<Account>> = context.store.data.map { p ->
        p[K.accounts]?.let { runCatching { json.decodeFromString<List<Account>>(it) }.getOrNull() } ?: emptyList()
    }
    val currentAccountKey: Flow<String?> = context.store.data.map { it[K.current] }

    suspend fun saveAccount(account: Account) = context.store.edit { p ->
        val list = (p[K.accounts]?.let { runCatching { json.decodeFromString<List<Account>>(it) }.getOrNull() } ?: emptyList())
            .filterNot { it.key == account.key } + account
        p[K.accounts] = json.encodeToString(list)
        p[K.current] = account.key
    }

    suspend fun removeAccount(key: String) = context.store.edit { p ->
        val list = (p[K.accounts]?.let { runCatching { json.decodeFromString<List<Account>>(it) }.getOrNull() } ?: emptyList())
            .filterNot { it.key == key }
        p[K.accounts] = json.encodeToString(list)
        if (p[K.current] == key) p.remove(K.current)
    }
}
