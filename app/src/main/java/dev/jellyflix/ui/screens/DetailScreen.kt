package dev.jellyflix.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import coil3.compose.AsyncImage
import dev.jellyflix.R
import dev.jellyflix.data.MediaRepository
import dev.jellyflix.ui.appViewModel
import dev.jellyflix.ui.components.ItemRow
import dev.jellyflix.ui.components.Load
import dev.jellyflix.ui.components.LoadView
import dev.jellyflix.ui.components.MediaCard
import dev.jellyflix.ui.components.SectionTitle
import dev.jellyflix.ui.components.focusRing
import dev.jellyflix.ui.components.formatRuntime
import dev.jellyflix.ui.rememberContainer
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.ImageType
import java.util.UUID

data class DetailData(
    val item: BaseItemDto,
    val seasons: List<BaseItemDto> = emptyList(),
    val selectedSeason: UUID? = null,
    val episodes: List<BaseItemDto> = emptyList(),
    val similar: List<BaseItemDto> = emptyList(),
    /** Episode/movie to start when pressing Play (next unwatched episode for a series). */
    val playTarget: BaseItemDto? = null,
    val tracks: List<BaseItemDto> = emptyList(),
    val albums: List<BaseItemDto> = emptyList(),
)

class DetailViewModel(private val repo: MediaRepository, private val id: UUID) : ViewModel() {
    private val _state = MutableStateFlow<Load<DetailData>>(Load.Loading)
    val state: StateFlow<Load<DetailData>> = _state

    init { load() }

    fun load() {
        viewModelScope.launch {
            _state.value = Load.Loading
            _state.value = runCatching {
                coroutineScope {
                    val item = repo.item(id)
                    val similar = async { runCatching { repo.similar(id) }.getOrDefault(emptyList()) }
                    when (item.type) {
                        BaseItemKind.SERIES -> {
                            val seasons = repo.seasons(id)
                            val next = runCatching { repo.episodes(id, null).firstOrNull { it.userData?.played != true } ?: repo.episodes(id, null).firstOrNull() }.getOrNull()
                            val season = next?.seasonId ?: seasons.firstOrNull()?.id
                            val eps = season?.let { repo.episodes(id, it) } ?: emptyList()
                            DetailData(item, seasons, season, eps, similar.await(), next)
                        }
                        BaseItemKind.SEASON -> {
                            val eps = repo.episodes(item.seriesId ?: id, id)
                            DetailData(item, episodes = eps, playTarget = eps.firstOrNull { it.userData?.played != true } ?: eps.firstOrNull())
                        }
                        BaseItemKind.MUSIC_ALBUM -> DetailData(item, tracks = repo.tracks(id, playlistOrder = false), similar = similar.await())
                        BaseItemKind.PLAYLIST -> if (item.mediaType == org.jellyfin.sdk.model.api.MediaType.AUDIO) DetailData(item, tracks = repo.tracks(id, playlistOrder = true))
                            else DetailData(item, episodes = repo.children(id))
                        BaseItemKind.PERSON -> DetailData(item, albums = repo.filmography(id))
                        BaseItemKind.MUSIC_ARTIST -> DetailData(item, albums = repo.albumsOfArtist(id))
                        BaseItemKind.AUDIO -> DetailData(item, tracks = listOf(item))
                        BaseItemKind.MOVIE, BaseItemKind.EPISODE, BaseItemKind.VIDEO -> DetailData(item, similar = similar.await(), playTarget = item)
                        else -> DetailData(item, episodes = repo.children(id), similar = emptyList())
                    }
                }
            }.fold({ Load.Ready(it) }, { Load.Failed(it.message) })
        }
    }

    fun selectSeason(seasonId: UUID) {
        val cur = (_state.value as? Load.Ready)?.data ?: return
        viewModelScope.launch {
            runCatching { repo.episodes(cur.item.id, seasonId) }.onSuccess { eps ->
                _state.value = Load.Ready(cur.copy(selectedSeason = seasonId, episodes = eps))
            }
        }
    }

    fun toggleFavorite() = mutate { d -> val f = d.item.userData?.isFavorite != true; repo.setFavorite(id, f); f to null }
    fun togglePlayed() = mutate { d -> val p = d.item.userData?.played != true; repo.setPlayed(id, p); null to p }

    private fun mutate(op: suspend (DetailData) -> Pair<Boolean?, Boolean?>) {
        val cur = (_state.value as? Load.Ready)?.data ?: return
        viewModelScope.launch {
            runCatching { op(cur) }.onSuccess { (fav, played) ->
                val ud = cur.item.userData
                val newItem = cur.item.copy(userData = ud?.copy(isFavorite = fav ?: ud.isFavorite, played = played ?: ud.played))
                _state.update { Load.Ready(cur.copy(item = newItem)) }
            }
        }
    }
}

@Composable
fun DetailScreen(id: UUID, onBack: () -> Unit, onOpen: (BaseItemDto) -> Unit, onPlay: (BaseItemDto) -> Unit, onPlayTracks: (List<BaseItemDto>, Int, Boolean) -> Unit = { _, _, _ -> }) {
    val vm = appViewModel(key = "detail-$id") { DetailViewModel(it.repository, id) }
    val repo = rememberContainer().repository
    val state by vm.state.collectAsState()

    Box(Modifier.fillMaxSize()) {
        LoadView(state, vm::load) { d ->
            val item = d.item
            LazyColumn(Modifier.fillMaxSize()) {
                item {
                    Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f)) {
                        val backdrop = repo.imageUrl(item, ImageType.BACKDROP, 1280) ?: repo.imageUrl(item, ImageType.PRIMARY, 800)
                        if (backdrop != null) AsyncImage(backdrop, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, MaterialTheme.colorScheme.background))))
                        Text(item.name.orEmpty(), style = MaterialTheme.typography.headlineLarge, modifier = Modifier.align(Alignment.BottomStart).padding(16.dp))
                    }
                }
                if (d.tracks.isNotEmpty()) {
                    item {
                        Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Button({ onPlayTracks(d.tracks, 0, false) }, Modifier.focusRing()) { Icon(Icons.Default.PlayArrow, null); Text(stringResource(R.string.play_all), Modifier.padding(start = 6.dp)) }
                            androidx.compose.material3.OutlinedButton({ onPlayTracks(d.tracks, 0, true) }, Modifier.focusRing()) { Text(stringResource(R.string.shuffle)) }
                        }
                    }
                    itemsIndexed(d.tracks, key = { _, t -> t.id }) { i, t -> TrackRow(i, t) { onPlayTracks(d.tracks, i, false) } }
                }
                if (d.albums.isNotEmpty()) item { ItemRow(stringResource(if (item.type == BaseItemKind.PERSON) R.string.filmography else R.string.albums), d.albums, repo, onOpen) }
                val cast = item.people.orEmpty().filter { it.id != null && it.type in setOf(org.jellyfin.sdk.model.api.PersonKind.ACTOR, org.jellyfin.sdk.model.api.PersonKind.DIRECTOR) }.take(20)
                if (cast.isNotEmpty() && item.type != BaseItemKind.PERSON) item {
                    // People become minimal items so they reuse the same card (and open the person page).
                    ItemRow(stringResource(R.string.cast), cast.map { p ->
                        BaseItemDto(id = p.id, type = BaseItemKind.PERSON, name = p.name, productionYear = null,
                            imageTags = p.primaryImageTag?.let { mapOf(ImageType.PRIMARY to it) })
                    }, repo, onOpen)
                }
                item {
                    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        MetaLine(item)
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                            d.playTarget?.let { t ->
                                val resume = (t.userData?.playbackPositionTicks ?: 0L) > 0
                                Button(onClick = { onPlay(t) }, modifier = Modifier.focusRing()) {
                                    Icon(Icons.Default.PlayArrow, null)
                                    Text(stringResource(if (resume) R.string.resume else R.string.play), Modifier.padding(start = 6.dp))
                                }
                            }
                            DownloadButton(item, d.episodes, d.tracks)
                            FilledTonalIconButton(vm::togglePlayed, Modifier.focusRing(androidx.compose.foundation.shape.CircleShape)) {
                                Icon(Icons.Default.Check, stringResource(R.string.mark_watched), tint = if (item.userData?.played == true) MaterialTheme.colorScheme.primary else LocalContentColorFallback())
                            }
                            FilledTonalIconButton(vm::toggleFavorite, Modifier.focusRing(androidx.compose.foundation.shape.CircleShape)) {
                                val fav = item.userData?.isFavorite == true
                                Icon(if (fav) Icons.Default.Favorite else Icons.Default.FavoriteBorder, stringResource(R.string.favorite), tint = if (fav) MaterialTheme.colorScheme.primary else LocalContentColorFallback())
                            }
                        }
                        item.overview?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                        if (!item.genres.isNullOrEmpty()) Text(item.genres!!.joinToString(" · "), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (d.seasons.isNotEmpty()) item {
                    LazyRow(Modifier.padding(top = 12.dp), contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(d.seasons, key = { it.id }) { s ->
                            FilterChip(selected = s.id == d.selectedSeason, onClick = { vm.selectSeason(s.id) }, label = { Text(s.name.orEmpty()) }, modifier = Modifier.focusRing())
                        }
                    }
                }
                if (d.episodes.isNotEmpty() && item.type in setOf(BaseItemKind.SERIES, BaseItemKind.SEASON)) {
                    item { SectionTitle(stringResource(R.string.episodes), Modifier.padding(top = 8.dp)) }
                    items(d.episodes, key = { it.id }) { ep -> EpisodeRow(ep, onPlay = { onPlay(ep) }, repo = repo) }
                } else if (d.episodes.isNotEmpty()) {
                    item { ItemRow("", d.episodes, repo, onOpen) }
                }
                item { ItemRow(stringResource(R.string.similar), d.similar, repo, onOpen) }
                item { Spacer(Modifier.height(24.dp)) }
            }
        }
        IconButton(onBack, Modifier.padding(8.dp).background(Color.Black.copy(alpha = 0.4f), androidx.compose.foundation.shape.CircleShape).focusRing(androidx.compose.foundation.shape.CircleShape)) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back), tint = Color.White)
        }
    }
}

@Composable
private fun LocalContentColorFallback() = androidx.compose.material3.LocalContentColor.current

@Composable
private fun MetaLine(item: BaseItemDto) {
    val parts = listOfNotNull(
        item.productionYear?.toString(), item.officialRating, formatRuntime(item.runTimeTicks),
        item.communityRating?.let { "★ %.1f".format(it) },
    )
    if (parts.isNotEmpty()) Text(parts.joinToString("  ·  "), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun EpisodeRow(ep: BaseItemDto, onPlay: () -> Unit, repo: MediaRepository) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        MediaCard(ep, repo, onPlay, wide = true, width = 160.dp)
        Column(Modifier.weight(1f)) {
            Text("${ep.indexNumber ?: ""}. ${ep.name}", style = MaterialTheme.typography.titleSmall, maxLines = 2)
            formatRuntime(ep.runTimeTicks)?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            ep.overview?.let { Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 3, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) }
        }
    }
}

/**
 * Movie/episode: download, show progress, or mark as downloaded. Series/season: download every episode listed.
 * Asks for the notification permission first on Android 13+ (the download runs as a foreground service).
 */
@Composable
private fun DownloadButton(item: BaseItemDto, episodes: List<BaseItemDto>, tracks: List<BaseItemDto>) {
    val downloads = rememberContainer().downloads
    val all by downloads.entries.collectAsState()
    val targets = when (item.type) {
        BaseItemKind.SERIES, BaseItemKind.SEASON -> episodes.filter { it.type == BaseItemKind.EPISODE }
        BaseItemKind.MUSIC_ALBUM, BaseItemKind.PLAYLIST -> tracks.filter { it.type == BaseItemKind.AUDIO }
        else -> listOf(item)
    }
    if (targets.isEmpty()) return
    val states = targets.mapNotNull { all[it.id.toString()]?.status }
    val allDone = states.size == targets.size && states.all { it == dev.jellyflix.download.DownloadStatus.COMPLETE }
    val active = states.any { it == dev.jellyflix.download.DownloadStatus.DOWNLOADING || it == dev.jellyflix.download.DownloadStatus.QUEUED }
    val launcher = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) {
        downloads.enqueue(targets) // download even if notifications were declined
    }
    val ctx = androidx.compose.ui.platform.LocalContext.current
    FilledTonalIconButton(
        {
            if (allDone || active) return@FilledTonalIconButton
            if (android.os.Build.VERSION.SDK_INT >= 33 &&
                androidx.core.content.ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
            ) launcher.launch(android.Manifest.permission.POST_NOTIFICATIONS) else downloads.enqueue(targets)
        },
        Modifier.focusRing(androidx.compose.foundation.shape.CircleShape),
    ) {
        when {
            allDone -> Icon(Icons.Default.DownloadDone, stringResource(R.string.downloaded), tint = MaterialTheme.colorScheme.primary)
            active -> androidx.compose.material3.CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            else -> Icon(Icons.Default.Download, stringResource(if (targets.size > 1) R.string.download_season else R.string.download))
        }
    }
}

@Composable
private fun TrackRow(index: Int, track: BaseItemDto, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).focusRing().padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("${track.indexNumber ?: (index + 1)}", Modifier.width(28.dp), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Column(Modifier.weight(1f)) {
            Text(track.name.orEmpty(), style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            (track.artists?.joinToString(", ") ?: track.albumArtist)?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1) }
        }
        formatRuntime(track.runTimeTicks)?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}
