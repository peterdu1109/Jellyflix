package dev.jellyflix.download

import kotlinx.serialization.json.Json
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.UUID

class DownloadEntryTest {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Test fun metadataSurvivesTheDiskRoundTrip() {
        val id = UUID.randomUUID()
        val item = BaseItemDto(id = id, type = BaseItemKind.EPISODE, name = "Pilot", seriesName = "Show", indexNumber = 1, parentIndexNumber = 2, runTimeTicks = 27_000_000_000)
        val entry = DownloadEntry(id.toString(), "https://srv|user", item, "video.mkv", DownloadStatus.PAUSED, totalBytes = 1000, downloadedBytes = 250, positionTicks = 5, positionDirty = true)

        val back = json.decodeFromString(DownloadEntry.serializer(), json.encodeToString(DownloadEntry.serializer(), entry))

        assertEquals(entry, back)
        assertEquals(0.25f, back.progress, 0.0001f)
    }

    @Test fun unknownFieldsFromNewerVersionsAreIgnored() {
        val id = UUID.randomUUID()
        val item = BaseItemDto(id = id, type = BaseItemKind.MOVIE, name = "M")
        val text = json.encodeToString(DownloadEntry.serializer(), DownloadEntry(id.toString(), "k", item, "video.mp4")).replaceFirst("{", "{\"futureField\":1,")
        assertEquals("M", json.decodeFromString(DownloadEntry.serializer(), text).item.name)
    }
}
