package com.lagradost.cloudstream4.desktop

import com.lagradost.cloudstream4.desktop.data.SettingsStore
import com.lagradost.cloudstream4.desktop.player.ScreenAwake
import com.lagradost.cloudstream4.desktop.player.StreamProxy
import com.lagradost.cloudstream4.desktop.player.StreamSource
import com.lagradost.cloudstream4.desktop.player.VlcPlayer
import com.sun.net.httpserver.HttpServer
import java.io.File
import java.io.RandomAccessFile
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Real VLC, one streaming connection, and a host that hangs up after every megabyte: playback
 * goes on past each drop instead of stopping as if the video had ended. Set CLOUDSTREAM_VLC_TEST
 * to an MP4 of a minute or more.
 */
class VlcReconnectTest {
    @Test
    fun playsOnPastAHostThatKeepsHangingUp() {
        val video = File(System.getenv("CLOUDSTREAM_VLC_TEST") ?: return println("Skipping, set CLOUDSTREAM_VLC_TEST to a video file"))
        System.setProperty("cloudstream.dataDir", Files.createTempDirectory("cs-vlc-reconnect").toString())
        SettingsStore.update { it.copy(streamConnections = 1) }
        val cutAfter = 1L shl 20
        val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0).apply {
            executor = Executors.newCachedThreadPool()
            createContext("/movie.mp4") { ex ->
                val total = video.length()
                val spec = ex.requestHeaders.getFirst("Range")?.removePrefix("bytes=")
                val start = spec?.substringBefore('-')?.toLong() ?: 0L
                val end = spec?.substringAfter('-')?.takeIf { it.isNotEmpty() }?.toLong() ?: (total - 1)
                ex.responseHeaders.set("Content-Type", "video/mp4")
                ex.responseHeaders.set("Accept-Ranges", "bytes")
                if (spec != null) ex.responseHeaders.set("Content-Range", "bytes $start-$end/$total")
                ex.sendResponseHeaders(if (spec != null) 206 else 200, end - start + 1)
                runCatching {
                    RandomAccessFile(video, "r").use { raf ->
                        raf.seek(start)
                        val bytes = ByteArray(minOf(cutAfter, end - start + 1).toInt())
                        raf.readFully(bytes)
                        ex.responseBody.write(bytes)
                        ex.responseBody.flush()
                    }
                }
                ex.close()
            }
            start()
        }
        val player = VlcPlayer.create() ?: return println("Skipping, VLC not found")
        try {
            player.open(StreamSource(StreamProxy.proxiedUrl("http://127.0.0.1:${server.address.port}/movie.mp4", null, emptyMap(), parallel = true)), 0, emptyList())
            player.setRate(4f)
            val deadline = System.currentTimeMillis() + 60_000
            var awakeWhilePlaying = false
            while (System.currentTimeMillis() < deadline) {
                val s = player.state.value
                if (s.playing && s.timeMs > 1_000 && ScreenAwake.active) awakeWhilePlaying = true
                if (s.timeMs > 50_000 || s.ended || s.error != null) break
                Thread.sleep(200)
            }
            val s = player.state.value
            println("At ${s.timeMs} ms of ${s.lengthMs}, ended=${s.ended} error=${s.error}")
            assertTrue(s.timeMs > 50_000 && !s.ended, "playback stopped at ${s.timeMs} ms, ended=${s.ended}")
            assertTrue(awakeWhilePlaying, "the screen was kept awake while playing")
            player.togglePause()
            val pausedBy = System.currentTimeMillis() + 5_000
            while (ScreenAwake.active && System.currentTimeMillis() < pausedBy) Thread.sleep(100)
            assertFalse(ScreenAwake.active, "and may sleep once paused")
        } finally {
            Thread { player.release() }.apply { isDaemon = true; start() }.join(5_000)
            server.stop(0)
            SettingsStore.update { it.copy(streamConnections = 16) }
        }
    }
}
