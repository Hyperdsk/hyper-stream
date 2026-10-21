package com.lagradost.cloudstream4.desktop.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream4.desktop.plugins.ExtensionManager

/**
 * Pages like Home for one kind of media, each showing the home page of an extension made for it.
 * Grouped like CloudStream's own filters.
 */
enum class HomeSection(val label: String, val types: Set<TvType>) {
    Movies("Movies", setOf(TvType.Movie)),
    Shows("TV Shows", setOf(TvType.TvSeries, TvType.AsianDrama)),
    Anime("Anime", setOf(TvType.Anime, TvType.OVA, TvType.AnimeMovie)),
    ;

    /**
     * Whether [api] belongs on this page. Anime takes sites with anime only; Movies and TV Shows take
     * sites with that kind and no anime, so a movies-and-series site shows on both. Sites that mix in
     * anime show on none. Other kinds they list, such as cartoons, don't count.
     */
    fun owns(api: MainAPI): Boolean {
        val kinds = entries.filter { section -> api.supportedTypes.any { it in section.types } }
        return if (this == Anime) kinds == listOf(Anime) else this in kinds && Anime !in kinds
    }

    /** The extensions this page offers: those that belong here and have a home page. */
    fun providers(apis: List<MainAPI>): List<MainAPI> = apis.filter { it.hasMainPage && owns(it) }
}

/** The pages some installed extension can fill; the others aren't shown. */
fun availableSections(apis: List<MainAPI>): List<HomeSection> = HomeSection.entries.filter { it.providers(apis).isNotEmpty() }

@Composable
fun rememberAvailableSections(): List<HomeSection> {
    val extensions by ExtensionManager.state.collectAsState()
    return remember(extensions.providersVersion) { availableSections(ExtensionManager.providers()) }
}
