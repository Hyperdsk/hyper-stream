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
    fun pagesListTheirOwnKindWithoutAnimeMixedIn() {
        val movies = api("Movies Only", TvType.Movie, TvType.Torrent)
        val moviesAndSeries = api("Movies And Series", TvType.Movie, TvType.TvSeries)
        val everything = api("Everything", TvType.Movie, TvType.TvSeries, TvType.Anime)
        // Cartoons and anime films don't make an anime site a movie site
        val anime = api("Anime Site", TvType.Anime, TvType.AnimeMovie, TvType.Cartoon)
        val dramas = api("Dramas", TvType.AsianDrama, TvType.TvSeries)
        val searchOnly = api("No Home Page", TvType.TvSeries, mainPage = false)
        val all = listOf(movies, moviesAndSeries, everything, anime, dramas, searchOnly)

        // A movies-and-series site shows on both; one that mixes in anime shows on neither
        assertEquals(listOf("Movies Only", "Movies And Series"), HomeSection.Movies.providers(all).map { it.name })
        assertEquals(listOf("Movies And Series", "Dramas"), HomeSection.Shows.providers(all).map { it.name })
        assertEquals(listOf("Anime Site"), HomeSection.Anime.providers(all).map { it.name })

        assertEquals(listOf(HomeSection.Movies, HomeSection.Shows), availableSections(listOf(moviesAndSeries, everything)))
        // A site with everything fills no page
        assertEquals(emptyList(), availableSections(listOf(everything)))
        // An extension without a home page can't fill a page
        assertEquals(emptyList(), availableSections(listOf(searchOnly)))
        assertEquals(listOf(HomeSection.Anime), availableSections(listOf(everything, anime)))
    }
}
