package com.lagradost.cloudstream4.desktop.ui

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
    data object Search : Route
    data object Library : Route
    data object Downloads : Route
    data object Extensions : Route
    data object Settings : Route
    data class Details(val apiName: String, val url: String, val name: String) : Route
    data class Player(val request: PlayRequest) : Route

    val isTab: Boolean get() = this is Home || this is Search || this is Library || this is Downloads || this is Extensions || this is Settings
}

/** A back stack per tab, like the Android app's bottom navigation. */
class Navigator {
    val stack = mutableStateListOf<Route>(Route.Home)
    val current: Route get() = stack.last()

    /** The tab the current screen belongs to. */
    val tab: Route get() = stack.lastOrNull { it.isTab } ?: Route.Home

    fun open(route: Route) {
        if (route.isTab) {
            stack.clear()
            stack.add(route)
        } else {
            stack.add(route)
        }
    }

    fun back(): Boolean {
        if (stack.size <= 1) return false
        stack.removeAt(stack.lastIndex)
        return true
    }
}

val LocalNavigator = staticCompositionLocalOf<Navigator> { error("No navigator") }
