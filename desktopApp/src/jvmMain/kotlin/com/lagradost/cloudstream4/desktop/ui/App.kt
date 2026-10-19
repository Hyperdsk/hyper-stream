package com.lagradost.cloudstream4.desktop.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.lagradost.cloudstream4.desktop.DesktopToasts
import com.lagradost.cloudstream4.desktop.player.VlcPlayer

private data class Tab(val route: Route, val label: String, val icon: ImageVector)

private val tabs = listOf(
    Tab(Route.Home, "Home", Icons.Default.Home),
    Tab(Route.Search, "Search", Icons.Default.Search),
    Tab(Route.Library, "Library", Icons.Default.VideoLibrary),
    Tab(Route.Downloads, "Downloads", Icons.Default.Download),
    Tab(Route.Extensions, "Extensions", Icons.Default.Extension),
    Tab(Route.Settings, "Settings", Icons.Default.Settings),
)

/** Holds screen models for routes on the back stack, and disposes them once they leave it. */
class ModelStore {
    val home = HomeModel()
    val search = SearchModel()
    private val models = mutableMapOf<Route, ScreenModel<*>>()

    @Suppress("UNCHECKED_CAST")
    fun <M : ScreenModel<*>> get(route: Route, create: () -> M): M = models.getOrPut(route, create) as M

    fun retain(stack: List<Route>) {
        val gone = models.keys - stack.toSet()
        gone.forEach { models.remove(it)?.dispose() }
    }
}

@Composable
fun CloudStreamApp(nav: Navigator, store: ModelStore, vlc: VlcPlayer?) {
    LaunchedEffect(nav) {
        snapshotFlow { nav.stack.toList() }.collect { store.retain(it) }
    }

    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(Unit) {
        DesktopToasts.messages.collect { snackbar.showSnackbar(it) }
    }

    Box(Modifier.fillMaxSize()) {
        AppContent(nav, store, vlc)
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(24.dp))
    }
}

@Composable
private fun AppContent(nav: Navigator, store: ModelStore, vlc: VlcPlayer?) {
    CompositionLocalProvider(LocalNavigator provides nav) {
        val route = nav.current
        if (route is Route.Player) {
            val model = store.get(route) { PlayerModel(route.request, vlc) }
            PlayerScreen(model) { nav.back() }
            return@CompositionLocalProvider
        }

        Row(Modifier.fillMaxSize()) {
            NavigationRail(Modifier.fillMaxHeight()) {
                Spacer(Modifier.height(12.dp))
                tabs.forEach { tab ->
                    NavigationRailItem(
                        selected = nav.tab == tab.route,
                        onClick = { nav.open(tab.route) },
                        icon = { Icon(tab.icon, tab.label) },
                        label = { Text(tab.label) },
                    )
                }
            }
            VerticalDivider()
            Box(Modifier.weight(1f).fillMaxHeight()) {
                when (route) {
                    Route.Home -> HomeScreen(store.home)
                    Route.Search -> SearchScreen(store.search)
                    Route.Library -> LibraryScreen()
                    Route.Downloads -> DownloadsScreen()
                    Route.Extensions -> ExtensionsScreen()
                    Route.Settings -> SettingsScreen(vlcAvailable = vlc != null)
                    is Route.Details -> DetailsScreen(route, store.get(route) { DetailsModel(route) })
                    is Route.Player -> Unit
                }
            }
        }
    }
}

@Composable
fun rememberNavigator() = remember { Navigator() }
