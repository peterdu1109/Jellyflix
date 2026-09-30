package dev.jellyflix.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.ui.composed
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import dev.jellyflix.R
import dev.jellyflix.data.MediaRepository
import dev.jellyflix.ui.theme.LocalIsTv
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.ImageType

/** Async result shared by every screen so loading/error handling is written once. */
sealed interface Load<out T> {
    data object Loading : Load<Nothing>
    data class Failed(val message: String?) : Load<Nothing>
    data class Ready<T>(val data: T) : Load<T>
}

/** Focus ring + slight zoom for D-pad navigation (only visible when focus comes from a remote/keyboard). */
fun Modifier.focusRing(shape: androidx.compose.ui.graphics.Shape = RoundedCornerShape(12.dp)): Modifier = composed {
    var focused by remember { mutableStateOf(false) }
    val color = MaterialTheme.colorScheme.primary
    this
        .onFocusChanged { focused = it.isFocused }
        .scale(if (focused) 1.06f else 1f)
        .border(if (focused) BorderStroke(3.dp, color) else BorderStroke(0.dp, Color.Transparent), shape)
}

@Composable
fun LoadingView(modifier: Modifier = Modifier.fillMaxSize()) {
    Box(modifier, contentAlignment = Alignment.Center) { CircularProgressIndicator() }
}

@Composable
fun ErrorView(message: String?, onRetry: () -> Unit, modifier: Modifier = Modifier.fillMaxSize()) {
    Column(modifier.padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(stringResource(R.string.error_generic), style = MaterialTheme.typography.titleMedium)
        if (!message.isNullOrBlank()) Text(message, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 4.dp))
        Button(onClick = onRetry, modifier = Modifier.padding(top = 16.dp)) { Text(stringResource(R.string.retry)) }
    }
}

@Composable
fun <T> LoadView(state: Load<T>, onRetry: () -> Unit, content: @Composable (T) -> Unit) = when (state) {
    Load.Loading -> LoadingView()
    is Load.Failed -> ErrorView(state.message, onRetry)
    is Load.Ready -> content(state.data)
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) =
    Text(text, style = MaterialTheme.typography.titleLarge, modifier = modifier.padding(horizontal = 16.dp, vertical = 8.dp), maxLines = 1)

/** Poster-style card. [wide] switches to a 16:9 thumbnail (continue watching, episodes). */
@Composable
fun MediaCard(
    item: BaseItemDto,
    repo: MediaRepository,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    wide: Boolean = false,
    width: Dp? = null,
    fillWidth: Boolean = false,
) {
    val isTv = LocalIsTv.current
    val w = width ?: if (wide) (if (isTv) 280.dp else 220.dp) else (if (isTv) 170.dp else 130.dp)
    val ratio = if (wide) 16f / 9f else 2f / 3f
    val shape = RoundedCornerShape(12.dp)
    val url = remember(item.id, wide) {
        repo.imageUrl(item, if (wide) ImageType.THUMB else ImageType.PRIMARY, maxWidth = if (wide) 600 else 400)
            ?: repo.imageUrl(item, ImageType.BACKDROP, 600).takeIf { wide }
    }
    Column(if (fillWidth) modifier else modifier.width(w)) {
        Box(
            Modifier.fillMaxWidth().aspectRatioBox(ratio).focusRing(shape).clip(shape)
                .background(MaterialTheme.colorScheme.surfaceVariant).clickable(onClick = onClick),
        ) {
            if (url != null) AsyncImage(model = url, contentDescription = item.name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            else Text(item.name.orEmpty(), Modifier.align(Alignment.Center).padding(8.dp), textAlign = TextAlign.Center, style = MaterialTheme.typography.labelLarge)
            val data = item.userData
            val unplayed = data?.unplayedItemCount ?: 0
            if (unplayed > 0 && item.type in setOf(BaseItemKind.SERIES, BaseItemKind.SEASON, BaseItemKind.BOX_SET)) {
                Text(
                    unplayed.toString(), color = MaterialTheme.colorScheme.onPrimary, style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.align(Alignment.TopEnd).padding(6.dp).background(MaterialTheme.colorScheme.primary, RoundedCornerShape(10.dp)).padding(horizontal = 6.dp, vertical = 1.dp),
                )
            } else if (data?.played == true) {
                Icon(Icons.Default.CheckCircle, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.align(Alignment.TopEnd).padding(6.dp).size(20.dp))
            }
            val pct = data?.playedPercentage
            if (pct != null && pct > 0 && pct < 100) {
                LinearProgressIndicator(progress = { (pct / 100).toFloat() }, modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(4.dp))
            }
        }
        Text(cardTitle(item), Modifier.padding(top = 6.dp, start = 2.dp), style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
        cardSubtitle(item)?.let {
            Text(it, Modifier.padding(start = 2.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

private fun Modifier.aspectRatioBox(ratio: Float) = this.then(Modifier.aspectRatio(ratio))

fun cardTitle(item: BaseItemDto): String =
    if (item.type == BaseItemKind.EPISODE) item.seriesName ?: item.name.orEmpty() else item.name.orEmpty()

fun cardSubtitle(item: BaseItemDto): String? = when (item.type) {
    BaseItemKind.EPISODE -> listOfNotNull(
        item.parentIndexNumber?.let { "S$it" }, item.indexNumber?.let { "E$it" },
    ).joinToString("").takeIf { it.isNotEmpty() }?.let { "$it · ${item.name}" } ?: item.name
    BaseItemKind.TV_CHANNEL -> item.currentProgram?.name
    BaseItemKind.PROGRAM -> item.channelName
    else -> item.productionYear?.toString()
}

@Composable
fun <T : BaseItemDto> ItemRow(
    title: String,
    items: List<T>,
    repo: MediaRepository,
    onClick: (BaseItemDto) -> Unit,
    wide: Boolean = false,
) {
    if (items.isEmpty()) return
    Column(Modifier.padding(vertical = 8.dp)) {
        SectionTitle(title)
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            items(items, key = { it.id }) { MediaCard(it, repo, { onClick(it) }, wide = wide) }
        }
    }
}

fun formatRuntime(ticks: Long?): String? {
    val min = ticks?.div(600_000_000L)?.toInt() ?: return null
    if (min <= 0) return null
    return if (min >= 60) "${min / 60}h ${min % 60}m" else "${min}m"
}
