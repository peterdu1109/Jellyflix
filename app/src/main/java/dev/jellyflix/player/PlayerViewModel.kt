package dev.jellyflix.player

import android.app.Application
import android.net.Uri
import androidx.annotation.OptIn
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import dev.jellyflix.data.AppContainer
import dev.jellyflix.data.AppSettings
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.jellyfin.sdk.api.client.extensions.mediaInfoApi
import org.jellyfin.sdk.api.client.extensions.mediaSegmentsApi
import org.jellyfin.sdk.api.client.extensions.playStateApi
import org.jellyfin.sdk.api.client.extensions.tvShowsApi
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.MediaSegmentType
import org.jellyfin.sdk.model.api.MediaSourceInfo
import org.jellyfin.sdk.model.api.MediaStream
import org.jellyfin.sdk.model.api.MediaStreamType
import org.jellyfin.sdk.model.api.PlayMethod
import org.jellyfin.sdk.model.api.PlaybackOrder
import org.jellyfin.sdk.model.api.RepeatMode
import org.jellyfin.sdk.model.api.PlaybackInfoDto
import org.jellyfin.sdk.model.api.PlaybackProgressInfo
import org.jellyfin.sdk.model.api.PlaybackStartInfo
import org.jellyfin.sdk.model.api.PlaybackStopInfo
import org.jellyfin.sdk.model.api.SubtitleDeliveryMethod
import java.util.UUID

data class Segment(val type: MediaSegmentType, val startMs: Long, val endMs: Long)
data class TrackOption(val streamIndex: Int, val label: String)

data class PlayerUiState(
    val item: BaseItemDto? = null,
    val loading: Boolean = true,
    val error: String? = null,
    val audio: List<TrackOption> = emptyList(),
    val subtitles: List<TrackOption> = emptyList(),
    val audioIndex: Int? = null,
    val subtitleIndex: Int? = null, // null = off
    val activeSegment: Segment? = null,
    val playMethod: PlayMethod = PlayMethod.DIRECT_PLAY,
)

@OptIn(UnstableApi::class)
class PlayerViewModel(
    private val app: Application,
    private val container: AppContainer,
    private val itemId: UUID,
) : ViewModel() {
    private val session get() = container.session.current ?: error("Not signed in")
    private val api get() = session.api

    val player: ExoPlayer = ExoPlayer.Builder(app)
        .setMediaSourceFactory(
            DefaultMediaSourceFactory(
                DefaultHttpDataSource.Factory().setAllowCrossProtocolRedirects(true)
                    .setDefaultRequestProperties(mapOf("X-Emby-Token" to session.account.token)),
            ),
        )
        .setLoadControl(DefaultLoadControl.Builder().setBufferDurationsMs(20_000, 60_000, 1_500, 3_000).build())
        .setHandleAudioBecomingNoisy(true)
        .setSeekBackIncrementMs(10_000).setSeekForwardIncrementMs(10_000)
        .build()

    private val _ui = MutableStateFlow(PlayerUiState())
    val ui: StateFlow<PlayerUiState> = _ui

    /** One-shot: id of the next episode to open when autoplay is on. */
    private val _next = MutableSharedFlow<UUID>(extraBufferCapacity = 1)
    val nextEpisode: SharedFlow<UUID> = _next

    private var settings = AppSettings()
    private var source: MediaSourceInfo? = null
    private var playSessionId: String? = null
    private var segments: List<Segment> = emptyList()
    private var loopJob: Job? = null
    private var stopped = false

    init {
        player.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                // A direct play failure (unsupported codec…) falls back to server transcoding once.
                if (_ui.value.playMethod != PlayMethod.TRANSCODE && !forcedTranscode) {
                    forcedTranscode = true
                    reload(player.currentPosition, _ui.value.audioIndex, _ui.value.subtitleIndex)
                } else _ui.update { it.copy(loading = false, error = error.message ?: error.errorCodeName) }
            }
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY) _ui.update { it.copy(loading = false) }
                if (state == Player.STATE_ENDED) onEnded()
            }
            override fun onTracksChanged(tracks: Tracks) { /* selection is driven by the user; nothing to sync */ }
        })
        viewModelScope.launch {
            settings = container.settings.settings.first()
            start()
        }
    }

    private var forcedTranscode = false

    private suspend fun start() {
        runCatching {
            val item = container.repository.item(itemId)
            _ui.update { it.copy(item = item) }
            val resumeMs = (item.userData?.playbackPositionTicks ?: 0L) / 10_000
            // Restart from the beginning when almost finished.
            val startMs = if (resumeMs > 0 && (item.runTimeTicks ?: Long.MAX_VALUE) / 10_000 - resumeMs < 30_000) 0 else resumeMs
            load(startMs, audioIndex = null, subtitleIndex = null, first = true)
            if (container.plugins.segmentSkip(settings)) loadSegments()
            startLoop()
        }.onFailure { e -> _ui.update { it.copy(loading = false, error = e.message) } }
    }

    private fun reload(positionMs: Long, audioIndex: Int?, subtitleIndex: Int?) {
        viewModelScope.launch {
            runCatching { load(positionMs, audioIndex, subtitleIndex, first = false) }
                .onFailure { e -> _ui.update { it.copy(loading = false, error = e.message) } }
        }
    }

    private suspend fun load(startMs: Long, audioIndex: Int?, subtitleIndex: Int?, first: Boolean) {
        _ui.update { it.copy(loading = true, error = null) }
        val forceTranscode = forcedTranscode || (audioIndex != null && _ui.value.playMethod == PlayMethod.TRANSCODE)
        val info = api.mediaInfoApi.getPostedPlaybackInfo(
            itemId = itemId,
            data = PlaybackInfoDto(
                userId = session.userId,
                startTimeTicks = startMs * 10_000,
                audioStreamIndex = audioIndex, subtitleStreamIndex = subtitleIndex,
                maxStreamingBitrate = settings.quality.bitrate,
                deviceProfile = DeviceProfiles.build(settings.quality.bitrate),
                enableDirectPlay = !forceTranscode && settings.quality.bitrate == null,
                enableDirectStream = !forceTranscode && settings.quality.bitrate == null,
                enableTranscoding = true, autoOpenLiveStream = true,
            ),
        ).content
        val src = info.mediaSources.firstOrNull() ?: error(info.errorCode?.name ?: "No playable source")
        source = src
        playSessionId = info.playSessionId
        val base = session.account.serverUrl.trimEnd('/')
        val token = Uri.encode(session.account.token)

        val method: PlayMethod
        val url: String
        when {
            src.supportsDirectPlay && !forceTranscode -> {
                method = PlayMethod.DIRECT_PLAY
                url = "$base/Videos/$itemId/stream?static=true&mediaSourceId=${src.id}&api_key=$token" +
                    (playSessionId?.let { "&playSessionId=$it" } ?: "")
            }
            src.transcodingUrl != null -> {
                method = if (src.supportsDirectStream && !forceTranscode) PlayMethod.DIRECT_STREAM else PlayMethod.TRANSCODE
                url = base + src.transcodingUrl
            }
            else -> error("No compatible stream (transcoding unavailable)")
        }

        val streams = src.mediaStreams.orEmpty()
        val audio = streams.filter { it.type == MediaStreamType.AUDIO }
        val subs = streams.filter { it.type == MediaStreamType.SUBTITLE }
        val chosenAudio = audioIndex ?: src.defaultAudioStreamIndex ?: audio.firstOrNull { it.isDefault }?.index ?: audio.firstOrNull()?.index
        val chosenSub = if (first) src.defaultSubtitleStreamIndex?.takeIf { it >= 0 } else subtitleIndex

        val externalSubs = subs.filter { it.deliveryMethod == SubtitleDeliveryMethod.EXTERNAL && it.deliveryUrl != null && method != PlayMethod.TRANSCODE }
            .map { s ->
                val mime = when {
                    s.deliveryUrl!!.contains(".vtt", true) || s.codec.equals("webvtt", true) -> MimeTypes.TEXT_VTT
                    s.deliveryUrl!!.contains(".ass", true) || s.deliveryUrl!!.contains(".ssa", true) -> MimeTypes.TEXT_SSA
                    else -> MimeTypes.APPLICATION_SUBRIP
                }
                MediaItem.SubtitleConfiguration.Builder(Uri.parse(base + s.deliveryUrl))
                    .setId("ext${s.index}").setMimeType(mime).setLanguage(s.language).setLabel(s.label())
                    .setSelectionFlags(0).build()
            }

        _ui.update {
            it.copy(
                audio = audio.map { s -> TrackOption(s.index, s.label()) },
                subtitles = subs.map { s -> TrackOption(s.index, s.label()) },
                audioIndex = chosenAudio, subtitleIndex = chosenSub, playMethod = method,
            )
        }

        val mediaItem = MediaItem.Builder().setUri(url).setMediaId(itemId.toString()).setSubtitleConfigurations(externalSubs).build()
        // For transcoded HLS the server already applied the start offset; for direct play we seek.
        player.setMediaItem(mediaItem, if (method == PlayMethod.TRANSCODE) C.TIME_UNSET else startMs.coerceAtLeast(0))
        player.prepare()
        player.playWhenReady = true
        applyClientTrackSelection(chosenAudio, chosenSub)
        reportStart()
    }

    private fun MediaStream.label(): String =
        displayTitle ?: listOfNotNull(language?.uppercase(), codec?.uppercase(), title).joinToString(" · ").ifBlank { "#$index" }

    /** Direct play exposes every track to ExoPlayer, so switching is instant and needs no server round trip. */
    private fun applyClientTrackSelection(audioIndex: Int?, subIndex: Int?) {
        if (_ui.value.playMethod == PlayMethod.TRANSCODE) return
        val streams = source?.mediaStreams.orEmpty()
        val params = player.trackSelectionParameters.buildUpon()
        val audioOrdinal = streams.filter { it.type == MediaStreamType.AUDIO }.indexOfFirst { it.index == audioIndex }
        params.setPreferredAudioLanguage(null)
        if (subIndex == null) params.setIgnoredTextSelectionFlags(0).setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
        else params.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
        player.trackSelectionParameters = params.build()
        // Overrides need the tracks to be available, so wait for the first Tracks event.
        val listener = object : Player.Listener {
            override fun onTracksChanged(tracks: Tracks) {
                var done = true
                val groups = tracks.groups
                val audioGroups = groups.filter { it.type == C.TRACK_TYPE_AUDIO }
                if (audioOrdinal >= 0 && audioGroups.size > audioOrdinal) {
                    player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                        .setOverrideForType(TrackSelectionOverride(audioGroups[audioOrdinal].mediaTrackGroup, 0)).build()
                } else if (audioOrdinal >= 0) done = false
                if (subIndex != null) {
                    val textGroups = groups.filter { it.type == C.TRACK_TYPE_TEXT }
                    val ext = textGroups.firstOrNull { g -> (0 until g.length).any { g.getTrackFormat(it).id?.endsWith("ext$subIndex") == true } }
                    val embeddedOrdinal = streams.filter { it.type == MediaStreamType.SUBTITLE && it.deliveryMethod != SubtitleDeliveryMethod.EXTERNAL }.indexOfFirst { it.index == subIndex }
                    val target = ext ?: textGroups.filter { g -> (0 until g.length).none { g.getTrackFormat(it).id?.contains("ext") == true } }.getOrNull(embeddedOrdinal)
                    if (target != null) player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                        .setOverrideForType(TrackSelectionOverride(target.mediaTrackGroup, 0)).build()
                    else done = false
                }
                if (done) player.removeListener(this)
            }
        }
        player.addListener(listener)
        // Tracks may already be known (e.g. user switching mid-playback).
        listener.onTracksChanged(player.currentTracks)
    }

    fun selectAudio(index: Int) {
        _ui.update { it.copy(audioIndex = index) }
        if (_ui.value.playMethod == PlayMethod.TRANSCODE) reload(player.currentPosition, index, _ui.value.subtitleIndex)
        else applyClientTrackSelection(index, _ui.value.subtitleIndex)
    }

    fun selectSubtitle(index: Int?) {
        val stream = source?.mediaStreams.orEmpty().firstOrNull { it.index == index }
        _ui.update { it.copy(subtitleIndex = index) }
        val needsBurnIn = index != null && stream != null && stream.deliveryMethod != SubtitleDeliveryMethod.EXTERNAL && stream.deliveryMethod != SubtitleDeliveryMethod.EMBED
        if (_ui.value.playMethod == PlayMethod.TRANSCODE || needsBurnIn) reload(player.currentPosition, _ui.value.audioIndex, index)
        else applyClientTrackSelection(_ui.value.audioIndex, index)
    }

    fun changeQuality(cap: dev.jellyflix.data.QualityCap) {
        settings = settings.copy(quality = cap)
        forcedTranscode = cap.bitrate != null
        reload(player.currentPosition, _ui.value.audioIndex, _ui.value.subtitleIndex)
    }

    private suspend fun loadSegments() {
        segments = runCatching {
            api.mediaSegmentsApi.getItemSegments(itemId).content.items.map { Segment(it.type, it.startTicks / 10_000, it.endTicks / 10_000) }
        }.getOrDefault(emptyList())
    }

    fun skipActiveSegment() {
        _ui.value.activeSegment?.let { player.seekTo(it.endMs) }
    }

    private fun startLoop() {
        loopJob?.cancel()
        loopJob = viewModelScope.launch {
            var sinceReport = 0
            while (isActive) {
                delay(500)
                val pos = player.currentPosition
                val seg = segments.firstOrNull { it.type != MediaSegmentType.UNKNOWN && pos >= it.startMs && pos < it.endMs - 1000 }
                if (seg != _ui.value.activeSegment) _ui.update { it.copy(activeSegment = seg) }
                if (++sinceReport >= 20 && player.isPlaying) { sinceReport = 0; reportProgress() }
            }
        }
    }

    private fun onEnded() {
        viewModelScope.launch {
            reportStopped()
            val item = _ui.value.item
            if (item?.type == org.jellyfin.sdk.model.api.BaseItemKind.EPISODE && container.plugins.autoPlayNext(settings)) {
                val seriesId = item.seriesId ?: return@launch
                runCatching {
                    api.tvShowsApi.getEpisodes(seriesId = seriesId, userId = session.userId, startItemId = itemId, limit = 2).content.items
                }.getOrNull()?.getOrNull(1)?.let { _next.emit(it.id) }
            }
        }
    }

    private fun ticks() = player.currentPosition * 10_000

    private suspend fun reportStart() = runCatching {
        api.playStateApi.reportPlaybackStart(PlaybackStartInfo(
            itemId = itemId, canSeek = true, isPaused = false, isMuted = false, positionTicks = ticks(),
            playMethod = _ui.value.playMethod, playSessionId = playSessionId, mediaSourceId = source?.id,
            audioStreamIndex = _ui.value.audioIndex, subtitleStreamIndex = _ui.value.subtitleIndex,
            repeatMode = RepeatMode.REPEAT_NONE, playbackOrder = PlaybackOrder.DEFAULT,
        ))
    }

    fun reportProgress() {
        viewModelScope.launch {
            runCatching {
                api.playStateApi.reportPlaybackProgress(PlaybackProgressInfo(
                    itemId = itemId, canSeek = true, isPaused = !player.isPlaying, isMuted = player.volume == 0f, positionTicks = ticks(),
                    playMethod = _ui.value.playMethod, playSessionId = playSessionId, mediaSourceId = source?.id,
                    audioStreamIndex = _ui.value.audioIndex, subtitleStreamIndex = _ui.value.subtitleIndex,
                    repeatMode = RepeatMode.REPEAT_NONE, playbackOrder = PlaybackOrder.DEFAULT,
                ))
            }
        }
    }

    private suspend fun reportStopped() {
        if (stopped) return
        stopped = true
        runCatching {
            api.playStateApi.reportPlaybackStopped(PlaybackStopInfo(
                itemId = itemId, positionTicks = ticks(), playSessionId = playSessionId, mediaSourceId = source?.id, failed = false,
            ))
        }
    }

    override fun onCleared() {
        loopJob?.cancel()
        val ticks = ticks()
        // viewModelScope is already cancelled here, so report on the application scope.
        if (!stopped) container.appScope.launch {
            runCatching {
                api.playStateApi.reportPlaybackStopped(PlaybackStopInfo(itemId = itemId, positionTicks = ticks, playSessionId = playSessionId, mediaSourceId = source?.id, failed = false))
            }
        }
        player.release()
    }
}
