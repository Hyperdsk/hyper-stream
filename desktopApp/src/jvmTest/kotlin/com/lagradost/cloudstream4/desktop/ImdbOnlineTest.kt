package com.lagradost.cloudstream4.desktop

import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream4.desktop.meta.Imdb
import com.lagradost.cloudstream4.desktop.meta.ReviewSort
import kotlinx.coroutines.runBlocking
import java.net.URI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** IMDb matching and details against the live service. Skipped when offline. */
class ImdbOnlineTest {
    private fun online() = runCatching {
        URI("https://v3.sg.media-imdb.com/suggestion/x/test.json").toURL().openConnection().apply { connectTimeout = 3000 }.getInputStream().close()
    }.isSuccess

    @Test
    fun matchesTitlesAndReadsDetails() = runBlocking {
        if (!online()) return@runBlocking
        // Same name, different years: the year decides
        assertEquals("tt27419420", Imdb.findId("Street Fighter", 2026, TvType.Movie))
        assertEquals("tt0111301", Imdb.findId("Street Fighter", 1994, TvType.Movie))

        val movie = assertNotNull(Imdb.fetch("tt27419420"))
        assertTrue((movie.rating ?: 0.0) > 0)
        assertTrue(movie.directors.isNotEmpty() && movie.stars.isNotEmpty())
        println("Movie: ${movie.title} ${movie.year} ★${movie.rating} (${movie.votes}) MS ${movie.metascore} ${movie.runtimeMinutes}m ${movie.certificate} dir=${movie.directors.map { it.name }}")

        // A series: Blue Box (anime, 2024)
        val seriesId = assertNotNull(Imdb.findId("Blue Box", 2024, TvType.Anime))
        val series = assertNotNull(Imdb.fetch(seriesId))
        println("Series: ${series.title} ${series.year}-${series.endYear} ★${series.rating} seasons=${series.seasons} episodes=${series.episodes} creators=${series.creators.map { it.name }}")
        assertTrue(series.type?.contains("Series") == true, "type was ${series.type}")

        // Every score's votes, and reviews in pages and sort orders
        val bttf = assertNotNull(Imdb.fetch("tt0096874"))
        assertEquals(10, bttf.ratingHistogram.size)
        assertTrue(bttf.ratingHistogram.sum() > 100_000)
        val first = assertNotNull(Imdb.reviews("tt0096874", ReviewSort.Helpful, count = 5))
        assertEquals(5, first.reviews.size)
        val second = assertNotNull(Imdb.reviews("tt0096874", ReviewSort.Helpful, after = first.nextCursor, count = 5))
        assertTrue(second.reviews.none { r -> first.reviews.any { it.id == r.id } }, "second page repeats the first")
        val lowest = assertNotNull(Imdb.reviews("tt0096874", ReviewSort.Lowest, count = 3))
        assertTrue(lowest.reviews.all { (it.rating ?: 0) <= 3 }, "lowest rated: ${lowest.reviews.map { it.rating }}")
        println("Reviews: ${first.total} total, histogram ${bttf.ratingHistogram}, top '${first.reviews.first().title}' by ${first.reviews.first().author}")
    }
}
