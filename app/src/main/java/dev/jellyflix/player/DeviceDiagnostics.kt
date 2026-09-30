package dev.jellyflix.player

import android.content.Context
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.os.Build
import android.view.Display
import android.view.WindowManager

/** A plain-text report of what this device can decode and display, to tune playback for a given box or TV. */
object DeviceDiagnostics {
    private val video = listOf(
        "video/avc" to "H.264", "video/hevc" to "HEVC", "video/x-vnd.on2.vp9" to "VP9", "video/av01" to "AV1",
        "video/dolby-vision" to "Dolby Vision", "video/mpeg2" to "MPEG-2",
    )
    private val audio = listOf(
        "audio/ac3" to "AC-3", "audio/eac3" to "E-AC-3", "audio/eac3-joc" to "E-AC-3 JOC (Atmos)", "audio/true-hd" to "TrueHD",
        "audio/vnd.dts" to "DTS", "audio/vnd.dts.hd" to "DTS-HD", "audio/opus" to "Opus", "audio/flac" to "FLAC",
    )

    fun report(context: Context, appVersion: String): String = buildString {
        appendLine("Jellyflix $appVersion")
        appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE}), Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        runCatching {
            @Suppress("DEPRECATION")
            val display = (context.getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay
            val mode = display.mode
            appendLine("Display: ${mode.physicalWidth}x${mode.physicalHeight} @ ${"%.1f".format(mode.refreshRate)} Hz")
            appendLine("Display modes: " + display.supportedModes.joinToString { "${it.physicalWidth}x${it.physicalHeight}@${it.refreshRate.toInt()}" })
            @Suppress("DEPRECATION")
            val hdr = display.hdrCapabilities?.supportedHdrTypes?.map {
                when (it) {
                    Display.HdrCapabilities.HDR_TYPE_DOLBY_VISION -> "Dolby Vision"
                    Display.HdrCapabilities.HDR_TYPE_HDR10 -> "HDR10"
                    Display.HdrCapabilities.HDR_TYPE_HLG -> "HLG"
                    Display.HdrCapabilities.HDR_TYPE_HDR10_PLUS -> "HDR10+"
                    else -> "type $it"
                }
            }.orEmpty()
            appendLine("HDR: " + hdr.ifEmpty { listOf("none") }.joinToString())
        }
        val codecs = runCatching { MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter { !it.isEncoder } }.getOrDefault(emptyList())
        appendLine()
        appendLine("Video decoders:")
        video.forEach { (mime, label) -> line(label, mime, codecs, video = true) }
        appendLine()
        appendLine("Audio decoders:")
        audio.forEach { (mime, label) -> line(label, mime, codecs, video = false) }
        appendLine()
        appendLine("Profile sent to the server — video: " + DeviceProfiles.build(null).directPlayProfiles.firstOrNull()?.videoCodec.orEmpty())
        appendLine("Profile sent to the server — audio: " + DeviceProfiles.build(null).directPlayProfiles.firstOrNull()?.audioCodec.orEmpty())
    }

    private fun StringBuilder.line(label: String, mime: String, codecs: List<MediaCodecInfo>, video: Boolean) {
        val matching = codecs.filter { c -> c.supportedTypes.any { it.equals(mime, true) } }
        if (matching.isEmpty()) { appendLine("  $label: no"); return }
        val details = matching.joinToString("; ") { c ->
            val hw = if (Build.VERSION.SDK_INT >= 29) (if (c.isHardwareAccelerated) "hardware" else "software") else "?"
            val size = if (video) runCatching {
                val v = c.getCapabilitiesForType(mime).videoCapabilities
                " up to ${v.supportedWidths.upper}x${v.supportedHeights.upper}"
            }.getOrDefault("") else ""
            "${c.name} ($hw$size)"
        }
        appendLine("  $label: $details")
    }
}
