package com.lagradost.cloudstream4.desktop.data

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import java.security.MessageDigest

data class CachedLink(
    @JsonProperty("source") val source: String,
    @JsonProperty("name") val name: String,
    @JsonProperty("url") val url: String,
    @JsonProperty("referer") val referer: String,
    @JsonProperty("quality") val quality: Int,
    @JsonProperty("headers") val headers: Map<String, String>,
    @JsonProperty("type") val type: ExtractorLinkType,
    @JsonProperty("extractorData") val extractorData: String? = null,
) {
    @Suppress("DEPRECATION")
    fun toLink() = ExtractorLink(source, name, url, referer, quality, headers, extractorData, type)

    companion object {
        fun of(l: ExtractorLink) = CachedLink(l.source, l.name, l.url, l.referer, l.quality, l.headers, l.type, l.extractorData)
    }
}

data class CachedSubtitle(
    @JsonProperty("lang") val lang: String,
    @JsonProperty("url") val url: String,
    @JsonProperty("headers") val headers: Map<String, String>? = null,
) {
    suspend fun toSubtitle(): SubtitleFile = newSubtitleFile(lang, url) { this@CachedSubtitle.headers?.let { headers = it } }

    companion object {
        fun of(s: SubtitleFile) = CachedSubtitle(s.lang, s.url, s.headers)
    }
}

data class CachedLinks(
    @JsonProperty("savedAt") val savedAt: Long,
    @JsonProperty("links") val links: List<CachedLink>,
    @JsonProperty("subtitles") val subtitles: List<CachedSubtitle>,
    /** The link that played last for this episode. */
    @JsonProperty("lastPlayedUrl") val lastPlayedUrl: String? = null,
)

/**
 * The sources found for each episode, so opening it again starts at once with the link that
 * played last instead of searching every site again. Kept for a day: links from many hosts
 * expire. When a cached link fails, the player searches again by itself.
 */
object LinkCache {
    const val MAX_AGE_MS = 24L * 60 * 60 * 1000
    private const val MAX_ENTRIES = 150

    private val file = jsonFile<Map<String, CachedLinks>>("link-cache.json") { emptyMap() }
    private var cache: MutableMap<String, CachedLinks>? = null

    private fun key(apiName: String, episodeData: String): String =
        MessageDigest.getInstance("SHA-1").digest("$apiName|$episodeData".toByteArray()).joinToString("") { "%02x".format(it) }

    private fun load() = cache ?: file.read().toMutableMap().also { cache = it }

    /** The cached sources, if fresh enough to use. */
    @Synchronized
    fun get(apiName: String, episodeData: String): CachedLinks? =
        load()[key(apiName, episodeData)]?.takeIf { System.currentTimeMillis() - it.savedAt < MAX_AGE_MS && it.links.isNotEmpty() }

    @Synchronized
    fun put(apiName: String, episodeData: String, links: List<ExtractorLink>, subtitles: List<SubtitleFile>, lastPlayedUrl: String?) {
        if (links.isEmpty()) return
        val map = load()
        map[key(apiName, episodeData)] = CachedLinks(
            System.currentTimeMillis(),
            links.map(CachedLink::of),
            subtitles.map(CachedSubtitle::of),
            lastPlayedUrl ?: map[key(apiName, episodeData)]?.lastPlayedUrl,
        )
        if (map.size > MAX_ENTRIES) map.entries.sortedBy { it.value.savedAt }.take(map.size - MAX_ENTRIES).forEach { map.remove(it.key) }
        file.write(map.toMap())
    }

    @Synchronized
    fun markPlayed(apiName: String, episodeData: String, url: String) {
        val map = load()
        val k = key(apiName, episodeData)
        val entry = map[k] ?: return
        if (entry.lastPlayedUrl == url) return
        map[k] = entry.copy(lastPlayedUrl = url)
        file.write(map.toMap())
    }

    @Synchronized
    fun remove(apiName: String, episodeData: String) {
        if (load().remove(key(apiName, episodeData)) != null) file.write(load().toMap())
    }
}
