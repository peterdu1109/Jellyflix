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
                if (_ui.value.playMethod == PlayMethod.DIRECT_PLAY && !forcedTranscode && !isLocal) {
                    forcedTranscode = true
                    reload(positionMs(), _ui.value.audioIndex, _ui.value.subtitleIndex)
                } else _ui.update { it.copy(loading = false, error = error.message ?: error.errorCodeName) }
            }
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY) { resolveStartOffset(); _ui.update { it.copy(loading = false) } }
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
    /** Media position (ms) at which the current server stream's own timeline starts; 0 for absolute timelines. */
    private var streamOffsetMs = 0L
    private var pendingStartMs: Long? = null
    private var runtimeMs = 0L
    /** Playing a downloaded file: no server calls are needed (or possible) to start it. */
    private var isLocal = false
    /** Set for Live TV: the tuner stream must be closed on the server when we leave. */
    private var liveStreamId: String? = null

    private suspend fun start() {
        runCatching {
            container.downloads.playable(itemId)?.let { startLocal(it); return@runCatching }
            val item = container.repository.item(itemId)
            _ui.update { it.copy(item = item) }
            runtimeMs = (item.runTimeTicks ?: 0L) / 10_000
            val resumeMs = (item.userData?.playbackPositionTicks ?: 0L) / 10_000
            // Restart from the beginning when almost finished.
            val startMs = if (resumeMs > 0 && (item.runTimeTicks ?: Long.MAX_VALUE) / 10_000 - resumeMs < 30_000) 0 else resumeMs
            load(startMs, audioIndex = null, subtitleIndex = null, first = true)
            if (container.plugins.segmentSkip(settings)) loadSegments()
            startLoop()
        }.onFailure { e -> _ui.update { it.copy(loading = false, error = e.message) } }
    }

    /** Plays the downloaded file. Works fully offline: metadata comes from the entry saved with the file. */
    private fun startLocal(entry: dev.jellyflix.download.DownloadEntry) {
        isLocal = true
        val item = entry.item
        runtimeMs = (item.runTimeTicks ?: 0L) / 10_000
        val src = item.mediaSources?.firstOrNull()
        source = src
        val streams = src?.mediaStreams.orEmpty()
        val audio = streams.filter { it.type == MediaStreamType.AUDIO }
        // External subtitle files aren't downloaded; embedded ones live inside the video file.
        val subs = streams.filter { it.type == MediaStreamType.SUBTITLE && !it.isExternal }
        val audioIdx = src?.defaultAudioStreamIndex ?: audio.firstOrNull { it.isDefault }?.index ?: audio.firstOrNull()?.index
        val subIdx = src?.defaultSubtitleStreamIndex?.takeIf { d -> d >= 0 && subs.any { it.index == d } }
        val resumeMs = maxOf(entry.positionTicks, item.userData?.playbackPositionTicks ?: 0L) / 10_000
        val startMs = if (resumeMs > 0 && runtimeMs - resumeMs < 30_000) 0 else resumeMs
        _ui.update {
            it.copy(
                item = item, playMethod = PlayMethod.DIRECT_PLAY, audioIndex = audioIdx, subtitleIndex = subIdx,
                audio = audio.map { s -> TrackOption(s.index, s.label()) }, subtitles = subs.map { s -> TrackOption(s.index, s.label()) },
            )
        }
        player.setMediaItem(MediaItem.fromUri(Uri.fromFile(container.downloads.videoFile(entry))), startMs)
        player.prepare()
        player.playWhenReady = true
        applyClientTrackSelection(audioIdx, subIdx)
        startLoop()
        viewModelScope.launch { reportStart() } // fails silently when offline
    }

    private fun reload(positionMs: Long, audioIndex: Int?, subtitleIndex: Int?) {
        viewModelScope.launch {
            runCatching { load(positionMs, audioIndex, subtitleIndex, first = false) }
                .onFailure { e -> _ui.update { it.copy(loading = false, error = e.message) } }
        }
    }

    private suspend fun fetchInfo(startMs: Long, audioIndex: Int?, subtitleIndex: Int?, burnIn: Boolean) =
        api.mediaInfoApi.getPostedPlaybackInfo(
            itemId = itemId,
            data = PlaybackInfoDto(
                userId = session.userId,
                startTimeTicks = startMs * 10_000,
                audioStreamIndex = audioIndex, subtitleStreamIndex = subtitleIndex,
                maxStreamingBitrate = settings.quality.bitrate,
                deviceProfile = DeviceProfiles.build(settings.quality.bitrate, burnInSubtitles = burnIn),
                enableDirectPlay = !forcedTranscode && settings.quality.bitrate == null,
                enableDirectStream = !forcedTranscode && settings.quality.bitrate == null,
                enableTranscoding = true, autoOpenLiveStream = true,
            ),
        ).content

    private fun methodOf(src: MediaSourceInfo): PlayMethod = when {
        src.supportsDirectPlay && !forcedTranscode -> PlayMethod.DIRECT_PLAY
        src.transcodingUrl != null -> if (src.supportsDirectStream && !forcedTranscode) PlayMethod.DIRECT_STREAM else PlayMethod.TRANSCODE
        else -> error("No compatible stream (transcoding unavailable)")
    }

    /**
     * (Re)loads the stream at [startMs] (absolute position in the media). [subtitleIndex] null means "off",
     * except on the very first load where the server's default is used.
     * Only a real direct play exposes every track to ExoPlayer; any server-produced stream (direct stream or
     * transcode) carries a single audio track and needs the server to switch tracks, so those go through here.
     */
    private suspend fun load(startMs: Long, audioIndex: Int?, subtitleIndex: Int?, first: Boolean) {
        val wasPlaying = first || player.playWhenReady
        _ui.update { it.copy(loading = true, error = null) }
        val requestedSub = if (first) null else (subtitleIndex ?: -1) // -1 = explicitly off

        var info = fetchInfo(startMs, audioIndex, requestedSub, burnIn = false)
        var src = info.mediaSources.firstOrNull() ?: error(info.errorCode?.name ?: "No playable source")
        var method = methodOf(src)
        val wantedSub = if (first) src.defaultSubtitleStreamIndex?.takeIf { it >= 0 } else subtitleIndex
        if (method != PlayMethod.DIRECT_PLAY && wantedSub != null) {
            // Sidecar subtitles can't follow a server stream's timeline reliably: have the server burn them in.
            info = fetchInfo(startMs, audioIndex, wantedSub, burnIn = true)
            src = info.mediaSources.firstOrNull() ?: error(info.errorCode?.name ?: "No playable source")
            method = methodOf(src)
        }
        source = src
        playSessionId = info.playSessionId
        val base = session.account.serverUrl.trimEnd('/')
        val token = Uri.encode(session.account.token)

        liveStreamId = src.liveStreamId
        val url = if (method == PlayMethod.DIRECT_PLAY)
            "$base/Videos/$itemId/stream?static=true&mediaSourceId=${src.id}&api_key=$token" + (playSessionId?.let { "&playSessionId=$it" } ?: "") +
                (src.liveStreamId?.let { "&liveStreamId=$it" } ?: "")
        else base + src.transcodingUrl

        val streams = src.mediaStreams.orEmpty()
        val audio = streams.filter { it.type == MediaStreamType.AUDIO }
        val subs = streams.filter { it.type == MediaStreamType.SUBTITLE }
        val chosenAudio = audioIndex ?: src.defaultAudioStreamIndex ?: audio.firstOrNull { it.isDefault }?.index ?: audio.firstOrNull()?.index
        val chosenSub = wantedSub

        val externalSubs = (if (method != PlayMethod.DIRECT_PLAY) emptyList() else subs.filter { it.deliveryMethod == SubtitleDeliveryMethod.EXTERNAL && it.deliveryUrl != null })
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

        streamOffsetMs = 0
        pendingStartMs = null
        val mediaItem = MediaItem.Builder().setUri(url).setMediaId(itemId.toString()).setSubtitleConfigurations(externalSubs).build()
        if (method == PlayMethod.DIRECT_PLAY) {
            player.setMediaItem(mediaItem, startMs.coerceAtLeast(0))
        } else {
            // Whether the HLS timeline is absolute or starts at the requested offset is settled once the duration is known.
            player.setMediaItem(mediaItem)
            if (startMs > 0) pendingStartMs = startMs
        }
        player.prepare()
        player.playWhenReady = wasPlaying
        if (method == PlayMethod.DIRECT_PLAY) applyClientTrackSelection(chosenAudio, chosenSub)
        reportStart()
    }

    /**
     * Server streams may either expose the full timeline (seek to the start position) or begin at the requested
     * offset (position 0 == startMs). Compare the reported duration with the known runtime to tell which.
     */
    private fun resolveStartOffset() {
        val start = pendingStartMs ?: return
        pendingStartMs = null
        val dur = player.duration
        val full = runtimeMs
        if (dur == C.TIME_UNSET || full <= 0) { player.seekTo(start); return }
        if (kotlin.math.abs(dur - full) <= maxOf(15_000L, full / 50)) player.seekTo(start) else streamOffsetMs = start
    }

    /** Absolute position in the media, regardless of how the stream's own timeline starts. */
    fun positionMs(): Long = streamOffsetMs + player.currentPosition
    fun durationMs(): Long = if (runtimeMs > 0) runtimeMs else player.duration.coerceAtLeast(0)

    fun seekToMs(ms: Long) {
        val target = ms.coerceIn(0, durationMs().takeIf { it > 0 } ?: Long.MAX_VALUE)
        val rel = target - streamOffsetMs
        if (rel >= 0) player.seekTo(rel) else reload(target, _ui.value.audioIndex, _ui.value.subtitleIndex)
    }

    fun seekByMs(delta: Long) = seekToMs(positionMs() + delta)

    private fun MediaStream.label(): String =
        displayTitle ?: listOfNotNull(language?.uppercase(), codec?.uppercase(), title).joinToString(" · ").ifBlank { "#$index" }

    /** Direct play exposes every track to ExoPlayer, so switching is instant and needs no server round trip. */
    private fun applyClientTrackSelection(audioIndex: Int?, subIndex: Int?) {
        if (_ui.value.playMethod != PlayMethod.DIRECT_PLAY) return
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

    /** Direct play: instant client-side switch. Any server stream: reload at the current position with the new track. */
    fun selectAudio(index: Int) {
        val sub = _ui.value.subtitleIndex
        _ui.update { it.copy(audioIndex = index) }
        if (_ui.value.playMethod == PlayMethod.DIRECT_PLAY) applyClientTrackSelection(index, sub)
        else reload(positionMs(), index, sub)
    }

    fun selectSubtitle(index: Int?) {
        val audio = _ui.value.audioIndex
        val stream = source?.mediaStreams.orEmpty().firstOrNull { it.index == index }
        _ui.update { it.copy(subtitleIndex = index) }
        // Image subtitles (PGS, VobSub) can't be shown by the player: the server must burn them in.
        val needsBurnIn = !isLocal && index != null && stream != null && stream.deliveryMethod != SubtitleDeliveryMethod.EXTERNAL && stream.deliveryMethod != SubtitleDeliveryMethod.EMBED
        if (_ui.value.playMethod == PlayMethod.DIRECT_PLAY && !needsBurnIn) applyClientTrackSelection(audio, index)
        else reload(positionMs(), audio, index)
    }

    fun changeQuality(cap: dev.jellyflix.data.QualityCap) {
        settings = settings.copy(quality = cap)
        forcedTranscode = cap.bitrate != null
        reload(positionMs(), _ui.value.audioIndex, _ui.value.subtitleIndex)
    }

    private suspend fun loadSegments() {
        segments = runCatching {
            api.mediaSegmentsApi.getItemSegments(itemId).content.items.map { Segment(it.type, it.startTicks / 10_000, it.endTicks / 10_000) }
        }.getOrDefault(emptyList())
    }

    fun skipActiveSegment() {
        _ui.value.activeSegment?.let { seekToMs(it.endMs) }
    }

    private fun startLoop() {
        loopJob?.cancel()
        loopJob = viewModelScope.launch {
            var sinceReport = 0
            while (isActive) {
                delay(500)
                val pos = positionMs()
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

    private fun ticks() = positionMs() * 10_000

    private suspend fun reportStart() = runCatching {
        api.playStateApi.reportPlaybackStart(PlaybackStartInfo(
            itemId = itemId, canSeek = true, isPaused = false, isMuted = false, positionTicks = ticks(),
            playMethod = _ui.value.playMethod, playSessionId = playSessionId, mediaSourceId = source?.id,
            audioStreamIndex = _ui.value.audioIndex, subtitleStreamIndex = _ui.value.subtitleIndex,
            repeatMode = RepeatMode.REPEAT_NONE, playbackOrder = PlaybackOrder.DEFAULT,
        ))
    }

    fun reportProgress() {
        if (isLocal) container.downloads.savePosition(itemId, ticks())
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
        if (isLocal) container.downloads.savePosition(itemId, ticks)
        // viewModelScope is already cancelled here, so report on the application scope.
        liveStreamId?.let { id -> container.appScope.launch { runCatching { api.mediaInfoApi.closeLiveStream(id) } } }
        if (!stopped) container.appScope.launch {
            runCatching {
                api.playStateApi.reportPlaybackStopped(PlaybackStopInfo(itemId = itemId, positionTicks = ticks, playSessionId = playSessionId, mediaSourceId = source?.id, failed = false))
            }
        }
        player.release()
    }
}
