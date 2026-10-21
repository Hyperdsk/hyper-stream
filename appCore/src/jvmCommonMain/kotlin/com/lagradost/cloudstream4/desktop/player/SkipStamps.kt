package com.lagradost.cloudstream4.desktop.player

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.LoadResponse.Companion.getImdbId
import com.lagradost.cloudstream3.LoadResponse.Companion.getMalId
import com.lagradost.cloudstream3.LoadResponse.Companion.getTMDbId
import com.lagradost.cloudstream3.LoadResponse.Companion.isMovie
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream4.desktop.data.jsonMapper
import com.lagradost.cloudstream4.desktop.meta.Imdb
import com.lagradost.cloudstream4.desktop.ui.PlayableEpisode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
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
 *
 * Unlike the Android app, a title without ids still gets skip times: most extensions give none, so
 * the IMDb id comes from the IMDb match the details page uses, and an anime's MyAnimeList id from
 * an AniList (or Kitsu) search by title.
 */
object SkipStamps {
    private abstract class Source(val name: String, val types: Set<TvType>) {
        abstract suspend fun stamps(data: LoadResponse, ids: Ids, episode: PlayableEpisode, durationMs: Long): List<SkipStamp>?
    }

    /** The title's ids, from the extension or looked up the first time a source asks. */
    private class Ids(private val data: LoadResponse, private val season: Int?) {
        private var mal: Int? = null
        private var malDone = false
        private var imdb: String? = null
        private var imdbDone = false

        val tmdb: String? get() = runCatching { data.getTMDbId() }.getOrNull()?.takeIf { it.isNotBlank() }

        suspend fun mal(): Int? {
            if (!malDone) {
                malDone = true
                mal = runCatching { data.getMalId() }.getOrNull()?.toIntOrNull()
                    // Each anime season has its own MyAnimeList entry, so a title search only fits the first
                    ?: if ((season ?: 1) == 1) malIdByTitle(data.name, data.year) else null
            }
            return mal
        }

        suspend fun imdb(): String? {
            if (!imdbDone) {
                imdbDone = true
                imdb = runCatching { data.getImdbId() }.getOrNull()?.takeIf { it.startsWith("tt") }
                    ?: runCatching { Imdb.forTitle(data)?.id }.getOrNull()
            }
            return imdb
        }
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
        val ids = Ids(data, episode.season)
        for (source in sources) {
            if (data.type !in source.types) continue
            val stamps = try {
                // A slow source must not hold up the others
                withTimeoutOrNull(10_000) { source.stamps(data, ids, episode, durationMs) }
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

    private fun normalize(title: String) = title.lowercase().replace(Regex("[^a-z0-9]+"), " ").trim()

    /** The MyAnimeList id of the anime called [name]: from AniList, or Kitsu when AniList is down or has no match. */
    private suspend fun malIdByTitle(name: String, year: Int?): Int? =
        runCatching { aniListMalId(name, year) }.getOrNull() ?: runCatching { kitsuMalId(name, year) }.getOrNull()

    private fun yearFits(year: Int?, other: Int?) = year == null || other == null || kotlin.math.abs(year - other) <= 1

    /** From AniList's free API, when a title matches exactly. */
    private suspend fun aniListMalId(name: String, year: Int?): Int? {
        val query = "query(\$s: String) { Page(perPage: 5) { media(search: \$s, type: ANIME) { idMal seasonYear title { romaji english } synonyms } } }"
        val body = jsonMapper.writeValueAsString(mapOf("query" to query, "variables" to mapOf("s" to name)))
        val json = jsonMapper.readTree(
            app.post(
                "https://graphql.anilist.co",
                requestBody = body.toRequestBody("application/json".toMediaType()),
            ).text,
        )
        val target = normalize(name)
        return json.path("data").path("Page").path("media").firstOrNull { m ->
            val titles = listOf(m.path("title").path("romaji").asText(), m.path("title").path("english").asText()) + m.path("synonyms").map { it.asText() }
            val y = m.path("seasonYear").takeIf { it.isNumber }?.asInt()
            titles.any { normalize(it) == target } && yearFits(year, y)
        }?.path("idMal")?.takeIf { it.isNumber }?.asInt()
    }

    /** From Kitsu's free API, which maps its entries to MyAnimeList ids, when a title matches exactly. */
    private suspend fun kitsuMalId(name: String, year: Int?): Int? {
        val url = "https://kitsu.app/api/edge/anime".toHttpUrl().newBuilder()
            .addQueryParameter("filter[text]", name)
            .addQueryParameter("page[limit]", "5")
            .addQueryParameter("include", "mappings")
            .addQueryParameter("fields[anime]", "canonicalTitle,titles,startDate,mappings")
            .addQueryParameter("fields[mappings]", "externalSite,externalId")
            .build().toString()
        val json = jsonMapper.readTree(app.get(url).text)
        val malIds = json.path("included")
            .filter { it.path("attributes").path("externalSite").asText() == "myanimelist/anime" }
            .associate { it.path("id").asText() to it.path("attributes").path("externalId").asText().toIntOrNull() }
        val target = normalize(name)
        return json.path("data").firstNotNullOfOrNull { anime ->
            val attributes = anime.path("attributes")
            val titles = listOf(attributes.path("canonicalTitle").asText()) + attributes.path("titles").map { it.asText() }
            val y = attributes.path("startDate").asText().take(4).toIntOrNull()
            if (titles.none { normalize(it) == target } || !yearFits(year, y)) return@firstNotNullOfOrNull null
            anime.path("relationships").path("mappings").path("data").firstNotNullOfOrNull { malIds[it.path("id").asText()] }
        }
    }

    private object AniSkip : Source("AniSkip", setOf(TvType.Anime, TvType.OVA)) {
        data class Response(@JsonProperty("found") val found: Boolean = false, @JsonProperty("results") val results: List<Stamp>? = null)
        data class Stamp(@JsonProperty("interval") val interval: Interval, @JsonProperty("skipType") val skipType: String)
        data class Interval(@JsonProperty("startTime") val startTime: Double, @JsonProperty("endTime") val endTime: Double)

        override suspend fun stamps(data: LoadResponse, ids: Ids, episode: PlayableEpisode, durationMs: Long): List<SkipStamp>? {
            val malId = ids.mal() ?: return null
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

        override suspend fun stamps(data: LoadResponse, ids: Ids, episode: PlayableEpisode, durationMs: Long): List<SkipStamp>? {
            val id = ids.tmdb?.let { "tmdb_id=$it" } ?: ids.imdb()?.let { "imdb_id=$it" } ?: return null
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

        override suspend fun stamps(data: LoadResponse, ids: Ids, episode: PlayableEpisode, durationMs: Long): List<SkipStamp>? {
            val season = episode.season ?: return null
            val number = episode.episode ?: return null
            val imdbId = ids.imdb() ?: return null
            val r = get<Response>("https://api.introdb.app/segments?imdb_id=$imdbId&season=$season&episode=$number")
            fun Segment?.stamp(type: SkipType) = this?.let { s -> SkipStamp(type, s.startMs ?: return@let null, s.endMs ?: return@let null) }
            return listOfNotNull(r.intro.stamp(SkipType.Opening), r.recap.stamp(SkipType.Recap), r.outro.stamp(SkipType.Ending))
        }
    }
}
