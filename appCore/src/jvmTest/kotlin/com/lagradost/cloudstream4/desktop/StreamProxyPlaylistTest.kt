package com.lagradost.cloudstream4.desktop

import com.lagradost.cloudstream4.desktop.player.StreamProxy
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URI
import java.nio.file.Files
import java.util.concurrent.Executors
import java.util.zip.GZIPOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Playlists that don't say they are playlists: they must still be rewritten, or their segments
 * and variants reach the host without the extension's headers and get refused.
 */
class StreamProxyPlaylistTest {
    private val media = "#EXTM3U\n#EXT-X-TARGETDURATION:4\n#EXTINF:4.0,\nseg0.ts\n#EXT-X-ENDLIST\n"

    private fun send(ex: HttpExchange, type: String, body: ByteArray) {
        ex.responseHeaders.set("Content-Type", type)
        ex.sendResponseHeaders(200, body.size.toLong())
        ex.responseBody.use { it.write(body) }
    }

    private fun server() = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0).apply {
        executor = Executors.newCachedThreadPool()
        // A master playlist as plain text, from a url without ".m3u8"
        createContext("/master") { ex ->
            send(ex, "text/plain", "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=800000\nlow/index?token=abc\n".toByteArray())
        }
        // Its variant: only with the extension's header, as a byte stream starting with a byte order mark
        createContext("/low/index") { ex ->
            if (ex.requestHeaders.getFirst("X-Auth") != "secret") {
                ex.sendResponseHeaders(403, -1)
                ex.close()
                return@createContext
            }
            send(ex, "application/octet-stream", "\uFEFF$media".toByteArray())
        }
        // Compressed when the request accepts gzip
        createContext("/zipped") { ex ->
            val text = media.toByteArray()
            val gzip = ex.requestHeaders.getFirst("Accept-Encoding")?.contains("gzip") == true
            if (gzip) ex.responseHeaders.set("Content-Encoding", "gzip")
            send(ex, "application/vnd.apple.mpegurl", if (gzip) ByteArrayOutputStream().also { o -> GZIPOutputStream(o).use { it.write(text) } }.toByteArray() else text)
        }
        start()
    }

    private fun get(url: String): Pair<Int, String> {
        val c = URI(url).toURL().openConnection() as HttpURLConnection
        return c.responseCode to (if (c.responseCode < 400) c.inputStream.use { String(it.readBytes()) } else "")
    }

    /** The lines that aren't tags: segment and variant urls. */
    private fun uris(playlist: String) = playlist.lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }

    @Test
    fun playlistsServedAsTextOrBytesAreRewritten() {
        System.setProperty("cloudstream.dataDir", Files.createTempDirectory("cs-playlist").toString())
        val server = server()
        val base = "http://127.0.0.1:${server.address.port}"
        try {
            val (code, master) = get(StreamProxy.proxiedUrl("$base/master?token=abc", null, mapOf("X-Auth" to "secret")))
            assertEquals(200, code)
            val variants = uris(master)
            assertEquals(1, variants.size, master)
            assertTrue(variants[0].startsWith("http://127.0.0.1:") && "/s/" in variants[0], "variant not rewritten: ${variants[0]}")

            // Through the proxy the variant gets the header, and is itself rewritten
            val (variantCode, variant) = get(variants[0])
            assertEquals(200, variantCode, "the variant reached the host without the extension's headers")
            assertTrue(variant.startsWith("#EXTM3U"), variant)
            assertTrue(uris(variant).single().startsWith("http://127.0.0.1:"), "segment not rewritten: $variant")
        } finally {
            StreamProxy.clear()
            server.stop(0)
        }
    }

    @Test
    fun theExtensionsAcceptEncodingDoesNotLeaveThePlaylistCompressed() {
        System.setProperty("cloudstream.dataDir", Files.createTempDirectory("cs-playlist").toString())
        val server = server()
        try {
            val url = StreamProxy.proxiedUrl("http://127.0.0.1:${server.address.port}/zipped", null, mapOf("accept-encoding" to "gzip, deflate"))
            val (code, playlist) = get(url)
            assertEquals(200, code)
            assertTrue(playlist.startsWith("#EXTM3U"), "compressed or not rewritten: ${playlist.take(40)}")
            assertTrue(uris(playlist).single().startsWith("http://127.0.0.1:"), playlist)
        } finally {
            StreamProxy.clear()
            server.stop(0)
        }
    }
}
