package com.lagradost.cloudstream4.desktop

import com.lagradost.cloudstream4.desktop.player.StreamProxy
import com.lagradost.cloudstream4.desktop.plugins.ExtensionManager
import com.sun.net.httpserver.HttpServer
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URI
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

/** Names from repositories, and where the local stream proxy sends a link's headers. */
class SafetyChecksTest {
    @Test
    fun extensionNamesCantLeaveTheirFolder() {
        for (bad in listOf("../../../Extensions/Trusted", "..", "a/b", "C:evil")) {
            assertFailsWith<java.io.IOException>(bad) { ExtensionManager.pluginFile("https://x.org/r.json", bad) }
        }
        assertEquals("MyProvider.cs3", ExtensionManager.pluginFile("https://x.org/r.json", "MyProvider").name)

        // Two urls that read the same once shortened get their own folders
        val long = "https://example.com/" + "a".repeat(80)
        assertNotEquals(ExtensionManager.folderName("$long/one.json"), ExtensionManager.folderName("$long/two.json"))
        assertNotEquals(ExtensionManager.folderName("https://x.org/r.json"), ExtensionManager.folderName("http://x.org/r.json"))
    }

    @Test
    fun streamProxyOnlyFetchesWhatItsPlaylistsList() {
        val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0).apply {
            createContext("/list.m3u8") { ex ->
                val body = "#EXTM3U\n#EXT-X-TARGETDURATION:4\n#EXTINF:4.0,\nseg0.ts\n#EXT-X-ENDLIST\n".toByteArray()
                ex.responseHeaders.set("Content-Type", "application/vnd.apple.mpegurl")
                ex.sendResponseHeaders(200, body.size.toLong())
                ex.responseBody.use { it.write(body) }
            }
            createContext("/private") { ex ->
                val body = (ex.requestHeaders.getFirst("Cookie") ?: "no cookie").toByteArray()
                ex.sendResponseHeaders(200, body.size.toLong())
                ex.responseBody.use { it.write(body) }
            }
            createContext("/seg0.ts") { ex ->
                val body = ByteArray(188) { 0x47 }
                ex.sendResponseHeaders(200, body.size.toLong())
                ex.responseBody.use { it.write(body) }
            }
            start()
        }
        try {
            val port = server.address.port
            val proxied = StreamProxy.proxiedUrl("http://127.0.0.1:$port/list.m3u8", null, mapOf("Cookie" to "secret"))
            fun status(url: String) = (URI(url).toURL().openConnection() as HttpURLConnection).run {
                try { responseCode } finally { disconnect() }
            }
            val playlist = URI(proxied).toURL().readText()
            val segment = playlist.lines().first { it.startsWith("http") }
            assertEquals(200, status(segment), "a segment the playlist lists")

            // The same session, made to point at another host: its cookie goes nowhere else
            val session = proxied.substringAfter("/s/").substringBefore('/')
            val elsewhere = Base64.getUrlEncoder().withoutPadding().encodeToString("http://localhost:$port/private".toByteArray())
            val sent = URI("http://127.0.0.1:${URI(proxied).port}/s/$session/$elsewhere/private").toURL().readText()
            assertEquals("no cookie", sent)
        } finally {
            StreamProxy.clear()
            server.stop(0)
        }
    }
}
