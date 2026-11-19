package com.lagradost.cloudstream4.desktop

import com.lagradost.cloudstream4.desktop.player.StreamProxy
import com.sun.net.httpserver.HttpServer
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URI
import java.util.concurrent.Executors
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertTrue

/** HLS segments come over several connections at once, as hosts cap each connection's speed. */
class HlsSegmentPartsTest {
    private val segment = Random(7).nextBytes(2 * 1024 * 1024)

    @Test
    fun segmentIsFetchedInParallelParts() {
        val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0).apply {
            executor = Executors.newCachedThreadPool()
            createContext("/index.m3u8") { ex ->
                val bytes = "#EXTM3U\n#EXTINF:6.0,\nseg0.ts\n#EXT-X-ENDLIST\n".toByteArray()
                ex.responseHeaders.set("Content-Type", "application/vnd.apple.mpegurl")
                ex.sendResponseHeaders(200, bytes.size.toLong())
                ex.responseBody.use { it.write(bytes) }
            }
            // 330 KB/s per connection, like the Prime Video host
            createContext("/seg0.ts") { ex ->
                val spec = ex.requestHeaders.getFirst("Range")?.removePrefix("bytes=")
                val start = spec?.substringBefore('-')?.toInt() ?: 0
                val end = spec?.substringAfter('-')?.toIntOrNull()?.coerceAtMost(segment.size - 1) ?: (segment.size - 1)
                if (spec != null) ex.responseHeaders.set("Content-Range", "bytes $start-$end/${segment.size}")
                ex.sendResponseHeaders(if (spec != null) 206 else 200, (end - start + 1).toLong())
                runCatching {
                    ex.responseBody.use { out ->
                        var pos = start
                        while (pos <= end) {
                            val n = minOf(330 * 1024 / 20, end - pos + 1)
                            out.write(segment, pos, n)
                            out.flush()
                            pos += n
                            Thread.sleep(50)
                        }
                    }
                }
            }
            start()
        }
        try {
            val playlist = URI(StreamProxy.proxiedUrl("http://127.0.0.1:${server.address.port}/index.m3u8", null, emptyMap())).toURL().readText()
            val segmentUrl = playlist.lines().first { it.startsWith("http") }
            val t = System.nanoTime()
            val bytes = URI(segmentUrl).toURL().readBytes()
            val ms = (System.nanoTime() - t) / 1_000_000
            println("2 MB segment in $ms ms (one connection at 330 KB/s takes about 6200 ms)")
            assertContentEquals(segment, bytes)
            assertTrue(ms < 3_000, "segment took $ms ms")
        } finally {
            server.stop(0)
        }
    }
}
