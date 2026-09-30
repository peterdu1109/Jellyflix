package dev.jellyflix.data

import org.jellyfin.sdk.api.client.exception.ApiClientException
import org.jellyfin.sdk.api.client.extensions.imageApi
import org.jellyfin.sdk.api.client.extensions.itemsApi
import org.jellyfin.sdk.api.client.extensions.libraryApi
import org.jellyfin.sdk.api.client.extensions.playStateApi
import org.jellyfin.sdk.api.client.extensions.tvShowsApi
import org.jellyfin.sdk.api.client.extensions.userLibraryApi
import org.jellyfin.sdk.api.client.extensions.userViewsApi
import org.jellyfin.sdk.api.client.extensions.userApi
import org.jellyfin.sdk.api.client.extensions.artistsApi
import org.jellyfin.sdk.api.client.extensions.liveTvApi
import org.jellyfin.sdk.api.client.extensions.displayPreferencesApi
import org.jellyfin.sdk.api.client.extensions.pluginsApi
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.ImageType
import org.jellyfin.sdk.model.api.ItemFields
import org.jellyfin.sdk.model.api.ItemSortBy
import org.jellyfin.sdk.model.api.PluginInfo
import org.jellyfin.sdk.model.api.SortOrder
import java.util.UUID

data class Page(val items: List<BaseItemDto>, val total: Int)

/** How the server (per user) wants the client to lay things out: home sections order and library visibility. */
data class UserLayout(
    val homeSections: List<String>,
    val orderedViews: List<UUID>,
    val hiddenViews: Set<UUID>,
    val hiddenLatest: Set<UUID>,
) {
    companion object {
        /** Same defaults as the Jellyfin web client when the user never customised the home screen. */
        val DefaultSections = listOf("smalllibrarytiles", "resume", "resumeaudio", "resumebook", "livetv", "nextup", "latestmedia", "none")
    }
}

private val CARD_FIELDS = listOf(ItemFields.OVERVIEW, ItemFields.PRIMARY_IMAGE_ASPECT_RATIO)

/** All server reads/writes used by the UI. Throws on failure; ViewModels wrap calls in [runCatching]. */
class MediaRepository(private val sessions: SessionManager) {
    private val s get() = sessions.current ?: throw IllegalStateException("Not signed in")
    private val api get() = s.api
    private val uid get() = s.userId

    suspend fun views(): List<BaseItemDto> = api.userViewsApi.getUserViews(userId = uid).content.items

    /** Reads the user's server-side configuration; any failure falls back to the web client defaults. */
    suspend fun userLayout(): UserLayout {
        val config = runCatching { api.userApi.getCurrentUser().content.configuration }.getOrNull()
        val prefs = runCatching { api.displayPreferencesApi.getDisplayPreferences("usersettings", uid, "emby").content.customPrefs }.getOrNull().orEmpty()
        val sections = (0..9).mapNotNull { prefs["homesection$it"]?.lowercase() }.ifEmpty { UserLayout.DefaultSections }
        return UserLayout(
            homeSections = sections,
            orderedViews = config?.orderedViews.orEmpty(),
            hiddenViews = config?.myMediaExcludes.orEmpty().toSet(),
            hiddenLatest = config?.latestItemsExcludes.orEmpty().toSet(),
        )
    }

    suspend fun liveChannels(limit: Int = 200): List<BaseItemDto> = api.liveTvApi.getLiveTvChannels(
        userId = uid, limit = limit, addCurrentProgram = true, enableFavoriteSorting = true,
    ).content.items

    /** Programs starting in the next [hours] hours (or already on air) for the given channels, in time order. */
    suspend fun guide(channelIds: List<UUID>, hours: Long = 12): List<BaseItemDto> {
        val now = java.time.LocalDateTime.now(java.time.ZoneOffset.UTC)
        return api.liveTvApi.getPrograms(
            org.jellyfin.sdk.model.api.GetProgramsDto(
                channelIds = channelIds, userId = uid, minEndDate = now, maxStartDate = now.plusHours(hours),
                sortBy = listOf(org.jellyfin.sdk.model.api.ItemSortBy.START_DATE), enableImages = false, limit = 600,
            ),
        ).content.items
    }

    /** Scheduled recordings (timers) with their program. */
    suspend fun timers(): List<org.jellyfin.sdk.model.api.TimerInfoDto> = api.liveTvApi.getTimers().content.items

    /** Records [programId] with the server's default padding/keep settings (same as the web client). */
    suspend fun scheduleRecording(programId: String) {
        val d = api.liveTvApi.getDefaultTimer(programId).content
        api.liveTvApi.createTimer(
            org.jellyfin.sdk.model.api.TimerInfoDto(
                programId = programId, channelId = d.channelId, name = d.name, overview = d.overview,
                startDate = d.startDate, endDate = d.endDate, serviceName = d.serviceName, priority = d.priority,
                prePaddingSeconds = d.prePaddingSeconds, postPaddingSeconds = d.postPaddingSeconds, keepUntil = d.keepUntil,
            ),
        )
    }

    suspend fun cancelTimer(id: String) { api.liveTvApi.cancelTimer(id) }

    suspend fun recordings(): List<BaseItemDto> = api.liveTvApi.getRecordings(userId = uid).content.items

    /** Channels with what's on right now; empty when the server has no Live TV. */
    suspend fun liveTvNow(): List<BaseItemDto> = api.liveTvApi.getLiveTvChannels(
        userId = uid, limit = 20, addCurrentProgram = true, enableFavoriteSorting = true,
    ).content.items

    suspend fun resumeAudio(): List<BaseItemDto> = api.itemsApi.getResumeItems(
        userId = uid, limit = 20, fields = CARD_FIELDS, mediaTypes = listOf(org.jellyfin.sdk.model.api.MediaType.AUDIO),
    ).content.items

    suspend fun resume(): List<BaseItemDto> = api.itemsApi.getResumeItems(
        userId = uid, limit = 20, fields = CARD_FIELDS,
        mediaTypes = listOf(org.jellyfin.sdk.model.api.MediaType.VIDEO),
    ).content.items

    suspend fun nextUp(): List<BaseItemDto> = api.tvShowsApi.getNextUp(userId = uid, limit = 20, fields = CARD_FIELDS).content.items

    suspend fun latest(viewId: UUID): List<BaseItemDto> =
        api.userLibraryApi.getLatestMedia(userId = uid, parentId = viewId, limit = 20, fields = CARD_FIELDS).content

    suspend fun favorites(): List<BaseItemDto> = api.itemsApi.getItems(
        userId = uid, isFavorite = true, recursive = true, limit = 30, fields = CARD_FIELDS,
        includeItemTypes = listOf(BaseItemKind.MOVIE, BaseItemKind.SERIES, BaseItemKind.EPISODE),
    ).content.items

    suspend fun browse(
        parentId: UUID?, sortBy: ItemSortBy, order: SortOrder, start: Int, limit: Int = 60, types: List<BaseItemKind>? = null,
    ): Page {
        val r = api.itemsApi.getItems(
            userId = uid, parentId = parentId, recursive = true, startIndex = start, limit = limit,
            sortBy = listOf(sortBy), sortOrder = listOf(order), fields = CARD_FIELDS,
            includeItemTypes = types ?: listOf(BaseItemKind.MOVIE, BaseItemKind.SERIES, BaseItemKind.BOX_SET, BaseItemKind.MUSIC_ALBUM),
        ).content
        return Page(r.items, r.totalRecordCount)
    }

    /** Album tracks in disc/track order, or a playlist in its own order. */
    suspend fun tracks(parentId: UUID, playlistOrder: Boolean): List<BaseItemDto> = api.itemsApi.getItems(
        userId = uid, parentId = parentId, recursive = true, fields = CARD_FIELDS,
        includeItemTypes = listOf(BaseItemKind.AUDIO),
        sortBy = if (playlistOrder) null else listOf(ItemSortBy.PARENT_INDEX_NUMBER, ItemSortBy.INDEX_NUMBER, ItemSortBy.SORT_NAME),
    ).content.items

    suspend fun albumsOfArtist(artistId: UUID): List<BaseItemDto> = api.itemsApi.getItems(
        userId = uid, albumArtistIds = listOf(artistId), recursive = true, fields = CARD_FIELDS,
        includeItemTypes = listOf(BaseItemKind.MUSIC_ALBUM), sortBy = listOf(ItemSortBy.PRODUCTION_YEAR, ItemSortBy.SORT_NAME), sortOrder = listOf(SortOrder.DESCENDING),
    ).content.items

    suspend fun browseArtists(parentId: UUID?, start: Int, limit: Int = 60): Page {
        val r = api.artistsApi.getAlbumArtists(userId = uid, parentId = parentId, startIndex = start, limit = limit, fields = CARD_FIELDS, sortBy = listOf(ItemSortBy.SORT_NAME)).content
        return Page(r.items, r.totalRecordCount)
    }

    /** Everything a person (actor, director…) appears in. */
    suspend fun filmography(personId: UUID): List<BaseItemDto> = api.itemsApi.getItems(
        userId = uid, personIds = listOf(personId), recursive = true, fields = CARD_FIELDS,
        includeItemTypes = listOf(BaseItemKind.MOVIE, BaseItemKind.SERIES),
        sortBy = listOf(ItemSortBy.PREMIERE_DATE), sortOrder = listOf(SortOrder.DESCENDING),
    ).content.items

    suspend fun children(parentId: UUID): List<BaseItemDto> = api.itemsApi.getItems(
        userId = uid, parentId = parentId, sortBy = listOf(ItemSortBy.SORT_NAME), fields = CARD_FIELDS,
    ).content.items

    suspend fun item(id: UUID): BaseItemDto = api.userLibraryApi.getItem(itemId = id, userId = uid).content

    suspend fun seasons(seriesId: UUID): List<BaseItemDto> =
        api.tvShowsApi.getSeasons(seriesId = seriesId, userId = uid).content.items

    suspend fun episodes(seriesId: UUID, seasonId: UUID?): List<BaseItemDto> =
        api.tvShowsApi.getEpisodes(seriesId = seriesId, userId = uid, seasonId = seasonId, fields = CARD_FIELDS + ItemFields.MEDIA_SOURCES).content.items

    suspend fun similar(id: UUID): List<BaseItemDto> =
        api.libraryApi.getSimilarItems(itemId = id, userId = uid, limit = 12, fields = CARD_FIELDS).content.items

    suspend fun search(term: String): List<BaseItemDto> = api.itemsApi.getItems(
        userId = uid, searchTerm = term, recursive = true, limit = 60, fields = CARD_FIELDS,
        includeItemTypes = listOf(BaseItemKind.MOVIE, BaseItemKind.SERIES, BaseItemKind.EPISODE, BaseItemKind.BOX_SET, BaseItemKind.PERSON),
    ).content.items

    suspend fun setPlayed(id: UUID, played: Boolean) {
        if (played) api.playStateApi.markPlayedItem(itemId = id, userId = uid) else api.playStateApi.markUnplayedItem(itemId = id, userId = uid)
    }

    suspend fun setFavorite(id: UUID, favorite: Boolean) {
        if (favorite) api.userLibraryApi.markFavoriteItem(itemId = id, userId = uid) else api.userLibraryApi.unmarkFavoriteItem(itemId = id, userId = uid)
    }

    /** Server plugins are admin-only on some setups: failure is expected and mapped to an empty list. */
    suspend fun serverPlugins(): List<PluginInfo> = try {
        api.pluginsApi.getPlugins().content
    } catch (_: ApiClientException) { emptyList() }

    fun imageUrl(item: BaseItemDto, type: ImageType = ImageType.PRIMARY, maxWidth: Int = 400): String? {
        // Episodes look better with their series/parent artwork when they lack their own primary image.
        val (id, tag) = when {
            type == ImageType.PRIMARY && item.imageTags?.get(ImageType.PRIMARY) != null -> item.id to item.imageTags?.get(ImageType.PRIMARY)
            type == ImageType.BACKDROP -> when {
                !item.backdropImageTags.isNullOrEmpty() -> item.id to item.backdropImageTags?.first()
                !item.parentBackdropImageTags.isNullOrEmpty() -> (item.parentBackdropItemId ?: item.id) to item.parentBackdropImageTags?.first()
                else -> return null
            }
            type == ImageType.THUMB && item.imageTags?.get(ImageType.THUMB) != null -> item.id to item.imageTags?.get(ImageType.THUMB)
            item.seriesId != null && item.seriesPrimaryImageTag != null -> item.seriesId!! to item.seriesPrimaryImageTag
            else -> return null
        }
        return api.imageApi.getItemImageUrl(itemId = id, imageType = if (type == ImageType.BACKDROP) ImageType.BACKDROP else type, maxWidth = maxWidth, tag = tag, quality = 90)
    }

    fun imageUrlFor(id: UUID, tag: String?, type: ImageType = ImageType.PRIMARY, maxWidth: Int = 300): String =
        api.imageApi.getItemImageUrl(itemId = id, imageType = type, maxWidth = maxWidth, tag = tag)
}
