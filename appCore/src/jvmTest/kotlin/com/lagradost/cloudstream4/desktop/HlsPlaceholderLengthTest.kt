package com.lagradost.cloudstream4.desktop

import com.lagradost.cloudstream4.desktop.player.StreamProxy
import com.sun.net.httpserver.HttpServer
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Prime Video's subtitle playlists claim 99999 seconds, which made VLC think the movie lasted 27 hours. */
class HlsPlaceholderLengthTest {
    private fun get(url: String) = URI(url).toURL().readText()

    @Test
    fun subtitlePlaceholderGetsTheVideoLength() {
        val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0).apply {
            fun reply(path: String, body: String) = createContext(path) { ex ->
                val bytes = body.toByteArray()
                ex.responseHeaders.set("Content-Type", "application/vnd.apple.mpegurl")
                ex.sendResponseHeaders(200, bytes.size.toLong())
                ex.responseBody.use { it.write(bytes) }
            }
            reply("/master.m3u8", """
                #EXTM3U
                #EXT-X-MEDIA:TYPE=SUBTITLES,GROUP-ID="subs",NAME="Hindi",URI="subs/hi.m3u8"
                #EXT-X-STREAM-INF:BANDWIDTH=1000000,SUBTITLES="subs"
                1080p/index.m3u8
            """.trimIndent())
            // The host times out the first time it's asked for the video playlist, as Alpha's did
            val videoHits = java.util.concurrent.atomic.AtomicInteger()
            createContext("/1080p/index.m3u8") { ex ->
                if (videoHits.incrementAndGet() == 1) { ex.sendResponseHeaders(503, -1); ex.close(); return@createContext }
                val bytes = "#EXTM3U\n#EXTINF:4.0,\na.ts\n#EXTINF:4.0,\nb.ts\n#EXTINF:2.5,\nc.ts\n#EXT-X-ENDLIST\n".toByteArray()
                ex.sendResponseHeaders(200, bytes.size.toLong())
                ex.responseBody.use { it.write(bytes) }
            }
            reply("/subs/hi.m3u8", "#EXTM3U\n#EXT-X-TARGETDURATION:99999\n#EXTINF:99999,\nhi.vtt\n#EXT-X-ENDLIST\n")
            start()
        }
        try {
            val base = "http://127.0.0.1:${server.address.port}"
            val master = get(StreamProxy.proxiedUrl("$base/master.m3u8", null, emptyMap()))
            val subtitles = Regex("URI=\"([^\"]+)\"").find(master)!!.groupValues[1]
            // VLC may ask for the subtitles before the video playlist: the proxy fetches the video's itself
            val rewritten = get(subtitles)
            assertTrue("#EXTINF:10.500" in rewritten, rewritten)
            assertTrue("99999," !in rewritten, rewritten)

            // Real segments are left alone
            val video = get(master.lines().last { it.startsWith("http") })
            assertEquals(3, Regex("#EXTINF:").findAll(video).count(), video)
            assertTrue("#EXTINF:2.5," in video, video)
        } finally {
            server.stop(0)
        }
    }
}
