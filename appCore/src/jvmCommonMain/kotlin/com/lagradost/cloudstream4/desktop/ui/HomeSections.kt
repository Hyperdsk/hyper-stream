package com.lagradost.cloudstream4.desktop.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream4.desktop.plugins.ExtensionManager

/**
 * Pages like Home for one kind of media, each showing the home page of an extension that has it.
 * Grouped like CloudStream's own filters.
 */
enum class HomeSection(val label: String, val types: Set<TvType>) {
    Movies("Movies", setOf(TvType.Movie)),
    Shows("TV Shows", setOf(TvType.TvSeries, TvType.AsianDrama)),
    Anime("Anime", setOf(TvType.Anime, TvType.OVA, TvType.AnimeMovie)),
    ;

    /** Whether [api] has this kind of media at all. */
    fun fits(api: MainAPI) = api.supportedTypes.any { it in types }

    /** Whether [api] has only this kind, such as an anime site under Anime: it comes first. */
    fun dedicated(api: MainAPI) = api.supportedTypes.isNotEmpty() && api.supportedTypes.all { it in types }

    /** The extensions to offer on this page: those with a home page and this kind, dedicated ones first. */
    fun providers(apis: List<MainAPI>): List<MainAPI> =
        apis.filter { it.hasMainPage && fits(it) }.sortedByDescending { dedicated(it) }
}

/** The pages some installed extension can fill; the others aren't shown. */
fun availableSections(apis: List<MainAPI>): List<HomeSection> = HomeSection.entries.filter { it.providers(apis).isNotEmpty() }

@Composable
fun rememberAvailableSections(): List<HomeSection> {
    val extensions by ExtensionManager.state.collectAsState()
    return remember(extensions.providersVersion) { availableSections(ExtensionManager.providers()) }
}
