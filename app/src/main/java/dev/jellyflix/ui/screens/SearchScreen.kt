package dev.jellyflix.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.jellyflix.R
import dev.jellyflix.data.MediaRepository
import dev.jellyflix.ui.appViewModel
import dev.jellyflix.ui.components.Load
import dev.jellyflix.ui.components.LoadingView
import dev.jellyflix.ui.components.MediaCard
import dev.jellyflix.ui.rememberContainer
import dev.jellyflix.ui.theme.LocalIsTv
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import org.jellyfin.sdk.model.api.BaseItemDto

@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
class SearchViewModel(private val repo: MediaRepository) : ViewModel() {
    val query = MutableStateFlow("")

    /** Debounced, cancels in-flight requests when the query changes. */
    val results: StateFlow<Load<List<BaseItemDto>>?> = query
        .debounce(350).distinctUntilChanged()
        .flatMapLatest { q ->
            flow<Load<List<BaseItemDto>>?> {
                if (q.isBlank()) { emit(null); return@flow }
                emit(Load.Loading)
                emit(runCatching { repo.search(q.trim()) }.fold({ Load.Ready(it) }, { Load.Failed(it.message) }))
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
}

@Composable
fun SearchScreen(onOpen: (BaseItemDto) -> Unit) {
    val vm = appViewModel { SearchViewModel(it.repository) }
    val repo = rememberContainer().repository
    val query by vm.query.collectAsState()
    val results by vm.results.collectAsState()
    val isTv = LocalIsTv.current

    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = query, onValueChange = { vm.query.value = it }, singleLine = true,
            leadingIcon = { Icon(Icons.Default.Search, null) }, placeholder = { Text(stringResource(R.string.search_hint)) },
            modifier = Modifier.fillMaxWidth().padding(16.dp),
        )
        when (val r = results) {
            null -> Unit
            Load.Loading -> LoadingView()
            is Load.Failed -> Box(Modifier.fillMaxSize(), Alignment.Center) { Text(r.message ?: stringResource(R.string.error_generic)) }
            is Load.Ready -> if (r.data.isEmpty()) Box(Modifier.fillMaxSize(), Alignment.Center) { Text(stringResource(R.string.no_results)) }
            else LazyVerticalGrid(
                columns = GridCells.Adaptive(if (isTv) 170.dp else 116.dp), contentPadding = PaddingValues(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp),
            ) { items(r.data, key = { it.id }) { MediaCard(it, repo, { onOpen(it) }, fillWidth = true) } }
        }
    }
}
