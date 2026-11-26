package com.lagradost.cloudstream4.desktop

import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream4.desktop.meta.Imdb
import kotlinx.coroutines.runBlocking
import java.net.URI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** IMDb matching and details against the live service. Skipped when offline, and on CI, where IMDb often refuses data-center addresses. */
class ImdbOnlineTest {
    private fun online() = System.getenv("CI") == null && runCatching {
        URI("https://v3.sg.media-imdb.com/suggestion/x/test.json").toURL().openConnection().apply { connectTimeout = 3000 }.getInputStream().close()
    }.isSuccess

    @Test
    fun matchesTitlesAndReadsDetails() = runBlocking {
        // Reported as skipped, not passed, when it can't run (offline, or on CI)
        org.junit.Assume.assumeTrue("IMDb not reachable, or running on CI", online())
        // Same name, different years: the year decides
        assertEquals("tt27419420", Imdb.findId("Street Fighter", 2026, TvType.Movie))
        assertEquals("tt0111301", Imdb.findId("Street Fighter", 1994, TvType.Movie))

        // Details from Cinemeta (IMDb's own API refuses the app)
        val movie = assertNotNull(Imdb.fetch("tt27419420"))
        assertTrue((movie.rating ?: 0.0) > 0)
        assertTrue(movie.directors.isNotEmpty() && movie.stars.isNotEmpty())
        assertEquals("Movie", movie.type)
        println("Movie: ${movie.title} ${movie.year} ★${movie.rating} ${movie.runtimeMinutes}m dir=${movie.directors.map { it.name }} released=${movie.released}")

        // A series asked for as a movie: IMDb says it's a series, so Cinemeta's series entry is used
        // (its movie entry for this id is another title)
        val bb = assertNotNull(Imdb.fetch("tt0903747", series = false))
        assertEquals("Breaking Bad", bb.title)
        assertEquals("TV Series", bb.type)
        assertEquals(2008, bb.year)
        assertEquals(2013, bb.endYear)
        assertEquals(5, bb.seasons)
        assertTrue((bb.episodes ?: 0) >= 60, "episodes ${bb.episodes}")

        // A series found by name: Blue Box (anime, 2024)
        val seriesId = assertNotNull(Imdb.findId("Blue Box", 2024, TvType.Anime))
        val series = assertNotNull(Imdb.fetch(seriesId, series = true))
        println("Series: ${series.title} ${series.year}-${series.endYear} ★${series.rating} seasons=${series.seasons} episodes=${series.episodes}")
        assertTrue(series.type?.contains("Series") == true, "type was ${series.type}")

        // An id nobody knows: nothing, rather than an empty page
        assertEquals(null, Imdb.fetch("tt0000000"))
    }
}
