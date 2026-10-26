package com.lagradost.cloudstream4.desktop

import com.lagradost.cloudstream3.LoadResponse.Companion.addImdbId
import com.lagradost.cloudstream3.LoadResponse.Companion.addMalId
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.DubStatus
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.newAnimeLoadResponse
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.addEpisodes
import com.lagradost.cloudstream4.desktop.player.SkipStamps
import com.lagradost.cloudstream4.desktop.player.SkipType
import com.lagradost.cloudstream4.desktop.ui.PlayableEpisode
import kotlinx.coroutines.runBlocking
import java.net.URI
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Looks up real skip times, like the Android app. Skipped when the services can't be reached.
 * One Piece episode 1000 on AniSkip, and Breaking Bad S01E02 by IMDb id.
 */
class SkipStampsOnlineTest {
    // Not on CI: the results depend on what the online databases hold that day
    private fun online() = System.getenv("CI") == null && runCatching {
        URI("https://api.aniskip.com/").toURL().openConnection().apply { connectTimeout = 3000 }.getInputStream().close()
    }.isSuccess || runCatching {
        URI("https://api.introdb.app/").toURL().openConnection().apply { connectTimeout = 3000 }.connect()
    }.isSuccess

    private val api = object : MainAPI() {
        override var name = "SkipTest"
        override var mainUrl = "https://example.com"
    }

    @Test
    fun findsAnimeOpeningAndSeriesIntro() = runBlocking {
        LibrarySetup.init()
        org.junit.Assume.assumeTrue("skip services not reachable (or on CI)", online())
        val anime = api.newAnimeLoadResponse("One Piece", "https://example.com/op", TvType.Anime) {
            addMalId(21)
            addEpisodes(DubStatus.Subbed, listOf(api.newEpisode("ep1000") { episode = 1000 }))
        }
        val op = SkipStamps.find(anime, PlayableEpisode("ep1000", null, null, 1000), 1_430_000, hasNextEpisode = true)
        println("AniSkip: $op")
        assertTrue(op.any { it.stamp.type == SkipType.Opening && it.stamp.endMs > it.stamp.startMs && it.source == "AniSkip" })

        val series = api.newTvSeriesLoadResponse("Breaking Bad", "https://example.com/bb", TvType.TvSeries, emptyList()) {
            addImdbId("tt0903747")
        }
        val intro = SkipStamps.find(series, PlayableEpisode("bb-1-2", null, 1, 2), 2_900_000, hasNextEpisode = true)
        println("Series: $intro")
        // The databases change: any section found (intro or credits) shows the lookup works
        assertTrue(intro.isNotEmpty(), "expected skip times, got $intro")
        // Credits running to the end become "Next Episode"
        assertTrue(intro.filter { !it.stamp.type.isIntro && 2_900_000 - it.stamp.endMs < 20_000 }.all { it.skipToNextEpisode })

        // Most extensions give no ids: the title is looked up instead (IMDb for series, AniList for anime)
        System.setProperty("cloudstream.dataDir", java.nio.file.Files.createTempDirectory("cs-skip").toString())
        val noIds = api.newTvSeriesLoadResponse("Breaking Bad", "https://example.com/bb-noid", TvType.TvSeries, emptyList()) { year = 2008 }
        val found = SkipStamps.find(noIds, PlayableEpisode("bb-noid-1-2", null, 1, 2), 2_900_000, hasNextEpisode = true)
        println("Series without ids: $found")
        assertTrue(found.isNotEmpty(), "expected skip times from the IMDb match, got $found")

        val animeNoIds = api.newAnimeLoadResponse("One Piece", "https://example.com/op-noid", TvType.Anime) { year = 1999 }
        val anime2 = SkipStamps.find(animeNoIds, PlayableEpisode("op-noid-1000", null, null, 1000), 1_430_000, hasNextEpisode = true)
        println("Anime without ids: $anime2")
        assertTrue(anime2.any { it.stamp.type == SkipType.Opening && it.source == "AniSkip" }, "expected AniSkip's opening via AniList, got $anime2")
    }
}
