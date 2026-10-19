package com.lagradost.cloudstream4.desktop.data

import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonProperty
import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toPersistentList
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Same bookmark categories as the Android app. */
enum class WatchType(val label: String) {
    WATCHING("Watching"),
    PLANTOWATCH("Plan to watch"),
    COMPLETED("Completed"),
    ONHOLD("On hold"),
    DROPPED("Dropped"),
}

data class Bookmark(
    @JsonProperty("url") val url: String,
    @JsonProperty("apiName") val apiName: String,
    @JsonProperty("name") val name: String,
    @JsonProperty("posterUrl") val posterUrl: String?,
    @JsonProperty("posterHeaders") val posterHeaders: Map<String, String>? = null,
    @JsonProperty("type") val type: String?,
    @JsonProperty("watchType") val watchType: WatchType,
    @JsonProperty("updatedAt") val updatedAt: Long = System.currentTimeMillis(),
)

/** Playback progress for one movie or episode. */
data class HistoryEntry(
    @JsonProperty("parentUrl") val parentUrl: String,
    @JsonProperty("apiName") val apiName: String,
    @JsonProperty("name") val name: String,
    @JsonProperty("posterUrl") val posterUrl: String?,
    @JsonProperty("posterHeaders") val posterHeaders: Map<String, String>? = null,
    /** The data string passed to loadLinks. Identifies the episode. */
    @JsonProperty("episodeData") val episodeData: String,
    @JsonProperty("episodeName") val episodeName: String?,
    @JsonProperty("season") val season: Int?,
    @JsonProperty("episode") val episode: Int?,
    @JsonProperty("positionMs") val positionMs: Long,
    @JsonProperty("durationMs") val durationMs: Long,
    @JsonProperty("updatedAt") val updatedAt: Long = System.currentTimeMillis(),
) {
    @get:JsonIgnore
    val progress: Float get() = if (durationMs <= 0) 0f else (positionMs.toFloat() / durationMs).coerceIn(0f, 1f)

    /** Counts as watched near the end, matching the Android app's 95% threshold. */
    @get:JsonIgnore
    val finished: Boolean get() = progress >= 0.95f
}

data class LibraryState(
    val bookmarks: PersistentList<Bookmark> = persistentListOf(),
    val history: PersistentList<HistoryEntry> = persistentListOf(),
) {
    fun bookmark(apiName: String, url: String) = bookmarks.firstOrNull { it.apiName == apiName && it.url == url }
    fun progress(apiName: String, episodeData: String) =
        history.firstOrNull { it.apiName == apiName && it.episodeData == episodeData }

    /** One entry per show, the most recently watched, excluding finished ones. */
    val continueWatching: List<HistoryEntry>
        get() = history.sortedByDescending { it.updatedAt }
            .distinctBy { it.apiName to it.parentUrl }
            .filterNot { it.finished }
}

object LibraryStore {
    private val bookmarksFile = jsonFile<List<Bookmark>>("bookmarks.json") { emptyList() }
    private val historyFile = jsonFile<List<HistoryEntry>>("history.json") { emptyList() }

    private val _state = MutableStateFlow(
        LibraryState(bookmarksFile.read().toPersistentList(), historyFile.read().toPersistentList())
    )
    val state: StateFlow<LibraryState> = _state.asStateFlow()

    fun setBookmark(bookmark: Bookmark?, apiName: String, url: String) {
        _state.update { s ->
            val rest = s.bookmarks.removingAll { it.apiName == apiName && it.url == url }
            s.copy(bookmarks = if (bookmark == null) rest else rest.addingAt(0, bookmark))
        }
        bookmarksFile.write(_state.value.bookmarks)
    }

    fun saveProgress(entry: HistoryEntry) {
        _state.update { s ->
            val rest = s.history.removingAll { it.apiName == entry.apiName && it.episodeData == entry.episodeData }
            s.copy(history = rest.addingAt(0, entry))
        }
        historyFile.write(_state.value.history)
    }

    fun removeFromContinueWatching(apiName: String, parentUrl: String) {
        _state.update { s ->
            s.copy(history = s.history.map {
                if (it.apiName == apiName && it.parentUrl == parentUrl) it.copy(positionMs = it.durationMs) else it
            }.toPersistentList())
        }
        historyFile.write(_state.value.history)
    }

    fun clearHistory() {
        _state.update { it.copy(history = persistentListOf()) }
        historyFile.write(emptyList())
    }
}
