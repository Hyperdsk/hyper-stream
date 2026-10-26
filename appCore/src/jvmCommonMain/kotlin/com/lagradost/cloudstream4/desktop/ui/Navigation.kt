package com.lagradost.cloudstream4.desktop.ui

import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.staticCompositionLocalOf

/** One playable item, a movie or an episode. [data] is what the provider's loadLinks expects. */
data class PlayableEpisode(
    val data: String,
    val name: String?,
    val season: Int?,
    val episode: Int?,
) {
    val label: String
        get() = listOfNotNull(
            season?.let { "S$it" }?.let { s -> episode?.let { "${s}:E$it" } ?: s } ?: episode?.let { "Episode $it" },
            name,
        ).joinToString(" · ").ifBlank { "Play" }
}

data class PlayRequest(
    val apiName: String,
    val parentUrl: String,
    val title: String,
    val posterUrl: String?,
    val posterHeaders: Map<String, String>?,
    val episodes: List<PlayableEpisode>,
    val index: Int,
    val startMs: Long = 0,
    /** The show as loaded on its page, for its ids (skip times). Loaded later when missing. */
    val loadResponse: com.lagradost.cloudstream3.LoadResponse? = null,
)

sealed interface Route {
    data object Home : Route
    /** Home for one kind of media: Movies, TV Shows or Anime. */
    data class Section(val section: HomeSection) : Route
    data object Search : Route
    data object Library : Route
    data object Downloads : Route
    data object Extensions : Route
    data object Settings : Route
    data class Details(val apiName: String, val url: String, val name: String) : Route
    data class Player(val request: PlayRequest) : Route

    val isTab: Boolean get() = this is Home || this is Section || this is Search || this is Library || this is Downloads || this is Extensions || this is Settings
}

/**
 * A back stack per tab, like the Android app's bottom navigation: switching tabs keeps each tab's
 * screens (a title opened from Home is still there after a visit to Search), choosing the tab
 * you're on returns to its top, and Back from a tab's first screen goes to the tab used before.
 */
class Navigator {
    // Each tab's stack is made when the tab is first opened, never while a screen reads it
    private val stacks = mutableStateMapOf<Route, SnapshotStateList<Route>>(Route.Home to mutableStateListOf(Route.Home))
    /** Tabs visited before the current one, most recent last, for Back across tabs. */
    private val tabHistory = mutableStateListOf<Route>()

    /** The tab the current screen belongs to. */
    var tab: Route by mutableStateOf(Route.Home)
        private set

    /** The current tab's screens, its own page first. */
    val stack: SnapshotStateList<Route> get() = stacks.getValue(tab)
    val current: Route get() = stack.last()

    /** Every screen kept on any tab, for keeping their state and models. */
    val allRoutes: List<Route> get() = stacks.values.flatMap { it.toList() }

    fun open(route: Route) {
        if (!route.isTab) {
            stack.add(route)
            return
        }
        if (route == tab) {
            // The tab you're on: back to its top
            stack.removeRange(1, stack.size)
            return
        }
        tabHistory.remove(route)
        tabHistory.add(tab)
        tab = route
        stacks.getOrPut(route) { mutableStateListOf(route) }
    }

    fun back(): Boolean {
        if (stack.size > 1) {
            stack.removeAt(stack.lastIndex)
            return true
        }
        val previous = tabHistory.removeLastOrNull() ?: return false
        tab = previous
        return true
    }
}

val LocalNavigator = staticCompositionLocalOf<Navigator> { error("No navigator") }
