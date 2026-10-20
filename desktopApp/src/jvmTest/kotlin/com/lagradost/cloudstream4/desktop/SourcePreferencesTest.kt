package com.lagradost.cloudstream4.desktop

import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream4.desktop.data.AppSettings
import com.lagradost.cloudstream4.desktop.ui.SourcePreferences
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SourcePreferencesTest {
    private fun link(source: String, quality: Int): ExtractorLink = runBlocking {
        newExtractorLink(source, source, "https://example.com/$source/$quality", ExtractorLinkType.VIDEO) { this.quality = quality }
    }

    private val links = listOf(
        link("MovieBlast", 1080), // 0
        link("FibWatch", 2160),   // 1
        link("MovieBox", 1080),   // 2
        link("Pixel", 720),       // 3
        link("Slowhost", 2160),   // 4
    )

    private fun order(settings: AppSettings) = SourcePreferences.order(links, links.indices.toList(), settings).map { links[it].source }

    @Test
    fun highestFirstPutsFourKOnTop() {
        val o = order(AppSettings())
        assertEquals(setOf("FibWatch", "Slowhost"), o.take(2).toSet())
        assertEquals("Pixel", o.last())
    }

    @Test
    fun chosenQualityThenNearestLowerThenHigher() {
        val o = order(AppSettings(preferredQuality = 1080))
        assertEquals(setOf("MovieBlast", "MovieBox"), o.take(2).toSet())
        assertEquals("Pixel", o[2])
        assertEquals(setOf("FibWatch", "Slowhost"), o.drop(3).toSet())
    }

    @Test
    fun starredServersAndAvoidedServers() {
        // Quality first: the star breaks the tie among 1080p
        assertEquals(listOf("MovieBox", "MovieBlast"), order(AppSettings(preferredQuality = 1080, preferredSources = listOf("MovieBox"))).take(2))
        // Servers first: the star wins over 4K
        assertEquals("MovieBox", order(AppSettings(preferredSources = listOf("MovieBox"), sourceOrder = "servers")).first())
        // Avoided goes last even when it's 4K
        assertEquals("Slowhost", order(AppSettings(avoidedSources = listOf("slowhost"))).last())
    }

    @Test
    fun matchesWhatTheUserIsWaitingFor() {
        assertTrue(SourcePreferences.matches(links[1], AppSettings()))          // 4K under "highest"
        assertFalse(SourcePreferences.matches(links[0], AppSettings()))         // 1080p isn't
        assertTrue(SourcePreferences.matches(links[2], AppSettings(preferredSources = listOf("moviebox"))))
        assertFalse(SourcePreferences.matches(links[4], AppSettings(avoidedSources = listOf("Slowhost"))))
    }
}
