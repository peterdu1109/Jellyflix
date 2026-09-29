package dev.jellyflix.music

import android.content.Intent
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import dev.jellyflix.JellyflixApp

/**
 * Hosts the music player so playback continues with the screen off or the app closed, with the system
 * media notification, lock-screen controls and headset/Bluetooth buttons (all provided by Media3).
 */
@UnstableApi
class MusicService : MediaSessionService() {
    private var session: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        val container = (application as JellyflixApp).container
        // The token is added per request, so an account switch never leaves a stale header behind.
        val dataSource = ResolvingDataSource.Factory(DefaultHttpDataSource.Factory().setAllowCrossProtocolRedirects(true)) { spec ->
            container.session.current?.let { spec.withAdditionalHeaders(mapOf("X-Emby-Token" to it.account.token)) } ?: spec
        }
        val player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSource))
            .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), true)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
        session = MediaSession.Builder(this, player).build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    /** Swiping the app away stops the service unless music is actually playing. */
    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = session?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        session?.run { player.release(); release() }
        session = null
        super.onDestroy()
    }
}
