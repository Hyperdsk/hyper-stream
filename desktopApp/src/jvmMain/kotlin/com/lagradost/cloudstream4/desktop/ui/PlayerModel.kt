package com.lagradost.cloudstream4.desktop.ui

import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.flow.first
import com.lagradost.cloudstream4.desktop.player.SubtitleFiles
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.PersistentMap
import com.lagradost.cloudstream3.AnimeLoadResponse
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvSeriesLoadResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream4.desktop.data.HistoryEntry
import com.lagradost.cloudstream4.desktop.data.LibraryStore
import com.lagradost.cloudstream4.desktop.data.SettingsStore
import com.lagradost.cloudstream4.desktop.data.Snapshots
import com.lagradost.cloudstream4.desktop.download.DownloadManager
import com.lagradost.cloudstream4.desktop.player.SkipStamps
import com.lagradost.cloudstream4.desktop.player.StreamProxy
import com.lagradost.cloudstream4.desktop.player.VideoSkipStamp
import com.lagradost.cloudstream4.desktop.player.VlcPlayer
import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.PersistentSet
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.collections.immutable.toPersistentList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.launch
import java.io.File

sealed interface SubtitleLoad {
    data object Loading : SubtitleLoad
    /** Downloaded and checked, ready to show instantly. */
    data class Ready(val file: java.io.File) : SubtitleLoad
    data object Loaded : SubtitleLoad
    data class Failed(val reason: String) : SubtitleLoad
}

/** Marks [PlayerUiState.current] while a downloaded file plays. */
const val OFFLINE = -1

data class PlayerUiState(
    val index: Int,
    /** Episodes for next and previous. Starts as the request's list, may grow once the show loads. */
    val episodes: PersistentList<PlayableEpisode> = persistentListOf(),
    val links: PersistentList<ExtractorLink> = persistentListOf(),
    val subtitles: PersistentList<SubtitleFile> = persistentListOf(),
    val loadingLinks: Boolean = true,
    val current: Int? = null,
    val failed: PersistentSet<Int> = persistentSetOf(),
    val error: String? = null,
    /** Set when playing a downloaded file instead of streaming. */
    val offlineFile: String? = null,
    /** Provider subtitles the user picked, by url. */
    val loadedSubtitles: PersistentMap<String, SubtitleLoad> = persistentMapOf(),
    /** Intro, recap and credit sections of this episode, from the online skip databases. */
    val skips: PersistentList<VideoSkipStamp> = persistentListOf(),
)

fun ExtractorLink.label(): String =
    listOf(name, Qualities.getStringByInt(quality), type.name.takeIf { it != "VIDEO" }).filterNot { it.isNullOrBlank() }.joinToString(" · ")

/**
 * Loads links for an episode through the extension, picks the best one, and keeps watch
 * progress saved. Same flow as the Android player: loadLinks, sort by quality, fall back
 * to the next source when one fails.
 */
class PlayerModel(val request: PlayRequest, val player: VlcPlayer?) :
    ScreenModel<PlayerUiState>(PlayerUiState(request.index, request.episodes.toPersistentList())) {

    private var linksJob: Job? = null
    private var pendingStartMs = request.startMs

    val episode: PlayableEpisode get() = current.episodes[current.index]
    val hasNext: Boolean get() = current.index < current.episodes.lastIndex
    val hasPrevious: Boolean get() = current.index > 0

    /** The show, for the ids skip databases need. From the details page, or loaded on demand. */
    private var loadResponse: LoadResponse? = request.loadResponse
    private val autoSkipped = mutableSetOf<VideoSkipStamp>()

    init {
        loadEpisode(request.index, request.startMs)
        loadEpisodeList()
        player?.let { p ->
            // Once the video's length is known, look up its skip times
            scope.launch {
                p.state.distinctUntilChangedBy { it.lengthMs > 0 }.collect { s ->
                    if (s.lengthMs > 0) findSkips(s.lengthMs)
                }
            }
            // Jump over intros and recaps when auto-skip is on, once per section
            scope.launch {
                p.state.distinctUntilChangedBy { it.timeMs / 500 }.collect { s ->
                    if (!SettingsStore.state.value.autoSkipIntros || !s.playing) return@collect
                    val stamp = current.skips.firstOrNull { it.stamp.type.isIntro && s.timeMs in it && it !in autoSkipped }
                        ?: return@collect
                    autoSkipped += stamp
                    p.seekTo(stamp.stamp.endMs)
                }
            }
            // Save progress every few seconds while playing
            scope.launch {
                while (true) {
                    delay(5_000)
                    if (p.state.value.playing) saveProgress(snapshot = false)
                }
            }
            scope.launch {
                p.state.distinctUntilChangedBy { it.ended }.collect { s ->
                    if (s.ended) {
                        saveProgress(finished = true)
                        if (SettingsStore.state.value.autoPlayNext && hasNext) next()
                    }
                }
            }
            scope.launch {
                p.state.distinctUntilChangedBy { it.error }.collect { s ->
                    if (s.error != null) onSourceFailed()
                }
            }
        }
    }

    fun loadEpisode(index: Int, startMs: Long = 0, allowOffline: Boolean = true) {
        saveProgress()
        linksJob?.cancel()
        player?.stop()
        StreamProxy.clear()
        pendingStartMs = startMs
        autoSkipped.clear()
        subtitleCheck?.cancel()
        update { PlayerUiState(index, episodes) }

        // Play the downloaded file when there is one, like the Android app
        val downloaded = if (allowOffline) DownloadManager.completedFile(request.apiName, current.episodes[index].data) else null
        if (downloaded != null && player != null) {
            val file = downloaded.file!!
            update { copy(loadingLinks = false, current = OFFLINE, offlineFile = file.path) }
            // file:/// form: VLC can't open the file:/ urls that File.toURI() makes
            player.open(file.toPath().toUri().toString(), startMs, downloaded.subtitles.map { File(it).toPath().toUri().toString() })
            return
        }

        val api = providerByName(request.apiName)
        if (api == null) {
            update { copy(loadingLinks = false, error = "The ${request.apiName} extension isn't loaded.") }
            return
        }
        val data = current.episodes[index].data
        linksJob = scope.launch {
            var failure: Throwable? = null
            val loader = launch(Dispatchers.IO) {
                providerCall {
                    api.loadLinks(
                        data,
                        false,
                        { sub -> update { copy(subtitles = (subtitles + sub).distinctBy { it.url }.toPersistentList()) } },
                        { link ->
                            if (link.type.name != "TORRENT" && link.type.name != "MAGNET") {
                                update { copy(links = (links + link).distinctBy { it.url }.toPersistentList()) }
                            }
                        },
                    )
                }.onFailure { failure = it }
            }
            // Start once loading is done, or early: when the user presses Play now, when the
            // "play first source" setting is on, or when some links are in and loading is slow.
            // The remaining links keep loading in the background either way.
            val started = System.currentTimeMillis()
            val settings = SettingsStore.state.value
            val playFirst = settings.playFirstSource
            while (loader.isActive) {
                if (current.current != null) break
                val waited = System.currentTimeMillis() - started
                val early = current.links.isNotEmpty() && (api.instantLinkLoading || playFirst || waited > 8_000)
                // Starting early: only with a source that matches the preferences, unless it's taking long
                val acceptable = !settings.waitForPreferred || waited > 30_000 ||
                    sortedCandidates().firstOrNull()?.let { SourcePreferences.matches(current.links[it], settings) } == true
                if (early && acceptable) break
                delay(200)
            }
            if (current.current == null) playBest()
            loader.join()
            update {
                copy(
                    loadingLinks = false,
                    error = if (links.isEmpty()) failure?.userMessage() ?: "No playable links were found." else error,
                )
            }
            if (current.current == null && current.links.isNotEmpty()) playBest()
        }
    }

    /**
     * Started with a single episode of a show (from Continue watching): load the show's episode
     * list in the background, so next and previous work like when starting from its page.
     */
    private fun loadEpisodeList() {
        val only = request.episodes.singleOrNull() ?: return
        if (only.season == null && only.episode == null) return
        val api = providerByName(request.apiName) ?: return
        scope.launch {
            val response = providerCall { api.load(request.parentUrl) }.getOrNull() ?: return@launch
            if (loadResponse == null) loadResponse = response
            val raw = when (response) {
                is TvSeriesLoadResponse -> response.episodes
                // The dub list that contains this episode
                is AnimeLoadResponse -> response.episodes.values.firstOrNull { list -> list.any { it.data == only.data } }.orEmpty()
                else -> emptyList()
            }
            val list = raw.sortedWith(compareBy<Episode>({ it.season ?: 0 }, { it.episode ?: Int.MAX_VALUE }))
                .map { PlayableEpisode(it.data, it.name, it.season, it.episode) }
            val index = list.indexOfFirst { it.data == only.data }
            if (index >= 0 && list.size > 1 && current.episodes.size == 1) {
                update { copy(episodes = list.toPersistentList(), index = index) }
            }
        }
    }

    /** Plays the best source found so far, while the rest keep loading. */
    fun playNow() {
        if (current.current == null && current.links.isNotEmpty()) playBest()
    }

    /** Untried sources, best first by the user's source preferences. */
    private fun sortedCandidates(): List<Int> =
        SourcePreferences.order(current.links, current.links.indices.filter { it !in current.failed })

    private fun playBest() {
        val best = sortedCandidates().firstOrNull() ?: return
        playLink(best)
    }

    fun playLink(index: Int) {
        val p = player ?: run {
            update { copy(current = index) }
            return
        }
        val link = current.links.getOrNull(index) ?: return
        val start = if (current.current == null) pendingStartMs else p.state.value.timeMs
        update { copy(current = index, error = null) }
        // Single video files stream over several connections, playlists stream segment by segment
        val url = StreamProxy.proxiedUrl(link.url, link.referer, link.headers, parallel = link.type == ExtractorLinkType.VIDEO)
        // No provider subtitles here: VLC would fetch every one before starting the video.
        // They load one at a time when picked, see loadSubtitle.
        p.open(url, start)
        update { copy(loadedSubtitles = persistentMapOf()) }
        checkSubtitles()
    }

    /** Shows [sub] in the player, downloading it first unless the background check already did. */
    fun loadSubtitle(sub: SubtitleFile) {
        val p = player ?: return
        when (val state = current.loadedSubtitles[sub.url]) {
            is SubtitleLoad.Ready -> {
                p.addSubtitle(state.file.toPath().toUri().toString())
                update { copy(loadedSubtitles = loadedSubtitles.put(sub.url, SubtitleLoad.Loaded)) }
                return
            }
            SubtitleLoad.Loading, SubtitleLoad.Loaded -> return
            else -> Unit
        }
        update { copy(loadedSubtitles = loadedSubtitles.put(sub.url, SubtitleLoad.Loading)) }
        scope.launch {
            runCatching { SubtitleFiles.fetch(sub) }
                .onSuccess { file ->
                    p.addSubtitle(file.toPath().toUri().toString())
                    update { copy(loadedSubtitles = loadedSubtitles.put(sub.url, SubtitleLoad.Loaded)) }
                }
                .onFailure { e ->
                    update { copy(loadedSubtitles = loadedSubtitles.put(sub.url, SubtitleLoad.Failed(e.message ?: "Couldn't load it"))) }
                }
        }
    }

    /**
     * Once the video shows its first frame, downloads the provider's subtitles in the background,
     * a few at a time, so the list shows which ones work and picking one is instant. Waiting for
     * the first frame keeps this from slowing the start of the video.
     */
    private var subtitleCheck: Job? = null

    private fun checkSubtitles() {
        val p = player ?: return
        subtitleCheck?.cancel()
        subtitleCheck = scope.launch {
            p.state.first { it.timeMs > 0 }
            val limit = kotlinx.coroutines.sync.Semaphore(4)
            val started = mutableSetOf<String>()
            // Subtitles keep arriving while the remaining sources load
            state.collect { ui ->
                ui.subtitles.filter { it.url !in started && ui.loadedSubtitles[it.url] == null }.forEach { sub ->
                    started += sub.url
                    launch {
                        limit.withPermit {
                            if (current.loadedSubtitles[sub.url] != null) return@withPermit
                            val result = runCatching { SubtitleFiles.fetch(sub) }
                            update {
                                // Don't overwrite a pick the user made meanwhile
                                if (loadedSubtitles[sub.url] != null) this
                                else copy(
                                    loadedSubtitles = loadedSubtitles.put(
                                        sub.url,
                                        result.fold({ SubtitleLoad.Ready(it) }, { SubtitleLoad.Failed(it.message ?: "Couldn't load it") }),
                                    )
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    private fun onSourceFailed() {
        // A downloaded file that won't play falls back to streaming
        if (current.offlineFile != null) {
            loadEpisode(current.index, player?.state?.value?.timeMs ?: 0, allowOffline = false)
            return
        }
        val failedIndex = current.current ?: return
        update { copy(failed = failed.add(failedIndex)) }
        val next = sortedCandidates().firstOrNull()
        if (next != null) {
            playLink(next)
        } else if (!current.loadingLinks) {
            update { copy(error = "None of the ${links.size} sources could be played.") }
        }
    }

    private fun findSkips(durationMs: Long) {
        if (!SettingsStore.state.value.skipButtons) return
        val index = current.index
        val episode = current.episodes[index]
        scope.launch {
            val show = loadResponse ?: providerByName(request.apiName)
                ?.let { api -> providerCall { api.load(request.parentUrl) }.getOrNull() }
                ?.also { loadResponse = it }
                ?: return@launch
            val skips = SkipStamps.find(show, episode, durationMs, hasNextEpisode = index < current.episodes.lastIndex)
            // Ignore results that arrive after the user moved to another episode
            if (current.index == index) update { copy(skips = skips.toPersistentList()) }
        }
    }

    /** The section the playhead is in, if any. */
    fun activeSkip(positionMs: Long): VideoSkipStamp? = current.skips.firstOrNull { positionMs in it }

    /** Skips a section: to its end, or to the next episode when it runs to the end of the video. */
    fun skip(stamp: VideoSkipStamp) {
        if (stamp.skipToNextEpisode && hasNext) next() else player?.seekTo(stamp.stamp.endMs)
    }

    /** The Android player's "Skip OP" button: jump 85 seconds, the length of a typical anime opening. */
    fun skipOpening() {
        player?.seekBy(85_000)
    }

    fun next() {
        if (hasNext) loadEpisode(current.index + 1)
    }

    fun previous() {
        if (hasPrevious) loadEpisode(current.index - 1)
    }

    private var lastSnapshotAt = 0L

    /**
     * Saves watch progress. [snapshot] also saves a picture of the current frame for Continue
     * watching; when leaving the player or the episode, and about once a minute while playing.
     */
    fun saveProgress(finished: Boolean = false, snapshot: Boolean = true) {
        val p = player ?: return
        // The player is shared, so only save once this session has started a source
        if (current.current == null) return
        val s = p.state.value
        if (s.lengthMs <= 0 || (s.timeMs <= 0 && !finished)) return
        val ep = episode
        val now = System.currentTimeMillis()
        val picture = if (!finished && (snapshot || now - lastSnapshotAt > 60_000)) {
            p.frame?.let { Snapshots.save(request.apiName, ep.data, it) }?.also { lastSnapshotAt = now }
        } else null
        LibraryStore.saveProgress(
            HistoryEntry(
                parentUrl = request.parentUrl,
                apiName = request.apiName,
                name = request.title,
                posterUrl = request.posterUrl,
                posterHeaders = request.posterHeaders,
                episodeData = ep.data,
                episodeName = ep.name,
                season = ep.season,
                episode = ep.episode,
                positionMs = if (finished) s.lengthMs else s.timeMs,
                durationMs = s.lengthMs,
                snapshotPath = picture?.path,
            )
        )
    }

    override fun dispose() {
        saveProgress()
        linksJob?.cancel()
        player?.stop()
        StreamProxy.clear()
        super.dispose()
    }
}
