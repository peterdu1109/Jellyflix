package dev.jellyflix.ui.screens

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.jellyflix.R
import dev.jellyflix.data.AppSettings
import dev.jellyflix.data.MediaRepository
import dev.jellyflix.data.UserLayout
import dev.jellyflix.plugin.HomeSection
import dev.jellyflix.plugin.PluginRegistry
import dev.jellyflix.ui.appViewModel
import dev.jellyflix.ui.components.ItemRow
import dev.jellyflix.ui.components.Load
import dev.jellyflix.ui.components.LoadView
import dev.jellyflix.ui.rememberContainer
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.CollectionType

enum class RowKind { Libraries, Resume, ResumeAudio, NextUp, Latest, LiveTv, Favorites }

data class HomeRow(val kind: RowKind, val items: List<BaseItemDto>, val libraryName: String? = null, val key: String)

class HomeViewModel(private val repo: MediaRepository) : ViewModel() {
    private val _state = MutableStateFlow<Load<List<HomeRow>>>(Load.Loading)
    val state: StateFlow<Load<List<HomeRow>>> = _state

    init { load() }

    /** Builds the rows in the order the user configured on the server (Dashboard → Display → Home). */
    fun load() {
        viewModelScope.launch {
            _state.value = Load.Loading
            _state.value = runCatching { build() }.fold({ Load.Ready(it) }, { Load.Failed(it.message) })
        }
    }

    private suspend fun build(): List<HomeRow> = coroutineScope {
        val layout = repo.userLayout()
        val allViews = repo.views()
        val ordered = allViews.sortedBy { v -> layout.orderedViews.indexOf(v.id).let { if (it < 0) Int.MAX_VALUE else it } }
        val views = ordered.filter { it.id !in layout.hiddenViews }

        val resume = async { runCatching { repo.resume() }.getOrDefault(emptyList()) }
        val resumeAudio = async { runCatching { repo.resumeAudio() }.getOrDefault(emptyList()) }
        val next = async { runCatching { repo.nextUp() }.getOrDefault(emptyList()) }
        val favs = async { runCatching { repo.favorites() }.getOrDefault(emptyList()) }
        val liveTv = async { runCatching { repo.liveTvNow() }.getOrDefault(emptyList()) }
        val latestViews = views.filter { it.id !in layout.hiddenLatest && it.collectionType != CollectionType.PLAYLISTS && it.collectionType != CollectionType.BOXSETS && it.collectionType != CollectionType.LIVETV }
        val latest = latestViews.map { v -> async { v to runCatching { repo.latest(v.id) }.getOrDefault(emptyList()) } }

        val rows = mutableListOf<HomeRow>()
        var latestAdded = false
        for (section in layout.homeSections) when (section) {
            "smalllibrarytiles", "librarybuttons" -> rows += HomeRow(RowKind.Libraries, views, key = "libraries")
            "resume" -> rows += HomeRow(RowKind.Resume, resume.await(), key = "resume")
            "resumeaudio" -> rows += HomeRow(RowKind.ResumeAudio, resumeAudio.await(), key = "resumeaudio")
            "nextup" -> rows += HomeRow(RowKind.NextUp, next.await(), key = "nextup")
            "livetv" -> rows += HomeRow(RowKind.LiveTv, liveTv.await(), key = "livetv")
            "latestmedia" -> if (!latestAdded) {
                latestAdded = true
                latest.forEach { d -> val (v, items) = d.await(); rows += HomeRow(RowKind.Latest, items, v.name, key = "latest-${v.id}") }
            }
        }
        rows += HomeRow(RowKind.Favorites, favs.await(), key = "favorites")
        rows
    }

    /** Silent refresh when coming back from the player so progress rows are current. */
    fun refresh() {
        viewModelScope.launch {
            runCatching {
                val r = repo.resume(); val n = repo.nextUp()
                val cur = (_state.value as? Load.Ready)?.data ?: return@runCatching
                _state.value = Load.Ready(cur.map { row ->
                    when (row.kind) { RowKind.Resume -> row.copy(items = r); RowKind.NextUp -> row.copy(items = n); else -> row }
                })
            }
        }
    }
}

@Composable
fun HomeScreen(settings: AppSettings, plugins: PluginRegistry, onOpen: (BaseItemDto) -> Unit, onPlay: (BaseItemDto) -> Unit, onOpenLibrary: (BaseItemDto) -> Unit, onOpenDownloads: () -> Unit = {}) {
    val vm = appViewModel { HomeViewModel(it.repository) }
    val repo = rememberContainer().repository
    val state by vm.state.collectAsState()
    val allowed = plugins.homeSections(settings)
    LaunchedEffect(Unit) { vm.refresh() }

    // Offline (or server unreachable): the downloads are still one tap away.
    if (state is Load.Failed) {
        androidx.compose.foundation.layout.Column(Modifier.fillMaxSize(), verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center, horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
            dev.jellyflix.ui.components.ErrorView((state as Load.Failed).message, vm::load, Modifier.padding(24.dp))
            androidx.compose.material3.Text(stringResource(R.string.offline_hint), style = androidx.compose.material3.MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 24.dp))
            androidx.compose.material3.Button(onOpenDownloads, Modifier.padding(top = 12.dp)) { androidx.compose.material3.Text(stringResource(R.string.open_downloads)) }
        }
        return
    }
    LoadView(state, vm::load) { rows ->
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 8.dp)) {
            items(rows, key = { it.key }) { row ->
                when (row.kind) {
                    RowKind.Libraries -> ItemRow(stringResource(R.string.my_libraries), row.items, repo, onOpenLibrary, wide = true)
                    RowKind.Resume -> if (HomeSection.ContinueWatching in allowed) ItemRow(stringResource(R.string.continue_watching), row.items, repo, onPlay, wide = true)
                    RowKind.ResumeAudio -> if (HomeSection.ContinueWatching in allowed) ItemRow(stringResource(R.string.continue_listening), row.items, repo, onPlay)
                    RowKind.NextUp -> if (HomeSection.NextUp in allowed) ItemRow(stringResource(R.string.next_up), row.items, repo, onPlay, wide = true)
                    RowKind.LiveTv -> ItemRow(stringResource(R.string.live_tv_now), row.items, repo, onPlay, wide = true)
                    RowKind.Latest -> if (HomeSection.LatestPerLibrary in allowed) ItemRow(stringResource(R.string.latest_in, row.libraryName.orEmpty()), row.items, repo, onOpen)
                    RowKind.Favorites -> if (HomeSection.Favorites in allowed) ItemRow(stringResource(R.string.favorites), row.items, repo, onOpen)
                }
            }
            item { androidx.compose.foundation.layout.Spacer(Modifier.padding(16.dp)) }
        }
    }
}
