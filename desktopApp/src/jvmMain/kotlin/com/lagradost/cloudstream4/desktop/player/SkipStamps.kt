package com.lagradost.cloudstream4.desktop.player

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.AnimeLoadResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.LoadResponse.Companion.getImdbId
import com.lagradost.cloudstream3.LoadResponse.Companion.getMalId
import com.lagradost.cloudstream3.LoadResponse.Companion.getTMDbId
import com.lagradost.cloudstream3.LoadResponse.Companion.isMovie
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream4.desktop.data.jsonMapper
import com.lagradost.cloudstream4.desktop.ui.PlayableEpisode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap

/** Same kinds of sections as the Android app's videoskip package. */
enum class SkipType(val label: String, val isIntro: Boolean) {
    Opening("Opening", true),
    MixedOpening("Opening", true),
    Intro("Intro", true),
    Recap("Recap", true),
    Ending("Ending", false),
    MixedEnding("Ending", false),
    Credits("Credits", false),
    Preview("Preview", false),
}

data class SkipStamp(val type: SkipType, val startMs: Long, val endMs: Long)

data class VideoSkipStamp(
    val stamp: SkipStamp,
    /** An ending that runs to the end of the video: the button goes to the next episode instead. */
    val skipToNextEpisode: Boolean,
    val source: String,
) {
    val buttonText: String get() = if (skipToNextEpisode) "Next Episode" else "Skip ${stamp.type.label}"
    operator fun contains(positionMs: Long) = positionMs >= stamp.startMs && positionMs < stamp.endMs
}

/**
 * Finds intro, recap and credit sections for an episode. Port of the Android app's SkipAPI with
 * the same free sources in the same order: AniSkip (anime, by MyAnimeList id), TheIntroDB (by TMDB
 * or IMDb id) and IntroDB (series, by IMDb id). The first source with results wins.
 * Anime-Skip is left out because it needs an account.
 */
object SkipStamps {
    private abstract class Source(val name: String, val types: Set<TvType>) {
        abstract suspend fun stamps(data: LoadResponse, episode: PlayableEpisode, durationMs: Long): List<SkipStamp>?
    }

    private val sources = listOf(AniSkip, TheIntroDb, IntroDb)
    private val cache = ConcurrentHashMap<String, List<VideoSkipStamp>>()

    suspend fun find(
        data: LoadResponse,
        episode: PlayableEpisode,
        durationMs: Long,
        hasNextEpisode: Boolean,
    ): List<VideoSkipStamp> = withContext(Dispatchers.IO) {
        val key = "${data.apiName}|${episode.data}"
        cache[key]?.let { return@withContext it }
        for (source in sources) {
            if (data.type !in source.types) continue
            val stamps = try {
                // A slow source must not hold up the others
                withTimeoutOrNull(10_000) { source.stamps(data, episode, durationMs) }
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                System.err.println("Skip times from ${source.name} failed: $t")
                null
            }
            if (stamps.isNullOrEmpty()) continue
            return@withContext stamps
                .filter { it.endMs > it.startMs }
                .sortedBy { it.startMs }
                .map { VideoSkipStamp(it, hasNextEpisode && durationMs - it.endMs < 20_000L, source.name) }
                .also { cache[key] = it }
        }
        emptyList()
    }

    private suspend inline fun <reified T> get(url: String): T = jsonMapper.readValue(app.get(url).text, T::class.java)

    private object AniSkip : Source("AniSkip", setOf(TvType.Anime, TvType.OVA)) {
        data class Response(@JsonProperty("found") val found: Boolean = false, @JsonProperty("results") val results: List<Stamp>? = null)
        data class Stamp(@JsonProperty("interval") val interval: Interval, @JsonProperty("skipType") val skipType: String)
        data class Interval(@JsonProperty("startTime") val startTime: Double, @JsonProperty("endTime") val endTime: Double)

        override suspend fun stamps(data: LoadResponse, episode: PlayableEpisode, durationMs: Long): List<SkipStamp>? {
            if (data !is AnimeLoadResponse) return null
            val malId = data.getMalId()?.toIntOrNull() ?: return null
            val number = episode.episode ?: return null
            val url = "https://api.aniskip.com/v2/skip-times/$malId/$number" +
                "?types[]=ed&types[]=mixed-ed&types[]=mixed-op&types[]=op&types[]=recap&episodeLength=${durationMs / 1000L}"
            return get<Response>(url).results?.mapNotNull { stamp ->
                val type = when (stamp.skipType) {
                    "op" -> SkipType.Opening
                    "ed" -> SkipType.Ending
                    "recap" -> SkipType.Recap
                    "mixed-op" -> SkipType.MixedOpening
                    "mixed-ed" -> SkipType.MixedEnding
                    else -> return@mapNotNull null
                }
                SkipStamp(type, (stamp.interval.startTime * 1000).toLong(), (stamp.interval.endTime * 1000).toLong())
            }
        }
    }

    /** https://theintrodb.org/docs */
    private object TheIntroDb : Source(
        "TheIntroDB",
        setOf(TvType.TvSeries, TvType.Cartoon, TvType.Anime, TvType.Movie, TvType.AsianDrama),
    ) {
        data class Root(
            @JsonProperty("intro") val intro: List<Stamp> = emptyList(),
            @JsonProperty("recap") val recap: List<Stamp> = emptyList(),
            @JsonProperty("credits") val credits: List<Stamp> = emptyList(),
            @JsonProperty("preview") val preview: List<Stamp> = emptyList(),
        )
        data class Stamp(@JsonProperty("start_ms") val startMs: Long? = null, @JsonProperty("end_ms") val endMs: Long? = null)

        override suspend fun stamps(data: LoadResponse, episode: PlayableEpisode, durationMs: Long): List<SkipStamp>? {
            val id = data.getTMDbId()?.let { "tmdb_id=$it" } ?: data.getImdbId()?.let { "imdb_id=$it" } ?: return null
            val url = if (data.isMovie()) {
                "https://api.theintrodb.org/v4/media?$id"
            } else {
                val season = episode.season ?: return null
                "https://api.theintrodb.org/v4/media?$id&season=$season&episode=${episode.episode ?: return null}"
            }
            val root = get<Root>(url)
            return listOf(
                root.intro to SkipType.Intro,
                root.credits to SkipType.Credits,
                root.recap to SkipType.Recap,
                root.preview to SkipType.Preview,
            ).flatMap { (list, type) -> list.map { SkipStamp(type, it.startMs ?: 0L, it.endMs ?: durationMs) } }
        }
    }

    private object IntroDb : Source("IntroDB", setOf(TvType.TvSeries, TvType.AsianDrama)) {
        data class Response(
            @JsonProperty("intro") val intro: Segment? = null,
            @JsonProperty("recap") val recap: Segment? = null,
            @JsonProperty("outro") val outro: Segment? = null,
        )
        data class Segment(@JsonProperty("start_ms") val startMs: Long? = null, @JsonProperty("end_ms") val endMs: Long? = null)

        override suspend fun stamps(data: LoadResponse, episode: PlayableEpisode, durationMs: Long): List<SkipStamp>? {
            val season = episode.season ?: return null
            val number = episode.episode ?: return null
            val imdbId = data.getImdbId() ?: return null
            val r = get<Response>("https://api.introdb.app/segments?imdb_id=$imdbId&season=$season&episode=$number")
            fun Segment?.stamp(type: SkipType) = this?.let { s -> SkipStamp(type, s.startMs ?: return@let null, s.endMs ?: return@let null) }
            return listOfNotNull(r.intro.stamp(SkipType.Opening), r.recap.stamp(SkipType.Recap), r.outro.stamp(SkipType.Ending))
        }
    }
}
