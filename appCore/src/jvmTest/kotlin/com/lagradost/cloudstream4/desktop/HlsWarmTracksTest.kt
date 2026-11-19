package com.lagradost.cloudstream4.desktop

import com.lagradost.cloudstream4.desktop.player.StreamProxy
import com.sun.net.httpserver.HttpServer
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URI
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** VLC opens HLS tracks one by one; the proxy fetches them all ahead when the master playlist arrives. */
class HlsWarmTracksTest {
    private fun get(url: String) = URI(url).toURL().readText()

    @Test
    fun findsTheSegmentsAtAResumePoint() {
        val playlist = "#EXTM3U\n#EXTINF:6.0,\ns0.ts\n#EXTINF:6.0,\ns1.ts\n#EXTINF:6.0,\ns2.ts\n#EXTINF:4.0,\ns3.ts\n#EXT-X-ENDLIST\n"
        val at = { s: Double -> StreamProxy.segmentsAt(playlist, "https://h.example/v/index.m3u8", s).map { it.substringAfterLast('/') } }
        assertEquals(listOf("s0.ts", "s1.ts"), at(0.0))
        assertEquals(listOf("s1.ts", "s2.ts"), at(7.5))
        assertEquals(listOf("s3.ts"), at(19.0))
    }

    @Test
    fun tracksAreFetchedAheadInParallel() {
        val audio = listOf("en", "hi", "ta", "te", "fr")
        val subs = listOf("en", "hi")
        val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0).apply {
            executor = Executors.newCachedThreadPool()
            fun reply(path: String, body: String) = createContext(path) { ex ->
                Thread.sleep(500) // a slow host: half a second per request, whatever its size
                val bytes = body.toByteArray()
                ex.sendResponseHeaders(200, bytes.size.toLong())
                ex.responseBody.use { it.write(bytes) }
            }
            reply("/master.m3u8", buildString {
                appendLine("#EXTM3U")
                audio.forEach { appendLine("#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID=\"a\",NAME=\"$it\",URI=\"a/$it.m3u8\"") }
                subs.forEach { appendLine("#EXT-X-MEDIA:TYPE=SUBTITLES,GROUP-ID=\"s\",NAME=\"$it\",URI=\"s/$it.m3u8\"") }
                // A lower quality first, as VLC would start on it and switch up later
                appendLine("#EXT-X-STREAM-INF:BANDWIDTH=300000,AUDIO=\"a\",SUBTITLES=\"s\"")
                appendLine("low/index.m3u8")
                appendLine("#EXT-X-STREAM-INF:BANDWIDTH=1000000,AUDIO=\"a\",SUBTITLES=\"s\"")
                appendLine("v/index.m3u8")
            })
            reply("/v/index.m3u8", "#EXTM3U\n#EXTINF:6.0,\nv0.ts\n#EXTINF:6.0,\nv1.ts\n#EXT-X-ENDLIST\n")
            reply("/v/v0.ts", "video0")
            audio.forEach { reply("/a/$it.m3u8", "#EXTM3U\n#EXTINF:6.0,\n$it-0.aac\n#EXT-X-ENDLIST\n"); reply("/a/$it-0.aac", "audio-$it") }
            subs.forEach { reply("/s/$it.m3u8", "#EXTM3U\n#EXTINF:99999,\n$it.vtt\n#EXT-X-ENDLIST\n"); reply("/s/$it.vtt", "WEBVTT") }
            start()
        }
        try {
            val masterUrl = StreamProxy.proxiedUrl("http://127.0.0.1:${server.address.port}/master.m3u8", null, emptyMap(), audio = "ta")
            val master = get(masterUrl)
            // VLC downloads every audio language it's given: it gets the one asked for, the menu lists them all
            assertEquals(listOf("ta"), Regex("TYPE=AUDIO,GROUP-ID=\"a\",NAME=\"(\\w+)\"").findAll(master).map { it.groupValues[1] }.toList(), master)
            assertEquals(audio to "ta", StreamProxy.audioChoices(masterUrl))
            // Only the best quality, so VLC never starts on one nothing was fetched ahead for
            assertEquals(1, Regex("#EXT-X-STREAM-INF").findAll(master).count(), master)
            assertTrue("BANDWIDTH=1000000" in master && "low" !in master, master)
            val tracks = Regex("URI=\"([^\"]+)\"").findAll(master).map { it.groupValues[1] }.toList() +
                master.lines().filter { it.startsWith("http") }
            assertEquals(1 + subs.size + 1, tracks.size)
            // Like VLC: each track's playlist, then its first segment, one track after another
            val t = System.nanoTime()
            val firstSegments = tracks.map { playlistUrl ->
                val playlist = get(playlistUrl)
                get(playlist.lines().first { it.startsWith("http") })
            }
            val ms = (System.nanoTime() - t) / 1_000_000
            println("${tracks.size} tracks opened in $ms ms (one by one from the host: about ${tracks.size * 1000} ms)")
            assertEquals(setOf("audio-ta"), firstSegments.filter { it.startsWith("audio") }.toSet())
            assertTrue("video0" in firstSegments)
            assertTrue(ms < 3_000, "opening the tracks took $ms ms")
        } finally {
            server.stop(0)
        }
    }
}
