package dev.jellyflix.player

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Rect
import android.util.LruCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.jellyfin.sdk.model.api.TrickplayInfo
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

/**
 * Seek-bar preview thumbnails from the server's trickplay sheets.
 *
 * Sheets are downloaded once, nearest-to-the-playhead first, and kept as compressed bytes; a single
 * thumbnail is cropped with [BitmapRegionDecoder] on demand, so a full decoded sheet (~20 MB) is never held.
 */
class Trickplay(
    private val baseUrl: String,
    private val token: String,
    private val itemId: String,
    private val mediaSourceId: String,
    private val info: TrickplayInfo,
) {
    private val perSheet = (info.tileWidth * info.tileHeight).coerceAtLeast(1)
    private val sheets = ConcurrentHashMap<Int, ByteArray>()
    private val crops = object : LruCache<Int, Bitmap>(24) {}
    private val gate = Semaphore(2)
    private var job: Job? = null

    val thumbWidth get() = info.width
    val thumbHeight get() = info.height
    private val sheetCount get() = (info.thumbnailCount + perSheet - 1) / perSheet

    /** Starts downloading every sheet, closest to [startMs] first. */
    fun prefetch(scope: CoroutineScope, startMs: Long) {
        if (job != null) return
        val first = indexFor(startMs) / perSheet
        val order = (0 until sheetCount).sortedBy { abs(it - first) }
        job = scope.launch(Dispatchers.IO) { order.forEach { launch { sheet(it) } } }
    }

    fun cancel() { job?.cancel(); job = null }

    private fun indexFor(ms: Long) = (ms / info.interval.coerceAtLeast(1)).toInt().coerceIn(0, (info.thumbnailCount - 1).coerceAtLeast(0))

    private suspend fun sheet(n: Int): ByteArray? {
        sheets[n]?.let { return it }
        return withContext(Dispatchers.IO) {
            gate.withPermit {
                sheets[n] ?: runCatching {
                    val url = "$baseUrl/Videos/$itemId/Trickplay/${info.width}/$n.jpg?MediaSourceId=$mediaSourceId"
                    val c = URL(url).openConnection() as HttpURLConnection
                    c.setRequestProperty("X-Emby-Token", token)
                    c.connectTimeout = 8_000; c.readTimeout = 15_000
                    try { if (c.responseCode == 200) c.inputStream.use { it.readBytes() } else null } finally { c.disconnect() }
                }.getOrNull()?.also { sheets[n] = it }
            }
        }
    }

    /** The thumbnail shown at [ms], or null while its sheet is still downloading. Safe to call from a coroutine. */
    suspend fun thumbnail(ms: Long): Bitmap? {
        val idx = indexFor(ms)
        crops.get(idx)?.let { return it }
        val bytes = sheets[idx / perSheet] ?: return null
        return withContext(Dispatchers.Default) {
            val slot = idx % perSheet
            val x = (slot % info.tileWidth) * info.width
            val y = (slot / info.tileWidth) * info.height
            runCatching {
                @Suppress("DEPRECATION")
                val decoder = BitmapRegionDecoder.newInstance(bytes, 0, bytes.size, false)
                try {
                    val r = Rect(x, y, minOf(x + info.width, decoder.width), minOf(y + info.height, decoder.height))
                    decoder.decodeRegion(r, BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.RGB_565 })
                } finally { decoder.recycle() }
            }.getOrNull()?.also { crops.put(idx, it) }
        }
    }

    companion object {
        /** Picks the trickplay resolution closest to 320 px wide for [mediaSourceId] (falls back to any source). */
        fun pick(all: Map<String, Map<String, TrickplayInfo>>?, mediaSourceId: String?): TrickplayInfo? {
            val forSource = all?.get(mediaSourceId) ?: all?.values?.firstOrNull() ?: return null
            return forSource.values.minByOrNull { abs(it.width - 320) }
        }
    }
}
