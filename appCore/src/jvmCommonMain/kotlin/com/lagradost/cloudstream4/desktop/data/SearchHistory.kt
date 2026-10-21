package com.lagradost.cloudstream4.desktop.data

import com.fasterxml.jackson.annotation.JsonProperty
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class SearchHistoryEntry(
    @JsonProperty("query") val query: String,
    @JsonProperty("at") val at: Long = System.currentTimeMillis(),
)

/**
 * Recent searches, newest first, shown on the Search screen before typing. They sync with the
 * account like the library: one item per query, so searches on two devices merge.
 */
object SearchHistory {
    private const val MAX = 30
    private val file = jsonFile<List<SearchHistoryEntry>>("search-history.json") { emptyList() }
    private val _entries = MutableStateFlow(file.read().sortedByDescending { it.at })
    val entries: StateFlow<List<SearchHistoryEntry>> = _entries.asStateFlow()

    /** The same search typed differently ("Dune", " dune ") is one entry. */
    fun key(query: String) = query.trim().lowercase()

    fun add(query: String) {
        val text = query.trim()
        if (text.length < 2) return
        update { list -> listOf(SearchHistoryEntry(text)) + list.filter { key(it.query) != key(text) } }
    }

    fun remove(query: String) = update { list -> list.filter { key(it.query) != key(query) } }

    fun clear() = update { emptyList() }

    /** Another device's search, or its removal when [entry] is null. */
    fun applyRemote(key: String, entry: SearchHistoryEntry?) = update { list ->
        val rest = list.filter { key(it.query) != key }
        if (entry == null) rest else rest + entry
    }

    @Synchronized
    private fun update(change: (List<SearchHistoryEntry>) -> List<SearchHistoryEntry>) {
        val next = change(_entries.value).sortedByDescending { it.at }.take(MAX)
        _entries.value = next
        file.write(next)
    }
}
