package com.lagradost.cloudstream4.desktop

import com.lagradost.cloudstream4.desktop.data.SettingsStore
import com.lagradost.cloudstream4.desktop.player.StreamProxy
import com.sun.net.httpserver.HttpServer
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URI
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Streaming through the proxy with several connections, against a host that throttles each one. */
class ParallelStreamTest {
    private val video = Random(7).nextBytes(24 * 1024 * 1024)
    private val segments = List(10) { i -> Random(i).nextBytes(300_000) }
    private val active = AtomicInteger()
    private val peak = AtomicInteger()
    private val segmentHits = ConcurrentHashMap.newKeySet<Int>()

    /** About 4 MB/s per connection, like hosts that limit each connection. */
    private fun server() = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0).apply {
        executor = Executors.newCachedThreadPool()
        createContext("/video.mp4") { ex ->
            val now = active.incrementAndGet()
            peak.accumulateAndGet(now) { a, b -> maxOf(a, b) }
            try {
                val spec = ex.requestHeaders.getFirst("Range")?.removePrefix("bytes=")
                val start = spec?.substringBefore('-')?.toLong() ?: 0L
                val end = spec?.substringAfter('-')?.takeIf { it.isNotEmpty() }?.toLong() ?: (video.size - 1L)
                ex.responseHeaders.set("Content-Type", "video/mp4")
                if (spec != null) ex.responseHeaders.set("Content-Range", "bytes $start-$end/${video.size}")
                ex.sendResponseHeaders(if (spec != null) 206 else 200, end - start + 1)
                runCatching {
                    ex.responseBody.use { out ->
                        var pos = start.toInt()
                        while (pos <= end) {
                            val n = minOf(64 * 1024, (end - pos + 1).toInt())
                            out.write(video, pos, n)
                            pos += n
                            Thread.sleep(16)
                        }
                    }
                }
            } finally {
                active.decrementAndGet()
            }
        }
        createContext("/hls/") { ex ->
            val name = ex.requestURI.path.substringAfterLast('/')
            if (name == "index.m3u8") {
                val body = buildString {
                    appendLine("#EXTM3U"); appendLine("#EXT-X-TARGETDURATION:4")
                    segments.indices.forEach { appendLine("#EXTINF:4.0,"); appendLine("seg$it.ts") }
                    appendLine("#EXT-X-ENDLIST")
                }.toByteArray()
                ex.responseHeaders.set("Content-Type", "application/vnd.apple.mpegurl")
                ex.sendResponseHeaders(200, body.size.toLong()); ex.responseBody.use { it.write(body) }
            } else {
                val i = name.removePrefix("seg").removeSuffix(".ts").toInt()
                segmentHits += i
                ex.sendResponseHeaders(200, segments[i].size.toLong()); ex.responseBody.use { it.write(segments[i]) }
            }
        }
        start()
    }

    private fun get(url: String, range: String? = null): Pair<Int, ByteArray> {
        val c = URI(url).toURL().openConnection() as HttpURLConnection
        range?.let { c.setRequestProperty("Range", it) }
        return c.responseCode to c.inputStream.use { it.readBytes() }
    }

    @Test
    fun parsesRanges() {
        assertEquals(0L to 99L, StreamProxy.parseRange(null, 100))
        assertEquals(10L to 99L, StreamProxy.parseRange("bytes=10-", 100))
        assertEquals(10L to 19L, StreamProxy.parseRange("bytes=10-19", 100))
        assertEquals(90L to 99L, StreamProxy.parseRange("bytes=-10", 100))
        assertEquals(10L to 99L, StreamProxy.parseRange("bytes=10-500", 100))
        assertEquals(null, StreamProxy.parseRange("bytes=100-", 100))
    }

    @Test
    fun streamsVideoOverParallelConnectionsAndPrefetchesHls() {
        System.setProperty("cloudstream.dataDir", Files.createTempDirectory("cs-stream").toString())
        val server = server()
        val base = "http://127.0.0.1:${server.address.port}"
        try {
            // One connection, for comparison
            SettingsStore.update { it.copy(streamConnections = 1) }
            var url = StreamProxy.proxiedUrl("$base/video.mp4", null, emptyMap(), parallel = true)
            var t = System.nanoTime()
            assertContentEquals(video, get(url).second)
            val single = (System.nanoTime() - t) / 1_000_000
            StreamProxy.clear()

            SettingsStore.update { it.copy(streamConnections = 16) }
            peak.set(0)
            url = StreamProxy.proxiedUrl("$base/video.mp4", null, emptyMap(), parallel = true)
            // Watch the stats while it streams: speed and connections are reported as they happen
            val stats = StreamProxy.stats
            var sawSpeed = 0L
            var sawConnections = 0
            val watcher = Thread {
                while (!Thread.currentThread().isInterrupted) {
                    sawSpeed = maxOf(sawSpeed, stats.value.bytesPerSecond)
                    sawConnections = maxOf(sawConnections, stats.value.connections)
                    try { Thread.sleep(50) } catch (_: InterruptedException) { break }
                }
            }.apply { start() }
            val before = stats.value.totalBytes
            t = System.nanoTime()
            val (code, whole) = get(url)
            val parallel = (System.nanoTime() - t) / 1_000_000
            assertEquals(200, code)
            assertContentEquals(video, whole)
            // A seek: open-ended range from the middle
            val (code2, tail) = get(url, "bytes=10000000-")
            assertEquals(206, code2)
            assertContentEquals(video.copyOfRange(10_000_000, video.size), tail)
            Thread.sleep(700)
            watcher.interrupt()
            println("24 MB: one connection ${single} ms, parallel ${parallel} ms, peak ${peak.get()} connections")
            println("Stats seen: top speed ${sawSpeed / 1_000_000} MB/s, ${sawConnections} connections, ${(stats.value.totalBytes - before) / 1_000_000} MB counted")
            assertTrue(stats.value.totalBytes - before >= video.size, "bytes counted: ${stats.value.totalBytes - before}")
            assertTrue(peak.get() >= 8, "expected parallel connections, peak was ${peak.get()}")
            assertTrue(parallel * 2 < single, "parallel ${parallel} ms should be well under single ${single} ms")

            // HLS: asking for the first segment fetches the next ones ahead
            val playlistUrl = StreamProxy.proxiedUrl("$base/hls/index.m3u8", null, emptyMap())
            val playlist = String(get(playlistUrl).second)
            val segmentUrls = playlist.lines().filter { it.startsWith("http") }
            assertEquals(10, segmentUrls.size)
            assertContentEquals(segments[0], get(segmentUrls[0]).second)
            Thread.sleep(500)
            assertTrue(segmentHits.containsAll((1..8).toList()), "prefetched segments: $segmentHits")
            // Already prefetched, so it's served from memory with the right bytes
            assertContentEquals(segments[3], get(segmentUrls[3]).second)
            println("HLS segments fetched from host: ${segmentHits.sorted()}")
        } finally {
            StreamProxy.clear()
            server.stop(0)
        }
    }
}
