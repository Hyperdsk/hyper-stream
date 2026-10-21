package com.lagradost.cloudstream4.desktop

import com.lagradost.cloudstream4.desktop.ui.RELEVANT
import com.lagradost.cloudstream4.desktop.ui.searchRelevance
import kotlin.test.Test
import kotlin.test.assertTrue

class SearchRelevanceTest {
    private val query = "jawani phir nahi ani"

    @Test
    fun closeTitlesComeFirstAndUnrelatedOnesLast() {
        val exact = searchRelevance(query, "Jawani Phir Nahi Ani")
        val sequel = searchRelevance(query, "Jawani Phir Nahi Ani 2")
        val misspelt = searchRelevance(query, "Jawaani Phir Nahin Aani")
        val unrelated = searchRelevance(query, "BLUE LOCK THE MOVIE -EPISODE NAGI-")
        println("exact=$exact sequel=$sequel misspelt=$misspelt unrelated=$unrelated")
        assertTrue(exact > sequel && sequel > misspelt && misspelt > unrelated)
        assertTrue(misspelt >= RELEVANT, "a near spelling still counts as a match")
        assertTrue(unrelated < RELEVANT, "an unrelated title goes under Other results")

        // Searching part of a title
        assertTrue(searchRelevance("dune", "Dune: Part Two") >= RELEVANT)
        assertTrue(searchRelevance("spider", "Spider-Man: No Way Home") >= RELEVANT)
        assertTrue(searchRelevance("dune", "Dune") > searchRelevance("dune", "Dune: Part Two"))
        assertTrue(searchRelevance("dune", "Dunkirk") < RELEVANT)
    }
}
