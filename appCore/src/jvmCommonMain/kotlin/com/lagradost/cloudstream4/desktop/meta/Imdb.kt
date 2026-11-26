package com.lagradost.cloudstream4.desktop.meta

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.JsonNode
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.LoadResponse.Companion.getImdbId
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream4.desktop.data.jsonFile
import com.lagradost.cloudstream4.desktop.data.jsonMapper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

/** One IMDb user review. */
data class ImdbReview(
    val id: String,
    val author: String,
    /** The reviewer's own score out of 10, when they gave one. */
    val rating: Int?,
    val title: String?,
    val text: String,
    val date: String?,
    val upVotes: Int,
    val downVotes: Int,
    val spoiler: Boolean,
)

data class ImdbReviewPage(val reviews: List<ImdbReview>, val total: Int, val nextCursor: String?)

enum class ReviewSort(val label: String, val by: String, val order: String) {
    Helpful("Most helpful", "HELPFULNESS_SCORE", "DESC"),
    Newest("Newest", "SUBMISSION_DATE", "DESC"),
    Oldest("Oldest", "SUBMISSION_DATE", "ASC"),
    Highest("Highest rated", "USER_RATING", "DESC"),
    Lowest("Lowest rated", "USER_RATING", "ASC"),
    MostVotes("Most votes", "TOTAL_VOTES", "DESC"),
}

data class ImdbPerson(
    @JsonProperty("id") val id: String,
    @JsonProperty("name") val name: String,
    @JsonProperty("image") val image: String?,
)

/** What IMDb knows about a title, as shown on the details page. */
data class ImdbInfo(
    @JsonProperty("id") val id: String,
    @JsonProperty("title") val title: String,
    @JsonProperty("type") val type: String?,
    @JsonProperty("year") val year: Int?,
    @JsonProperty("endYear") val endYear: Int?,
    @JsonProperty("rating") val rating: Double?,
    @JsonProperty("votes") val votes: Long?,
    @JsonProperty("metascore") val metascore: Int?,
    @JsonProperty("popularity") val popularity: Int?,
    @JsonProperty("runtimeMinutes") val runtimeMinutes: Int?,
    @JsonProperty("certificate") val certificate: String?,
    @JsonProperty("genres") val genres: List<String> = emptyList(),
    @JsonProperty("plot") val plot: String?,
    @JsonProperty("directors") val directors: List<ImdbPerson> = emptyList(),
    @JsonProperty("writers") val writers: List<ImdbPerson> = emptyList(),
    @JsonProperty("creators") val creators: List<ImdbPerson> = emptyList(),
    @JsonProperty("stars") val stars: List<ImdbPerson> = emptyList(),
    @JsonProperty("countries") val countries: List<String> = emptyList(),
    @JsonProperty("languages") val languages: List<String> = emptyList(),
    @JsonProperty("released") val released: String?,
    @JsonProperty("awards") val awards: String?,
    @JsonProperty("budget") val budget: String?,
    @JsonProperty("grossWorldwide") val grossWorldwide: String?,
    @JsonProperty("seasons") val seasons: Int?,
    @JsonProperty("episodes") val episodes: Int?,
    /** Votes for each score, index 0 is 1 star up to index 9 for 10 stars. */
    @JsonProperty("ratingHistogram") val ratingHistogram: List<Long> = emptyList(),
    @JsonProperty("reviewCount") val reviewCount: Int? = null,
) {
    val url: String get() = "https://www.imdb.com/title/$id/"
}

private data class CachedImdb(
    @JsonProperty("info") val info: ImdbInfo?,
    @JsonProperty("savedAt") val savedAt: Long,
)

/**
 * IMDb details for a title. Uses the IMDb id when the extension provides one, otherwise finds
 * the title by name, year and type with IMDb's search suggestions.
 *
 * The details come from Cinemeta (Stremio's public catalogue of IMDb titles): IMDb's own data
 * API began refusing the app (403) in November 2026. Cinemeta has the rating, genres, run time,
 * plot, people, country, release date, awards and episodes, but no vote counts, Metascore,
 * popularity, certificate, budget or box office. Reviews still ask IMDb, and are simply missing
 * while it refuses.
 */
object Imdb {
    private const val TTL_MS = 3L * 24 * 60 * 60 * 1000
    private const val MISS_TTL_MS = 6L * 60 * 60 * 1000
    private const val BROWSER_UA =
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/149.0.0.0 Safari/537.36"

    // v3: v2 holds the misses saved while IMDb refused, which would hide details for hours
    private val file = jsonFile<Map<String, CachedImdb>>("imdb-cache-v3.json") { emptyMap() }
    private val cache = ConcurrentHashMap<String, CachedImdb>(file.read())

    /** Details for [response], or null when IMDb has no matching title. Cached for a few days. */
    suspend fun forTitle(response: LoadResponse): ImdbInfo? = withContext(Dispatchers.IO) {
        val key = "${response.apiName}|${response.url}"
        val now = System.currentTimeMillis()
        // No match is kept for a few hours only: it can be a passing IMDb error, not a missing title
        cache[key]?.takeIf { now - it.savedAt < (if (it.info == null) MISS_TTL_MS else TTL_MS) }?.let { return@withContext it.info }
        val id = runCatching { response.getImdbId() }.getOrNull()?.takeIf { it.startsWith("tt") }
            ?: findId(response.name, response.year, response.type)
        val info = id?.let { fetch(it, series = response.type != TvType.Movie && response.type != TvType.AnimeMovie, name = response.name, year = response.year) }
        cache[key] = CachedImdb(info, now)
        // Expired entries go, so the file doesn't grow with every title ever opened
        cache.entries.removeIf { now - it.value.savedAt > TTL_MS }
        file.write(HashMap(cache))
        info
    }

    private fun normalize(title: String) = title.lowercase().replace(Regex("[^a-z0-9]+"), " ").trim()

    /** Picks the best IMDb suggestion: same title, close year, and the right kind (movie or series). */
    internal suspend fun findId(name: String, year: Int?, type: TvType): String? {
        val query = URLEncoder.encode(name.trim().lowercase(), "UTF-8").replace("+", "%20")
        val body = app.get("https://v3.sg.media-imdb.com/suggestion/x/$query.json", headers = mapOf("User-Agent" to BROWSER_UA)).text
        val results = jsonMapper.readTree(body).path("d")
        val wantsSeries = type in setOf(TvType.TvSeries, TvType.Anime, TvType.AsianDrama, TvType.Cartoon, TvType.Documentary) &&
            type != TvType.Movie
        val target = normalize(name)
        return results.filter { it.path("id").asText().startsWith("tt") }
            .map { r ->
                val title = normalize(r.path("l").asText())
                val y = r.path("y").takeIf { it.isNumber }?.asInt()
                val kind = r.path("qid").asText()
                var score = 0
                if (title == target) score += 10 else if (title.contains(target) || target.contains(title)) score += 4 else score -= 20
                if (year != null && y != null) score += when (kotlin.math.abs(year - y)) { 0 -> 6; 1 -> 3; else -> -6 }
                val isSeries = kind.startsWith("tv") && kind != "tvMovie"
                if (wantsSeries == isSeries) score += 3
                r.path("id").asText() to score
            }
            .filter { it.second > 0 }
            .maxByOrNull { it.second }
            ?.first
    }

    private fun graphql(query: String, variables: Map<String, Any?>): JsonNode? {
        val payload = jsonMapper.writeValueAsString(mapOf("query" to query, "variables" to variables))
        val request = Request.Builder()
            .url("https://api.graphql.imdb.com/")
            .header("User-Agent", BROWSER_UA)
            .header("Origin", "https://www.imdb.com")
            .header("Referer", "https://www.imdb.com/")
            .post(payload.toRequestBody("application/json".toMediaType()))
            .build()
        val text = app.baseClient.newCall(request).execute().use { if (it.isSuccessful) it.body.string() else return null }
        return jsonMapper.readTree(text).path("data").path("title").takeIf { it.isObject }
    }

    /** What IMDb's search says an id is: its title, year and whether it's a series. */
    private data class ImdbEntry(val title: String, val year: Int?, val series: Boolean)

    private suspend fun lookup(id: String): ImdbEntry? = runCatching {
        val body = app.get("https://v3.sg.media-imdb.com/suggestion/t/$id.json", headers = mapOf("User-Agent" to BROWSER_UA)).text
        jsonMapper.readTree(body).path("d").firstOrNull { it.path("id").asText() == id }?.let { r ->
            val kind = r.path("qid").asText()
            ImdbEntry(r.path("l").asText(), r.path("y").takeIf { it.isNumber }?.asInt(), kind == "tvSeries" || kind == "tvMiniSeries")
        }
    }.getOrNull()

    /**
     * Details for an IMDb [id] from Cinemeta. It files movies and series apart and must be asked
     * for the right kind: asked for a series as a movie it answers with another title entirely
     * (Breaking Bad's id came back as the 1975 film "Mirror"), and it does the same for ids it
     * doesn't have. So the kind comes from IMDb's search when it knows the id ([series] otherwise),
     * and the answer counts only when its title or year is the one IMDb (or the extension, as
     * [name] and [year]) gives.
     */
    internal suspend fun fetch(id: String, series: Boolean = false, name: String? = null, year: Int? = null): ImdbInfo? =
        withContext(Dispatchers.IO) {
            val known = lookup(id)
            val kind = if (known?.series ?: series) "series" else "movie"
            val body = runCatching { app.get("https://v3-cinemeta.strem.io/meta/$kind/$id.json").text }.getOrNull() ?: return@withContext null
            val meta = runCatching { jsonMapper.readTree(body).path("meta") }.getOrNull() ?: return@withContext null
            val info = meta.textOrNull("name")?.let { parseCinemeta(id, kind, meta) } ?: return@withContext null
            val title = known?.title ?: name ?: return@withContext null
            val expectedYear = known?.year ?: year
            val same = normalize(info.title) == normalize(title) || (expectedYear != null && info.year == expectedYear)
            info.takeIf { same }
        }

    private fun parseCinemeta(id: String, kind: String, m: JsonNode): ImdbInfo {
        // "2008–2013" for a series that ended, "2019–" for one still running
        val years = (m.textOrNull("releaseInfo") ?: m.textOrNull("year")).orEmpty()
            .split('–', '-').map { it.trim().toIntOrNull() }
        val released = m.textOrNull("released")?.let { iso ->
            runCatching {
                val date = java.time.LocalDate.parse(iso.take(10))
                date.format(java.time.format.DateTimeFormatter.ofPattern("MMMM d, yyyy", java.util.Locale.ENGLISH))
            }.getOrNull()
        }
        fun names(field: String) = m.path(field).mapNotNull { it.takeIf { n -> n.isTextual }?.asText()?.takeIf { n -> n.isNotBlank() } }
            .distinct().map { ImdbPerson("name:$it", it, null) }
        // Specials are season 0: not counted, as on IMDb's page
        val episodes = m.path("videos").filter { it.path("season").asInt(0) > 0 }
        return ImdbInfo(
            id = id,
            title = m.textOrNull("name") ?: "",
            type = if (kind == "series") "TV Series" else "Movie",
            year = years.getOrNull(0),
            endYear = years.getOrNull(1),
            rating = m.textOrNull("imdbRating")?.toDoubleOrNull()?.takeIf { it > 0 },
            votes = null,
            metascore = null,
            popularity = null,
            runtimeMinutes = m.textOrNull("runtime")?.let { Regex("(\\d+)").find(it)?.value?.toIntOrNull() },
            certificate = null,
            genres = (m.path("genres").takeIf { it.size() > 0 } ?: m.path("genre")).mapNotNull { it.textOrNull() },
            plot = m.textOrNull("description"),
            directors = names("director"),
            writers = names("writer"),
            creators = emptyList(),
            stars = names("cast"),
            countries = m.textOrNull("country")?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty(),
            languages = emptyList(),
            released = released,
            awards = m.textOrNull("awards"),
            budget = null,
            grossWorldwide = null,
            seasons = episodes.map { it.path("season").asInt() }.distinct().size.takeIf { it > 0 },
            episodes = episodes.size.takeIf { it > 0 },
        )
    }

    private val reviewsQuery = """
        query(${'$'}id: ID!, ${'$'}first: Int!, ${'$'}after: ID, ${'$'}by: ReviewsSortBy!, ${'$'}order: SortOrder!) { title(id: ${'$'}id) {
          reviews(first: ${'$'}first, after: ${'$'}after, sort: { by: ${'$'}by, order: ${'$'}order }) {
            total pageInfo { endCursor hasNextPage }
            edges { node {
              id author { nickName } authorRating summary { originalText }
              text { originalText { plainText } } submissionDate helpfulness { upVotes downVotes } spoiler
            } }
          }
        } }
    """.trimIndent()

    /** A page of user reviews. Pass the previous page's [ImdbReviewPage.nextCursor] to continue. */
    suspend fun reviews(id: String, sort: ReviewSort = ReviewSort.Helpful, after: String? = null, count: Int = 20): ImdbReviewPage? =
        withContext(Dispatchers.IO) {
            val r = graphql(
                reviewsQuery,
                mapOf("id" to id, "first" to count, "after" to after, "by" to sort.by, "order" to sort.order),
            )?.path("reviews") ?: return@withContext null
            val reviews = r.path("edges").mapNotNull { edge ->
                val n = edge.path("node")
                val text = n.textOrNull("text", "originalText", "plainText") ?: return@mapNotNull null
                ImdbReview(
                    id = n.path("id").asText(),
                    author = n.textOrNull("author", "nickName") ?: "IMDb user",
                    rating = n.path("authorRating").takeIf { it.isNumber }?.asInt(),
                    title = n.textOrNull("summary", "originalText"),
                    text = text,
                    date = n.textOrNull("submissionDate"),
                    upVotes = n.path("helpfulness").path("upVotes").asInt(0),
                    downVotes = n.path("helpfulness").path("downVotes").asInt(0),
                    spoiler = n.path("spoiler").asBoolean(false),
                )
            }
            val next = r.path("pageInfo").takeIf { it.path("hasNextPage").asBoolean(false) }?.textOrNull("endCursor")
            ImdbReviewPage(reviews, r.path("total").asInt(reviews.size), next)
        }

    private fun JsonNode.textOrNull(vararg path: String): String? =
        path.fold(this) { node, key -> node.path(key) }.takeIf { it.isTextual }?.asText()?.takeIf { it.isNotBlank() }

}

/** "10K", "3.2M" for vote counts. */
fun compactCount(n: Long): String = when {
    n >= 1_000_000 -> "%.1fM".format(n / 1e6).replace(".0", "")
    n >= 1_000 -> "%.1fK".format(n / 1e3).replace(".0", "")
    else -> n.toString()
}
