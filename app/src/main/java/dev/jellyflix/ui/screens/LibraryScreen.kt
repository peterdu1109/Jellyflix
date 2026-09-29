package dev.jellyflix.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.jellyflix.data.MediaRepository
import dev.jellyflix.ui.appViewModel
import dev.jellyflix.ui.components.ItemRow
import dev.jellyflix.ui.components.Load
import dev.jellyflix.ui.components.LoadView
import dev.jellyflix.ui.components.LoadingView
import dev.jellyflix.ui.components.MediaCard
import dev.jellyflix.ui.rememberContainer
import dev.jellyflix.ui.theme.LocalIsTv
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.CollectionType
import org.jellyfin.sdk.model.api.ItemSortBy
import org.jellyfin.sdk.model.api.SortOrder
import java.util.UUID

/** Library tab: the list of the user's libraries. */
@Composable
fun LibrariesScreen(onOpenLibrary: (BaseItemDto) -> Unit) {
    val repo = rememberContainer().repository
    val vm = appViewModel { LibrariesViewModel(it.repository) }
    val state by vm.state.collectAsState()
    LoadView(state, vm::load) { views ->
        androidx.compose.foundation.lazy.LazyColumn { item { ItemRow(dev.jellyflix.R.string.nav_library.let { androidx.compose.ui.res.stringResource(it) }, views, repo, onOpenLibrary, wide = true) } }
    }
}

class LibrariesViewModel(private val repo: MediaRepository) : ViewModel() {
    private val _state = MutableStateFlow<Load<List<BaseItemDto>>>(Load.Loading)
    val state: StateFlow<Load<List<BaseItemDto>>> = _state
    init { load() }
    fun load() {
        viewModelScope.launch {
            _state.value = Load.Loading
            _state.value = runCatching {
                // Same order and visibility as configured on the server.
                val layout = repo.userLayout()
                repo.views().filter { it.id !in layout.hiddenViews }
                    .sortedBy { v -> layout.orderedViews.indexOf(v.id).let { if (it < 0) Int.MAX_VALUE else it } }
            }.fold({ Load.Ready(it) }, { Load.Failed(it.message) })
        }
    }
}

data class GridState(
    val items: List<BaseItemDto> = emptyList(),
    val total: Int = 0,
    val loading: Boolean = true,
    val error: String? = null,
    val sort: ItemSortBy = ItemSortBy.SORT_NAME,
    val order: SortOrder = SortOrder.ASCENDING,
)

/** Paged grid: fetches 60 items at a time as the user scrolls near the end. */
class LibraryViewModel(private val repo: MediaRepository, private val parentId: UUID) : ViewModel() {
    private val _state = MutableStateFlow(GridState())
    val state: StateFlow<GridState> = _state
    private var generation = 0

    /** Item kinds shown for each library type, so a music library lists albums and a playlist library lists playlists. */
    private var types: List<BaseItemKind>? = null

    init {
        viewModelScope.launch {
            types = runCatching { typesFor(repo.item(parentId).collectionType) }.getOrNull()
            reload()
        }
    }

    private fun typesFor(type: CollectionType?): List<BaseItemKind>? = when (type) {
        CollectionType.MOVIES -> listOf(BaseItemKind.MOVIE)
        CollectionType.TVSHOWS -> listOf(BaseItemKind.SERIES)
        CollectionType.MUSIC -> listOf(BaseItemKind.MUSIC_ALBUM)
        CollectionType.BOXSETS -> listOf(BaseItemKind.BOX_SET)
        CollectionType.PLAYLISTS -> listOf(BaseItemKind.PLAYLIST)
        CollectionType.HOMEVIDEOS, CollectionType.MUSICVIDEOS -> listOf(BaseItemKind.VIDEO, BaseItemKind.MUSIC_VIDEO)
        else -> null
    }

    fun setSort(sort: ItemSortBy, order: SortOrder) { _state.update { it.copy(sort = sort, order = order) }; reload() }

    private fun reload() { generation++; _state.update { it.copy(items = emptyList(), total = 0) }; loadMore() }

    fun loadMore() {
        val s = _state.value
        if (s.items.isNotEmpty() && s.items.size >= s.total) return
        val gen = generation
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            runCatching { repo.browse(parentId, s.sort, s.order, s.items.size, types = types) }
                .onSuccess { page -> if (gen == generation) _state.update { it.copy(items = it.items + page.items, total = page.total, loading = false) } }
                .onFailure { e -> if (gen == generation) _state.update { it.copy(loading = false, error = e.message) } }
        }
    }
}

@Composable
fun LibraryScreen(parentId: UUID, onOpen: (BaseItemDto) -> Unit) {
    val repo = rememberContainer().repository
    val vm = appViewModel(key = "lib-$parentId") { LibraryViewModel(it.repository, parentId) }
    val state by vm.state.collectAsState()
    val gridState = rememberLazyGridState()
    val isTv = LocalIsTv.current

    LaunchedEffect(gridState) {
        snapshotFlow { gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }.collect { last ->
            if (last >= state.items.size - 12) vm.loadMore()
        }
    }
    val sorts = listOf(ItemSortBy.SORT_NAME to SortOrder.ASCENDING, ItemSortBy.DATE_CREATED to SortOrder.DESCENDING,
        ItemSortBy.PREMIERE_DATE to SortOrder.DESCENDING, ItemSortBy.COMMUNITY_RATING to SortOrder.DESCENDING)
    val labels = mapOf(ItemSortBy.SORT_NAME to "A–Z", ItemSortBy.DATE_CREATED to "Added", ItemSortBy.PREMIERE_DATE to "Year", ItemSortBy.COMMUNITY_RATING to "Rating")

    Box(Modifier.fillMaxSize()) {
        LazyVerticalGrid(
            state = gridState, columns = GridCells.Adaptive(if (isTv) 170.dp else 116.dp),
            contentPadding = PaddingValues(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
                androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(sorts.size) { i ->
                        val (by, order) = sorts[i]
                        FilterChip(selected = state.sort == by, onClick = { vm.setSort(by, order) }, label = { Text(labels.getValue(by)) })
                    }
                }
            }
            itemsIndexed(state.items, key = { _, it -> it.id }) { _, item ->
                MediaCard(item, repo, { onOpen(item) }, fillWidth = true)
            }
        }
        if (state.loading && state.items.isEmpty()) LoadingView()
    }
}
