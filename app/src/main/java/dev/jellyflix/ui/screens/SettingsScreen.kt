package dev.jellyflix.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.jellyflix.R
import dev.jellyflix.data.AppSettings
import dev.jellyflix.data.QualityCap
import dev.jellyflix.data.ThemeMode
import dev.jellyflix.ui.components.focusRing
import dev.jellyflix.ui.rememberContainer
import dev.jellyflix.ui.theme.Palettes
import kotlinx.coroutines.launch
import org.jellyfin.sdk.model.api.PluginInfo

private val AccentChoices = listOf(0xFFAA5CC3, 0xFF00A4DC, 0xFFE11D48, 0xFFF59E0B, 0xFF10B981, 0xFF6366F1, 0xFFEC4899, 0xFFFFFFFF).map { it.toInt() }

@Composable
fun SettingsScreen(settings: AppSettings) {
    val c = rememberContainer()
    val scope = rememberCoroutineScope()
    val update: (( dev.jellyflix.data.SettingsRepository.MutablePrefs) -> Unit) -> Unit = { block -> scope.launch { c.settings.update(block) } }
    var serverPlugins by remember { mutableStateOf<List<PluginInfo>?>(null) }
    LaunchedEffect(Unit) { serverPlugins = runCatching { c.repository.serverPlugins() }.getOrDefault(emptyList()) }
    val accounts by c.session.accounts.collectAsState()

    LazyColumn(Modifier.fillMaxWidth(), contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Header(R.string.settings_interface) }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                dev.jellyflix.data.InterfaceMode.entries.forEach { m ->
                    FilterChip(settings.interfaceMode == m, { update { it.interfaceMode(m) } }, label = {
                        Text(stringResource(if (m == dev.jellyflix.data.InterfaceMode.Server) R.string.interface_server else R.string.interface_native))
                    }, modifier = Modifier.focusRing())
                }
            }
            Text(stringResource(R.string.interface_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        item {
            Label(R.string.settings_video_player)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(true to R.string.video_player_native, false to R.string.video_player_browser).forEach { (native, label) ->
                    FilterChip(settings.nativePlayer == native, { update { it.nativePlayer(native) } }, label = { Text(stringResource(label)) }, modifier = Modifier.focusRing())
                }
            }
            Text(stringResource(R.string.video_player_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        item { HorizontalDivider() }
        item { Header(R.string.settings_appearance) }
        item {
            SwitchRow(stringResource(R.string.settings_server_theme), settings.useServerTheme) { v ->
                update { it.useServerTheme(v) }
                if (v) scope.launch { c.serverTheme.sync(c.settings) }
            }
            Text(
                stringResource(if (settings.serverTheme.accent != null || settings.serverTheme.background != null) R.string.settings_server_theme_desc else R.string.settings_server_theme_none),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        item {
            Label(R.string.settings_theme_mode)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ThemeMode.entries.forEach { m ->
                    FilterChip(settings.themeMode == m, { update { it.themeMode(m) } }, label = {
                        Text(stringResource(when (m) { ThemeMode.System -> R.string.mode_system; ThemeMode.Light -> R.string.mode_light; ThemeMode.Dark -> R.string.mode_dark }))
                    }, modifier = Modifier.focusRing())
                }
            }
        }
        item {
            Label(R.string.settings_palette)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                items(Palettes, key = { it.id }) { p ->
                    val selected = settings.paletteId == p.id
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clickable { update { it.palette(p.id) } }.focusRing().padding(4.dp)) {
                        Box(
                            Modifier.size(52.dp).background(p.darkBg, CircleShape).border(if (selected) 3.dp else 1.dp, if (selected) p.primary else Color.Gray, CircleShape).padding(10.dp)
                                .background(p.primary, CircleShape),
                        )
                        Text(p.name, style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
        item { SwitchRow(stringResource(R.string.settings_dynamic), settings.dynamicColor) { v -> update { it.dynamic(v) } } }
        item { SwitchRow(stringResource(R.string.settings_amoled), settings.amoled) { v -> update { it.amoled(v) } } }
        item {
            Label(R.string.settings_accent)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                item {
                    FilterChip(settings.customAccent == null, { update { it.accent(null) } }, label = { Text(stringResource(R.string.settings_accent_none)) }, modifier = Modifier.focusRing())
                }
                items(AccentChoices) { color ->
                    Box(
                        Modifier.size(40.dp).background(Color(color), CircleShape)
                            .border(if (settings.customAccent == color) 3.dp else 1.dp, if (settings.customAccent == color) MaterialTheme.colorScheme.onSurface else Color.Gray, CircleShape)
                            .clickable { update { it.accent(color) } }.focusRing(CircleShape),
                    )
                }
            }
        }
        item { HorizontalDivider() }

        item { Header(R.string.settings_playback) }
        item {
            Label(R.string.settings_max_bitrate)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(QualityCap.entries) { q ->
                    FilterChip(settings.quality == q, { update { it.quality(q) } }, label = {
                        Text(when (q) { QualityCap.Auto -> stringResource(R.string.auto); QualityCap.P1080 -> "1080p"; QualityCap.P720 -> "720p"; QualityCap.P480 -> "480p" })
                    }, modifier = Modifier.focusRing())
                }
            }
        }
        item { HorizontalDivider() }

        item { Header(R.string.settings_downloads) }
        item { SwitchRow(stringResource(R.string.settings_wifi_only), settings.downloadWifiOnly) { v -> update { it.downloadWifiOnly(v) } } }
        item { HorizontalDivider() }

        item { Header(R.string.settings_plugins) }
        items(c.plugins.all, key = { it.id }) { plugin ->
            val enabled = plugin.id !in settings.disabledPlugins
            Column {
                SwitchRow(stringResource(plugin.nameRes), enabled) { on ->
                    update { it.disabledPlugins(if (on) settings.disabledPlugins - plugin.id else settings.disabledPlugins + plugin.id) }
                }
                Text(stringResource(plugin.descriptionRes), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        item { Header(R.string.settings_server_plugins) }
        val sp = serverPlugins
        if (sp != null && sp.isEmpty()) item { Text(stringResource(R.string.no_server_plugins), style = MaterialTheme.typography.bodySmall) }
        items(sp.orEmpty(), key = { it.id }) { p ->
            Text("${p.name} ${p.version}", style = MaterialTheme.typography.bodyMedium)
        }
        item { HorizontalDivider() }

        item { Header(R.string.settings_account) }
        items(accounts, key = { it.key }) { acc ->
            Row(Modifier.fillMaxWidth().clickable { scope.launch { c.session.switchTo(acc) } }.focusRing().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(acc.userName, style = MaterialTheme.typography.titleSmall)
                    Text("${acc.serverName} · ${acc.serverUrl}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        item {
            OutlinedButton({ scope.launch { c.session.signOut() } }, Modifier.focusRing()) { Text(stringResource(R.string.sign_out)) }
        }
    }
}

@Composable private fun Header(res: Int) = Text(stringResource(res), style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
@Composable private fun Label(res: Int) = Text(stringResource(res), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(bottom = 6.dp))

@Composable
private fun SwitchRow(text: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!checked) }.focusRing().padding(vertical = 4.dp, horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Switch(checked, onChange)
    }
}
