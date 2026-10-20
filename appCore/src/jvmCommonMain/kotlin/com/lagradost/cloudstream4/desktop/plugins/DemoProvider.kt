package com.lagradost.cloudstream4.desktop.plugins

import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink

/**
 * Built-in provider with public-domain and Creative Commons open movies, served from public
 * test streams (Mux, Apple, Unified Streaming). Off by default. It exists so the player and
 * the whole browse, details and playback flow can be tried without any third-party extension.
 */
class DemoProvider : MainAPI() {
    override var name = "Demo (open movies)"
    override var mainUrl = "https://demo.cloudstream.invalid"
    override var lang = "en"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    override val mainPage = mainPageOf("$mainUrl/movies" to "Open movies", "$mainUrl/series" to "Series")

    private data class Title(
        val id: String,
        val name: String,
        val year: Int,
        val poster: String?,
        val plot: String,
        val links: List<Triple<String, String, ExtractorLinkType>>,
    )

    private val titles = listOf(
        Title(
            "bbb", "Big Buck Bunny", 2008,
            "https://upload.wikimedia.org/wikipedia/commons/c/c5/Big_buck_bunny_poster_big.jpg",
            "A giant rabbit takes on three bullying rodents. Blender Foundation open movie, CC BY 3.0.",
            listOf(
                Triple("Mux HLS", "https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8", ExtractorLinkType.M3U8),
                Triple("MP4 10s clip", "https://test-videos.co.uk/vids/bigbuckbunny/mp4/h264/720/Big_Buck_Bunny_720_10s_1MB.mp4", ExtractorLinkType.VIDEO),
            ),
        ),
        Title(
            "tos", "Tears of Steel", 2012,
            "https://mango.blender.org/wp-content/uploads/2013/05/01_thom_celia_bridge.jpg",
            "Warriors and scientists try to save the world from destructive robots. Blender Foundation open movie, CC BY 3.0.",
            listOf(
                Triple("Unified HLS", "https://demo.unified-streaming.com/k8s/features/stable/video/tears-of-steel/tears-of-steel.ism/.m3u8", ExtractorLinkType.M3U8),
                Triple("Unified DASH", "https://demo.unified-streaming.com/k8s/features/stable/video/tears-of-steel/tears-of-steel.ism/.mpd", ExtractorLinkType.DASH),
                Triple("Mux HLS", "https://test-streams.mux.dev/tos_ismc/main.m3u8", ExtractorLinkType.M3U8),
            ),
        ),
        Title(
            "bipbop", "Apple BipBop", 2016,
            null,
            "Apple's HLS test stream with several audio and subtitle tracks. Useful for testing track switching.",
            listOf(
                Triple("Apple HLS", "https://devstreaming-cdn.apple.com/videos/streaming/examples/img_bipbop_adv_example_fmp4/master.m3u8", ExtractorLinkType.M3U8),
            ),
        ),
    )

    private val seriesUrl get() = "$mainUrl/series/collection"

    private fun movieResponse(t: Title): SearchResponse =
        newMovieSearchResponse(t.name, "$mainUrl/movie/${t.id}", TvType.Movie) { posterUrl = t.poster; year = t.year }

    private fun seriesResponse(): SearchResponse =
        newTvSeriesSearchResponse("Open Movie Collection", seriesUrl, TvType.TvSeries) { posterUrl = titles.first().poster }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val list = if (request.data.endsWith("/series")) listOf(seriesResponse()) else titles.map(::movieResponse)
        return newHomePageResponse(request, list, hasNext = false)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val q = query.trim().lowercase()
        return titles.filter { q in it.name.lowercase() }.map(::movieResponse) +
            listOfNotNull(seriesResponse().takeIf { "collection".contains(q) || "open movie".contains(q) })
    }

    override suspend fun load(url: String): LoadResponse {
        if (url == seriesUrl) {
            val episodes = titles.mapIndexed { i, t ->
                newEpisode(t.id) {
                    name = t.name
                    season = 1
                    episode = i + 1
                    posterUrl = t.poster
                    description = t.plot
                }
            }
            return newTvSeriesLoadResponse("Open Movie Collection", url, TvType.TvSeries, episodes) {
                posterUrl = titles.first().poster
                backgroundPosterUrl = titles[1].poster
                plot = "The demo titles as episodes, for trying episode lists, next episode and resume."
                tags = listOf("Demo", "Open movies")
            }
        }
        val t = titles.first { url.endsWith("/${it.id}") }
        return newMovieLoadResponse(t.name, url, TvType.Movie, t.id) {
            posterUrl = t.poster
            backgroundPosterUrl = t.poster
            year = t.year
            plot = t.plot
            tags = listOf("Demo", "Open movie")
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val id = data.substringAfterLast('/')
        val t = titles.firstOrNull { it.id == id } ?: return false
        t.links.forEach { (label, link, type) ->
            callback(newExtractorLink(name, label, link, type) { quality = 720 })
        }
        return true
    }

    companion object {
        const val SOURCE = "builtin:demo"
        private var instance: DemoProvider? = null

        /** Adds or removes the demo provider. Returns true when the provider list changed. */
        @Synchronized
        fun setEnabled(enabled: Boolean): Boolean {
            if (enabled == (instance != null)) return false
            if (enabled) {
                val api = DemoProvider().apply { sourcePlugin = SOURCE }
                APIHolder.allProviders.add(api)
                APIHolder.addPluginMapping(api)
                instance = api
            } else {
                val api = instance ?: return false
                APIHolder.removePluginMapping(api)
                APIHolder.allProviders.withLock { APIHolder.allProviders.removeAll { it.sourcePlugin == SOURCE } }
                instance = null
            }
            return true
        }
    }
}
