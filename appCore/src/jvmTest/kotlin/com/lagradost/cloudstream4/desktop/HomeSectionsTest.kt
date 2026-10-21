package com.lagradost.cloudstream4.desktop

import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream4.desktop.ui.HomeSection
import com.lagradost.cloudstream4.desktop.ui.availableSections
import kotlin.test.Test
import kotlin.test.assertEquals

class HomeSectionsTest {
    private fun api(name: String, vararg types: TvType, mainPage: Boolean = true) = object : MainAPI() {
        override var name = name
        override val supportedTypes = types.toSet()
        override val hasMainPage = mainPage
    }

    @Test
    fun pagesShowOnlyWhenAnExtensionHasThatKind() {
        val movies = api("Movies Only", TvType.Movie)
        val mixed = api("Everything", TvType.Movie, TvType.TvSeries, TvType.Anime)
        val anime = api("Anime Site", TvType.Anime, TvType.OVA)
        val searchOnly = api("No Home Page", TvType.TvSeries, mainPage = false)

        assertEquals(listOf(HomeSection.Movies), availableSections(listOf(movies)))
        // An extension without a home page can't fill a page
        assertEquals(emptyList(), availableSections(listOf(searchOnly)))
        assertEquals(HomeSection.entries, availableSections(listOf(mixed)))

        // Extensions with only that kind come first, then mixed ones
        assertEquals(listOf("Anime Site", "Everything"), HomeSection.Anime.providers(listOf(mixed, anime, movies)).map { it.name })
        assertEquals(listOf("Movies Only", "Everything"), HomeSection.Movies.providers(listOf(mixed, anime, movies)).map { it.name })
        assertEquals(listOf("Everything"), HomeSection.Shows.providers(listOf(mixed, anime, movies, searchOnly)).map { it.name })
    }
}
