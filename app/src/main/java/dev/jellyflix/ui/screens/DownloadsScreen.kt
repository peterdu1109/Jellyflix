package dev.jellyflix.ui.screens

import android.text.format.Formatter
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import dev.jellyflix.R
import dev.jellyflix.download.DownloadEntry
import dev.jellyflix.download.DownloadStatus
import dev.jellyflix.ui.components.cardSubtitle
import dev.jellyflix.ui.components.cardTitle
import dev.jellyflix.ui.components.focusRing
import dev.jellyflix.ui.rememberContainer
import org.jellyfin.sdk.model.api.BaseItemDto

@Composable
fun DownloadsScreen(onPlay: (BaseItemDto) -> Unit) {
    val c = rememberContainer()
    val ctx = LocalContext.current
    val all by c.downloads.entries.collectAsState()
    val account by c.session.state.collectAsState()
    val key = (account as? dev.jellyflix.data.AuthState.SignedIn)?.session?.account?.key
    val list = all.values.filter { it.accountKey == key }.sortedWith(compareBy({ it.item.seriesName ?: it.item.name }, { it.item.parentIndexNumber }, { it.item.indexNumber }))

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.nav_downloads), style = MaterialTheme.typography.titleLarge)
                Text(stringResource(R.string.downloads_storage, Formatter.formatFileSize(ctx, list.sumOf { if (it.status == DownloadStatus.COMPLETE) it.totalBytes else it.downloadedBytes })), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (list.isNotEmpty()) OutlinedButton({ list.forEach { c.downloads.remove(it.id) } }, Modifier.focusRing()) { Text(stringResource(R.string.downloads_delete_all)) }
        }
        if (list.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(32.dp), Alignment.Center) { Text(stringResource(R.string.downloads_empty), style = MaterialTheme.typography.bodyLarge) }
        } else LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 4.dp)) {
            items(list, key = { it.id }) { e -> DownloadRow(e, onPlay) }
        }
    }
}

@Composable
private fun DownloadRow(e: DownloadEntry, onPlay: (BaseItemDto) -> Unit) {
    val c = rememberContainer()
    val ctx = LocalContext.current
    val complete = e.status == DownloadStatus.COMPLETE
    val poster = c.downloads.posterFile(e)
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceVariant)
            .then(if (complete) Modifier.clickable { onPlay(e.item) } else Modifier).focusRing().padding(8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(64.dp).aspectRatio(2f / 3f).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surface)) {
            if (poster != null) AsyncImage(poster, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        }
        Column(Modifier.weight(1f)) {
            Text(cardTitle(e.item), style = MaterialTheme.typography.titleSmall, maxLines = 1)
            cardSubtitle(e.item)?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1) }
            val size = Formatter.formatFileSize(ctx, if (complete) e.totalBytes else e.downloadedBytes)
            Text(
                when (e.status) {
                    DownloadStatus.COMPLETE -> stringResource(R.string.downloaded) + " · " + size
                    DownloadStatus.DOWNLOADING -> "${(e.progress * 100).toInt()}% · $size"
                    DownloadStatus.PAUSED -> stringResource(R.string.download_paused) + " · " + size
                    DownloadStatus.QUEUED -> stringResource(R.string.download_queued)
                    DownloadStatus.FAILED -> stringResource(R.string.download_failed) + (e.error?.let { ": $it" } ?: "")
                },
                style = MaterialTheme.typography.labelSmall, color = if (e.status == DownloadStatus.FAILED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (e.status == DownloadStatus.DOWNLOADING || e.status == DownloadStatus.PAUSED) LinearProgressIndicator(progress = { e.progress }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
        }
        when (e.status) {
            DownloadStatus.COMPLETE -> IconButton({ onPlay(e.item) }, Modifier.focusRing(androidx.compose.foundation.shape.CircleShape)) { Icon(Icons.Default.PlayArrow, stringResource(R.string.play)) }
            DownloadStatus.DOWNLOADING, DownloadStatus.QUEUED -> IconButton({ c.downloads.pause(e.id) }, Modifier.focusRing(androidx.compose.foundation.shape.CircleShape)) { Icon(Icons.Default.Pause, stringResource(R.string.download_pause)) }
            DownloadStatus.PAUSED, DownloadStatus.FAILED -> IconButton({ c.downloads.resume(e.id) }, Modifier.focusRing(androidx.compose.foundation.shape.CircleShape)) { Icon(Icons.Default.Refresh, stringResource(R.string.download_resume)) }
        }
        IconButton({ c.downloads.remove(e.id) }, Modifier.focusRing(androidx.compose.foundation.shape.CircleShape)) { Icon(Icons.Default.Delete, null) }
    }
}
