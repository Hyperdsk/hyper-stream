package com.lagradost.cloudstream4.desktop

import com.lagradost.cloudstream4.desktop.data.SettingsStore
import com.lagradost.cloudstream4.desktop.player.StreamProxy
import com.sun.net.httpserver.HttpServer
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URI
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertTrue

/**
 * A host that drops the connection partway through, as many do on a long, slowly read video.
 * With one streaming connection the proxy hands VLC a single response; a drop must not end the
 * video there (VLC takes it as the end of the file and stops).
 */
class StreamProxyReconnectTest {
    private val video = Random(7).nextBytes(5 * 1024 * 1024)
    private val cutAfter = 1024 * 1024
    private val requests = AtomicInteger()

    private fun server() = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0).apply {
        createContext("/movie.mp4") { ex ->
            requests.incrementAndGet()
            val start = ex.requestHeaders.getFirst("Range")?.removePrefix("bytes=")?.substringBefore('-')?.toLongOrNull()?.toInt() ?: 0
            ex.responseHeaders.set("Content-Type", "video/mp4")
            ex.responseHeaders.set("Accept-Ranges", "bytes")
            if (start > 0) ex.responseHeaders.set("Content-Range", "bytes $start-${video.size - 1}/${video.size}")
            ex.sendResponseHeaders(if (start > 0) 206 else 200, (video.size - start).toLong())
            // Sends at most 1 MB, then hangs up without finishing
            runCatching { ex.responseBody.write(video, start, minOf(cutAfter, video.size - start)); ex.responseBody.flush() }
            ex.close()
        }
        start()
    }

    @Test
    fun aDroppedConnectionCarriesOnFromWhereItStopped() {
        System.setProperty("cloudstream.dataDir", Files.createTempDirectory("cs-reconnect").toString())
        SettingsStore.update { it.copy(streamConnections = 1) }
        val server = server()
        try {
            val url = StreamProxy.proxiedUrl("http://127.0.0.1:${server.address.port}/movie.mp4", null, emptyMap(), parallel = true)
            val received = URI(url).toURL().openConnection().apply { readTimeout = 20_000 }.getInputStream().use { input ->
                val out = java.io.ByteArrayOutputStream()
                runCatching { input.copyTo(out) }
                out.toByteArray()
            }
            assertTrue(received.size == video.size, "got ${received.size} of ${video.size} bytes; the stream ended at the drop")
            assertContentEquals(video, received)
            assertTrue(requests.get() > 1, "it reconnected to the host")
        } finally {
            server.stop(0)
            SettingsStore.update { it.copy(streamConnections = 16) }
        }
    }
}
