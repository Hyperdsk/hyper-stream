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
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
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
}

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
                ?: (System.getProperty("user.home") + "/Movies/CloudStream")
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
                jobs.remove(id)?.cancel()
                set(id) {
                    it.copy(link = request.link, chosen = true, bytesDone = 0, segmentsDone = 0, segmentsTotal = null, bytesTotal = null, chunkSize = null, doneChunks = emptyList(), error = null)
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
        jobs.remove(id)?.cancel()
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

    /** Resumes or retries every unfinished download of one title. */
    fun resumeAll(apiName: String, parentUrl: String) {
        _entries.value.filter { it.apiName == apiName && it.parentUrl == parentUrl }
            .filter { it.status == DownloadStatus.Paused || it.status == DownloadStatus.Failed }
            .forEach { resume(it.id) }
    }

    /** Cancels and removes the download, deleting its files. */
    fun delete(id: String) {
        jobs.remove(id)?.cancel()
        val entry = _entries.value.firstOrNull { it.id == id } ?: return
        entry.file?.delete()
        entry.file?.let { File(it.path + ".part").delete() }
        entry.subtitles.forEach { File(it).delete() }
        // Remove the title's folder once it is empty
        entry.file?.parentFile?.takeIf { it != folder && it.listFiles()?.isEmpty() == true }?.delete()
        _entries.update { list -> list.filterNot { it.id == id }.toPersistentList() }
        save()
        pump()
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
                jobs[entry.id] = scope.launch { work(entry.id) }
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
                // Try the saved link first when resuming, then freshly loaded links
                link?.let { tryLinks(id, it) } == true || run {
                    set(id) { it.copy(bytesDone = 0, segmentsDone = 0, segmentsTotal = null, bytesTotal = null, chunkSize = null, doneChunks = emptyList()) }
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
            jobs.remove(id)
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
            set(id) { it.copy(link = link, error = null) }
            try {
                if (link.type == ExtractorLinkType.M3U8) downloadHls(id, link) else downloadDirect(id, link)
                return true
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                lastError = t
                set(id) { it.copy(bytesDone = 0, segmentsDone = 0, segmentsTotal = null, bytesTotal = null, chunkSize = null, doneChunks = emptyList()) }
            }
        }
        lastError?.let { throw it }
        return false
    }

    private fun headersFor(link: SavedLink): Map<String, String> = buildMap {
        put("User-Agent", USER_AGENT)
        if (link.referer.isNotBlank()) put("Referer", link.referer)
        link.headers.forEach { (k, v) ->
            keys.firstOrNull { it.equals(k, ignoreCase = true) }?.let { remove(it) }
            put(k, v)
        }
    }

    private fun targetBase(entry: DownloadEntry): File {
        val dir = File(folder, safeName(entry.title)).apply { mkdirs() }
        val name = when {
            entry.season != null && entry.episode != null ->
                "S%02dE%02d".format(entry.season, entry.episode) + (entry.episodeName?.let { " - $it" } ?: "")
            entry.episode != null -> "E%02d".format(entry.episode) + (entry.episodeName?.let { " - $it" } ?: "")
            entry.episodeName != null -> entry.episodeName
            else -> entry.title
        }
        return File(dir, safeName(name))
    }

    private fun safeName(name: String) =
        name.replace(Regex("[/\\\\:*?\"<>|\\p{Cntrl}]"), " ").replace(Regex("\\s+"), " ").trim().take(120).ifBlank { "Untitled" }

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
            if (probe != null && probe.acceptsRanges && total != null && total > 16L shl 20) {
                val resume = entry.chunkSize != null && entry.bytesTotal == total && entry.link?.url == link.url &&
                    part.exists() && part.length() == total
                val chunkSize = if (resume) entry.chunkSize!! else SegmentedDownload.chunkSizeFor(total)
                // A partial file from a single-connection download is complete from its start,
                // so the chunks it fully covers are already done
                val singlePrefix = !resume && entry.chunkSize == null && entry.link?.url == link.url &&
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
        val offset = if (entry.link?.url == link.url && entry.chunkSize == null && part.exists()) part.length() else 0L
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
                        meter.tick(done)?.let { speed -> set(id) { it.copy(bytesDone = done, speed = speed) }; save() }
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
        val items = M3u8Helper2.hslLazy(stream, selectBest = true, requireAudio = true)
        val base = targetBase(entry)
        // Extension is decided at the end from the data, "ts" or "mp4"
        val part = File(base.path + ".hls.part")

        val sameLink = entry.link?.url == link.url && part.exists() && entry.segmentsTotal == items.size
        var index = if (sameLink) entry.segmentsDone else 0
        var written = if (sameLink) entry.bytesDone else 0L
        RandomAccessFile(part, "rw").use { it.setLength(written) }
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
                if (speed != null) save()
            }
        }
        // MPEG-TS packets start with the sync byte 0x47, anything else is fragmented mp4
        val firstByte = part.inputStream().use { it.read() }
        val target = File(base.path + if (firstByte == 0x47) ".ts" else ".mp4")
        finish(id, part, target)
    }

    private fun finish(id: String, part: File, target: File) {
        target.delete()
        if (!part.renameTo(target)) throw IOException("Could not save ${target.name}.")
        set(id) { it.copy(filePath = target.path) }
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
