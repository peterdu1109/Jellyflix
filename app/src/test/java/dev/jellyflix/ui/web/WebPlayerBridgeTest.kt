package dev.jellyflix.ui.web

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jellyfin.sdk.model.api.MediaProtocol
import org.jellyfin.sdk.model.api.MediaSourceInfo
import org.jellyfin.sdk.model.api.MediaSourceType
import org.jellyfin.sdk.model.api.PlayMethod
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WebPlayerBridgeTest {
    private val source = MediaSourceInfo(
        protocol = MediaProtocol.FILE, id = "cccccccccccccccccccccccccccccccc", type = MediaSourceType.DEFAULT,
        isRemote = false, readAtNativeFramerate = false, ignoreDts = false, ignoreIndex = false, genPtsInput = false,
        supportsTranscoding = true, supportsDirectStream = true, supportsDirectPlay = true, isInfiniteStream = false,
        requiresOpening = false, requiresClosing = false, requiresLooping = false, supportsProbing = true,
        container = "mkv", runTimeTicks = 72_000_000_000,
        transcodingSubProtocol = org.jellyfin.sdk.model.api.MediaStreamProtocol.HTTP, hasSegments = false,
    )

    private fun playMessage(extra: String = "") = """{"cmd":"play","itemId":"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb","url":"https://h/Videos/x/stream.mkv?ApiKey=t",
        "playMethod":"DirectPlay","startTicks":123000000,"playSessionId":null,"liveStreamId":null,"audioIndex":1,"subtitleIndex":-1,
        "mediaSource":${Json.encodeToString(MediaSourceInfo.serializer(), source)}$extra}"""

    @Test fun playMessageBecomesAnExternalStream() {
        val cmd = WebPlayerBridge.parse(playMessage(), serial = 7) as BridgeCommand.Play
        val s = cmd.stream
        assertEquals(7, s.serial)
        assertEquals("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb", s.itemId.toString())
        assertEquals(PlayMethod.DIRECT_PLAY, s.playMethod)
        assertEquals(123_000_000L, s.startTicks)
        assertEquals(1, s.audioIndex)
        assertEquals(-1, s.subtitleIndex)
        assertEquals("mkv", s.mediaSource.container)
        assertNull(s.playSessionId)
    }

    @Test fun transcodeAndDirectStreamAreDistinguished() {
        fun method(m: String) = (WebPlayerBridge.parse(playMessage().replace("DirectPlay", m), 1) as BridgeCommand.Play).stream.playMethod
        assertEquals(PlayMethod.DIRECT_STREAM, method("DirectStream"))
        assertEquals(PlayMethod.TRANSCODE, method("Transcode"))
    }

    @Test fun simpleCommands() {
        assertEquals(BridgeCommand.Stop, WebPlayerBridge.parse("""{"cmd":"stop"}""", 1))
        assertEquals(BridgeCommand.Pause, WebPlayerBridge.parse("""{"cmd":"pause"}""", 1))
        assertEquals(BridgeCommand.Unpause, WebPlayerBridge.parse("""{"cmd":"unpause"}""", 1))
        assertEquals(BridgeCommand.Quit, WebPlayerBridge.parse("""{"cmd":"quit"}""", 1))
        assertEquals(BridgeCommand.Seek(61_000), WebPlayerBridge.parse("""{"cmd":"seek","ms":61000}""", 1))
        assertEquals(BridgeCommand.Audio(2), WebPlayerBridge.parse("""{"cmd":"audio","index":2}""", 1))
        assertEquals(BridgeCommand.Subtitle(-1), WebPlayerBridge.parse("""{"cmd":"subtitle","index":-1}""", 1))
    }

    @Test fun aSourceMissingFieldsThatDifferBetweenServerVersionsStillPlays() {
        val minimal = "{\"cmd\":\"play\",\"itemId\":\"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb\",\"url\":\"https://h/v\",\"playMethod\":\"Transcode\"," +
            "\"mediaSource\":{\"Id\":\"cc\",\"Container\":\"mkv\",\"SupportsDirectPlay\":false}}"
        val cmd = WebPlayerBridge.parse(minimal, 3) as BridgeCommand.Play
        assertEquals("cc", cmd.stream.mediaSource.id)
        assertEquals(false, cmd.stream.mediaSource.supportsDirectPlay)
        assertEquals(PlayMethod.TRANSCODE, cmd.stream.playMethod)
    }

    @Test fun malformedMessagesAreIgnoredNotFatal() {
        assertNull(WebPlayerBridge.parse("not json", 1))
        assertNull(WebPlayerBridge.parse("""{"cmd":"explode"}""", 1))
        assertNull(WebPlayerBridge.parse("""{"cmd":"seek"}""", 1))
        assertNull(WebPlayerBridge.parse("""{"cmd":"play","itemId":"zz"}""", 1))
        assertNull(WebPlayerBridge.parse("""{"cmd":"play","itemId":"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb","url":"u"}""", 1))
    }

    @Test fun eventsAreJsonNotCode() {
        val hostile = "\"});alert(1);//"
        val script = WebPlayerBridge.eventScript(WebPlayerBridge.event("error") { put("errorType", hostile) })
        assertTrue(script.startsWith("window.__jellyflixNative&&window.__jellyflixNative.onEvent({"))
        val payload = script.removePrefix("window.__jellyflixNative&&window.__jellyflixNative.onEvent(").removeSuffix(")")
        assertEquals(hostile, Json.parseToJsonElement(payload).jsonObject["errorType"]!!.jsonPrimitive.content)
    }

    @Test fun thirtyTwoHexIdsGetDashes() {
        assertEquals("11111111-1111-1111-1111-111111111111", WebPlayerBridge.dashed("11111111111111111111111111111111"))
        assertEquals("11111111-1111-1111-1111-111111111111", WebPlayerBridge.dashed("11111111-1111-1111-1111-111111111111"))
    }
}
