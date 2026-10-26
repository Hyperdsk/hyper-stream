package com.lagradost.cloudstream4.desktop

import com.lagradost.cloudstream4.desktop.player.StreamProxy
import com.lagradost.cloudstream4.desktop.plugins.ExtensionManager
import com.lagradost.cloudstream4.desktop.update.UpdateSignature
import com.sun.net.httpserver.HttpServer
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URI
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** Names from repositories, signatures on updates, and what the local stream proxy agrees to fetch. */
class SafetyChecksTest {
    @Test
    fun extensionNamesCantLeaveTheirFolder() {
        val name = ExtensionManager.fileName("../../../Extensions/Trusted")
        assertFalse(name.contains('/') || name.contains('\\') || name.startsWith("."), name)
        assertEquals("extension", ExtensionManager.fileName(".."))
        assertEquals("MyProvider", ExtensionManager.fileName("MyProvider"))

        // Two urls that read the same once shortened get their own folders
        val long = "https://example.com/" + "a".repeat(80)
        assertNotEquals(ExtensionManager.folderName("$long/one.json"), ExtensionManager.folderName("$long/two.json"))
        assertNotEquals(ExtensionManager.folderName("https://x.org/r.json"), ExtensionManager.folderName("http://x.org/r.json"))
    }

    // Made by scripts/SignRelease.java with a throwaway key, for a file holding "hello\n"
    private val publicKey = "MCowBQYDK2VwAyEAGjVuwVvfbGSjyeynZyPzmq70ecgwnJpBE44z3WKv3CA="
    private val signature = "qdKDsa/7S6R20VjLSdRFlHhsnaE5xx1zoMB6qeptvZoqwiyWO5T2Rgchc8nHzdxNCz+rfjkBkBmklF5Hd9YbCQ=="
    private val sha256 = "5891b5b522d5df086d0ff0b110fbd9d21bb4fc7163af34d08286a2e846f6be03"

    @Test
    fun updateSignaturesFromTheReleaseScriptAreAccepted() {
        assertTrue(UpdateSignature.verify("HyperStream-9.9.9-linux-x64.deb", sha256, signature, publicKey), "the script's signature checks out")
        assertFalse(UpdateSignature.verify("HyperStream-9.9.9-linux-x64.deb", "0".repeat(64), signature, publicKey), "another file's contents don't")
        assertFalse(UpdateSignature.verify("HyperStream-9.9.9-windows-x64.msi", sha256, signature, publicKey), "nor the same file under another platform's name")
        assertFalse(UpdateSignature.verify("HyperStream-9.9.9-linux-x64.deb", sha256, "not base64!", publicKey))
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

            // The same session, made to point at another host: refused, so its cookie goes nowhere else
            val session = proxied.substringAfter("/s/").substringBefore('/')
            val elsewhere = Base64.getUrlEncoder().withoutPadding().encodeToString("http://localhost:$port/private".toByteArray())
            assertEquals(404, status("http://127.0.0.1:${URI(proxied).port}/s/$session/$elsewhere/private"))
        } finally {
            StreamProxy.clear()
            server.stop(0)
        }
    }
}
