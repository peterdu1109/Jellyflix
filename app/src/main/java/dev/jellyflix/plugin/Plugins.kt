package dev.jellyflix.plugin

import dev.jellyflix.data.AppSettings

/**
 * Client-side plugin contract. Jellyflix plugins extend the *app*, while Jellyfin server plugins
 * (Intro Skipper, Jellyseerr bridges, ...) are detected via the server and surfaced here.
 *
 * Each capability is optional so a plugin only implements what it needs.
 */
interface ClientPlugin {
    val id: String
    val nameRes: Int
    val descriptionRes: Int
    /** Server-side plugin names this client plugin relies on (shown as a hint in Settings). */
    val serverPluginHints: List<String> get() = emptyList()
}

/** Home rows contributed by plugins, resolved by the Home screen. */
enum class HomeSection { ContinueWatching, NextUp, Favorites, LatestPerLibrary }

interface HomeSectionPlugin : ClientPlugin { val sections: List<HomeSection> }

/** Player behaviours contributed by plugins. */
interface PlayerPlugin : ClientPlugin {
    val showsSegmentSkip: Boolean get() = false
    val autoPlayNext: Boolean get() = false
}

class PluginRegistry(val all: List<ClientPlugin>) {
    fun enabled(settings: AppSettings) = all.filter { it.id !in settings.disabledPlugins }
    fun homeSections(settings: AppSettings): Set<HomeSection> =
        enabled(settings).filterIsInstance<HomeSectionPlugin>().flatMap { it.sections }.toSet()
    fun segmentSkip(settings: AppSettings) = enabled(settings).filterIsInstance<PlayerPlugin>().any { it.showsSegmentSkip }
    fun autoPlayNext(settings: AppSettings) = enabled(settings).filterIsInstance<PlayerPlugin>().any { it.autoPlayNext }
}

object BuiltInPlugins {
    fun create() = PluginRegistry(listOf(ContinuePlugin, FavoritesPlugin, SegmentSkipPlugin, AutoNextPlugin))
}

private object ContinuePlugin : HomeSectionPlugin {
    override val id = "home.continue"
    override val nameRes = dev.jellyflix.R.string.continue_watching
    override val descriptionRes = dev.jellyflix.R.string.next_up
    override val sections = listOf(HomeSection.ContinueWatching, HomeSection.NextUp, HomeSection.LatestPerLibrary)
}

private object FavoritesPlugin : HomeSectionPlugin {
    override val id = "home.favorites"
    override val nameRes = dev.jellyflix.R.string.favorites
    override val descriptionRes = dev.jellyflix.R.string.favorites
    override val sections = listOf(HomeSection.Favorites)
}

private object SegmentSkipPlugin : PlayerPlugin {
    override val id = "player.segments"
    override val nameRes = dev.jellyflix.R.string.skip_intro
    override val descriptionRes = dev.jellyflix.R.string.skip_credits
    override val serverPluginHints = listOf("Intro Skipper")
    override val showsSegmentSkip = true
}

private object AutoNextPlugin : PlayerPlugin {
    override val id = "player.autonext"
    override val nameRes = dev.jellyflix.R.string.next_up
    override val descriptionRes = dev.jellyflix.R.string.episodes
    override val autoPlayNext = true
}
