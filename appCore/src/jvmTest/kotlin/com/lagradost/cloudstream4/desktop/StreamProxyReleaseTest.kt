package com.lagradost.cloudstream4.desktop

import com.lagradost.cloudstream4.desktop.data.SettingsStore
import com.lagradost.cloudstream4.desktop.player.StreamProxy
import com.sun.net.httpserver.HttpServer
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URI
import java.nio.file.Files
import java.util.concurrent.Executors
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

/** Switching source releases the previous link's session, and only that one. */
class StreamProxyReleaseTest {
    private val video = Random(5).nextBytes(6 * 1024 * 1024)

    private fun server() = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0).apply {
        executor = Executors.newCachedThreadPool()
        createContext("/video.mp4") { ex ->
            val spec = ex.requestHeaders.getFirst("Range")?.removePrefix("bytes=")
            val start = spec?.substringBefore('-')?.toInt() ?: 0
            val end = spec?.substringAfter('-')?.takeIf { it.isNotEmpty() }?.toInt() ?: (video.size - 1)
            ex.responseHeaders.set("Content-Type", "video/mp4")
            if (spec != null) ex.responseHeaders.set("Content-Range", "bytes $start-$end/${video.size}")
            ex.sendResponseHeaders(if (spec != null) 206 else 200, (end - start + 1).toLong())
            runCatching { ex.responseBody.use { it.write(video, start, end - start + 1) } }
        }
        start()
    }

    private fun get(url: String, range: String): Pair<Int, ByteArray> {
        val c = URI(url).toURL().openConnection() as HttpURLConnection
        c.setRequestProperty("Range", range)
        return c.responseCode to (if (c.responseCode < 400) c.inputStream.use { it.readBytes() } else ByteArray(0))
    }

    @Test
    fun releasingALinkForgetsOnlyItsSession() {
        System.setProperty("cloudstream.dataDir", Files.createTempDirectory("cs-release").toString())
        SettingsStore.update { it.copy(streamConnections = 4) }
        val server = server()
        val target = "http://127.0.0.1:${server.address.port}/video.mp4"
        try {
            val old = StreamProxy.proxiedUrl(target, null, emptyMap(), parallel = true)
            val playing = StreamProxy.proxiedUrl(target, null, emptyMap(), parallel = true)
            assertEquals(206, get(old, "bytes=0-99").first)

            StreamProxy.release(old)
            assertEquals(404, get(old, "bytes=0-99").first, "the released link still plays")
            val (code, bytes) = get(playing, "bytes=3000000-3000099")
            assertEquals(206, code)
            assertContentEquals(video.copyOfRange(3_000_000, 3_000_100), bytes)
        } finally {
            StreamProxy.clear()
            server.stop(0)
            SettingsStore.update { it.copy(streamConnections = 16) }
        }
    }
}
