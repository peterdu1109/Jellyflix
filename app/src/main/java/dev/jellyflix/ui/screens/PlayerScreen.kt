package dev.jellyflix.ui.screens

import android.app.Activity
import android.content.pm.ActivityInfo
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.HighQuality
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.focusable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.nativeKeyCode
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import dev.jellyflix.R
import dev.jellyflix.data.QualityCap
import dev.jellyflix.player.PlayerViewModel
import dev.jellyflix.ui.appViewModel
import dev.jellyflix.ui.components.focusRing
import dev.jellyflix.ui.theme.LocalIsTv
import kotlinx.coroutines.delay
import org.jellyfin.sdk.model.api.MediaSegmentType
import java.util.UUID

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun PlayerScreen(itemId: UUID, onBack: () -> Unit, onNext: (UUID) -> Unit) {
    val ctx = LocalContext.current
    val app = ctx.applicationContext as android.app.Application
    val vm = appViewModel(key = "player-$itemId") { PlayerViewModel(app, it, itemId) }
    val ui by vm.ui.collectAsState()
    val player = vm.player
    val isTv = LocalIsTv.current
    val activity = ctx as? Activity

    var controls by remember { mutableStateOf(true) }
    var isPlaying by remember { mutableStateOf(true) }
    var position by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }
    var dragging by remember { mutableStateOf(false) }
    var lastInteraction by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val focus = remember { FocusRequester() }
    val poke = { controls = true; lastInteraction = System.currentTimeMillis() }

    // Immersive fullscreen + landscape + screen on for the whole player lifetime.
    DisposableEffect(Unit) {
        val window = activity?.window
        val prevOrientation = activity?.requestedOrientation
        if (window != null) {
            WindowCompat.setDecorFitsSystemWindows(window, false)
            WindowInsetsControllerCompat(window, window.decorView).apply {
                hide(WindowInsetsCompat.Type.systemBars()); systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        if (!isTv) activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        onDispose {
            if (window != null) {
                WindowInsetsControllerCompat(window, window.decorView).show(WindowInsetsCompat.Type.systemBars())
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
            prevOrientation?.let { activity.requestedOrientation = it }
        }
    }
    // Pause when the app goes to background.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_STOP) { if (ui.castDevice == null) vm.active.pause(); vm.reportProgress() } }
        lifecycle.addObserver(obs); onDispose { lifecycle.removeObserver(obs) }
    }
    LaunchedEffect(Unit) { vm.nextEpisode.collect { onNext(it) } }
    LaunchedEffect(Unit) { focus.requestFocus() }
    LaunchedEffect(Unit) {
        while (true) {
            isPlaying = vm.active.isPlaying
            if (!dragging) position = vm.positionMs()
            duration = vm.durationMs()
            if (controls && isPlaying && System.currentTimeMillis() - lastInteraction > 4000) controls = false
            delay(400)
        }
    }
    BackHandler { if (controls && isPlaying) controls = false else onBack() }

    Box(
        Modifier.fillMaxSize().background(Color.Black).focusRequester(focus).focusable()
            .onPreviewKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                val code = e.key.nativeKeyCode
                when (code) {
                    KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MEDIA_REWIND -> if (!controls) { vm.seekByMs(-10_000); poke(); true } else false
                    KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> if (!controls) { vm.seekByMs(10_000); poke(); true } else false
                    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> if (!controls) { poke(); true } else false
                    KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> { poke(); false }
                    KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_SPACE -> { if (vm.active.isPlaying) vm.active.pause() else vm.active.play(); poke(); true }
                    KeyEvent.KEYCODE_MEDIA_PLAY -> { vm.active.play(); true }
                    KeyEvent.KEYCODE_MEDIA_PAUSE -> { vm.active.pause(); poke(); true }
                    else -> false
                }
            }
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { if (controls) controls = false else poke() },
    ) {
        AndroidView(
            factory = { c -> PlayerView(c).apply { useController = false; resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT; this.player = player } },
            modifier = Modifier.fillMaxSize(),
        )
        if (ui.castDevice != null) Text(stringResource(R.string.casting_to, ui.castDevice!!), color = Color.White, style = MaterialTheme.typography.titleMedium, modifier = Modifier.align(Alignment.Center))
        if (ui.loading) CircularProgressIndicator(Modifier.align(Alignment.Center))
        ui.error?.let {
            Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(it, color = Color.White); Button(onBack, Modifier.padding(top = 12.dp)) { Text(stringResource(R.string.back)) }
            }
        }

        // Skip intro / credits button.
        ui.activeSegment?.let { seg ->
            val label = when (seg.type) {
                MediaSegmentType.INTRO -> R.string.skip_intro
                MediaSegmentType.OUTRO -> R.string.skip_credits
                MediaSegmentType.RECAP -> R.string.skip_recap
                MediaSegmentType.PREVIEW -> R.string.skip_preview
                else -> R.string.skip_intro
            }
            Button(vm::skipActiveSegment, Modifier.align(Alignment.BottomEnd).padding(end = 32.dp, bottom = if (controls) 120.dp else 40.dp).focusRing()) { Text(stringResource(label)) }
        }

        if (controls && ui.error == null) {
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Black.copy(0.7f), Color.Transparent, Color.Transparent, Color.Black.copy(0.8f))))) {
                Row(Modifier.align(Alignment.TopStart).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onBack, Modifier.focusRing()) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back), tint = Color.White) }
                    Column(Modifier.padding(start = 8.dp)) {
                        Text(ui.item?.let { dev.jellyflix.ui.components.cardTitle(it) }.orEmpty(), color = Color.White, style = MaterialTheme.typography.titleMedium)
                        ui.item?.let { dev.jellyflix.ui.components.cardSubtitle(it) }?.let { Text(it, color = Color.White.copy(0.7f), style = MaterialTheme.typography.bodySmall) }
                    }
                }
                Row(Modifier.align(Alignment.Center), horizontalArrangement = Arrangement.spacedBy(32.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton({ vm.seekByMs(-10_000); poke() }, Modifier.focusRing(androidx.compose.foundation.shape.CircleShape)) { Icon(Icons.Default.Replay10, null, tint = Color.White, modifier = Modifier.size(40.dp)) }
                    IconButton({ if (isPlaying) vm.active.pause() else vm.active.play(); poke() }, Modifier.size(72.dp).focusRing(androidx.compose.foundation.shape.CircleShape)) {
                        Icon(if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, null, tint = Color.White, modifier = Modifier.size(56.dp))
                    }
                    IconButton({ vm.seekByMs(10_000); poke() }, Modifier.focusRing(androidx.compose.foundation.shape.CircleShape)) { Icon(Icons.Default.Forward10, null, tint = Color.White, modifier = Modifier.size(40.dp)) }
                }
                Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp)) {
                    Slider(
                        value = if (duration > 0) position.toFloat() / duration else 0f,
                        onValueChange = { dragging = true; position = (it * duration).toLong(); poke() },
                        onValueChangeFinished = { vm.seekToMs(position); dragging = false },
                        modifier = Modifier.focusRing(),
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("${fmt(position)} / ${fmt(duration)}", color = Color.White, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                        if (vm.castAvailable) CastButton()
                        TrackMenu(Icons.Default.AudioFile, stringResource(R.string.audio), ui.audio.map { it.streamIndex to it.label }, ui.audioIndex, null) { vm.selectAudio(it!!); poke() }
                        TrackMenu(Icons.Default.Subtitles, stringResource(R.string.subtitles), ui.subtitles.map { it.streamIndex to it.label }, ui.subtitleIndex, stringResource(R.string.off)) { vm.selectSubtitle(it); poke() }
                        QualityMenu(vm) { poke() }
                    }
                }
            }
        }
    }
}

@Composable
private fun TrackMenu(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, options: List<Pair<Int, String>>, selected: Int?, offLabel: String?, onSelect: (Int?) -> Unit) {
    if (options.isEmpty() && offLabel == null) return
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton({ open = true }, Modifier.focusRing(androidx.compose.foundation.shape.CircleShape)) { Icon(icon, title, tint = Color.White) }
        DropdownMenu(open, { open = false }) {
            if (offLabel != null) DropdownMenuItem(text = { Text(offLabel, color = if (selected == null) MaterialTheme.colorScheme.primary else Color.Unspecified) }, onClick = { onSelect(null); open = false })
            options.forEach { (idx, label) ->
                DropdownMenuItem(text = { Text(label, color = if (selected == idx) MaterialTheme.colorScheme.primary else Color.Unspecified) }, onClick = { onSelect(idx); open = false })
            }
        }
    }
}

@Composable
private fun QualityMenu(vm: PlayerViewModel, onChange: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton({ open = true }, Modifier.focusRing(androidx.compose.foundation.shape.CircleShape)) { Icon(Icons.Default.HighQuality, stringResource(R.string.quality), tint = Color.White) }
        DropdownMenu(open, { open = false }) {
            QualityCap.entries.forEach { q ->
                DropdownMenuItem(
                    text = { Text(when (q) { QualityCap.Auto -> stringResource(R.string.auto); QualityCap.P1080 -> "1080p"; QualityCap.P720 -> "720p"; QualityCap.P480 -> "480p" }) },
                    onClick = { vm.changeQuality(q); open = false; onChange() },
                )
            }
        }
    }
}

private fun fmt(ms: Long): String {
    val s = ms / 1000
    val h = s / 3600; val m = (s % 3600) / 60; val sec = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
}

/** System Cast button; built defensively because it needs Play services and can fail on unusual devices. */
@Composable
private fun CastButton() {
    AndroidView(
        factory = { c ->
            runCatching {
                androidx.mediarouter.app.MediaRouteButton(c).also { com.google.android.gms.cast.framework.CastButtonFactory.setUpMediaRouteButton(c, it) }
            }.getOrElse { android.view.View(c) }
        },
        modifier = Modifier.size(48.dp),
    )
}
