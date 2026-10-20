package com.lagradost.cloudstream4.desktop.data

import com.fasterxml.jackson.annotation.JsonProperty

/** The source that last played for a title: provider, server and quality, not the episode's own link. */
data class RememberedSource(
    @JsonProperty("provider") val provider: String,
    @JsonProperty("server") val server: String?,
    @JsonProperty("quality") val quality: Int,
    @JsonProperty("savedAt") val savedAt: Long = System.currentTimeMillis(),
)

/**
 * Remembers which source played last for each title, so the next time (any episode) it starts
 * with that one right away. Picking another source by hand replaces it once that one plays.
 */
object SourceMemory {
    private val file = jsonFile<Map<String, RememberedSource>>("last-sources.json") { emptyMap() }
    private var cache: MutableMap<String, RememberedSource>? = null

    fun key(apiName: String, parentUrl: String) = "$apiName|$parentUrl"

    @Synchronized
    fun get(key: String): RememberedSource? = load()[key]

    @Synchronized
    fun put(key: String, source: RememberedSource) {
        val map = load()
        val old = map[key]
        if (old != null && old.provider == source.provider && old.server == source.server && old.quality == source.quality) return
        map[key] = source
        // Keep the file small: the 500 most recent titles
        if (map.size > 500) map.entries.sortedBy { it.value.savedAt }.take(map.size - 500).forEach { map.remove(it.key) }
        file.write(map.toMap())
    }

    private fun load(): MutableMap<String, RememberedSource> = cache ?: file.read().toMutableMap().also { cache = it }
}
