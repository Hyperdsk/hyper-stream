package com.lagradost.cloudstream4.desktop.ui

import com.lagradost.cloudstream4.desktop.data.LinkCache
import com.lagradost.cloudstream4.desktop.data.RememberedSource
import com.lagradost.cloudstream4.desktop.data.SourceMemory
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
import com.lagradost.cloudstream4.desktop.player.StreamGateway
import com.lagradost.cloudstream4.desktop.player.StreamSource
import com.lagradost.cloudstream4.desktop.player.VideoSkipStamp
import com.lagradost.cloudstream4.desktop.player.VideoEngine
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
    /** When this list of sources was found (older than now when it came from the cache). */
    val foundAt: Long? = null,
    /** A "Search again" running while the video keeps playing. */
    val refreshing: Boolean = false,
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
class PlayerModel(val request: PlayRequest, val player: VideoEngine?) :
    ScreenModel<PlayerUiState>(PlayerUiState(request.index, request.episodes.toPersistentList())) {

    private var linksJob: Job? = null
    private var pendingStartMs = request.startMs

    val episode: PlayableEpisode get() = current.episodes[current.index]
    val hasNext: Boolean get() = current.index < current.episodes.lastIndex
    val hasPrevious: Boolean get() = current.index > 0

    /** The show, for the ids skip databases need. From the details page, or loaded on demand. */
    private var loadResponse: LoadResponse? = request.loadResponse
    private val autoSkipped = mutableSetOf<VideoSkipStamp>()

    // Declared before init: init starts the first episode, and anything declared after it would be
    // reset to its initial value once init finished
    /** True while playing a link from the cache: if it fails, search again instead of trying the rest. */
    private var startedFromCache = false

    /**
     * Bumped for each episode load and each source started. Results that arrive later for an
     * earlier one (links, subtitles) are dropped instead of landing on what plays now.
     */
    private var loadToken = 0L
    private var playToken = 0L
    private val memoryKey = SourceMemory.key(request.apiName, request.parentUrl)
    /** The background download of the provider's subtitles, see checkSubtitles. */
    private var subtitleCheck: Job? = null

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
            // Remember the source once it actually plays, so this title starts with it next time
            scope.launch {
                p.state.distinctUntilChangedBy { it.timeMs > 0 }.collect { s ->
                    val index = current.current ?: return@collect
                    if (s.timeMs <= 0 || index == OFFLINE) return@collect
                    val link = current.links.getOrNull(index) ?: return@collect
                    val info = link.info()
                    SourceMemory.put(memoryKey, RememberedSource(info.provider, info.server, link.quality))
                    LinkCache.markPlayed(request.apiName, current.episodes[current.index].data, link.url)
                    // It plays, so a later failure is a normal one, not an expired cache
                    startedFromCache = false
                }
            }
            scope.launch {
                p.state.distinctUntilChangedBy { it.ended }.collect { s ->
                    if (!s.ended) return@collect
                    // A stream can also stop early (the connection ended): only the real end
                    // counts as watched; earlier, carry on from there as after an error
                    val nearEnd = s.lengthMs <= 0 || s.timeMs >= s.lengthMs - 60_000 || s.timeMs >= s.lengthMs * 0.95
                    if (!nearEnd) {
                        pendingStartMs = s.timeMs
                        onSourceFailed()
                        return@collect
                    }
                    saveProgress(finished = true)
                    if (SettingsStore.state.value.autoPlayNext && hasNext) next()
                }
            }
            scope.launch {
                p.state.distinctUntilChangedBy { it.error }.collect { s ->
                    if (s.error != null) onSourceFailed()
                }
            }
        }
    }


    /** Where playback should start or carry on: the playhead once it moved, else the resume point. */
    private fun resumePosition(): Long = player?.state?.value?.timeMs?.takeIf { it > 0 } ?: pendingStartMs

    /** "Reload links": search again from where the video was, not from the start. */
    fun reload() = loadEpisode(current.index, resumePosition(), useCache = false)

    fun loadEpisode(index: Int, startMs: Long = 0, allowOffline: Boolean = true, useCache: Boolean = true) {
        saveProgress()
        val token = ++loadToken
        playToken++
        linksJob?.cancel()
        player?.stop()
        StreamGateway.clear()
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
            player.open(StreamSource(file.toPath().toUri().toString(), mimeType = if (file.extension == "m3u8") StreamSource.HLS else null), startMs, downloaded.subtitles.map { File(it).toPath().toUri().toString() })
            return
        }

        val data = current.episodes[index].data

        // Sources found for this episode recently: start with the link that played last, no search
        val cached = if (useCache && player != null) LinkCache.get(request.apiName, data) else null
        if (cached != null) {
            startedFromCache = true
            linksJob = scope.launch {
                val links = cached.links.map { it.toLink() }
                val subs = cached.subtitles.map { it.toSubtitle() }
                update { copy(links = links.toPersistentList(), subtitles = subs.toPersistentList(), loadingLinks = false, foundAt = cached.savedAt) }
                val pick = links.indexOfFirst { it.url == cached.lastPlayedUrl }.takeIf { it >= 0 } ?: sortedCandidates().firstOrNull()
                if (pick != null) playLink(pick) else loadEpisode(index, startMs, allowOffline, useCache = false)
            }
            return
        }
        startedFromCache = false

        val api = providerByName(request.apiName)
        if (api == null) {
            update { copy(loadingLinks = false, error = "The ${request.apiName} extension isn't loaded.") }
            return
        }
        linksJob = scope.launch {
            var failure: Throwable? = null
            val loader = launch(Dispatchers.IO) {
                providerCall {
                    api.loadLinks(
                        data,
                        false,
                        { sub ->
                            if (token == loadToken) update { copy(subtitles = (subtitles + sub).distinctBy { it.url }.toPersistentList()) }
                        },
                        { link ->
                            if (token == loadToken && link.type.name != "TORRENT" && link.type.name != "MAGNET") {
                                update { copy(links = (links + link).distinctBy { it.url }.toPersistentList()) }
                                // Every source tried so far failed: try this one as it arrives
                                scope.launch {
                                    if (token == loadToken && current.current == null && current.failed.isNotEmpty()) playBest()
                                }
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
                // The source that played last time is here: start with it, no waiting or asking
                if (rememberedFound()) break
                val waited = System.currentTimeMillis() - started
                val early = current.links.isNotEmpty() && (api.instantLinkLoading || playFirst || waited > 8_000)
                // Starting early: only with a source that matches the preferences, unless it's taking long
                val acceptable = !settings.waitForPreferred || waited > 30_000 ||
                    sortedCandidates().firstOrNull()?.let { SourcePreferences.matches(current.links[it], settings) } == true
                // A source played for this title before: give it 30 s to turn up before picking another
                val waitingForLast = SourceMemory.get(memoryKey) != null && waited < 30_000
                if (early && acceptable && !waitingForLast) break
                delay(200)
            }
            if (current.current == null) playBest()
            loader.join()
            if (token != loadToken) return@launch
            update {
                copy(
                    loadingLinks = false,
                    error = if (links.isEmpty()) failure?.userMessage() ?: "No playable links were found." else error,
                )
            }
            if (current.current == null && current.links.isNotEmpty()) playBest()
            if (current.current == null && current.links.isNotEmpty() && current.error == null) {
                update { copy(error = "None of the ${links.size} sources could be played.") }
            }
            update { copy(foundAt = System.currentTimeMillis()) }
            LinkCache.put(request.apiName, data, current.links, current.subtitles, playingUrl())
        }
    }

    private fun playingUrl(): String? = current.current?.takeIf { it != OFFLINE }?.let { current.links.getOrNull(it)?.url }

    /**
     * Looks for sources again while the video keeps playing; the new list replaces the old one
     * when the search is done.
     */
    fun searchAgain() {
        val api = providerByName(request.apiName) ?: return
        val index = current.index
        val data = current.episodes[index].data
        if (current.refreshing || current.loadingLinks) return
        // Nothing is playing (the cached link failed): a normal search that also starts a source
        if (current.current == null || current.current == OFFLINE) {
            loadEpisode(index, player?.state?.value?.timeMs?.takeIf { it > 0 } ?: pendingStartMs, useCache = false)
            return
        }
        update { copy(refreshing = true) }
        scope.launch {
            val links = java.util.Collections.synchronizedList(mutableListOf<ExtractorLink>())
            val subs = java.util.Collections.synchronizedList(mutableListOf<SubtitleFile>())
            providerCall {
                api.loadLinks(data, false, { subs += it }, { if (it.type.name != "TORRENT" && it.type.name != "MAGNET") links += it })
            }
            if (current.index != index) return@launch
            val playing = playingUrl()
            val found = links.toList().distinctBy { it.url }.toMutableList()
            // Keep the link that's playing in the list, even if this search didn't return it
            current.current?.let { i -> current.links.getOrNull(i) }?.takeIf { p -> found.none { it.url == p.url } }?.let { found += it }
            update {
                copy(
                    links = found.toPersistentList(),
                    subtitles = (subs.toList() + subtitles).distinctBy { it.url }.toPersistentList(),
                    current = found.indexOfFirst { it.url == playing }.takeIf { it >= 0 } ?: current,
                    failed = persistentSetOf(),
                    foundAt = System.currentTimeMillis(),
                    refreshing = false,
                )
            }
            LinkCache.put(request.apiName, data, current.links, current.subtitles, playing)
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

    /** Untried sources, best first: the one that played last time for this title, then the user's preferences. */
    private fun sortedCandidates(): List<Int> {
        val ranked = SourcePreferences.order(current.links, current.links.indices.filter { it !in current.failed })
        val last = SourceMemory.get(memoryKey) ?: return ranked
        val (same, other) = ranked.partition { matchesRemembered(current.links[it], last) }
        return same + other
    }


    private fun matchesRemembered(link: ExtractorLink, last: RememberedSource): Boolean {
        val info = link.info()
        if (link.quality != last.quality || plain(info.provider) != plain(last.provider)) return false
        val a = serverKey(info.server)
        val b = serverKey(last.server)
        return a == null || b == null || a == b
    }

    /** Names often use decorative letters ("𝗕𝗼𝗹𝗹𝘆𝗳𝗹𝗶𝘅") and punctuation: compare plain letters and digits. */
    private fun plain(name: String?): String =
        java.text.Normalizer.normalize(name ?: "", java.text.Normalizer.Form.NFKC).lowercase().filter { it.isLetterOrDigit() }

    /** The server's name without what varies between searches ("GDFlix[Cloud…" -> "gdflix"). */
    private fun serverKey(server: String?): String? =
        server?.substringBefore('[')?.substringBefore('(')?.let(::plain)?.takeIf { it.isNotEmpty() }

    /** Whether the source that played last time for this title is among the links found so far. */
    private fun rememberedFound(): Boolean {
        val last = SourceMemory.get(memoryKey) ?: return false
        return current.links.withIndex().any { (i, l) -> i !in current.failed && matchesRemembered(l, last) }
    }

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
        // A source that failed before playing leaves the playhead at 0: keep the resume point
        val start = resumePosition()
        playToken++
        update { copy(current = index, error = null) }
        // Single video files stream over several connections, playlists stream segment by segment
        val source = StreamGateway.prepare(link.url, link.referer, link.headers, parallel = link.type == ExtractorLinkType.VIDEO).copy(
            mimeType = when (link.type) {
                ExtractorLinkType.M3U8 -> StreamSource.HLS
                ExtractorLinkType.DASH -> StreamSource.DASH
                else -> null
            },
        )
        // No provider subtitles here: VLC would fetch every one before starting the video.
        // They load one at a time when picked, see loadSubtitle.
        p.open(source, start)
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
        val token = playToken
        scope.launch {
            runCatching { SubtitleFiles.fetch(sub) }
                .onSuccess { file ->
                    // Another episode or source started meanwhile: don't put this one on it
                    if (token != playToken) return@onSuccess
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
            loadEpisode(current.index, resumePosition(), allowOffline = false)
            return
        }
        // The cached link stopped working (expired): search the sites again
        if (startedFromCache) {
            startedFromCache = false
            LinkCache.remove(request.apiName, current.episodes[current.index].data)
            update { copy(current = null) }
            searchAgain()
            return
        }
        val failedIndex = current.current ?: return
        update { copy(failed = failed.add(failedIndex)) }
        val next = sortedCandidates().firstOrNull()
        if (next != null) {
            playLink(next)
        } else if (!current.loadingLinks) {
            update { copy(error = "None of the ${links.size} sources could be played.") }
        } else {
            // More sources are still coming: nothing plays until one arrives (see the link callback)
            update { copy(current = null) }
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
            p.captureFrame()?.let { Snapshots.save(request.apiName, ep.data, it) }?.also { lastSnapshotAt = now }
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
        StreamGateway.clear()
        super.dispose()
    }
}
