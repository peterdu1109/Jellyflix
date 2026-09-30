package dev.jellyflix.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import coil3.compose.AsyncImage
import dev.jellyflix.R
import dev.jellyflix.data.MediaRepository
import dev.jellyflix.ui.appViewModel
import dev.jellyflix.ui.components.Load
import dev.jellyflix.ui.components.LoadView
import dev.jellyflix.ui.components.focusRing
import dev.jellyflix.ui.rememberContainer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.ImageType
import org.jellyfin.sdk.model.api.TimerInfoDto
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

class LiveTvViewModel(private val repo: MediaRepository) : ViewModel() {
    private val _channels = MutableStateFlow<Load<List<BaseItemDto>>>(Load.Loading)
    val channels: StateFlow<Load<List<BaseItemDto>>> = _channels
    private val _recordings = MutableStateFlow<Load<List<BaseItemDto>>>(Load.Loading)
    val recordings: StateFlow<Load<List<BaseItemDto>>> = _recordings

    private val _guide = MutableStateFlow<Load<List<BaseItemDto>>>(Load.Loading)
    val guide: StateFlow<Load<List<BaseItemDto>>> = _guide
    private val _timers = MutableStateFlow<List<TimerInfoDto>>(emptyList())
    val timers: StateFlow<List<TimerInfoDto>> = _timers

    init { load() }

    fun loadGuide(channels: List<BaseItemDto>) {
        viewModelScope.launch {
            _guide.value = Load.Loading
            _guide.value = runCatching { repo.guide(channels.map { it.id }) }.fold({ Load.Ready(it) }, { Load.Failed(it.message) })
        }
    }

    private fun loadTimers() { viewModelScope.launch { _timers.value = runCatching { repo.timers() }.getOrDefault(_timers.value) } }

    fun record(programId: String) { viewModelScope.launch { runCatching { repo.scheduleRecording(programId) }; loadTimers() } }
    fun cancel(timerId: String) { viewModelScope.launch { runCatching { repo.cancelTimer(timerId) }; loadTimers() } }

    fun load() {
        viewModelScope.launch {
            _channels.value = Load.Loading
            _channels.value = runCatching { repo.liveChannels() }.fold({ Load.Ready(it) }, { Load.Failed(it.message) })
        }
        viewModelScope.launch {
            _recordings.value = Load.Loading
            _recordings.value = runCatching { repo.recordings() }.fold({ Load.Ready(it) }, { Load.Failed(it.message) })
        }
        loadTimers()
    }
}

@Composable
fun LiveTvScreen(onPlay: (BaseItemDto) -> Unit, onBack: () -> Unit) {
    val vm = appViewModel { LiveTvViewModel(it.repository) }
    val repo = rememberContainer().repository
    var tab by remember { mutableStateOf(0) }
    val channels by vm.channels.collectAsState()
    val recordings by vm.recordings.collectAsState()
    val guide by vm.guide.collectAsState()
    val timers by vm.timers.collectAsState()
    val scheduledPrograms = timers.mapNotNull { it.programId }.toSet()
    androidx.compose.runtime.LaunchedEffect(tab, channels) {
        val c = channels
        if (tab == 1 && c is Load.Ready && guide !is Load.Ready) vm.loadGuide(c.data)
    }

    Column(Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(8.dp)) {
            IconButton(onBack, Modifier.focusRing(androidx.compose.foundation.shape.CircleShape)) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) }
            Text(stringResource(R.string.live_tv_now), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            FilterChip(tab == 0, { tab = 0 }, label = { Text(stringResource(R.string.channels)) }, modifier = Modifier.padding(end = 8.dp).focusRing())
            FilterChip(tab == 1, { tab = 1 }, label = { Text(stringResource(R.string.live_tv_guide)) }, modifier = Modifier.padding(end = 8.dp).focusRing())
            FilterChip(tab == 2, { tab = 2 }, label = { Text(stringResource(R.string.recordings)) }, modifier = Modifier.focusRing())
        }
        if (tab == 0) LoadView(channels, vm::load) { list ->
            if (list.isEmpty()) Empty() else LazyColumn(contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(list, key = { it.id }) { ch -> ChannelRow(ch, repo, onPlay) }
            }
        } else if (tab == 1) {
            val names = (channels as? Load.Ready)?.data.orEmpty().associateBy { it.id }
            LoadView(guide, { (channels as? Load.Ready)?.let { vm.loadGuide(it.data) } }) { list ->
                val byChannel = list.groupBy { it.channelId }
                if (list.isEmpty()) Empty() else LazyColumn(contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    byChannel.forEach { (chId, programs) ->
                        item(key = "h-$chId") {
                            Text(names[chId]?.let { listOfNotNull(it.number, it.name).joinToString("  ") } ?: programs.first().channelName.orEmpty(),
                                style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 10.dp))
                        }
                        items(programs, key = { it.id }) { p -> ProgramRow(p, p.id.toString() in scheduledPrograms) { vm.record(p.id.toString()) } }
                    }
                }
            }
        } else LoadView(recordings, vm::load) { list ->
            if (list.isEmpty() && timers.isEmpty()) Empty() else LazyColumn(contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (timers.isNotEmpty()) {
                    item { Text(stringResource(R.string.scheduled_recordings), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary) }
                    items(timers, key = { "t-${it.id}" }) { t -> TimerRow(t) { t.id?.let(vm::cancel) } }
                }
                items(list, key = { it.id }) { rec -> RecordingRow(rec, repo, onPlay) }
            }
        }
    }
}

@Composable
private fun Empty() = Box(Modifier.fillMaxSize(), Alignment.Center) { Text(stringResource(R.string.no_results)) }

private val timeFormat = DateTimeFormatter.ofPattern("HH:mm")

/** The server sends UTC; show local time. */
private fun LocalDateTime.local(): LocalDateTime = atOffset(ZoneOffset.UTC).atZoneSameInstant(ZoneId.systemDefault()).toLocalDateTime()

@Composable
private fun ChannelRow(ch: BaseItemDto, repo: MediaRepository, onPlay: (BaseItemDto) -> Unit) {
    val program = ch.currentProgram
    val logo = remember(ch.id) { repo.imageUrl(ch, ImageType.PRIMARY, 200) }
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceVariant).clickable { onPlay(ch) }.focusRing().padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(56.dp).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surface), Alignment.Center) {
            if (logo != null) AsyncImage(logo, ch.name, Modifier.fillMaxSize().padding(4.dp), contentScale = ContentScale.Fit)
            else Text(ch.number.orEmpty(), style = MaterialTheme.typography.labelLarge)
        }
        Column(Modifier.weight(1f)) {
            Text(listOfNotNull(ch.number, ch.name).joinToString("  "), style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (program != null) {
                Text(program.name.orEmpty(), style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val start = program.startDate?.local(); val end = program.endDate?.local()
                if (start != null && end != null) {
                    Text("${timeFormat.format(start)} – ${timeFormat.format(end)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    val total = java.time.Duration.between(start, end).seconds.coerceAtLeast(1)
                    val done = java.time.Duration.between(start, LocalDateTime.now()).seconds.coerceIn(0, total)
                    LinearProgressIndicator(progress = { done.toFloat() / total }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
                }
            }
        }
    }
}

@Composable
private fun RecordingRow(rec: BaseItemDto, repo: MediaRepository, onPlay: (BaseItemDto) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceVariant).clickable { onPlay(rec) }.focusRing().padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        val thumb = remember(rec.id) { repo.imageUrl(rec, ImageType.PRIMARY, 300) }
        Box(Modifier.size(width = 96.dp, height = 56.dp).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surface)) {
            if (thumb != null) AsyncImage(thumb, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        }
        Column(Modifier.weight(1f)) {
            Text(rec.name.orEmpty(), style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            listOfNotNull(rec.channelName, rec.startDate?.local()?.let { it.format(DateTimeFormatter.ofPattern("dd/MM HH:mm")) }).joinToString(" · ").takeIf { it.isNotEmpty() }
                ?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

@Composable
private fun ProgramRow(p: BaseItemDto, scheduled: Boolean, onRecord: () -> Unit) {
    val start = p.startDate?.local(); val end = p.endDate?.local()
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceVariant).padding(start = 12.dp, top = 4.dp, bottom = 4.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(if (start != null && end != null) "${timeFormat.format(start)}–${timeFormat.format(end)}" else "", style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(92.dp))
        Text(p.name.orEmpty(), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        IconButton(onRecord, enabled = !scheduled, modifier = Modifier.focusRing(androidx.compose.foundation.shape.CircleShape)) {
            Icon(if (scheduled) Icons.Default.Check else Icons.Default.FiberManualRecord, stringResource(R.string.record), tint = if (scheduled) MaterialTheme.colorScheme.primary else androidx.compose.ui.graphics.Color(0xFFE11D48))
        }
    }
}

@Composable
private fun TimerRow(t: TimerInfoDto, onCancel: () -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceVariant).padding(start = 12.dp, top = 4.dp, bottom = 4.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(t.name.orEmpty(), style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            listOfNotNull(t.channelName, t.startDate?.local()?.format(DateTimeFormatter.ofPattern("dd/MM HH:mm"))).joinToString(" · ").takeIf { it.isNotEmpty() }
                ?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        IconButton(onCancel, Modifier.focusRing(androidx.compose.foundation.shape.CircleShape)) { Icon(Icons.Default.Close, stringResource(R.string.cancel_recording)) }
    }
}
