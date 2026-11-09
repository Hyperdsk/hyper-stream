package com.lagradost.cloudstream4.desktop

import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream4.desktop.data.SettingsStore
import com.lagradost.cloudstream4.desktop.download.DownloadManager
import com.lagradost.cloudstream4.desktop.download.DownloadRequest
import com.lagradost.cloudstream4.desktop.download.DownloadStatus
import com.lagradost.cloudstream4.desktop.ui.PlayableEpisode
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Downloads from a local server: a direct file that is paused and resumed, and an AES-128 HLS stream. */
class DownloadManagerTest {
    private val movie = Random(1).nextBytes(3 * 1024 * 1024)
    // MPEG-TS packets start with 0x47
    private val segments = List(3) { i -> ByteArray(188 * 50) { if (it % 188 == 0) 0x47 else (i * 31 + it).toByte() } }
    private val key = Random(2).nextBytes(16)
    private val fmp4Init = "ftyp-fmp4-init".encodeToByteArray()
    private val fmp4Segments = List(2) { i -> ByteArray(256) { (i * 7 + it).toByte() } }
    private val rangeRequests = AtomicInteger()
    // Different files of the same size, from different sources
    private val flakyBytes = Random(3).nextBytes(3 * 1024 * 1024)
    private val otherBytes = Random(4).nextBytes(3 * 1024 * 1024)
    private val twinA = Random(5).nextBytes(1024 * 1024)
    private val twinB = Random(6).nextBytes(1024 * 1024)

    private fun encrypt(data: ByteArray, index: Int): ByteArray {
        // Same IV rule as the library and the Android app: segment index + 1, big endian
        val iv = ByteBuffer.allocate(16).putInt(12, index + 1).array()
        return Cipher.getInstance("AES/CBC/PKCS5Padding").run {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
            doFinal(data)
        }
    }

    private fun HttpExchange.send(bytes: ByteArray, code: Int = 200, type: String = "application/octet-stream") {
        responseHeaders.set("Content-Type", type)
        sendResponseHeaders(code, bytes.size.toLong())
        responseBody.use { it.write(bytes) }
    }

    /** Serves [bytes], honouring "Range: bytes=N-" for resuming. */
    private fun HttpExchange.sendRanged(bytes: ByteArray) {
        val start = requestHeaders.getFirst("Range")?.removePrefix("bytes=")?.substringBefore('-')?.toLongOrNull() ?: 0L
        responseHeaders.set("Content-Type", "video/mp4")
        if (start > 0) responseHeaders.set("Content-Range", "bytes $start-${bytes.size - 1}/${bytes.size}")
        sendResponseHeaders(if (start > 0) 206 else 200, bytes.size - start)
        runCatching { responseBody.use { it.write(bytes, start.toInt(), (bytes.size - start).toInt()) } }
    }

    private fun server() = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0).apply {
        // Promises the whole file, sends half, then drops the connection
        createContext("/flaky.mp4") { ex ->
            ex.responseHeaders.set("Content-Type", "video/mp4")
            ex.sendResponseHeaders(200, flakyBytes.size.toLong())
            runCatching {
                ex.responseBody.write(flakyBytes, 0, flakyBytes.size / 2)
                ex.responseBody.flush()
                ex.close()
            }
        }
        createContext("/other.mp4") { it.sendRanged(otherBytes) }
        createContext("/twin-a.mp4") { it.sendRanged(twinA) }
        createContext("/twin-b.mp4") { it.sendRanged(twinB) }
        createContext("/movie.mp4") { ex ->
            // Slow, so the test can pause part way. Honours Range for resuming.
            val start = ex.requestHeaders.getFirst("Range")?.removePrefix("bytes=")?.substringBefore('-')?.toLongOrNull() ?: 0L
            if (start > 0) rangeRequests.incrementAndGet()
            ex.responseHeaders.set("Content-Type", "video/mp4")
            if (start > 0) ex.responseHeaders.set("Content-Range", "bytes $start-${movie.size - 1}/${movie.size}")
            ex.sendResponseHeaders(if (start > 0) 206 else 200, movie.size - start)
            runCatching {
                ex.responseBody.use { out ->
                    var pos = start.toInt()
                    while (pos < movie.size) {
                        val n = minOf(64 * 1024, movie.size - pos)
                        out.write(movie, pos, n)
                        out.flush()
                        pos += n
                        Thread.sleep(25)
                    }
                }
            }
        }
        createContext("/hls/index.m3u8") { ex ->
            val playlist = buildString {
                appendLine("#EXTM3U")
                appendLine("#EXT-X-TARGETDURATION:4")
                appendLine("#EXT-X-KEY:METHOD=AES-128,URI=\"key.bin\"")
                segments.indices.forEach { appendLine("#EXTINF:4.0,"); appendLine("seg$it.ts") }
                appendLine("#EXT-X-ENDLIST")
            }
            ex.send(playlist.toByteArray(), type = "application/vnd.apple.mpegurl")
        }
        createContext("/hls/key.bin") { it.send(key) }
        segments.indices.forEach { i -> createContext("/hls/seg$i.ts") { it.send(encrypt(segments[i], i)) } }
        createContext("/fmp4/index.m3u8") { ex ->
            val playlist = buildString {
                appendLine("#EXTM3U")
                appendLine("#EXT-X-VERSION:6")
                appendLine("#EXT-X-TARGETDURATION:4")
                appendLine("#EXT-X-MAP:URI=\"init.mp4\"")
                fmp4Segments.indices.forEach { appendLine("#EXTINF:4.0,"); appendLine("seg$it.m4s") }
                appendLine("#EXT-X-ENDLIST")
            }
            ex.send(playlist.toByteArray(), type = "application/vnd.apple.mpegurl")
        }
        createContext("/fmp4/init.mp4") { it.send(fmp4Init) }
        fmp4Segments.indices.forEach { i -> createContext("/fmp4/seg$i.m4s") { it.send(fmp4Segments[i]) } }
        createContext("/subs.vtt") { it.send("WEBVTT\n\n00:00.000 --> 00:01.000\nHello\n".toByteArray(), type = "text/vtt") }
        start()
    }

    private class TestProvider(private val base: String) : MainAPI() {
        override var name = "DownloadTest"
        override var mainUrl = base
        override suspend fun loadLinks(
            data: String,
            isCasting: Boolean,
            subtitleCallback: (SubtitleFile) -> Unit,
            callback: (ExtractorLink) -> Unit,
        ): Boolean {
            subtitleCallback(newSubtitleFile("English", "$base/subs.vtt"))
            when (data) {
                "direct", "direct2" -> callback(newExtractorLink(name, "Direct", "$base/movie.mp4", ExtractorLinkType.VIDEO) { quality = 720 })
                "fmp4" -> callback(newExtractorLink(name, "HLS", "$base/fmp4/index.m3u8", ExtractorLinkType.M3U8) { quality = 720 })
                else -> callback(newExtractorLink(name, "HLS", "$base/hls/index.m3u8", ExtractorLinkType.M3U8) { quality = 720 })
            }
            return true
        }
    }

    /** Offers the same links, best first, for every episode. */
    private class FixedLinksProvider(providerName: String, private val links: List<Pair<String, Int>>) : MainAPI() {
        override var name = providerName
        override var mainUrl = "http://127.0.0.1"
        override suspend fun loadLinks(
            data: String,
            isCasting: Boolean,
            subtitleCallback: (SubtitleFile) -> Unit,
            callback: (ExtractorLink) -> Unit,
        ): Boolean {
            links.forEach { (url, q) -> callback(newExtractorLink(name, "Server $q", url, ExtractorLinkType.VIDEO) { quality = q }) }
            return true
        }
    }

    private suspend fun awaitFinished(id: String) = withTimeout(30_000) {
        DownloadManager.entries.first { list -> list.any { it.id == id && it.status in setOf(DownloadStatus.Completed, DownloadStatus.Failed) } }
    }.first { it.id == id }

    @Test
    fun aFallbackSourceNeverCarriesOnFromAnotherSourcesPartialFile() = runBlocking {
        System.setProperty("cloudstream.dataDir", Files.createTempDirectory("cs-dl-fallback").toString())
        val folder = Files.createTempDirectory("cs-downloads-fallback").toFile()
        SettingsStore.update { it.copy(downloadFolder = folder.path) }
        val server = server()
        val base = "http://127.0.0.1:${server.address.port}"
        // The better link breaks half way; the next one is a different file of the same size
        val api = FixedLinksProvider("FallbackTest", listOf("$base/flaky.mp4" to 1080, "$base/other.mp4" to 720))
            .apply { sourcePlugin = "test-fallback" }
        APIHolder.allProviders.add(api)
        try {
            DownloadManager.enqueue(
                DownloadRequest("FallbackTest", "$base/fallback", "Fallback Movie", null, null, PlayableEpisode("auto", null, null, null), maxQuality = -1)
            )
            val auto = awaitFinished(DownloadManager.idFor("FallbackTest", "auto"))
            assertEquals(DownloadStatus.Completed, auto.status, auto.error)
            assertEquals("$base/other.mp4", auto.link?.url)
            // Not the first half of one file joined to the second half of the other
            assertContentEquals(otherBytes, auto.file!!.readBytes())

            // A picked source that broke half way, then another source picked for the same episode
            val (links, _) = DownloadManager.findLinks("FallbackTest", "picked", maxQuality = -1)
            val episode = PlayableEpisode("picked", null, null, null)
            DownloadManager.enqueue(DownloadRequest("FallbackTest", "$base/picked", "Picked Fallback", null, null, episode, link = links.first { "flaky" in it.url }))
            val pickedId = DownloadManager.idFor("FallbackTest", "picked")
            val broken = awaitFinished(pickedId)
            assertEquals(DownloadStatus.Failed, broken.status)
            DownloadManager.enqueue(DownloadRequest("FallbackTest", "$base/picked", "Picked Fallback", null, null, episode, link = links.first { "other" in it.url }))
            val picked = withTimeout(30_000) {
                DownloadManager.entries.first { list -> list.any { it.id == pickedId && it.status == DownloadStatus.Completed } }
            }.first { it.id == pickedId }
            assertContentEquals(otherBytes, picked.file!!.readBytes())
            assertTrue(folder.walk().none { it.name.endsWith(".part") }, "no partial files left behind")
        } finally {
            APIHolder.allProviders.withLock { APIHolder.allProviders.removeAll { it.sourcePlugin == "test-fallback" } }
            server.stop(0)
        }
    }

    @Test
    fun downloadsWithTheSameNameGetTheirOwnFiles() = runBlocking {
        System.setProperty("cloudstream.dataDir", Files.createTempDirectory("cs-dl-twins").toString())
        val folder = Files.createTempDirectory("cs-downloads-twins").toFile()
        SettingsStore.update { it.copy(downloadFolder = folder.path, parallelDownloads = 2) }
        val server = server()
        val base = "http://127.0.0.1:${server.address.port}"
        val a = FixedLinksProvider("TwinA", listOf("$base/twin-a.mp4" to 720)).apply { sourcePlugin = "test-twins" }
        val b = FixedLinksProvider("TwinB", listOf("$base/twin-b.mp4" to 720)).apply { sourcePlugin = "test-twins" }
        APIHolder.allProviders.add(a)
        APIHolder.allProviders.add(b)
        try {
            // Two different movies that happen to share a title, from two extensions, downloading at
            // the same time, and a second item from the first extension with no episode numbers either
            DownloadManager.enqueue(DownloadRequest("TwinA", "$base/a", "Twin Movie", null, null, PlayableEpisode("twin", null, null, null)))
            delay(5) // distinct add times, so the order is defined
            DownloadManager.enqueue(DownloadRequest("TwinB", "$base/b", "Twin Movie", null, null, PlayableEpisode("twin", null, null, null)))
            delay(5)
            DownloadManager.enqueue(DownloadRequest("TwinA", "$base/a2", "Twin Movie", null, null, PlayableEpisode("twin2", null, null, null)))
            val first = awaitFinished(DownloadManager.idFor("TwinA", "twin"))
            val second = awaitFinished(DownloadManager.idFor("TwinB", "twin"))
            val third = awaitFinished(DownloadManager.idFor("TwinA", "twin2"))
            listOf(first, second, third).forEach { assertEquals(DownloadStatus.Completed, it.status, it.error) }

            assertEquals("Twin Movie.mp4", first.file!!.name)
            assertEquals("Twin Movie (TwinB).mp4", second.file!!.name)
            assertEquals(3, listOf(first, second, third).map { it.file!!.canonicalPath }.toSet().size, "each has its own file")
            assertContentEquals(twinA, first.file!!.readBytes())
            assertContentEquals(twinB, second.file!!.readBytes())
            assertContentEquals(twinA, third.file!!.readBytes())

            // Removing one leaves the others' videos alone
            DownloadManager.delete(second.id)
            withTimeout(5_000) { while (second.file!!.exists()) delay(50) }
            assertTrue(first.file!!.exists() && third.file!!.exists())
            DownloadManager.delete(first.id)
            DownloadManager.delete(third.id)
        } finally {
            APIHolder.allProviders.withLock { APIHolder.allProviders.removeAll { it.sourcePlugin == "test-twins" } }
            server.stop(0)
        }
    }

    private open class SeasonProvider(private val base: String) : MainAPI() {
        override var name = "SeasonTest"
        override var mainUrl = base
        override suspend fun loadLinks(
            data: String,
            isCasting: Boolean,
            subtitleCallback: (SubtitleFile) -> Unit,
            callback: (ExtractorLink) -> Unit,
        ): Boolean {
            // Each episode offers 1080p and 720p
            callback(newExtractorLink(name, "Server A", "$base/movie.mp4?ep=$data&q=1080", ExtractorLinkType.VIDEO) { quality = 1080 })
            callback(newExtractorLink(name, "Server B", "$base/movie.mp4?ep=$data&q=720", ExtractorLinkType.VIDEO) { quality = 720 })
            return true
        }
    }

    @Test
    fun downloadsAWholeSeasonAtTheChosenQuality() = runBlocking {
        System.setProperty("cloudstream.dataDir", Files.createTempDirectory("cs-dl").toString())
        val folder = Files.createTempDirectory("cs-downloads-season").toFile()
        SettingsStore.update { it.copy(downloadFolder = folder.path) }
        val server = server()
        val base = "http://127.0.0.1:${server.address.port}"
        val api = SeasonProvider(base).apply { sourcePlugin = "test-season" }
        APIHolder.allProviders.add(api)
        try {
            val episodes = (1..3).map { PlayableEpisode("s1e$it", "Episode $it", 1, it) }
            episodes.forEach {
                DownloadManager.enqueue(DownloadRequest("SeasonTest", "$base/show", "Season Show", null, null, it, maxQuality = 720))
            }
            val ids = episodes.map { DownloadManager.idFor("SeasonTest", it.data) }.toSet()
            var maxRunning = 0
            val done = withTimeout(60_000) {
                DownloadManager.entries.first { list ->
                    maxRunning = maxOf(maxRunning, list.count { it.id in ids && it.status == DownloadStatus.Downloading })
                    list.filter { it.id in ids }.all { it.status == DownloadStatus.Completed || it.status == DownloadStatus.Failed }
                }
            }.filter { it.id in ids }
            done.forEach { assertEquals(DownloadStatus.Completed, it.status, it.error) }
            done.forEach { assertEquals(720, it.link?.quality, "picked ${it.link?.name}") }
            assertTrue(maxRunning <= 2, "ran $maxRunning at once")
            val names = done.mapNotNull { it.file?.name }.sorted()
            println("Season files: $names, at most $maxRunning at once")
            assertEquals(listOf("S01E01 - Episode 1.mp4", "S01E02 - Episode 2.mp4", "S01E03 - Episode 3.mp4"), names)
        } finally {
            APIHolder.allProviders.withLock { APIHolder.allProviders.removeAll { it.sourcePlugin == "test-season" } }
            server.stop(0)
        }
    }

    @Test
    fun queueRespectsTheLimitAndMovesOnInOrder() = runBlocking {
        System.setProperty("cloudstream.dataDir", Files.createTempDirectory("cs-dl").toString())
        val folder = Files.createTempDirectory("cs-downloads-queue").toFile()
        SettingsStore.update { it.copy(downloadFolder = folder.path, parallelDownloads = 1) }
        val server = server()
        val base = "http://127.0.0.1:${server.address.port}"
        val api = SeasonProvider(base).apply { name = "QueueTest"; sourcePlugin = "test-queue" }
        APIHolder.allProviders.add(api)
        try {
            val episodes = (1..4).map { PlayableEpisode("q$it", "Part $it", 1, it) }
            episodes.forEach {
                DownloadManager.enqueue(DownloadRequest("QueueTest", "$base/queue", "Queue Show", null, null, it))
                delay(5) // distinct add times, so the order is defined
            }
            val ids = episodes.map { DownloadManager.idFor("QueueTest", it.data) }
            assertEquals(listOf(null, 1, 2, 3), ids.map { DownloadManager.queuePosition(it) })

            val startOrder = mutableListOf<String>()
            var maxRunning = 0
            var raised = false
            withTimeout(90_000) {
                DownloadManager.entries.first { list ->
                    val mine = list.filter { it.id in ids }
                    val running = mine.filter { it.status == DownloadStatus.Downloading }
                    running.forEach { if (it.id !in startOrder) startOrder += it.id }
                    maxRunning = maxOf(maxRunning, running.size)
                    // After the first file finishes, allow two at a time
                    if (!raised && mine.count { it.status == DownloadStatus.Completed } >= 1) {
                        raised = true
                        assertTrue(maxRunning <= 1, "ran $maxRunning at once with a limit of 1")
                        SettingsStore.update { it.copy(parallelDownloads = 2) }
                        DownloadManager.limitChanged()
                    }
                    mine.all { it.status == DownloadStatus.Completed || it.status == DownloadStatus.Failed }
                }
            }
            val done = DownloadManager.entries.value.filter { it.id in ids }
            done.forEach { assertEquals(DownloadStatus.Completed, it.status, it.error) }
            assertEquals(ids, startOrder, "started out of order")
            assertTrue(maxRunning == 2, "limit of 2 was not used after raising it, max was $maxRunning")
            println("Queue started in order, limit 1 then 2 respected")
        } finally {
            SettingsStore.update { it.copy(parallelDownloads = 2) }
            APIHolder.allProviders.withLock { APIHolder.allProviders.removeAll { it.sourcePlugin == "test-queue" } }
            server.stop(0)
        }
    }

    @Test
    fun usesOnlyThePickedSource() = runBlocking {
        System.setProperty("cloudstream.dataDir", Files.createTempDirectory("cs-dl").toString())
        val folder = Files.createTempDirectory("cs-downloads-picked").toFile()
        SettingsStore.update { it.copy(downloadFolder = folder.path) }
        val server = server()
        val base = "http://127.0.0.1:${server.address.port}"
        val api = TestProvider(base).apply { sourcePlugin = "test-picked" }
        APIHolder.allProviders.add(api)
        try {
            // The picker lists what findLinks returns: here a single direct file plus subtitles
            val (links, subs) = DownloadManager.findLinks("DownloadTest", "direct")
            assertEquals(1, links.size)
            assertEquals(1, subs.size)

            val episode = PlayableEpisode("picked", "Picked", null, null)
            DownloadManager.enqueue(
                DownloadRequest("DownloadTest", "$base/picked", "Picked Movie", null, null, episode, link = links.single(), subtitles = subs)
            )
            val id = DownloadManager.idFor("DownloadTest", "picked")
            val done = withTimeout(30_000) {
                DownloadManager.entries.first { list -> list.any { it.id == id && it.status in setOf(DownloadStatus.Completed, DownloadStatus.Failed) } }
            }.first { it.id == id }
            assertEquals(DownloadStatus.Completed, done.status, done.error)
            assertTrue(done.chosen)
            assertEquals(links.single().url, done.link?.url)
            assertEquals(1, done.subtitles.size)

            // A picked source that fails is reported, not silently replaced by another one
            val broken = links.single().copy(url = "$base/missing.mp4")
            val other = PlayableEpisode("broken", "Broken", null, null)
            DownloadManager.enqueue(DownloadRequest("DownloadTest", "$base/broken", "Broken Movie", null, null, other, link = broken))
            val brokenId = DownloadManager.idFor("DownloadTest", "broken")
            val failed = withTimeout(30_000) {
                DownloadManager.entries.first { list -> list.any { it.id == brokenId && it.status in setOf(DownloadStatus.Completed, DownloadStatus.Failed) } }
            }.first { it.id == brokenId }
            assertEquals(DownloadStatus.Failed, failed.status)
            assertEquals(broken.url, failed.link?.url)
            println("Picked source failure: ${failed.error}")
        } finally {
            APIHolder.allProviders.withLock { APIHolder.allProviders.removeAll { it.sourcePlugin == "test-picked" } }
            server.stop(0)
        }
    }

    @Test
    fun movesFinishedDownloadsAndTheirSubtitles() = runBlocking {
        System.setProperty("cloudstream.dataDir", Files.createTempDirectory("cs-dl").toString())
        val folder = Files.createTempDirectory("cs-downloads-move").toFile()
        SettingsStore.update { it.copy(downloadFolder = folder.path) }
        val server = server()
        val base = "http://127.0.0.1:${server.address.port}"
        val api = TestProvider(base).apply { name = "MoveTest"; sourcePlugin = "test-move" }
        APIHolder.allProviders.add(api)
        try {
            val episode = PlayableEpisode("move1", "Move Me", 1, 1)
            DownloadManager.enqueue(DownloadRequest("MoveTest", "$base/move", "Move Show", null, null, episode))
            val id = DownloadManager.idFor("MoveTest", "move1")
            val done = withTimeout(30_000) {
                DownloadManager.entries.first { list -> list.any { it.id == id && it.status in setOf(DownloadStatus.Completed, DownloadStatus.Failed) } }
            }.first { it.id == id }
            assertEquals(DownloadStatus.Completed, done.status, done.error)
            val subtitle = File(done.subtitles.single())
            assertTrue(subtitle.exists())

            // Canonical: on macOS the temp path is /var/..., which resolves to /private/var/...
            val destination = Files.createTempDirectory("cs-moved").toFile().canonicalFile
            val result = DownloadManager.move(listOf(id), destination)
            assertEquals(1, result.moved, "failed: ${result.failed}")

            val moved = DownloadManager.entry("MoveTest", "move1")!!
            assertTrue(moved.file!!.exists())
            assertTrue(moved.file!!.path.startsWith(destination.canonicalPath), moved.file!!.path)
            assertTrue(!File(folder, "Move Show").exists(), "the old title folder should be empty and removed")
            assertTrue(moved.subtitles.single().startsWith(destination.canonicalPath), moved.subtitles.single())
            assertTrue(File(moved.subtitles.single()).exists())
            assertTrue(!subtitle.exists(), "the subtitle should have moved from ${subtitle.path}")
            println("Moved to ${moved.file} with ${moved.subtitles}")
        } finally {
            APIHolder.allProviders.withLock { APIHolder.allProviders.removeAll { it.sourcePlugin == "test-move" } }
            server.stop(0)
        }
    }

    @Test
    fun downloadsFragmentedMp4HlsWithItsInitSegment() = runBlocking {
        System.setProperty("cloudstream.dataDir", Files.createTempDirectory("cs-dl-fmp4").toString())
        val folder = Files.createTempDirectory("cs-downloads-fmp4").toFile()
        SettingsStore.update { it.copy(downloadFolder = folder.path) }
        val server = server()
        val base = "http://127.0.0.1:${server.address.port}"
        val api = TestProvider(base).apply { sourcePlugin = "test-fmp4-single" }
        APIHolder.allProviders.add(api)
        try {
            DownloadManager.enqueue(DownloadRequest("DownloadTest", "$base/fmp4", "Fmp4 Movie", null, null, PlayableEpisode("fmp4", null, null, null)))
            val id = DownloadManager.idFor("DownloadTest", "fmp4")
            val entry = withTimeout(30_000) {
                DownloadManager.entries.first { list -> list.any { it.id == id && it.status in setOf(DownloadStatus.Completed, DownloadStatus.Failed) } }
            }.first { it.id == id }
            assertEquals(DownloadStatus.Completed, entry.status, entry.error)
            assertEquals("mp4", entry.file!!.extension)
            assertContentEquals(fmp4Init + fmp4Segments.reduce { a, b -> a + b }, entry.file!!.readBytes())
            DownloadManager.delete(id)
        } finally {
            APIHolder.allProviders.withLock { APIHolder.allProviders.removeAll { it.sourcePlugin == "test-fmp4-single" } }
            server.stop(0)
        }
    }

    @Test
    fun downloadsDirectFileWithPauseResumeAndEncryptedHls() = runBlocking {
        System.setProperty("cloudstream.dataDir", Files.createTempDirectory("cs-dl").toString())
        val folder = Files.createTempDirectory("cs-downloads").toFile()
        SettingsStore.update { it.copy(downloadFolder = folder.path) }
        val server = server()
        val base = "http://127.0.0.1:${server.address.port}"
        val api = TestProvider(base).apply { sourcePlugin = "test" }
        APIHolder.allProviders.add(api)
        try {
            fun request(data: String, episode: Int?) = DownloadRequest(
                "DownloadTest", "$base/show", "Test Show", null, null, PlayableEpisode(data, "Pilot", 1.takeIf { episode != null }, episode),
            )

            // Direct file: start, pause part way, resume, finish
            DownloadManager.enqueue(request("direct", null))
            val id = DownloadManager.idFor("DownloadTest", "direct")
            withTimeout(20_000) { DownloadManager.entries.first { list -> list.any { it.id == id && it.bytesDone > 512 * 1024 } } }
            DownloadManager.pause(id)
            assertEquals(DownloadStatus.Paused, DownloadManager.entry("DownloadTest", "direct")!!.status)
            DownloadManager.resume(id)
            val direct = withTimeout(30_000) {
                DownloadManager.entries.first { list -> list.any { it.id == id && it.status in setOf(DownloadStatus.Completed, DownloadStatus.Failed) } }
            }.first { it.id == id }
            assertEquals(DownloadStatus.Completed, direct.status, direct.error)
            assertContentEquals(movie, direct.file!!.readBytes())
            assertTrue(rangeRequests.get() > 0, "resume should use a Range request")
            assertEquals(1, direct.subtitles.size)
            println("Direct: ${direct.file} with ${direct.subtitles}")

            // Paused part way, then the download folder changes: it carries on where it was, not from nothing
            DownloadManager.enqueue(request("direct2", 3))
            val id2 = DownloadManager.idFor("DownloadTest", "direct2")
            withTimeout(20_000) { DownloadManager.entries.first { list -> list.any { it.id == id2 && it.bytesDone > 512 * 1024 } } }
            DownloadManager.pause(id2)
            val ranges = rangeRequests.get()
            val elsewhere = Files.createTempDirectory("cs-downloads-new").toFile()
            SettingsStore.update { it.copy(downloadFolder = elsewhere.path) }
            DownloadManager.resume(id2)
            val second = withTimeout(30_000) {
                DownloadManager.entries.first { list -> list.any { it.id == id2 && it.status in setOf(DownloadStatus.Completed, DownloadStatus.Failed) } }
            }.first { it.id == id2 }
            assertEquals(DownloadStatus.Completed, second.status, second.error)
            assertContentEquals(movie, second.file!!.readBytes())
            assertTrue(second.file!!.path.startsWith(folder.path), "finished in the folder it started in: ${second.file}")
            assertTrue(rangeRequests.get() > ranges, "and resumed with a Range request")
            SettingsStore.update { it.copy(downloadFolder = folder.path) }

            // HLS: decrypted segments joined in order, saved as .ts
            DownloadManager.enqueue(request("hls", 2))
            val hlsId = DownloadManager.idFor("DownloadTest", "hls")
            val hls = withTimeout(30_000) {
                DownloadManager.entries.first { list -> list.any { it.id == hlsId && it.status in setOf(DownloadStatus.Completed, DownloadStatus.Failed) } }
            }.first { it.id == hlsId }
            assertEquals(DownloadStatus.Completed, hls.status, hls.error)
            assertEquals("ts", hls.file!!.extension)
            assertTrue(hls.file!!.name.startsWith("S01E02"), hls.file!!.name)
            assertContentEquals(segments.reduce { a, b -> a + b }, hls.file!!.readBytes())
            println("HLS: ${hls.file}")

            assertTrue(DownloadManager.completedFile("DownloadTest", "hls") != null)
            DownloadManager.delete(hlsId)
            // Its worker may still be finishing up: the files go once it has stopped
            withTimeout(5_000) { while (hls.file!!.exists()) delay(50) }
        } finally {
            APIHolder.allProviders.withLock { APIHolder.allProviders.removeAll { it.sourcePlugin == "test" } }
            server.stop(0)
        }
    }
}
