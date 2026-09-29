package dev.jellyflix.ui.screens

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.jellyflix.R
import dev.jellyflix.data.AppSettings
import dev.jellyflix.data.MediaRepository
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

data class HomeData(
    val resume: List<BaseItemDto>,
    val nextUp: List<BaseItemDto>,
    val favorites: List<BaseItemDto>,
    val views: List<BaseItemDto>,
    val latest: List<Pair<BaseItemDto, List<BaseItemDto>>>,
)

class HomeViewModel(private val repo: MediaRepository) : ViewModel() {
    private val _state = MutableStateFlow<Load<HomeData>>(Load.Loading)
    val state: StateFlow<Load<HomeData>> = _state

    init { load() }

    fun load() {
        viewModelScope.launch {
            _state.value = Load.Loading
            _state.value = runCatching {
                coroutineScope {
                    val resume = async { repo.resume() }
                    val next = async { repo.nextUp() }
                    val favs = async { runCatching { repo.favorites() }.getOrDefault(emptyList()) }
                    val views = repo.views()
                    val latest = views.filter { it.collectionType != CollectionType.PLAYLISTS && it.collectionType != CollectionType.BOXSETS }
                        .map { v -> async { v to runCatching { repo.latest(v.id) }.getOrDefault(emptyList()) } }
                    HomeData(resume.await(), next.await(), favs.await(), views, latest.map { it.await() })
                }
            }.fold({ Load.Ready(it) }, { Load.Failed(it.message) })
        }
    }

    /** Silent refresh when coming back from the player so progress rows are current. */
    fun refresh() {
        viewModelScope.launch {
            runCatching {
                val r = repo.resume(); val n = repo.nextUp()
                (_state.value as? Load.Ready)?.let { _state.value = Load.Ready(it.data.copy(resume = r, nextUp = n)) }
            }
        }
    }
}

@Composable
fun HomeScreen(settings: AppSettings, plugins: PluginRegistry, onOpen: (BaseItemDto) -> Unit, onPlay: (BaseItemDto) -> Unit, onOpenLibrary: (BaseItemDto) -> Unit) {
    val vm = appViewModel { HomeViewModel(it.repository) }
    val repo = rememberContainer().repository
    val state by vm.state.collectAsState()
    val sections = plugins.homeSections(settings)
    androidx.compose.runtime.LaunchedEffect(Unit) { vm.refresh() }

    LoadView(state, vm::load) { data ->
        LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 8.dp)) {
            if (HomeSection.ContinueWatching in sections) item { ItemRow(stringResource(R.string.continue_watching), data.resume, repo, onPlay, wide = true) }
            if (HomeSection.NextUp in sections) item { ItemRow(stringResource(R.string.next_up), data.nextUp, repo, onPlay, wide = true) }
            item { ItemRow(stringResource(R.string.my_libraries), data.views, repo, onOpenLibrary, wide = true) }
            if (HomeSection.Favorites in sections) item { ItemRow(stringResource(R.string.favorites), data.favorites, repo, onOpen) }
            if (HomeSection.LatestPerLibrary in sections) data.latest.forEach { (view, items) ->
                item(key = view.id) { ItemRow(stringResource(R.string.latest_in, view.name.orEmpty()), items, repo, onOpen) }
            }
            item { androidx.compose.foundation.layout.Spacer(Modifier.padding(16.dp)) }
        }
    }
}
