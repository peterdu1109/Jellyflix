package dev.jellyflix.music

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import dev.jellyflix.data.MediaRepository
import dev.jellyflix.data.SessionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.jellyfin.sdk.api.client.extensions.playStateApi
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.ImageType
import org.jellyfin.sdk.model.api.PlayMethod
import org.jellyfin.sdk.model.api.PlaybackOrder
import org.jellyfin.sdk.model.api.PlaybackProgressInfo
import org.jellyfin.sdk.model.api.PlaybackStartInfo
import org.jellyfin.sdk.model.api.PlaybackStopInfo
import org.jellyfin.sdk.model.api.RepeatMode as JfRepeat
import java.util.UUID

data class QueueEntry(val id: String, val title: String, val artist: String?)

data class MusicState(
    val hasQueue: Boolean = false,
    val title: String = "",
    val artist: String = "",
    val artworkUrl: String? = null,
    val isPlaying: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val shuffle: Boolean = false,
    val repeat: Int = Player.REPEAT_MODE_OFF,
    val queue: List<QueueEntry> = emptyList(),
    val index: Int = 0,
)

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
/** App-side handle on the [MusicService] player: builds queues from Jellyfin items and mirrors playback state for the UI. */
class MusicController(
    private val context: Context,
    private val sessions: SessionManager,
    private val repo: MediaRepository,
    private val downloads: dev.jellyflix.download.DownloadRepository,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _state = MutableStateFlow(MusicState())
    val state: StateFlow<MusicState> = _state

    private var future: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null
    private var ticker: kotlinx.coroutines.Job? = null
    private var reportedId: String? = null
    private var lastPositionMs = 0L
    private var sinceProgress = 0

    private fun connect(then: (MediaController) -> Unit) {
        controller?.let { then(it); return }
        val f = future ?: MediaController.Builder(context, SessionToken(context, ComponentName(context, MusicService::class.java))).buildAsync()
            .also { future = it }
        f.addListener({
            val c = runCatching { f.get() }.getOrNull() ?: return@addListener
            if (controller == null) { controller = c; c.addListener(listener); startTicker(); refresh(c) }
            then(c)
        }, ContextCompat.getMainExecutor(context))
    }

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            refresh(player)
            if (events.contains(Player.EVENT_MEDIA_ITEM_TRANSITION)) onTrackChanged(player)
        }
    }

    private fun refresh(p: Player) {
        val md = p.mediaMetadata
        _state.update {
            it.copy(
                hasQueue = p.mediaItemCount > 0,
                title = md.title?.toString().orEmpty(), artist = (md.artist ?: md.albumArtist)?.toString().orEmpty(),
                artworkUrl = md.artworkUri?.toString(),
                isPlaying = p.isPlaying, positionMs = p.currentPosition.coerceAtLeast(0), durationMs = p.duration.coerceAtLeast(0),
                shuffle = p.shuffleModeEnabled, repeat = p.repeatMode, index = p.currentMediaItemIndex,
                queue = (0 until p.mediaItemCount).map { i ->
                    val m = p.getMediaItemAt(i)
                    QueueEntry(m.mediaId, m.mediaMetadata.title?.toString().orEmpty(), m.mediaMetadata.artist?.toString())
                },
            )
        }
    }

    private fun startTicker() {
        ticker?.cancel()
        ticker = scope.launch {
            while (isActive) {
                delay(500)
                val c = controller ?: continue
                if (c.isPlaying) {
                    lastPositionMs = c.currentPosition
                    _state.update { it.copy(positionMs = c.currentPosition.coerceAtLeast(0), durationMs = c.duration.coerceAtLeast(0)) }
                    if (++sinceProgress >= 20) { sinceProgress = 0; report { api, id -> reportProgress(api, id, c.currentPosition) } }
                }
            }
        }
    }

    // ---- public controls ----

    fun play(items: List<BaseItemDto>, startIndex: Int = 0) {
        val tracks = items.filter { it.mediaType == org.jellyfin.sdk.model.api.MediaType.AUDIO || it.type == org.jellyfin.sdk.model.api.BaseItemKind.AUDIO }
        if (tracks.isEmpty()) return
        val start = startIndex.coerceIn(0, tracks.lastIndex)
        val resumeMs = (tracks[start].userData?.playbackPositionTicks ?: 0L) / 10_000
        connect { c ->
            c.setMediaItems(tracks.mapNotNull(::mediaItem), start, if (tracks.size == 1) resumeMs else 0L)
            c.prepare(); c.play()
        }
    }

    fun toggle() = controller?.let { if (it.isPlaying) it.pause() else it.play() }
    fun next() { controller?.seekToNext() }
    fun previous() { controller?.seekToPrevious() }
    fun seekTo(ms: Long) { controller?.seekTo(ms) }
    fun skipTo(index: Int) { controller?.seekTo(index, 0) }
    fun setShuffle(on: Boolean) { controller?.shuffleModeEnabled = on }
    fun toggleShuffle() { controller?.let { it.shuffleModeEnabled = !it.shuffleModeEnabled } }
    fun cycleRepeat() {
        controller?.let {
            it.repeatMode = when (it.repeatMode) { Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL; Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE; else -> Player.REPEAT_MODE_OFF }
        }
    }
    fun stop() { controller?.let { it.stop(); it.clearMediaItems() }; _state.value = MusicState() }

    // ---- building items ----

    /** Jellyfin's universal audio endpoint: direct play when the device can, transcoded HLS otherwise. */
    private fun mediaItem(item: BaseItemDto): MediaItem? {
        val s = sessions.current ?: return null
        val base = s.account.serverUrl.trimEnd('/')
        // A downloaded track plays from the file, so it works without a connection.
        val local = downloads.playable(item.id)?.let { downloads.videoFile(it) }
        val url = if (local != null) Uri.fromFile(local).toString() else "$base/Audio/${item.id}/universal?UserId=${s.account.userId}&DeviceId=${Uri.encode(s.api.deviceInfo.id)}" +
            "&MaxStreamingBitrate=140000000&Container=mp3,aac,m4a,flac,ogg,wav,opus,webma" +
            "&TranscodingContainer=ts&TranscodingProtocol=hls&AudioCodec=aac&api_key=${Uri.encode(s.account.token)}"
        val art = repo.imageUrl(item, ImageType.PRIMARY, 600)
            ?: item.albumId?.let { repo.imageUrlFor(it, item.albumPrimaryImageTag, ImageType.PRIMARY, 600) }
        return MediaItem.Builder().setMediaId(item.id.toString()).setUri(url)
            .setMediaMetadata(
                MediaMetadata.Builder().setTitle(item.name).setArtist(item.artists?.joinToString(", ") ?: item.albumArtist)
                    .setAlbumTitle(item.album).setArtworkUri(art?.let(Uri::parse)).build(),
            ).build()
    }

    // ---- server reporting (scrobbling plugins, "recently played") ----

    private fun onTrackChanged(p: Player) {
        val id = p.currentMediaItem?.mediaId
        if (id == reportedId) return
        val prev = reportedId
        val prevPos = lastPositionMs
        reportedId = id
        lastPositionMs = 0
        report { api, _ ->
            prev?.let { runCatching { api.playStateApi.reportPlaybackStopped(PlaybackStopInfo(itemId = UUID.fromString(it), positionTicks = prevPos * 10_000, failed = false)) } }
            id?.let { runCatching { api.playStateApi.reportPlaybackStart(PlaybackStartInfo(
                itemId = UUID.fromString(it), canSeek = true, isPaused = false, isMuted = false, positionTicks = 0, playMethod = PlayMethod.DIRECT_PLAY,
                repeatMode = JfRepeat.REPEAT_NONE, playbackOrder = PlaybackOrder.DEFAULT)) } }
        }
    }

    private suspend fun reportProgress(api: org.jellyfin.sdk.api.client.ApiClient, id: String, positionMs: Long) {
        runCatching { api.playStateApi.reportPlaybackProgress(PlaybackProgressInfo(
            itemId = UUID.fromString(id), canSeek = true, isPaused = false, isMuted = false, positionTicks = positionMs * 10_000,
            playMethod = PlayMethod.DIRECT_PLAY, repeatMode = JfRepeat.REPEAT_NONE, playbackOrder = PlaybackOrder.DEFAULT)) }
    }

    private fun report(block: suspend (org.jellyfin.sdk.api.client.ApiClient, String) -> Unit) {
        val api = sessions.current?.api ?: return
        val id = reportedId ?: ""
        scope.launch(Dispatchers.IO) { block(api, id) }
    }
}
