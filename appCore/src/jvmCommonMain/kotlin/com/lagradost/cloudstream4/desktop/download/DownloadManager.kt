package com.lagradost.cloudstream4.desktop.download

import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.M3u8Helper2
import com.lagradost.cloudstream3.utils.HlsPlaylistParser
import com.lagradost.cloudstream4.desktop.data.SettingsStore
import com.lagradost.cloudstream4.desktop.data.jsonFile
import com.lagradost.cloudstream4.desktop.ui.PlayableEpisode
import com.lagradost.cloudstream4.desktop.ui.parseSourceInfo
import com.lagradost.cloudstream4.desktop.ui.providerByName
import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.toPersistentList
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import okhttp3.ConnectionPool
import okhttp3.Protocol
import okhttp3.Request
import java.util.concurrent.TimeUnit
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.net.URI
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

enum class DownloadStatus { Queued, Downloading, Paused, Completed, Failed }

/** The link a download is using, kept so a paused download resumes from the same source. */
data class SavedLink(
    @JsonProperty("source") val source: String,
    @JsonProperty("name") val name: String,
    @JsonProperty("url") val url: String,
    @JsonProperty("referer") val referer: String,
    @JsonProperty("quality") val quality: Int,
    @JsonProperty("headers") val headers: Map<String, String>,
    @JsonProperty("type") val type: ExtractorLinkType,
)

data class DownloadEntry(
    @JsonProperty("id") val id: String,
    @JsonProperty("apiName") val apiName: String,
    @JsonProperty("parentUrl") val parentUrl: String,
    @JsonProperty("title") val title: String,
    @JsonProperty("posterUrl") val posterUrl: String?,
    @JsonProperty("posterHeaders") val posterHeaders: Map<String, String>?,
    @JsonProperty("episodeData") val episodeData: String,
    @JsonProperty("episodeName") val episodeName: String?,
    @JsonProperty("season") val season: Int?,
    @JsonProperty("episode") val episode: Int?,
    @JsonProperty("status") val status: DownloadStatus,
    /** The finished file, or the partial file while downloading. */
    @JsonProperty("filePath") val filePath: String? = null,
    @JsonProperty("bytesDone") val bytesDone: Long = 0,
    @JsonProperty("bytesTotal") val bytesTotal: Long? = null,
    @JsonProperty("segmentsDone") val segmentsDone: Int = 0,
    @JsonProperty("segmentsTotal") val segmentsTotal: Int? = null,
    @JsonProperty("link") val link: SavedLink? = null,
    @JsonProperty("subtitles") val subtitles: List<String> = emptyList(),
    @JsonProperty("error") val error: String? = null,
    @JsonProperty("addedAt") val addedAt: Long = System.currentTimeMillis(),
    /** True when the user picked [link] themselves. Then only that source is used. */
    @JsonProperty("chosen") val chosen: Boolean = false,
    /** Highest quality to pick automatically, in pixels of height. -1 means best, null follows Settings. */
    @JsonProperty("maxQuality") val maxQuality: Int? = null,
    /** Bytes per second, only meaningful while downloading. */
    @JsonProperty("speed") val speed: Long = 0,
    /** Multi-connection downloads: the chunk size and which chunks are finished, for resuming. */
    @JsonProperty("chunkSize") val chunkSize: Long? = null,
    @JsonProperty("doneChunks") val doneChunks: List<Int> = emptyList(),
    /** Connections currently downloading this file. */
    @JsonProperty("connections") val connections: Int = 0,
    /**
     * Where this download's files go, without their extension, picked once when it first starts.
     * Two titles with the same name get different paths, so neither replaces the other's file.
     */
    @JsonProperty("basePath") val basePath: String? = null,
) {
    @get:JsonIgnore
    val playable: PlayableEpisode get() = PlayableEpisode(episodeData, episodeName, season, episode)

    @get:JsonIgnore
    val progress: Float?
        get() = when {
            status == DownloadStatus.Completed -> 1f
            segmentsTotal != null && segmentsTotal > 0 -> segmentsDone.toFloat() / segmentsTotal
            bytesTotal != null && bytesTotal > 0 -> bytesDone.toFloat() / bytesTotal
            else -> null
        }

    @get:JsonIgnore
    val file: File? get() = filePath?.let(::File)

    /** Saved as a folder of playlists and tracks (HLS with separate audio), not one file. */
    @get:JsonIgnore
    val isPackage: Boolean get() = file?.let(HlsPackage::isPackage) == true

    /** Space used on disk, the whole folder for a package. */
    @get:JsonIgnore
    val sizeOnDisk: Long get() = file?.let { f -> if (isPackage) f.parentFile?.listFiles()?.sumOf { it.length() } ?: 0L else f.length() } ?: 0L
}

/** What happened when finished downloads were moved to another folder. */
data class MoveResult(val moved: Int, val skipped: Int, val failed: Int)

/** What the details screen hands over to start a download. */
data class DownloadRequest(
    val apiName: String,
    val parentUrl: String,
    val title: String,
    val posterUrl: String?,
    val posterHeaders: Map<String, String>?,
    val episode: PlayableEpisode,
    /** The source the user picked. Null lets the downloader choose and fall back by itself. */
    val link: SavedLink? = null,
    /** Subtitles found together with [link], saved next to the video. */
    val subtitles: List<SubtitleFile> = emptyList(),
    /** Highest quality to pick automatically when no [link] is given. -1 means best, null follows Settings. */
    val maxQuality: Int? = null,
)

/**
 * Downloads movies and episodes, like the Android app's DownloadManager.
 *
 * Direct files resume with HTTP Range requests. HLS streams use the library's
 * M3u8Helper2.hslLazy (same as Android): best variant with audio, AES-128 decryption, and
 * several segments fetched in parallel but written in order. Subtitles are saved next to the
 * video. DASH and torrents are not downloadable, also like Android.
 */
object DownloadManager {
    /** How many files download at the same time; the rest wait in order. From Settings, 1 to 4. */
    private val maxParallel: Int get() = SettingsStore.state.value.parallelDownloads.coerceIn(1, 4)
    private const val PARALLEL_SEGMENTS = 3

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val file = jsonFile<List<DownloadEntry>>("downloads.json") { emptyList() }
    private val jobs = ConcurrentHashMap<String, Job>()

    /**
     * Jobs told to stop that may still be writing: a network read can take a while to notice. A
     * new job for the same download waits for them, so two never write the same file.
     */
    private val stopping = ConcurrentHashMap<String, Job>()

    /** Downloads switched to another source while running: progress is cleared again once the old job has stopped. */
    private val startFresh = ConcurrentHashMap.newKeySet<String>()

    private fun stop(id: String) {
        jobs.remove(id)?.let { job ->
            job.cancel()
            stopping[id] = job
            job.invokeOnCompletion { stopping.remove(id, job) }
        }
    }

    /** Subtitles handed over with a picked source, used when its download starts. */
    private val pendingSubtitles = ConcurrentHashMap<String, List<SubtitleFile>>()

    private val _entries = MutableStateFlow(
        // Anything that was running when the app closed is paused, the user resumes it
        file.read().map {
            if (it.status == DownloadStatus.Downloading || it.status == DownloadStatus.Queued) {
                it.copy(status = DownloadStatus.Paused, speed = 0)
            } else it
        }.toPersistentList()
    )
    val entries: StateFlow<PersistentList<DownloadEntry>> = _entries.asStateFlow()

    fun idFor(apiName: String, episodeData: String): String =
        UUID.nameUUIDFromBytes("$apiName|$episodeData".toByteArray()).toString()

    fun entry(apiName: String, episodeData: String): DownloadEntry? =
        _entries.value.firstOrNull { it.id == idFor(apiName, episodeData) }

    /** The downloaded file for an episode, if it finished and still exists on disk. */
    fun completedFile(apiName: String, episodeData: String): DownloadEntry? =
        entry(apiName, episodeData)?.takeIf { it.status == DownloadStatus.Completed && it.file?.exists() == true }

    val folder: File
        get() = File(
            SettingsStore.state.value.downloadFolder
                ?: com.lagradost.cloudstream4.desktop.Platform.defaultDownloadFolder().path
        ).apply { mkdirs() }

    // ---------- Commands ----------

    fun enqueue(request: DownloadRequest) {
        val id = idFor(request.apiName, request.episode.data)
        if (request.subtitles.isNotEmpty()) pendingSubtitles[id] = request.subtitles
        val existing = _entries.value.firstOrNull { it.id == id }
        if (existing != null) {
            if (existing.status == DownloadStatus.Completed) return
            // A different source was picked: start over with it
            if (request.link != null && request.link.url != existing.link?.url) {
                stop(id)
                startFresh += id
                set(id) {
                    it.copy(
                        // Queued again, so it starts: still "Downloading" with its job stopped, it never would
                        status = DownloadStatus.Queued, speed = 0, connections = 0,
                        link = request.link, chosen = true, bytesDone = 0, segmentsDone = 0, segmentsTotal = null, bytesTotal = null, chunkSize = null, doneChunks = emptyList(), error = null,
                    )
                }
            }
            resume(id)
            return
        }
        val entry = DownloadEntry(
            id = id,
            apiName = request.apiName,
            parentUrl = request.parentUrl,
            title = request.title,
            posterUrl = request.posterUrl,
            posterHeaders = request.posterHeaders,
            episodeData = request.episode.data,
            episodeName = request.episode.name,
            season = request.episode.season,
            episode = request.episode.episode,
            status = DownloadStatus.Queued,
            link = request.link,
            chosen = request.link != null,
            maxQuality = request.maxQuality,
        )
        _entries.update { (it + entry).toPersistentList() }
        save()
        pump()
    }

    fun pause(id: String) {
        stop(id)
        set(id) { if (it.status == DownloadStatus.Completed) it else it.copy(status = DownloadStatus.Paused, speed = 0) }
        save()
        pump()
    }

    fun resume(id: String) {
        set(id) {
            if (it.status == DownloadStatus.Completed || it.status == DownloadStatus.Downloading) it
            else it.copy(status = DownloadStatus.Queued, error = null)
        }
        save()
        pump()
    }

    /** Call after the parallel limit changes: starts more if it went up. Running ones are never stopped. */
    fun limitChanged() = pump()

    /** 1-based place in line for a waiting download, in the order they will start. Null when not waiting. */
    fun queuePosition(id: String): Int? {
        val waiting = _entries.value.filter { it.status == DownloadStatus.Queued }.sortedBy { it.addedAt }
        return waiting.indexOfFirst { it.id == id }.takeIf { it >= 0 }?.plus(1)
    }

    /** Pauses every unfinished download of one title. */
    fun pauseAll(apiName: String, parentUrl: String) {
        _entries.value.filter { it.apiName == apiName && it.parentUrl == parentUrl }
            .filter { it.status == DownloadStatus.Queued || it.status == DownloadStatus.Downloading }
            .forEach { pause(it.id) }
    }

    /** Whether anything is downloading or waiting to. */
    val anyActive: Boolean
        get() = _entries.value.any { it.status == DownloadStatus.Queued || it.status == DownloadStatus.Downloading }

    /** Pauses everything that's downloading or waiting, such as when Android stops background work. */
    fun pauseEverything() {
        _entries.value.filter { it.status == DownloadStatus.Queued || it.status == DownloadStatus.Downloading }.forEach { pause(it.id) }
    }

    /** Resumes or retries every unfinished download of one title. */
    fun resumeAll(apiName: String, parentUrl: String) {
        _entries.value.filter { it.apiName == apiName && it.parentUrl == parentUrl }
            .filter { it.status == DownloadStatus.Paused || it.status == DownloadStatus.Failed }
            .forEach { resume(it.id) }
    }

    /** Cancels and removes the download, deleting its files. */
    fun delete(id: String) {
        stop(id)
        val entry = _entries.value.firstOrNull { it.id == id } ?: return
        _entries.update { list -> list.filterNot { it.id == id }.toPersistentList() }
        save()
        pump()
        fun deleteFiles() {
            if (entry.isPackage) entry.file?.parentFile?.deleteRecursively() else entry.file?.delete()
            entry.file?.let { File(it.path + ".part").delete() }
            entry.subtitles.forEach { File(it).delete() }
            // Remove the title's folder once it is empty
            entry.file?.parentFile?.takeIf { it != folder && it.listFiles()?.isEmpty() == true }?.delete()
        }
        // A stopped job still writing would make the files again: wait for it to let go first
        val writing = stopping[id]
        if (writing == null) deleteFiles()
        else scope.launch { writing.join(); deleteFiles() }
    }

    /**
     * Moves finished downloads to [destination], keeping each title's own folder and subtitles.
     * Incomplete downloads are left where they are, since resuming depends on their path.
     */
    suspend fun move(ids: Collection<String>, destination: File): MoveResult = withContext(Dispatchers.IO) {
        val selected = _entries.value.filter { it.id in ids }
        val finished = selected.filter { it.status == DownloadStatus.Completed }
        val skipped = selected.size - finished.size
        if (finished.isEmpty()) return@withContext MoveResult(0, skipped, 0)
        if (!destination.isDirectory && !destination.mkdirs()) {
            return@withContext MoveResult(0, skipped, finished.size)
        }
        if (!destination.canWrite()) return@withContext MoveResult(0, skipped, finished.size)

        val moved = mutableMapOf<String, DownloadEntry>()
        var failed = 0
        for (entry in finished) {
            val updated = runCatching { moveEntry(entry, destination) }.getOrNull()
            if (updated != null) moved[entry.id] = updated else failed++
        }
        if (moved.isNotEmpty()) {
            _entries.update { list -> list.map { moved[it.id] ?: it }.toPersistentList() }
            save()
        }
        MoveResult(moved.size, skipped, failed)
    }

    private fun moveEntry(entry: DownloadEntry, destination: File): DownloadEntry? {
        val file = entry.file ?: return null
        if (!file.exists()) return null
        val titleDir = File(destination, safeName(entry.title)).apply { mkdirs() }

        if (entry.isPackage) {
            val sourceFolder = file.parentFile ?: return null
            val targetFolder = uniqueTarget(sourceFolder, titleDir)
            if (!movePath(sourceFolder, targetFolder)) return null
            val newFile = File(targetFolder, file.name)
            val subtitles = moveSubtitles(entry.subtitles, titleDir)
            removeEmptyTitleFolder(sourceFolder.parentFile)
            return entry.copy(filePath = newFile.path, subtitles = subtitles, basePath = null)
        }

        val target = uniqueTarget(file, titleDir)
        if (!movePath(file, target)) return null
        val subtitles = moveSubtitles(entry.subtitles, titleDir)
        removeEmptyTitleFolder(file.parentFile)
        return entry.copy(filePath = target.path, subtitles = subtitles, basePath = null)
    }

    /** [target] itself when it is already the source, otherwise a free name in [dir]. */
    private fun uniqueTarget(source: File, dir: File): File {
        val direct = File(dir, source.name)
        if (direct.absoluteFile.normalize() == source.absoluteFile.normalize()) return source
        if (!direct.exists()) return direct
        val directory = source.isDirectory
        // Keep a package's ".hls" ending, which is how a split-track download is recognised
        val hlsFolder = directory && source.extension.equals("hls", ignoreCase = true)
        val base = if (directory && !hlsFolder) source.name else source.nameWithoutExtension
        val extension = when {
            hlsFolder -> ".hls"
            directory -> ""
            else -> source.extension.takeIf { it.isNotBlank() }?.let { ".$it" }.orEmpty()
        }
        var n = 2
        while (true) {
            val candidate = File(dir, if (extension.isEmpty()) "$base ($n)" else "$base ($n)$extension")
            if (!candidate.exists()) return candidate
            n++
        }
    }

    /** Moves one file or folder, copying first when the destination is on another volume. */
    private fun movePath(source: File, target: File): Boolean {
        if (source.absoluteFile.normalize() == target.absoluteFile.normalize()) return true
        target.parentFile?.mkdirs()
        if (source.renameTo(target)) return true
        // Different volumes: copy first, then remove the source
        return try {
            if (source.isDirectory) source.copyRecursively(target, overwrite = false)
            else source.copyTo(target, overwrite = false)
            if (target.exists()) source.deleteRecursively() else false
        } catch (t: Throwable) {
            // Leave no half-copied file or folder behind
            target.deleteRecursively()
            false
        }
    }

    private fun moveSubtitles(subtitles: List<String>, titleDir: File): List<String> =
        subtitles.map { path ->
            val source = File(path)
            if (!source.exists()) return@map path
            val target = uniqueTarget(source, titleDir)
            if (movePath(source, target)) target.path else path
        }

    /** Removes the title's folder after moving, unless it still has something in it. */
    private fun removeEmptyTitleFolder(dir: File?) {
        if (dir == null) return
        val current = folder.absoluteFile.normalize()
        val title = dir.absoluteFile.normalize()
        if (title.parentFile == current && title.listFiles()?.isEmpty() == true) title.delete()
    }

    /** Starts queued downloads up to the parallel limit, oldest first. */
    @Synchronized
    private fun pump() {
        jobs.entries.removeIf { !it.value.isActive }
        val free = maxParallel - jobs.size
        if (free <= 0) return
        _entries.value
            .filter { it.status == DownloadStatus.Queued && !jobs.containsKey(it.id) }
            .sortedBy { it.addedAt }
            .take(free)
            .forEach { entry ->
                set(entry.id) { it.copy(status = DownloadStatus.Downloading, error = null) }
                val previous = stopping[entry.id]
                jobs[entry.id] = scope.launch {
                    previous?.join()
                    // The old job's last progress report may have landed after the switch
                    if (startFresh.remove(entry.id)) {
                        set(entry.id) { it.copy(bytesDone = 0, segmentsDone = 0, segmentsTotal = null, bytesTotal = null, chunkSize = null, doneChunks = emptyList()) }
                    }
                    work(entry.id)
                }
            }
    }

    // ---------- Worker ----------

    private suspend fun work(id: String) {
        try {
            val entry = current(id) ?: return
            val chosen = entry.link?.takeIf { entry.chosen }
            val succeeded = if (chosen != null) {
                // The user picked this source, so use only it
                pendingSubtitles.remove(id)?.let { downloadSubtitles(id, it) }
                try {
                    tryLinks(id, listOf(chosen))
                } catch (e: CancellationException) {
                    throw e
                } catch (t: Throwable) {
                    throw IOException("${t.message ?: "Download failed"} Try another source.", t)
                }
            } else {
                val link = entry.link?.let { listOf(it) }?.takeIf { entry.bytesDone > 0 || entry.segmentsDone > 0 }
                // Try the saved link first when resuming, then freshly loaded links. Progress is kept
                // until a link with another url starts, so a retry after a failure goes on from there
                link?.let { tryLinks(id, it) } == true || run {
                    val (links, subs) = findLinks(entry.apiName, entry.episodeData, maxQuality = entry.maxQuality)
                    if (links.isEmpty()) throw IOException("No downloadable links were found. Streams in DASH or torrent form can't be downloaded.")
                    downloadSubtitles(id, subs)
                    tryLinks(id, links)
                }
            }
            if (!succeeded) throw IOException("None of the sources could be downloaded.")
            set(id) { it.copy(status = DownloadStatus.Completed, speed = 0, error = null) }
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            set(id) { it.copy(status = DownloadStatus.Failed, speed = 0, error = t.message ?: t.javaClass.simpleName) }
        } finally {
            save()
            // Only this job's own place: a new one may already have taken it
            currentCoroutineContext()[Job]?.let { jobs.remove(id, it) }
            pump()
        }
    }

    /**
     * Asks the extension for links and keeps the downloadable ones (direct files and HLS), best
     * first. [onUpdate] gets the list so far each time a link arrives, so a picker can fill in live.
     */
    suspend fun findLinks(
        apiName: String,
        episodeData: String,
        maxQuality: Int? = null,
        onUpdate: (List<SavedLink>, List<SubtitleFile>) -> Unit = { _, _ -> },
    ): Pair<List<SavedLink>, List<SubtitleFile>> {
        val api = providerByName(apiName) ?: throw IOException("The $apiName extension isn't loaded.")
        val links = mutableListOf<ExtractorLink>()
        val subs = mutableListOf<SubtitleFile>()
        // -1 asks for the best, null follows the Settings preference
        val preferred = when (maxQuality) {
            null -> SettingsStore.state.value.preferredQuality
            -1 -> null
            else -> maxQuality
        }
        fun usable(): List<SavedLink> = synchronized(links) { links.toList() }
            .filter { it.type == ExtractorLinkType.VIDEO || it.type == ExtractorLinkType.M3U8 }
            .distinctBy { it.url }
            .sortedWith(
                compareBy<ExtractorLink> { if (preferred != null && it.quality > preferred) 1 else 0 }
                    .thenByDescending { it.quality }
                    // Direct files are simpler and faster than segmented streams
                    .thenBy { if (it.type == ExtractorLinkType.VIDEO) 0 else 1 }
            )
            .map { SavedLink(it.source, it.name, it.url, it.referer, it.quality, it.headers, it.type) }
        fun subtitles() = synchronized(subs) { subs.distinctBy { it.url } }

        withTimeoutOrNull(90_000) {
            api.loadLinks(
                episodeData,
                false,
                { sub -> synchronized(subs) { subs += sub }; onUpdate(usable(), subtitles()) },
                { link -> synchronized(links) { links += link }; onUpdate(usable(), subtitles()) },
            )
        }
        return usable() to subtitles()
    }

    /** Tries each link in turn. Returns true once one downloads completely. */
    private suspend fun tryLinks(id: String, links: List<SavedLink>): Boolean {
        var lastError: Throwable? = null
        for (link in links) {
            coroutineScope { ensureActive() }
            set(id) {
                // Progress belongs to one url: a different one starts from nothing. The same one keeps
                // it, so a failed download that's retried continues where it stopped
                if (it.link?.url == link.url) it.copy(link = link, error = null)
                else it.copy(link = link, error = null, bytesDone = 0, segmentsDone = 0, segmentsTotal = null, bytesTotal = null, chunkSize = null, doneChunks = emptyList())
            }
            try {
                if (link.type == ExtractorLinkType.M3U8) downloadHls(id, link) else downloadDirect(id, link)
                return true
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                lastError = t
            }
        }
        lastError?.let { throw it }
        return false
    }

    /** fMP4 streams keep their codec setup in a separate init segment; without it nothing plays. */
    private suspend fun fetchInitSegment(items: M3u8Helper2.LazyHlsDownloadData): ByteArray? {
        val url = items.initSegmentUrl ?: return null
        var last: Throwable? = null
        for (attempt in 0 until 3) {
            try {
                val body = app.get(url, headers = items.headers).body
                return body.bytes().also { body.close() }
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                last = t
                if (attempt < 2) delay(1_500)
            }
        }
        throw IOException("The stream's initialization segment failed to download.", last)
    }

    private fun File.startsWith(bytes: ByteArray): Boolean {
        if (!exists() || length() < bytes.size) return false
        val head = ByteArray(bytes.size)
        RandomAccessFile(this, "r").use { it.readFully(head) }
        return head.contentEquals(bytes)
    }

    private fun headersFor(link: SavedLink): Map<String, String> = buildMap {
        put("User-Agent", USER_AGENT)
        if (link.referer.isNotBlank()) put("Referer", link.referer)
        link.headers.forEach { (k, v) ->
            keys.firstOrNull { it.equals(k, ignoreCase = true) }?.let { remove(it) }
            put(k, v)
        }
    }

    /**
     * Where this download's files go, without their extension. Picked the first time and kept, so
     * resuming finds its partial file, and never one another download uses: two titles with the
     * same name, or two episodes with the same label, would otherwise replace each other's files.
     */
    @Synchronized
    private fun targetBase(entry: DownloadEntry): File {
        current(entry.id)?.basePath?.let { return File(it).also { f -> f.parentFile?.mkdirs() } }
        val dir = File(folder, safeName(entry.title)).apply { mkdirs() }
        val name = safeName(
            when {
                entry.season != null && entry.episode != null ->
                    "S%02dE%02d".format(entry.season, entry.episode) + (entry.episodeName?.let { " - $it" } ?: "")
                entry.episode != null -> "E%02d".format(entry.episode) + (entry.episodeName?.let { " - $it" } ?: "")
                entry.episodeName != null -> entry.episodeName
                else -> entry.title
            }
        )
        val others = _entries.value.filter { it.id != entry.id }
        fun ours(base: File) = entry.filePath?.let { belongsTo(it, base) } == true
        fun taken(base: File) = others.any { o -> o.basePath == base.path || o.filePath?.let { belongsTo(it, base) } == true } ||
            (dir.listFiles()?.any { it.name.startsWith(base.name + ".") } == true)
        var n = 1
        var base = File(dir, name)
        // A download from before paths were kept: its own partial file says which one is its
        while (!ours(base) && taken(base)) base = File(dir, "$name (${++n})")
        set(entry.id) { it.copy(basePath = base.path) }
        save()
        return base
    }

    /** Whether [path] is one of the files a download at [base] makes (its video, part file or package). */
    private fun belongsTo(path: String, base: File): Boolean =
        path.startsWith(base.path + ".") && !path.removePrefix(base.path + ".").contains(File.separatorChar) ||
            File(path).parentFile?.path == HlsPackage.folderFor(base).path

    private val windowsReserved = Regex("^(con|prn|aux|nul|com[0-9]|lpt[0-9])(\\..*)?$", RegexOption.IGNORE_CASE)

    /** A file or folder name that works on every system: Windows also refuses trailing dots and names like CON. */
    internal fun safeName(name: String): String {
        val cleaned = name.replace(Regex("[/\\\\:*?\"<>|\\p{Cntrl}]"), " ").replace(Regex("\\s+"), " ").trim().take(120)
            .trimEnd('.', ' ').ifBlank { "Untitled" }
        return if (windowsReserved.matches(cleaned)) "_$cleaned" else cleaned
    }

    /** Stops a download before the disk fills up, keeping what it has so it can go on later. */
    private fun requireSpace(dir: File, bytes: Long) {
        val free = dir.usableSpace
        // 0 means the system couldn't tell
        if (free in 1 until bytes + MIN_FREE_BYTES) {
            throw IOException("Not enough free space in ${dir.path}. Make room, then resume.")
        }
    }

    private const val MIN_FREE_BYTES = 200L shl 20

    /**
     * Separate HTTP/1.1 connections for downloads. Over HTTP/2 every range request would share a
     * single connection, and per-connection speed limits are what multiple connections get around.
     */
    private val downloadClient by lazy {
        app.baseClient.newBuilder()
            .protocols(listOf(Protocol.HTTP_1_1))
            .connectionPool(ConnectionPool(32, 5, TimeUnit.MINUTES))
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    private suspend fun downloadDirect(id: String, link: SavedLink) {
        val entry = current(id) ?: return
        // The url often has no extension, the release name in the link usually does (".mkv")
        val ext = extensionFrom(link.url)
            ?: parseSourceInfo(link.name, link.source, link.quality, link.type).format?.lowercase()?.takeIf { it.length in 2..4 }
            ?: "mp4"
        val target = File(targetBase(entry).path + ".$ext")
        val part = File(target.path + ".part")
        val headers = headersFor(link)
        val wanted = SettingsStore.state.value.downloadConnections

        if (wanted > 1) {
            val probe = runCatching { SegmentedDownload.probe(downloadClient, link.url, headers) }.getOrNull()
            if (probe?.contentType?.startsWith("text/html") == true) {
                throw IOException("The host returned a web page instead of the video.")
            }
            val total = probe?.total
            if (total != null) requireSpace(target.parentFile, total - if (part.exists()) part.length() else 0)
            if (probe != null && probe.acceptsRanges && total != null && total > 16L shl 20) {
                val resume = entry.chunkSize != null && entry.bytesTotal == total && entry.link?.url == link.url &&
                    part.exists() && part.length() == total
                val chunkSize = if (resume) entry.chunkSize!! else SegmentedDownload.chunkSizeFor(total)
                // A partial file from a single-connection download is complete from its start,
                // so the chunks it fully covers are already done
                val singlePrefix = !resume && entry.chunkSize == null && entry.link?.url == link.url && entry.bytesDone > 0 &&
                    part.exists() && part.length() in 1 until total
                val alreadyDone = when {
                    resume -> entry.doneChunks
                    singlePrefix -> (0 until (part.length() / chunkSize).toInt()).toList()
                    else -> emptyList()
                }
                if (!resume && !singlePrefix) part.delete()
                val segmented = SegmentedDownload(downloadClient, link.url, headers, part, total, chunkSize, wanted, alreadyDone)
                set(id) {
                    it.copy(filePath = part.path, bytesTotal = total, chunkSize = chunkSize, doneChunks = segmented.doneChunks, bytesDone = segmented.bytesDone)
                }
                try {
                    coroutineScope {
                        val ticker = launch {
                            val meter = SpeedMeter(segmented.bytesDone)
                            while (isActive) {
                                delay(1_000)
                                val bytes = segmented.bytesDone
                                val speed = meter.tick(bytes)
                                set(id) {
                                    it.copy(bytesDone = bytes, doneChunks = segmented.doneChunks, connections = segmented.activeConnections, speed = speed ?: it.speed)
                                }
                                save()
                            }
                        }
                        try {
                            segmented.run()
                        } finally {
                            ticker.cancel()
                        }
                    }
                } catch (e: RangesNotSupported) {
                    // The host stopped honouring ranges part way, start again in one piece
                    part.delete()
                    set(id) { it.copy(bytesDone = 0, chunkSize = null, doneChunks = emptyList(), connections = 0) }
                    downloadSingle(id, link, target, part)
                    return
                } finally {
                    set(id) { it.copy(bytesDone = segmented.bytesDone, doneChunks = segmented.doneChunks, connections = 0) }
                }
                finish(id, part, target)
                return
            }
        }
        downloadSingle(id, link, target, part)
    }

    /** One connection, for hosts that don't support ranges. Resumes by appending. */
    private suspend fun downloadSingle(id: String, link: SavedLink, target: File, part: File) {
        val entry = current(id) ?: return
        // A partial file from a multi-connection attempt has holes, so it can't be appended to
        val offset = if (entry.link?.url == link.url && entry.chunkSize == null && entry.bytesDone > 0 && part.exists()) part.length() else 0L
        if (offset == 0L) part.delete()

        val request = Request.Builder().url(link.url).apply {
            headersFor(link).forEach { (k, v) -> header(k, v) }
            if (offset > 0) header("Range", "bytes=$offset-")
        }.build()

        downloadClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("The host answered with HTTP ${response.code}.")
            val type = response.header("Content-Type").orEmpty()
            if (type.startsWith("text/html")) throw IOException("The host returned a web page instead of the video.")
            val resuming = offset > 0 && response.code == 206
            val start = if (resuming) offset else 0L
            val total = response.header("Content-Range")?.substringAfter('/')?.toLongOrNull()
                ?: response.body.contentLength().takeIf { it > 0 }?.plus(start)
            set(id) { it.copy(filePath = part.path, bytesDone = start, bytesTotal = total, connections = 1) }

            FileOutputStream(part, resuming).use { out ->
                response.body.byteStream().use { input ->
                    val buffer = ByteArray(256 * 1024)
                    var done = start
                    val meter = SpeedMeter(start)
                    while (true) {
                        coroutineScope { ensureActive() }
                        val read = input.read(buffer)
                        if (read < 0) break
                        out.write(buffer, 0, read)
                        done += read
                        meter.tick(done)?.let { speed ->
                            set(id) { it.copy(bytesDone = done, speed = speed) }
                            save()
                            requireSpace(part.parentFile, 0)
                        }
                    }
                    set(id) { it.copy(bytesDone = done, bytesTotal = total ?: done, connections = 0) }
                }
            }
        }
        finish(id, part, target)
    }

    private suspend fun downloadHls(id: String, link: SavedLink) {
        val entry = current(id) ?: return
        val stream = M3u8Helper.M3u8Stream(link.url, link.quality, headersFor(link))
        val items = try {
            M3u8Helper2.hslLazy(stream, selectBest = true, requireAudio = true)
        } catch (e: IllegalStateException) {
            // Audio in its own playlist: download both tracks and save them as a local HLS package
            if (e.message?.contains("no video with audio") != true) throw e
            downloadHlsSeparateAudio(id, link)
            return
        }
        val base = targetBase(entry)
        // Extension is decided at the end from the data, "ts" or "mp4"
        val part = File(base.path + ".hls.part")
        val init = fetchInitSegment(items)

        val sameLink = entry.link?.url == link.url && part.exists() && entry.segmentsTotal == items.size
        var index = if (sameLink) entry.segmentsDone else 0
        var written = if (sameLink) entry.bytesDone else 0L
        RandomAccessFile(part, "rw").use { it.setLength(written) }
        if (init != null && !part.startsWith(init)) {
            // Older partial downloads started with the first media segment. Keep those bytes and
            // put the init segment in front instead of downloading them again.
            if (part.length() > 0) {
                // Streamed through a second file: the partial download can be gigabytes
                val joined = File(part.path + ".init")
                joined.outputStream().use { out ->
                    out.write(init)
                    part.inputStream().use { it.copyTo(out) }
                }
                if (!joined.renameTo(part)) {
                    part.delete()
                    if (!joined.renameTo(part)) throw IOException("Could not save ${part.name}.")
                }
                written += init.size
            } else {
                part.writeBytes(init)
                written = init.size.toLong()
                index = 0
            }
        }
        set(id) { it.copy(filePath = part.path, segmentsTotal = items.size, segmentsDone = index, bytesDone = written) }

        FileOutputStream(part, true).use { out ->
            val meter = SpeedMeter(written)
            while (index < items.size) {
                coroutineScope { ensureActive() }
                val window = (index until minOf(index + PARALLEL_SEGMENTS, items.size)).toList()
                val chunks = coroutineScope {
                    window.map { i -> async { items.resolveLinkSafe(i) } }.awaitAll()
                }
                for (chunk in chunks) {
                    if (chunk == null) throw IOException("Segment ${index + 1} of ${items.size} failed to download.")
                    out.write(chunk)
                    written += chunk.size
                    index++
                }
                out.flush()
                val done = index
                val bytes = written
                val speed = meter.tick(bytes)
                set(id) { it.copy(segmentsDone = done, bytesDone = bytes, speed = speed ?: it.speed) }
                if (speed != null) {
                    save()
                    requireSpace(part.parentFile, 0)
                }
            }
        }
        // MPEG-TS packets start with the sync byte 0x47, anything else is fragmented mp4
        val firstByte = part.inputStream().use { it.read() }
        val target = File(base.path + if (firstByte == 0x47) ".ts" else ".mp4")
        finish(id, part, target)
    }

    /**
     * For streams that keep their audio in a separate playlist: the best video variant and its
     * default audio are downloaded side by side into a folder that plays offline as HLS.
     * Subtitles listed in the playlist are saved next to it too.
     */
    private suspend fun downloadHlsSeparateAudio(id: String, link: SavedLink) {
        val entry = current(id) ?: return
        val headers = headersFor(link)
        val text = app.get(link.url, headers = headers).text
        val master = HlsPlaylistParser.parse(link.url, text) ?: throw IOException("The stream's playlist couldn't be read.")
        val variant = master.variants.filter { !it.isTrickPlay() }
            .maxByOrNull { (it.format.width * it.format.height).toLong() * 1000L + it.format.averageBitrate.toLong() }
            ?: throw IOException("The stream has no video.")
        val group = master.audios.filter { it.groupId == variant.audioGroupId && it.url != null }
        val audio = group.firstOrNull { it.format.selectionFlags and 1 != 0 } ?: group.firstOrNull()
            ?: throw IOException("The stream's audio couldn't be found.")

        val video = M3u8Helper2.hslLazy(M3u8Helper.M3u8Stream(variant.url.toString(), variant.format.height.takeIf { it > 0 }, headers), selectBest = true, requireAudio = false)
        val sound = M3u8Helper2.hslLazy(M3u8Helper.M3u8Stream(audio.url.toString(), null, headers), selectBest = true, requireAudio = false)
        val videoInit = fetchInitSegment(video)
        val audioInit = fetchInitSegment(sound)

        val base = targetBase(entry)
        val dir = HlsPackage.folderFor(base)
        val total = video.size + sound.size
        // A different source or segment count means the old folder belongs to another stream
        if (!(entry.link?.url == link.url && entry.segmentsTotal == total)) dir.deleteRecursively()
        dir.mkdirs()
        val videoTrack = HlsTrack(dir, "video").apply { load() }
        val audioTrack = HlsTrack(dir, "audio").apply { load() }
        videoInit?.let { videoTrack.saveInit(it) }
        audioInit?.let { audioTrack.saveInit(it) }
        set(id) { it.copy(filePath = File(dir, HlsPackage.MASTER).path, segmentsTotal = total) }

        val meter = SpeedMeter(videoTrack.bytes + audioTrack.bytes)
        suspend fun fetch(items: M3u8Helper2.LazyHlsDownloadData, track: HlsTrack, label: String) {
            while (track.segments.size < items.size) {
                coroutineScope { ensureActive() }
                val start = track.segments.size
                val window = (start until minOf(start + PARALLEL_SEGMENTS, items.size)).toList()
                val chunks = coroutineScope { window.map { i -> async { items.resolveLinkSafe(i) } }.awaitAll() }
                chunks.forEachIndexed { k, chunk ->
                    val i = window[k]
                    if (chunk == null) throw IOException("$label segment ${i + 1} of ${items.size} failed to download.")
                    track.append(chunk, items.allTsLinks[i].time ?: 10.0)
                }
                val done = videoTrack.segments.size + audioTrack.segments.size
                val bytes = videoTrack.bytes + audioTrack.bytes
                val speed = meter.tick(bytes)
                set(id) { it.copy(segmentsDone = done, bytesDone = bytes, speed = speed ?: it.speed) }
                if (speed != null) {
                    save()
                    requireSpace(dir, 0)
                }
            }
        }
        fetch(video, videoTrack, "Video")
        fetch(sound, audioTrack, "Audio")

        HlsPackage.writeMaster(
            dir, videoTrack, audioTrack,
            audioName = audio.name.ifBlank { audio.format.label ?: "Audio" },
            audioLanguage = audio.format.language,
            width = variant.format.width.takeIf { it > 0 },
            height = variant.format.height.takeIf { it > 0 },
        )
        saveHlsSubtitles(id, master, variant.subtitleGroupId, headers, base)
        set(id) { it.copy(filePath = File(dir, HlsPackage.MASTER).path) }
    }

    /** Subtitles the playlist lists as a single file each (usually WebVTT), saved like provider subtitles. */
    private suspend fun saveHlsSubtitles(
        id: String,
        master: HlsPlaylistParser.HlsMultivariantPlaylist,
        groupId: String?,
        headers: Map<String, String>,
        base: File,
    ) {
        val renditions = master.subtitles.filter { it.url != null && (groupId == null || it.groupId == groupId) }.take(12)
        val saved = renditions.mapNotNull { r ->
            runCatching {
                val playlistUrl = r.url.toString()
                val playlist = app.get(playlistUrl, headers = headers).text
                val files = playlist.lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
                // Segmented subtitles would need joining; most sites list one file
                val only = files.singleOrNull() ?: return@runCatching null
                val url = java.net.URI(playlistUrl).resolve(only).toString()
                val lang = safeName(r.name.ifBlank { r.format.language ?: "Subtitles" }).take(30)
                val ext = extensionFrom(url)?.takeIf { it in setOf("srt", "vtt", "ass", "ssa") } ?: "vtt"
                val file = File("${base.path}.$lang.$ext")
                file.writeBytes(app.get(url, headers = headers).body.bytes())
                file.path
            }.getOrNull()
        }
        if (saved.isNotEmpty()) set(id) { it.copy(subtitles = (it.subtitles + saved).distinct()) }
    }

    private fun finish(id: String, part: File, target: File) {
        // Only ever replaces this download's own earlier file, never another download's
        var file = target
        var n = 2
        while (file.exists() && _entries.value.any { it.id != id && it.filePath == file.path }) {
            file = File(target.parentFile, "${target.nameWithoutExtension} ($n).${target.extension}")
            n++
        }
        file.delete()
        if (!part.renameTo(file)) throw IOException("Could not save ${file.name}.")
        set(id) { it.copy(filePath = file.path) }
    }

    private suspend fun downloadSubtitles(id: String, subs: List<SubtitleFile>) {
        val entry = current(id) ?: return
        val base = targetBase(entry)
        val saved = subs.take(12).mapIndexedNotNull { i, sub ->
            runCatching {
                val ext = extensionFrom(sub.url)?.takeIf { it in setOf("srt", "vtt", "ass", "ssa", "sub") } ?: "vtt"
                val lang = safeName(sub.lang).take(30)
                val file = File("${base.path}.${if (i == 0) lang else "$lang-$i"}.$ext")
                val request = Request.Builder().url(sub.url).apply {
                    header("User-Agent", USER_AGENT)
                    sub.headers?.forEach { (k, v) -> header(k, v) }
                }.build()
                app.baseClient.newCall(request).execute().use { r ->
                    if (!r.isSuccessful) return@runCatching null
                    file.writeBytes(r.body.bytes())
                }
                file.path
            }.getOrNull()
        }
        set(id) { it.copy(subtitles = saved) }
    }

    private fun extensionFrom(url: String): String? =
        runCatching { URI(url).path.substringAfterLast('/', "").substringAfterLast('.', "") }.getOrNull()
            ?.lowercase()?.takeIf { it.length in 2..4 && it.all(Char::isLetterOrDigit) && it !in setOf("m3u8", "php", "html", "htm") }

    // ---------- State ----------

    private fun current(id: String) = _entries.value.firstOrNull { it.id == id }

    private fun set(id: String, block: (DownloadEntry) -> DownloadEntry) = _entries.update { list ->
        list.map { if (it.id == id) block(it) else it }.toPersistentList()
    }

    @Synchronized
    private fun save() = file.write(_entries.value)

    /** Reports bytes per second at most once a second. */
    private class SpeedMeter(start: Long) {
        private var lastTime = System.currentTimeMillis()
        private var lastBytes = start
        fun tick(bytes: Long): Long? {
            val now = System.currentTimeMillis()
            val elapsed = now - lastTime
            if (elapsed < 1000) return null
            val speed = (bytes - lastBytes) * 1000 / elapsed
            lastTime = now
            lastBytes = bytes
            return speed
        }
    }
}
