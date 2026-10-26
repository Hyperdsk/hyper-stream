package com.lagradost.cloudstream4.desktop.ui

import androidx.compose.foundation.focusGroup
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.lagradost.cloudstream4.desktop.download.DownloadManager
import com.lagradost.cloudstream4.desktop.download.DownloadStatus
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
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.lagradost.cloudstream4.desktop.DesktopToasts
import com.lagradost.cloudstream4.desktop.data.SettingsStore
import com.lagradost.cloudstream4.desktop.player.VideoEngine
import com.lagradost.cloudstream4.desktop.update.UpdateChecker
import com.lagradost.cloudstream4.desktop.update.UpdateState

private data class Tab(val route: Route, val label: String, val icon: ImageVector)

/** Holds screen models for routes on the back stack, and disposes them once they leave it. */
class ModelStore {
    val home = HomeModel()
    private val sections = mutableMapOf<HomeSection, HomeModel>()

    /** Home's model, or a media page's (made the first time it's opened, then kept like Home's). */
    fun home(section: HomeSection?): HomeModel = if (section == null) home else sections.getOrPut(section) { HomeModel(section) }
    val search = SearchModel()
    private val models = mutableMapOf<Route, ScreenModel<*>>()

    @Suppress("UNCHECKED_CAST")
    fun <M : ScreenModel<*>> get(route: Route, create: () -> M): M = models.getOrPut(route, create) as M

    /** Closes every model, when the window that shows them goes away. */
    fun disposeAll() {
        models.values.forEach { it.dispose() }
        models.clear()
        sections.values.forEach { it.dispose() }
        sections.clear()
        home.dispose()
        search.dispose()
    }

    fun retain(stack: List<Route>) {
        val gone = models.keys - stack.toSet()
        gone.forEach { models.remove(it)?.dispose() }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun CloudStreamApp(nav: Navigator, store: ModelStore, vlc: VideoEngine?) {
    LaunchedEffect(nav) {
        // Models live while their screen is on any tab's stack
        snapshotFlow { nav.allRoutes }.collect { store.retain(it) }
    }

    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(Unit) {
        DesktopToasts.messages.collect { snackbar.showSnackbar(it) }
    }

    // Check GitHub for a new release while the app is open, and mention each new version once.
    LaunchedEffect(Unit) {
        while (true) {
            val result = UpdateChecker.check(automatic = true)
            if (result is UpdateState.Available && SettingsStore.state.value.checkForUpdates) {
                val version = result.release.version
                if (SettingsStore.state.value.lastUpdateNotice != version) {
                    SettingsStore.update { it.copy(lastUpdateNotice = version) }
                    DesktopToasts.show("Hyper Stream $version is available. Open Settings > Updates to install it.")
                }
            }
            val wait = if (result == UpdateState.Idle && SettingsStore.state.value.checkForUpdates) {
                UpdateChecker.millisUntilAutomaticCheck()
            } else {
                6 * 60 * 60 * 1000L
            }
            kotlinx.coroutines.delay(wait)
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val layout = AppLayout.forWidth(maxWidth)
        CompositionLocalProvider(
            LocalAppLayout provides layout,
            // Android TV's default scrolls every focused item to a fixed spot a third of the way
            // down the screen, which pushed the home banner off the top; scroll only as needed instead
            *(if (com.lagradost.cloudstream4.desktop.Platform.isTv) arrayOf(
                LocalBringIntoViewSpec provides ScrollOnlyAsNeeded,
                // Material's buttons, chips, switches, tabs and list items only tint a little when
                // focused, too faint to see from across the room: on TVs they get a ring instead
                androidx.compose.material3.LocalRippleConfiguration provides TvRipple,
                androidx.compose.material3.LocalRippleThemeConfiguration provides TvRippleShape,
            ) else emptyArray()),
        ) {
            AppContent(nav, store, vlc)
        }
        SnackbarHost(
            snackbar,
            Modifier.align(Alignment.BottomCenter).windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(bottom = if (layout.compact && nav.current !is Route.Player) 88.dp else 24.dp, start = 16.dp, end = 16.dp),
        )
    }
}

@Composable
private fun AppContent(nav: Navigator, store: ModelStore, vlc: VideoEngine?) {
    CompositionLocalProvider(LocalNavigator provides nav) {
        val route = nav.current
        // Each screen on the back stack keeps its scroll positions, carousel slide and tabs while
        // another screen (a title, the player) is open on top, so Back returns to where you were
        val saved = rememberSaveableStateHolder()
        val keys = remember { mutableSetOf<String>() }
        LaunchedEffect(nav.allRoutes) {
            val live = nav.allRoutes.map { it.toString() }.toSet()
            (keys - live).forEach { saved.removeState(it); keys -= it }
        }
        if (route is Route.Player) {
            val model = store.get(route) { PlayerModel(route.request, vlc) }
            PlayerScreen(model) { nav.back() }
            return@CompositionLocalProvider
        }

        val screenFor: @Composable (Route) -> Unit = { route ->
            when (route) {
                Route.Home -> HomeScreen(store.home)
                is Route.Section -> HomeScreen(store.home(route.section))
                Route.Search -> SearchScreen(store.search)
                Route.Library -> LibraryScreen()
                Route.Downloads -> DownloadsScreen()
                Route.Extensions -> ExtensionsScreen()
                Route.Settings -> SettingsScreen(vlcAvailable = vlc != null)
                is Route.Details -> DetailsScreen(route, store.get(route) { DetailsModel(route) })
                is Route.Player -> Unit
            }
        }
        val screen: @Composable () -> Unit = {
            keys += route.toString()
            saved.SaveableStateProvider(route.toString()) { screenFor(route) }
        }

        if (appLayout.compact) {
            // Phones: content on top, the tabs in a bottom bar, like the Android app
            Column(Modifier.fillMaxSize()) {
                Box(
                    Modifier.weight(1f).fillMaxWidth()
                        .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)),
                ) { screen() }
                BottomBar(nav)
            }
        } else {
            // Mac, tablets and TVs: a top bar. It floats over Home's banner, and title pages have
            // their own back button instead
            val bar = remember { TopBarState() }
            val overlay = route == Route.Home || route is Route.Section
            val showBar = route !is Route.Details
            // Remotes: down from the bar goes into the page (the bar is drawn over the page, and
            // focus search alone didn't cross into it)
            val page = remember { FocusRequester() }
            // TV: back on a page (after a title, the player or another tab), the remote starts in
            // the page where it was, not on the top bar's first tab
            if (com.lagradost.cloudstream4.desktop.Platform.isTv && route !is Route.Details) {
                LaunchedEffect(route) {
                    kotlinx.coroutines.delay(150)
                    runCatching { page.requestFocus() }
                }
            }
            Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                CompositionLocalProvider(LocalTopBarState provides bar) {
                    Box(
                        Modifier.fillMaxSize().padding(top = if (showBar && !overlay) TopBarHeight else 0.dp)
                            .focusRequester(page).focusGroup(),
                    ) { screen() }
                }
                if (showBar) {
                    TopBar(nav, solid = !overlay || bar.solid, modifier = Modifier.focusProperties { down = page }) {
                        when (route) {
                            Route.Home -> HomeProviderControls(store.home)
                            is Route.Section -> HomeProviderControls(store.home(route.section))
                            else -> Unit
                        }
                    }
                }
            }
        }
    }
}

/** The phone tabs. Extensions are reached from Settings, as in the Android app. */
@Composable
private fun BottomBar(nav: Navigator) {
    val downloads by DownloadManager.entries.collectAsState()
    val active = downloads.count { it.status == DownloadStatus.Downloading || it.status == DownloadStatus.Queued }
    val selected = when (nav.tab) {
        Route.Extensions -> Route.Settings
        // The media pages are reached from Home's chips
        is Route.Section -> Route.Home
        else -> nav.tab
    }
    NavigationBar {
        compactTabs.forEach { tab ->
            NavigationBarItem(
                selected = selected == tab.route,
                onClick = { nav.open(tab.route) },
                icon = {
                    if (tab.route == Route.Downloads && active > 0) {
                        BadgedBox(badge = { Badge { Text("$active") } }) { Icon(tab.icon, null) }
                    } else {
                        Icon(tab.icon, null)
                    }
                },
                label = { Text(tab.label, maxLines = 1) },
            )
        }
    }
}

private val compactTabs = listOf(
    Tab(Route.Home, "Home", Icons.Default.Home),
    Tab(Route.Search, "Search", Icons.Default.Search),
    Tab(Route.Library, "Library", Icons.Default.VideoLibrary),
    Tab(Route.Downloads, "Downloads", Icons.Default.Download),
    Tab(Route.Settings, "Settings", Icons.Default.Settings),
)

@Composable
fun rememberNavigator() = remember { Navigator() }

/** Brings a focused item into view with the smallest scroll, and none when it's already visible. */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
private object ScrollOnlyAsNeeded : BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
        val end = offset + size
        return when {
            offset >= 0f && end <= containerSize -> 0f
            // Bigger than the screen: line up its start
            size > containerSize -> offset
            offset < 0f -> offset
            else -> end - containerSize
        }
    }
}

/**
 * The focus look for Material controls on TVs: a ring at the edge with a thin inner edge of the
 * opposite colour, white on the dark theme and near-black on the light one, so it shows on any control.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
internal val TvRipple: androidx.compose.material3.RippleConfiguration
    @Composable get() {
        val light = isLightTheme
        val ring = if (light) androidx.compose.ui.graphics.Color(0xFF111111) else androidx.compose.ui.graphics.Color.White
        val edge = if (light) androidx.compose.ui.graphics.Color.White else androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.6f)
        return remember(light) {
            androidx.compose.material3.RippleConfiguration(
                focus = androidx.compose.material3.RippleConfiguration.Focus.InsetRing(outerStrokeColor = ring, innerStrokeColor = edge),
            )
        }
    }

/** The ring's shape for [TvRipple]: a 3-point white ring at the edge, a thin dark one inside it. */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
internal val TvRippleShape = androidx.compose.material3.RippleThemeConfiguration(
    androidx.compose.material3.RippleThemeConfiguration.Focus.InsetRing(0.dp, 3.dp, 3.dp, 1.5.dp),
)
