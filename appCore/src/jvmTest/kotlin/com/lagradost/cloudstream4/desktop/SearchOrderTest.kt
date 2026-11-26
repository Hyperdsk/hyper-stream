package com.lagradost.cloudstream4.desktop

import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream4.desktop.ui.ProviderResults
import com.lagradost.cloudstream4.desktop.ui.orderRows
import com.lagradost.cloudstream4.desktop.ui.sameTitle
import com.lagradost.cloudstream4.desktop.ui.yearOrNull
import kotlinx.collections.immutable.persistentListOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Search rows: ranked while results come in, then kept in place once the user moves into them. */
class SearchOrderTest {
    private val api = object : MainAPI() {
        override var name = "OrderTest"
        override var mainUrl = "https://example.com"
    }
    private val item = api.newMovieSearchResponse("Dune", "https://example.com/dune", TvType.Movie)

    private fun row(id: Int, best: Int, arrival: Int, loading: Boolean = false, found: Boolean = true) =
        ProviderResults("P$id", id, if (found) persistentListOf(item) else persistentListOf(), loading, best = best, arrival = arrival)

    @Test
    fun ranksByBestMatchUntilFrozen() {
        val rows = listOf(row(0, 40, 1), row(1, 100, 2), row(2, 85, 3), row(3, 0, 0, loading = true), row(4, 0, 4, found = false))
        val order = orderRows(rows, null)
        assertEquals(listOf(1, 2), order.relevant.map { it.id })
        assertEquals(listOf(0), order.others.map { it.id })
        assertTrue(order.later.isEmpty())
        // Still loading or found nothing: no row
        assertEquals(listOf(1, 2, 0), order.all.map { it.id })
    }

    @Test
    fun frozenRowsStayAndLaterOnesFollowInArrivalOrder() {
        val frozen = listOf(1, 2, 0, 9)
        // A better match arriving later doesn't jump ahead; two late ones keep their arrival order
        val rows = listOf(row(0, 40, 1), row(1, 100, 2), row(2, 85, 3), row(5, 100, 6), row(6, 90, 5))
        val order = orderRows(rows, frozen)
        assertEquals(listOf(1, 2), order.relevant.map { it.id })
        assertEquals(listOf(0), order.others.map { it.id })
        assertEquals(listOf(6, 5), order.later.map { it.id })
        // Id 9 is no longer there (a new search, or it errored): skipped
        assertEquals(listOf(1, 2, 0, 6, 5), order.all.map { it.id })
    }

    @Test
    fun sameTitleNeedsTheSameNameAndYear() {
        assertTrue(sameTitle("Dune", 2021, "dune", 2021))
        assertTrue(sameTitle("Dune: Part Two", null, "Dune - Part Two", 2024))
        assertTrue(sameTitle("Dune (2021)", null, "Dune", 2021))
        assertFalse(sameTitle("Dune", 2021, "Dune", 1984))
        assertFalse(sameTitle("Dune", null, "Dune: Part Two", null))
        assertFalse(sameTitle("", null, "", null))
        // A title that is only a year isn't cut down to nothing
        assertTrue(sameTitle("1917", 2019, "1917", null))
    }

    @Test
    fun readsTheYearOfEachKindOfResult() {
        assertEquals(2021, api.newMovieSearchResponse("Dune", "u", TvType.Movie) { year = 2021 }.yearOrNull)
        assertEquals(2008, api.newTvSeriesSearchResponse("Breaking Bad", "u", TvType.TvSeries) { year = 2008 }.yearOrNull)
    }
}
