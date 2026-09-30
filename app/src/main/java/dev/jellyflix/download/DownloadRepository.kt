package dev.jellyflix.download

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import androidx.core.content.ContextCompat
import dev.jellyflix.data.SessionManager
import dev.jellyflix.data.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.jellyfin.sdk.api.client.extensions.playStateApi
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.MediaStreamType
import org.jellyfin.sdk.model.api.PlaybackStopInfo
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.ensureActive

enum class DownloadStatus { QUEUED, DOWNLOADING, PAUSED, COMPLETE, FAILED }

/** Everything needed to show and play a download without the server: the item metadata is stored with the file. */
@Serializable
data class DownloadEntry(
    val id: String,
    val accountKey: String,
    val item: BaseItemDto,
    val fileName: String,
    val status: DownloadStatus = DownloadStatus.QUEUED,
    val totalBytes: Long = 0,
    val downloadedBytes: Long = 0,
    /** Position watched offline, pushed to the server when connectivity is back. */
    val positionTicks: Long = 0,
    val positionDirty: Boolean = false,
    val error: String? = null,
) {
    val progress: Float get() = if (totalBytes > 0) (downloadedBytes.toFloat() / totalBytes).coerceIn(0f, 1f) else 0f
}

/**
 * Offline library. One folder per item under the app's external files dir (no storage permission needed):
 *   downloads/<itemId>/meta.json, video.<ext>, poster.jpg
 * Downloads run one at a time in [DownloadService], resume from the partial file after any interruption,
 * and can be limited to unmetered networks.
 */
class DownloadRepository(
    private val context: Context,
    private val sessions: SessionManager,
    private val settings: SettingsRepository,
    private val scope: CoroutineScope,
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val root: File = (context.getExternalFilesDir("downloads") ?: File(context.filesDir, "downloads")).also { it.mkdirs() }
    private val _entries = MutableStateFlow<Map<String, DownloadEntry>>(emptyMap())
    val entries: StateFlow<Map<String, DownloadEntry>> = _entries
    private val runLock = Mutex()
    @Volatile private var cancelId: String? = null

    init {
        scope.launch(Dispatchers.IO) {
            val loaded = root.listFiles().orEmpty().mapNotNull { dir ->
                runCatching { json.decodeFromString<DownloadEntry>(File(dir, "meta.json").readText()) }.getOrNull()
            }.associateBy { it.id }
            // Anything that was mid-download when the app died is resumable, not "downloading".
            _entries.value = loaded.mapValues { (_, e) ->
                if (e.status == DownloadStatus.DOWNLOADING || e.status == DownloadStatus.QUEUED) e.copy(status = DownloadStatus.PAUSED) else e
            }
        }
    }

    fun entry(id: UUID): DownloadEntry? = _entries.value[id.toString()]
    fun videoFile(e: DownloadEntry) = File(File(root, e.id), e.fileName)
    /** A downloaded external subtitle file for stream [index], if any. */
    fun subtitleFile(e: DownloadEntry, index: Int): File? =
        File(root, e.id).listFiles { f -> f.name.startsWith("sub_$index.") }?.firstOrNull()
    fun posterFile(e: DownloadEntry) = File(File(root, e.id), "poster.jpg").takeIf { it.exists() }

    /** A finished download of the signed-in account whose file is still on disk. */
    fun playable(id: UUID): DownloadEntry? = entry(id)?.takeIf {
        it.status == DownloadStatus.COMPLETE && it.accountKey == sessions.current?.account?.key && videoFile(it).exists()
    }

    fun totalSize(): Long = root.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    // ---- queue management ----

    fun enqueue(items: List<BaseItemDto>) {
        val account = sessions.current?.account?.key ?: return
        val added = items.filter { it.type in setOf(BaseItemKind.MOVIE, BaseItemKind.EPISODE, BaseItemKind.VIDEO) }
            .filter { _entries.value[it.id.toString()]?.status.let { s -> s == null || s == DownloadStatus.FAILED } }
        if (added.isEmpty()) return
        added.forEach { item ->
            val ext = item.mediaSources?.firstOrNull()?.container?.takeIf { it.isNotBlank() && it.all(Char::isLetterOrDigit) } ?: "mkv"
            val entry = DownloadEntry(item.id.toString(), account, item, "video.$ext",
                totalBytes = item.mediaSources?.firstOrNull()?.size ?: 0)
            _entries.update { it + (entry.id to entry) }
            persist(entry)
        }
        DownloadService.start(context)
    }

    fun pause(id: String) {
        cancelId = id
        update(id) { if (it.status == DownloadStatus.QUEUED || it.status == DownloadStatus.DOWNLOADING) it.copy(status = DownloadStatus.PAUSED) else it }
    }

    fun resume(id: String) {
        update(id) { it.copy(status = DownloadStatus.QUEUED, error = null) }
        DownloadService.start(context)
    }

    fun remove(id: String) {
        cancelId = id
        _entries.update { it - id }
        scope.launch(Dispatchers.IO) { File(root, id).deleteRecursively() }
    }

    fun removeAll() = _entries.value.keys.toList().forEach(::remove)

    // ---- offline watching ----

    fun savePosition(id: UUID, ticks: Long) {
        update(id.toString()) { it.copy(positionTicks = ticks, positionDirty = true) }
    }

    /** Pushes positions watched offline to the server. Best effort: failures are retried on next launch. */
    suspend fun syncPositions() {
        val s = sessions.current ?: return
        _entries.value.values.filter { it.positionDirty && it.accountKey == s.account.key }.forEach { e ->
            runCatching {
                s.api.playStateApi.reportPlaybackStopped(PlaybackStopInfo(itemId = e.item.id, positionTicks = e.positionTicks, failed = false))
                update(e.id) { it.copy(positionDirty = false) }
            }
        }
    }

    // ---- the actual work (called by DownloadService) ----

    /** Downloads queued items one by one until the queue is empty. [onProgress] gets the active entry for notifications. */
    suspend fun processQueue(onProgress: (DownloadEntry) -> Unit) {
        if (!runLock.tryLock()) return
        try {
            while (true) {
                val next = _entries.value.values.firstOrNull { it.status == DownloadStatus.QUEUED } ?: break
                runCatching { download(next, onProgress) }.onFailure { e ->
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    update(next.id) { it.copy(status = if (cancelId == next.id) DownloadStatus.PAUSED else DownloadStatus.FAILED, error = e.message) }
                }
            }
        } finally { runLock.unlock() }
    }

    private fun isAllowedNetwork(wifiOnly: Boolean): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            (!wifiOnly || caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED))
    }

    private suspend fun download(start: DownloadEntry, onProgress: (DownloadEntry) -> Unit) = withContext(Dispatchers.IO) {
        val session = sessions.current?.takeIf { it.account.key == start.accountKey }
            ?: throw IOException("Sign in to the account that owns this download")
        if (!isAllowedNetwork(settings.settings.first().downloadWifiOnly)) throw IOException("Waiting for an unmetered network")
        cancelId = null
        val dir = File(root, start.id).also { it.mkdirs() }
        val base = session.account.serverUrl.trimEnd('/')
        val token = session.account.token
        update(start.id) { it.copy(status = DownloadStatus.DOWNLOADING, error = null) }

        // Poster first so the offline list looks right even if the video is interrupted.
        if (!File(dir, "poster.jpg").exists()) runCatching {
            val url = "$base/Items/${start.id}/Images/Primary?maxWidth=400&quality=85&api_key=${Uri.encode(token)}"
            (URL(url).openConnection() as HttpURLConnection).also { it.setRequestProperty("X-Emby-Token", token) }
                .inputStream.use { input -> File(dir, "poster.jpg").outputStream().use { input.copyTo(it) } }
        }

        // External text subtitles are small: fetch them first, best effort (the video matters more).
        val src = start.item.mediaSources?.firstOrNull()
        src?.mediaStreams.orEmpty().filter { it.type == MediaStreamType.SUBTITLE && it.isExternal && it.isTextSubtitleStream }.forEach { st ->
            val ext = if (st.codec.equals("ass", true) || st.codec.equals("ssa", true)) "ass" else "srt"
            val file = File(dir, "sub_${st.index}.$ext")
            if (!file.exists()) runCatching {
                val url = "$base/Videos/${start.id}/${src?.id ?: start.id}/Subtitles/${st.index}/0/Stream.$ext"
                val c = URL(url).openConnection() as HttpURLConnection
                c.setRequestProperty("X-Emby-Token", token); c.connectTimeout = 10_000; c.readTimeout = 20_000
                try {
                    if (c.responseCode == 200) { val tmp = File(dir, file.name + ".tmp"); c.inputStream.use { i -> tmp.outputStream().use { i.copyTo(it) } }; tmp.renameTo(file) }
                } finally { c.disconnect() }
            }
        }

        val part = File(dir, start.fileName + ".part")
        val target = File(dir, start.fileName)
        var offset = if (part.exists()) part.length() else 0L
        val conn = URL("$base/Items/${start.id}/Download").openConnection() as HttpURLConnection
        conn.setRequestProperty("X-Emby-Token", token)
        conn.connectTimeout = 15_000; conn.readTimeout = 30_000
        if (offset > 0) conn.setRequestProperty("Range", "bytes=$offset-")
        try {
            val code = conn.responseCode
            when (code) {
                200 -> { offset = 0; part.delete() }              // server ignored Range: restart
                206 -> Unit
                401, 403 -> throw IOException("Downloads are not allowed for this account")
                else -> throw IOException("Server answered HTTP $code")
            }
            val total = (if (code == 206) offset else 0) + conn.contentLengthLong.coerceAtLeast(0)
            var done = offset
            var lastEmit = 0L
            RandomAccessFile(part, "rw").use { out ->
                out.seek(offset)
                conn.inputStream.use { input ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        coroutineContext.ensureActive()
                        if (cancelId == start.id) throw IOException("paused")
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        val now = System.currentTimeMillis()
                        if (now - lastEmit > 500) {
                            lastEmit = now
                            update(start.id) { it.copy(downloadedBytes = done, totalBytes = if (total > 0) total else it.totalBytes) }
                            _entries.value[start.id]?.let(onProgress)
                        }
                    }
                }
            }
            if (total > 0 && done < total) throw IOException("Connection lost")
            if (!part.renameTo(target)) throw IOException("Could not save the file")
            update(start.id) { it.copy(status = DownloadStatus.COMPLETE, downloadedBytes = done, totalBytes = done, error = null) }
            _entries.value[start.id]?.let(onProgress)
        } finally { conn.disconnect() }
    }

    // ---- persistence ----

    private fun update(id: String, block: (DownloadEntry) -> DownloadEntry) {
        var updated: DownloadEntry? = null
        _entries.update { map ->
            val cur = map[id] ?: return@update map
            block(cur).also { updated = it }.let { map + (id to it) }
        }
        updated?.let { if (it.status != DownloadStatus.DOWNLOADING) persist(it) }
    }

    private fun persist(e: DownloadEntry) {
        scope.launch(Dispatchers.IO) {
            runCatching {
                val dir = File(root, e.id).also { it.mkdirs() }
                val tmp = File(dir, "meta.json.tmp")
                tmp.writeText(json.encodeToString(DownloadEntry.serializer(), e))
                tmp.renameTo(File(dir, "meta.json"))
            }
        }
    }
}
