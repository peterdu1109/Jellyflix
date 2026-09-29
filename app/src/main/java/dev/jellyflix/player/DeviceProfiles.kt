package dev.jellyflix.player

import android.media.MediaCodecList
import android.media.MediaFormat
import org.jellyfin.sdk.model.api.DeviceProfile
import org.jellyfin.sdk.model.api.DirectPlayProfile
import org.jellyfin.sdk.model.api.DlnaProfileType
import org.jellyfin.sdk.model.api.EncodingContext
import org.jellyfin.sdk.model.api.MediaStreamProtocol
import org.jellyfin.sdk.model.api.SubtitleDeliveryMethod
import org.jellyfin.sdk.model.api.SubtitleProfile
import org.jellyfin.sdk.model.api.TranscodingProfile

/** Builds the capability profile sent to the server so it only transcodes what this device can't decode. */
object DeviceProfiles {
    private fun hasDecoder(mime: String): Boolean = runCatching {
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any { !it.isEncoder && it.supportedTypes.any { t -> t.equals(mime, ignoreCase = true) } }
    }.getOrDefault(false)

    /** [burnInSubtitles]: every subtitle is rendered into the video by the server (needed for transcoded streams). */
    fun build(maxBitrate: Int?, burnInSubtitles: Boolean = false): DeviceProfile {
        val video = buildList {
            add("h264")
            if (hasDecoder(MediaFormat.MIMETYPE_VIDEO_HEVC)) { add("hevc"); add("h265") }
            if (hasDecoder(MediaFormat.MIMETYPE_VIDEO_VP9)) add("vp9")
            if (hasDecoder(MediaFormat.MIMETYPE_VIDEO_AV1)) add("av1")
        }
        val audio = buildList {
            addAll(listOf("aac", "mp3", "opus", "flac", "vorbis"))
            if (hasDecoder(MediaFormat.MIMETYPE_AUDIO_AC3)) add("ac3")
            if (hasDecoder(MediaFormat.MIMETYPE_AUDIO_EAC3)) add("eac3")
        }
        val textSubs = listOf("vtt", "srt", "subrip", "ass", "ssa")
        val imageSubs = listOf("pgs", "pgssub", "dvdsub", "dvbsub", "sub")
        return DeviceProfile(
            name = "Jellyflix Android",
            maxStreamingBitrate = maxBitrate ?: 120_000_000,
            maxStaticBitrate = 120_000_000,
            musicStreamingTranscodingBitrate = 192_000,
            directPlayProfiles = if (maxBitrate != null) emptyList() else listOf(
                DirectPlayProfile(container = "mp4,m4v,mkv,webm", audioCodec = audio.joinToString(","), videoCodec = video.joinToString(","), type = DlnaProfileType.VIDEO),
            ),
            transcodingProfiles = listOf(
                TranscodingProfile(
                    container = "ts", type = DlnaProfileType.VIDEO, videoCodec = "h264", audioCodec = "aac,mp3,ac3,eac3".let { c ->
                        if (audio.contains("ac3")) c else "aac,mp3"
                    },
                    protocol = MediaStreamProtocol.HLS, context = EncodingContext.STREAMING,
                    maxAudioChannels = "6", minSegments = 1, breakOnNonKeyFrames = true, copyTimestamps = false,
                    enableMpegtsM2TsMode = false, transcodeSeekInfo = org.jellyfin.sdk.model.api.TranscodeSeekInfo.AUTO,
                    estimateContentLength = false, enableSubtitlesInManifest = false, conditions = emptyList(),
                ),
            ),
            containerProfiles = emptyList(),
            codecProfiles = emptyList(),
            subtitleProfiles = if (burnInSubtitles) {
                (textSubs + imageSubs).map { SubtitleProfile(format = it, method = SubtitleDeliveryMethod.ENCODE) }
            } else {
                textSubs.map { SubtitleProfile(format = it, method = SubtitleDeliveryMethod.EXTERNAL) } +
                    imageSubs.map { SubtitleProfile(format = it, method = SubtitleDeliveryMethod.ENCODE) }
            },
        )
    }
}
