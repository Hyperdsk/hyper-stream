package com.lagradost.cloudstream4.desktop

import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream4.desktop.data.SettingsStore
import com.lagradost.cloudstream4.desktop.download.DownloadEntry
import com.lagradost.cloudstream4.desktop.download.DownloadManager
import com.lagradost.cloudstream4.desktop.download.DownloadRequest
import com.lagradost.cloudstream4.desktop.download.DownloadStatus
import com.lagradost.cloudstream4.desktop.download.SavedLink
import com.lagradost.cloudstream4.desktop.ui.PlayableEpisode
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** Downloads that used to overwrite each other, get stuck, or lose their progress. */
class DownloadFixesTest {
    private val fileA = Random(10).nextBytes(256 * 1024)
    private val fileB = Random(11).nextBytes(256 * 1024)
    private val big = Random(12).nextBytes(2 * 1024 * 1024)
    private val flakyCalls = AtomicInteger()
    private val rangeRequests = AtomicInteger()

    private fun HttpExchange.send(bytes: ByteArray) {
        responseHeaders.set("Content-Type", "video/mp4")
        sendResponseHeaders(200, bytes.size.toLong())
        responseBody.use { it.write(bytes) }
    }

    /** Sends [data] from the requested offset, slowly; [cutAt] hangs up part way. */
    private fun HttpExchange.slow(data: ByteArray, cutAt: Int? = null) {
        val start = requestHeaders.getFirst("Range")?.removePrefix("bytes=")?.substringBefore('-')?.toLongOrNull() ?: 0L
        if (start > 0) rangeRequests.incrementAndGet()
        responseHeaders.set("Content-Type", "video/mp4")
        if (start > 0) responseHeaders.set("Content-Range", "bytes $start-${data.size - 1}/${data.size}")
        sendResponseHeaders(if (start > 0) 206 else 200, data.size - start)
        runCatching {
            responseBody.use { out ->
                var pos = start.toInt()
                while (pos < data.size) {
                    if (cutAt != null && pos >= cutAt) {
                        // The connection drops: the client sees the file end early
                        close()
                        return
                    }
                    val n = minOf(32 * 1024, data.size - pos)
                    out.write(data, pos, n)
                    out.flush()
                    pos += n
                    Thread.sleep(40)
                }
            }
        }
    }

    private fun server() = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0).apply {
        createContext("/a.mp4") { it.send(fileA) }
        createContext("/b.mp4") { it.send(fileB) }
        createContext("/slow.mp4") { it.slow(big) }
        // The first request breaks off halfway; later ones (resuming with Range) work
        createContext("/flaky.mp4") { ex -> ex.slow(big, cutAt = if (flakyCalls.getAndIncrement() == 0) big.size / 2 else null) }
        start()
    }

    private class Provider(override var name: String, private val url: String) : MainAPI() {
        override var mainUrl = url
        override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
            callback(newExtractorLink(name, "Direct", url, ExtractorLinkType.VIDEO) { quality = 720 })
            return true
        }
    }

    private fun link(url: String) = SavedLink("Test", "Direct", url, "", 720, emptyMap(), ExtractorLinkType.VIDEO)

    private suspend fun finished(id: String): DownloadEntry = withTimeout(60_000) {
        DownloadManager.entries.first { list -> list.any { it.id == id && it.status in setOf(DownloadStatus.Completed, DownloadStatus.Failed) } }
    }.first { it.id == id }

    private fun <T> withSetup(tag: String, block: suspend (base: String) -> T): T = runBlocking {
        val folder = Files.createTempDirectory("cs-dl-$tag").toFile()
        val before = SettingsStore.state.value
        // One connection: these files are small, and the tests count requests
        SettingsStore.update { it.copy(downloadFolder = folder.path, downloadConnections = 1) }
        val server = server()
        try {
            block("http://127.0.0.1:${server.address.port}")
        } finally {
            server.stop(0)
            SettingsStore.update { it.copy(downloadFolder = before.downloadFolder, downloadConnections = before.downloadConnections) }
        }
    }

    @Test
    fun twoTitlesWithTheSameNameKeepTheirOwnFiles() = withSetup("same-name") { base ->
        val a = Provider("SameNameA", "$base/a.mp4").apply { sourcePlugin = "test-same-name" }
        val b = Provider("SameNameB", "$base/b.mp4").apply { sourcePlugin = "test-same-name" }
        APIHolder.allProviders.add(a)
        APIHolder.allProviders.add(b)
        try {
            val movie = PlayableEpisode("movie", null, null, null)
            DownloadManager.enqueue(DownloadRequest("SameNameA", "$base/dune-1984", "Dune", null, null, movie))
            val first = finished(DownloadManager.idFor("SameNameA", "movie"))
            DownloadManager.enqueue(DownloadRequest("SameNameB", "$base/dune-2021", "Dune", null, null, movie))
            val second = finished(DownloadManager.idFor("SameNameB", "movie"))

            assertEquals(DownloadStatus.Completed, first.status, first.error)
            assertEquals(DownloadStatus.Completed, second.status, second.error)
            assertNotEquals(first.file!!.path, second.file!!.path)
            assertContentEquals(fileA, first.file!!.readBytes(), "the first download is still its own file")
            assertContentEquals(fileB, second.file!!.readBytes())
        } finally {
            APIHolder.allProviders.withLock { APIHolder.allProviders.removeAll { it.sourcePlugin == "test-same-name" } }
        }
    }

    @Test
    fun pickingAnotherSourceWhileDownloadingFinishesWithIt() = withSetup("switch") { base ->
        val episode = PlayableEpisode("switch", null, null, null)
        DownloadManager.enqueue(DownloadRequest("SwitchTest", "$base/switch", "Switch Movie", null, null, episode, link = link("$base/slow.mp4")))
        val id = DownloadManager.idFor("SwitchTest", "switch")
        withTimeout(20_000) { DownloadManager.entries.first { list -> list.any { it.id == id && it.status == DownloadStatus.Downloading && it.connections > 0 } } }

        // Before, this left it "Downloading" with nothing running, forever
        DownloadManager.enqueue(DownloadRequest("SwitchTest", "$base/switch", "Switch Movie", null, null, episode, link = link("$base/a.mp4")))
        val done = finished(id)
        assertEquals(DownloadStatus.Completed, done.status, done.error)
        assertEquals("$base/a.mp4", done.link?.url)
        assertContentEquals(fileA, done.file!!.readBytes())
    }

    @Test
    fun aFailedDownloadGoesOnFromWhereItStopped() = withSetup("resume-after-failure") { base ->
        val episode = PlayableEpisode("flaky", null, null, null)
        DownloadManager.enqueue(DownloadRequest("FlakyTest", "$base/flaky", "Flaky Movie", null, null, episode, link = link("$base/flaky.mp4")))
        val id = DownloadManager.idFor("FlakyTest", "flaky")
        val failed = finished(id)
        assertEquals(DownloadStatus.Failed, failed.status)
        assertTrue(failed.bytesDone > 0, "progress is kept after the failure, not reset to nothing")

        DownloadManager.resume(id)
        val done = finished(id)
        assertEquals(DownloadStatus.Completed, done.status, done.error)
        assertContentEquals(big, done.file!!.readBytes())
        assertTrue(rangeRequests.get() > 0, "the retry asked for the rest only")
    }

    @Test
    fun namesWorkOnWindowsToo() {
        assertEquals("_CON", DownloadManager.safeName("CON"))
        assertEquals("_nul.txt", DownloadManager.safeName("nul.txt"))
        assertEquals("And Then There Were None", DownloadManager.safeName("And Then There Were None..."))
        assertEquals("AC DC Live", DownloadManager.safeName("AC/DC: Live"))
        assertEquals("Untitled", DownloadManager.safeName("..."))
    }
}
