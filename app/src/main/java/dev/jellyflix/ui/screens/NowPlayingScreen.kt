package dev.jellyflix.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import coil3.compose.AsyncImage
import dev.jellyflix.R
import dev.jellyflix.ui.components.focusRing
import dev.jellyflix.ui.rememberContainer

/** Compact bar shown above the navigation while something is queued. Tap to open the full player. */
@Composable
fun MiniPlayer(onOpen: () -> Unit) {
    val music = rememberContainer().music
    val s by music.state.collectAsState()
    if (!s.hasQueue) return
    Row(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant).clickable(onClick = onOpen).focusRing(RoundedCornerShape(0.dp)).padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Art(s.artworkUrl, Modifier.size(44.dp))
        Column(Modifier.weight(1f)) {
            Text(s.title, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(s.artist, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        IconButton(music::toggle, Modifier.focusRing(androidx.compose.foundation.shape.CircleShape)) { Icon(if (s.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, null) }
        IconButton(music::next, Modifier.focusRing(androidx.compose.foundation.shape.CircleShape)) { Icon(Icons.Default.SkipNext, null) }
    }
}

@Composable
private fun Art(url: String?, modifier: Modifier) {
    Box(modifier.clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surface)) {
        if (url != null) AsyncImage(url, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
    }
}

@Composable
fun NowPlayingScreen(onBack: () -> Unit) {
    val music = rememberContainer().music
    val s by music.state.collectAsState()
    var dragging by remember { mutableStateOf(false) }
    var drag by remember { mutableStateOf(0f) }

    Column(Modifier.fillMaxSize().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onBack, Modifier.focusRing(androidx.compose.foundation.shape.CircleShape)) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) }
            Text(stringResource(R.string.now_playing), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            IconButton({ music.stop(); onBack() }, Modifier.focusRing(androidx.compose.foundation.shape.CircleShape)) { Icon(Icons.Default.Close, null) }
        }
        Art(s.artworkUrl, Modifier.padding(vertical = 12.dp).fillMaxWidth(0.6f).aspectRatio(1f))
        Text(s.title, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(s.artist, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)

        val progress = if (s.durationMs > 0) s.positionMs.toFloat() / s.durationMs else 0f
        Slider(
            value = if (dragging) drag else progress,
            onValueChange = { dragging = true; drag = it },
            onValueChangeFinished = { music.seekTo((drag * s.durationMs).toLong()); dragging = false },
            modifier = Modifier.padding(top = 8.dp).focusRing(),
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(fmt(s.positionMs), style = MaterialTheme.typography.labelSmall); Text(fmt(s.durationMs), style = MaterialTheme.typography.labelSmall)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(music::toggleShuffle, Modifier.focusRing(androidx.compose.foundation.shape.CircleShape)) { Icon(Icons.Default.Shuffle, null, tint = if (s.shuffle) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface) }
            IconButton(music::previous, Modifier.focusRing(androidx.compose.foundation.shape.CircleShape)) { Icon(Icons.Default.SkipPrevious, null, Modifier.size(36.dp)) }
            IconButton(music::toggle, Modifier.size(64.dp).focusRing(androidx.compose.foundation.shape.CircleShape)) { Icon(if (s.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, null, Modifier.size(48.dp)) }
            IconButton(music::next, Modifier.focusRing(androidx.compose.foundation.shape.CircleShape)) { Icon(Icons.Default.SkipNext, null, Modifier.size(36.dp)) }
            IconButton(music::cycleRepeat, Modifier.focusRing(androidx.compose.foundation.shape.CircleShape)) {
                Icon(if (s.repeat == Player.REPEAT_MODE_ONE) Icons.Default.RepeatOne else Icons.Default.Repeat, null, tint = if (s.repeat != Player.REPEAT_MODE_OFF) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
            }
        }
        Text(stringResource(R.string.queue), Modifier.fillMaxWidth().padding(top = 8.dp), style = MaterialTheme.typography.titleSmall)
        LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
            itemsIndexed(s.queue, key = { i, q -> "$i-${q.id}" }) { i, q ->
                Row(Modifier.fillMaxWidth().clickable { music.skipTo(i) }.focusRing().padding(vertical = 8.dp, horizontal = 4.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text(q.title, style = MaterialTheme.typography.bodyMedium, color = if (i == s.index) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        q.artist?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1) }
                    }
                }
            }
        }
    }
}

private fun fmt(ms: Long): String { val s = ms / 1000; return "%d:%02d".format(s / 60, s % 60) }
