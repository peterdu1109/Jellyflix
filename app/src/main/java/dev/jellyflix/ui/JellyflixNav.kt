package dev.jellyflix.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.runtime.collectAsState
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import dev.jellyflix.R
import dev.jellyflix.data.AppSettings
import dev.jellyflix.plugin.PluginRegistry
import dev.jellyflix.ui.screens.DetailScreen
import dev.jellyflix.ui.screens.DownloadsScreen
import dev.jellyflix.ui.screens.HomeScreen
import dev.jellyflix.ui.screens.LibrariesScreen
import dev.jellyflix.ui.screens.LibraryScreen
import dev.jellyflix.ui.screens.PlayerScreen
import dev.jellyflix.ui.screens.SearchScreen
import dev.jellyflix.ui.screens.SettingsScreen
import dev.jellyflix.ui.theme.LocalIsTv
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import java.util.UUID

private data class Tab(val route: String, val label: Int, val icon: ImageVector)

private val Tabs = listOf(
    Tab("home", R.string.nav_home, Icons.Default.Home),
    Tab("libraries", R.string.nav_library, Icons.Default.VideoLibrary),
    Tab("search", R.string.nav_search, Icons.Default.Search),
    Tab("downloads", R.string.nav_downloads, Icons.Default.Download),
    Tab("settings", R.string.nav_settings, Icons.Default.Settings),
)

private object Routes {
    const val DETAIL = "detail/{id}"
    const val LIBRARY = "library/{id}"
    const val PLAYER = "player/{id}"
    fun detail(id: UUID) = "detail/$id"
    fun library(id: UUID) = "library/$id"
    fun player(id: UUID) = "player/$id"
}

private val UUID_ARG = listOf(navArgument("id") { type = NavType.StringType })

@Composable
fun JellyflixNav(settings: AppSettings, plugins: PluginRegistry) {
    val nav = rememberNavController()
    val isTv = LocalIsTv.current
    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route
    val showBars = Tabs.any { it.route == route }

    fun open(item: BaseItemDto) = when (item.type) {
        BaseItemKind.COLLECTION_FOLDER, BaseItemKind.USER_VIEW, BaseItemKind.FOLDER -> nav.navigate(Routes.library(item.id))
        else -> nav.navigate(Routes.detail(item.id))
    }
    fun play(item: BaseItemDto) = nav.navigate(Routes.player(item.id))

    fun go(tab: Tab) = nav.navigate(tab.route) {
        popUpTo(nav.graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true; restoreState = true
    }

    val content: @Composable (Modifier) -> Unit = { mod ->
        NavHost(nav, startDestination = "home", modifier = mod) {
            composable("home") { HomeScreen(settings, plugins, ::open, ::play, ::open, onOpenDownloads = { go(Tabs.first { it.route == "downloads" }) }) }
            composable("downloads") { DownloadsScreen(::play) }
            composable("libraries") { LibrariesScreen(::open) }
            composable("search") { SearchScreen(::open) }
            composable("settings") { SettingsScreen(settings) }
            composable(Routes.LIBRARY, UUID_ARG) { LibraryScreen(UUID.fromString(it.arguments!!.getString("id")), ::open) }
            composable(Routes.DETAIL, UUID_ARG) {
                DetailScreen(UUID.fromString(it.arguments!!.getString("id")), nav::popBackStack, ::open, ::play)
            }
            composable(Routes.PLAYER, UUID_ARG) {
                PlayerScreen(
                    UUID.fromString(it.arguments!!.getString("id")), onBack = { nav.popBackStack() },
                    onNext = { next -> nav.navigate(Routes.player(next)) { popUpTo(Routes.PLAYER) { inclusive = true } } },
                )
            }
        }
    }

    if (isTv) {
        Row(Modifier.fillMaxSize()) {
            if (showBars) NavigationRail {
                Tabs.forEach { t -> NavigationRailItem(route == t.route, { go(t) }, { Icon(t.icon, null) }, label = { Text(stringResource(t.label)) }) }
            }
            content(Modifier.weight(1f))
        }
    } else {
        Scaffold(bottomBar = {
            if (showBars) NavigationBar {
                Tabs.forEach { t -> NavigationBarItem(route == t.route, { go(t) }, { Icon(t.icon, null) }, label = { Text(stringResource(t.label)) }) }
            }
        }) { pad -> content(Modifier.padding(pad)) }
    }
}
