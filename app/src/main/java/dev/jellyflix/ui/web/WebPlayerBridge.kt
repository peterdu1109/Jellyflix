package dev.jellyflix.ui.web

import dev.jellyflix.player.ExternalStream
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import org.jellyfin.sdk.model.api.MediaSourceInfo
import org.jellyfin.sdk.model.api.PlayMethod
import java.util.UUID

/** Commands the web player plugin (assets/web/jellyflix-web.js) sends through `JellyflixNative.postMessage`. */
sealed interface BridgeCommand {
    data class Play(val stream: ExternalStream) : BridgeCommand
    data object Stop : BridgeCommand
    data object Pause : BridgeCommand
    data object Unpause : BridgeCommand
    data class Seek(val ms: Long) : BridgeCommand
    data class Audio(val index: Int) : BridgeCommand
    data class Subtitle(val index: Int) : BridgeCommand
    data object Quit : BridgeCommand
}

/** Pure parsing/encoding for the web <-> app protocol, so it can be unit-tested without a device. */
object WebPlayerBridge {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true }

    /** Returns null for anything malformed or unknown: a bad message must never crash the app. */
    fun parse(message: String, serial: Int): BridgeCommand? = runCatching {
        val o = json.parseToJsonElement(message).jsonObject
        when (o["cmd"]?.jsonPrimitive?.contentOrNull) {
            "play" -> BridgeCommand.Play(parsePlay(o, serial))
            "stop" -> BridgeCommand.Stop
            "pause" -> BridgeCommand.Pause
            "unpause" -> BridgeCommand.Unpause
            "seek" -> BridgeCommand.Seek(o.long("ms") ?: return null)
            "audio" -> BridgeCommand.Audio(o.int("index") ?: return null)
            "subtitle" -> BridgeCommand.Subtitle(o.int("index") ?: -1)
            "quit" -> BridgeCommand.Quit
            else -> null
        }
    }.getOrNull()

    private fun parsePlay(o: JsonObject, serial: Int): ExternalStream {
        val source = json.decodeFromJsonElement(MediaSourceInfo.serializer(), withDefaults(o["mediaSource"]?.jsonObject ?: error("mediaSource missing")))
        return ExternalStream(
            serial = serial,
            itemId = UUID.fromString(dashed(o.string("itemId") ?: error("itemId missing"))),
            url = o.string("url") ?: error("url missing"),
            playMethod = when (o.string("playMethod")) {
                "DirectPlay" -> PlayMethod.DIRECT_PLAY
                "DirectStream" -> PlayMethod.DIRECT_STREAM
                else -> PlayMethod.TRANSCODE
            },
            startTicks = o.long("startTicks") ?: 0L,
            playSessionId = o.string("playSessionId"),
            liveStreamId = o.string("liveStreamId"),
            mediaSource = source,
            audioIndex = o.int("audioIndex"),
            subtitleIndex = o.int("subtitleIndex"),
        )
    }

    /**
     * The SDK model requires a few fields that differ between server versions. A source that lacks one must still
     * play, so anything missing is filled with the neutral value before decoding.
     */
    fun withDefaults(source: JsonObject): JsonObject {
        val falseFlags = listOf(
            "IsRemote", "ReadAtNativeFramerate", "IgnoreDts", "IgnoreIndex", "GenPtsInput", "SupportsTranscoding", "SupportsDirectStream",
            "SupportsDirectPlay", "IsInfiniteStream", "RequiresOpening", "RequiresClosing", "RequiresLooping", "SupportsProbing", "HasSegments",
        )
        return buildJsonObject {
            source.forEach { (k, v) -> put(k, v) }
            falseFlags.forEach { if (it !in source) put(it, false) }
            if ("Protocol" !in source) put("Protocol", "File")
            if ("Type" !in source) put("Type", "Default")
            if ("TranscodingSubProtocol" !in source) put("TranscodingSubProtocol", "http")
        }
    }

    /** Jellyfin ids reach the web client as 32 hex digits; UUID.fromString wants dashes. */
    fun dashed(id: String): String =
        if (id.length == 32 && !id.contains('-')) "${id.substring(0, 8)}-${id.substring(8, 12)}-${id.substring(12, 16)}-${id.substring(16, 20)}-${id.substring(20)}" else id

    /** JS expression delivering [event] to the plugin. The payload is JSON, so nothing in it can be read as code. */
    fun eventScript(event: JsonObject): String = "window.__jellyflixNative&&window.__jellyflixNative.onEvent($event)"

    fun event(type: String, block: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit = {}): JsonObject =
        buildJsonObject { put("type", type); block() }

    private fun JsonObject.string(k: String): String? = (this[k] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull
    private fun JsonObject.int(k: String): Int? = (this[k] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.intOrNull
    private fun JsonObject.long(k: String): Long? = (this[k] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.longOrNull
    @Suppress("unused") private fun JsonElement.unused() = Unit
}
