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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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
    private val rangeRequests = AtomicInteger()

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

    private fun server() = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0).apply {
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
            if (data == "direct") callback(newExtractorLink(name, "Direct", "$base/movie.mp4", ExtractorLinkType.VIDEO) { quality = 720 })
            else callback(newExtractorLink(name, "HLS", "$base/hls/index.m3u8", ExtractorLinkType.M3U8) { quality = 720 })
            return true
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
            assertTrue(!hls.file!!.exists())
        } finally {
            APIHolder.allProviders.withLock { APIHolder.allProviders.removeAll { it.sourcePlugin == "test" } }
            server.stop(0)
        }
    }
}
