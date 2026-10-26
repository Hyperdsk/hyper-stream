package com.lagradost.cloudstream4.desktop

import com.lagradost.cloudstream4.desktop.data.SettingsStore
import com.lagradost.cloudstream4.desktop.player.StreamProxy
import com.lagradost.cloudstream4.desktop.player.VlcPlayer
import com.sun.net.httpserver.HttpServer
import java.io.File
import java.io.RandomAccessFile
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.Collections
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Real VLC playing a large MP4 (index at the end) through the stream proxy from a host that
 * limits speed per connection, with the seek-bar preview player open too, like the app.
 * Opt in: CLOUDSTREAM_VLC_TEST=/path/to/video.mp4 (needs VLC installed).
 */
class VlcStreamingTest {
    @Test
    fun startsPlayingQuickly() {
        val path = System.getenv("CLOUDSTREAM_VLC_TEST")
        org.junit.Assume.assumeTrue("set CLOUDSTREAM_VLC_TEST to a video file", path != null)
        path!!
        val video = File(path)
        val progress = File(System.getenv("CLOUDSTREAM_VLC_LOG") ?: "/tmp/cs-vlc-progress.txt").apply { writeText("") }
        fun say(line: String) { println(line); progress.appendText(line + "\n") }
        System.setProperty("cloudstream.dataDir", Files.createTempDirectory("cs-vlc").toString())
        SettingsStore.update { it.copy(streamConnections = 16) }

        val active = AtomicInteger()
        val log = Collections.synchronizedList(mutableListOf<String>())
        val started = System.nanoTime()
        fun now() = (System.nanoTime() - started) / 1_000_000
        val perConnection = 16 * 1024
        // The viewer's internet connection: this much in total, shared by every connection
        val totalPerSecond = (System.getenv("CLOUDSTREAM_VLC_BW")?.toLong() ?: (6L shl 20))
        val bucketLock = Object()
        var tokens = 0.0
        var lastRefill = System.nanoTime()
        fun take(n: Int) {
            while (true) {
                synchronized(bucketLock) {
                    val t = System.nanoTime()
                    tokens = minOf(totalPerSecond.toDouble() / 4, tokens + (t - lastRefill) * totalPerSecond / 1e9)
                    lastRefill = t
                    if (tokens >= n) { tokens -= n; return }
                }
                Thread.sleep(5)
            }
        }

        val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0).apply {
            executor = Executors.newCachedThreadPool()
            createContext("/movie") { ex ->
                active.incrementAndGet()
                try {
                    val total = video.length()
                    val spec = ex.requestHeaders.getFirst("Range")?.removePrefix("bytes=")
                    val start = spec?.substringBefore('-')?.toLong() ?: 0L
                    val end = spec?.substringAfter('-')?.takeIf { it.isNotEmpty() }?.toLong() ?: (total - 1)
                    if (start == 0L || start > total - 50_000_000) log += "${now()} ms: range $start-$end"
                    ex.responseHeaders.set("Content-Type", if (video.extension == "mkv") "video/x-matroska" else "video/mp4")
                    ex.responseHeaders.set("Accept-Ranges", "bytes")
                    if (spec != null) ex.responseHeaders.set("Content-Range", "bytes $start-$end/$total")
                    ex.sendResponseHeaders(if (spec != null) 206 else 200, end - start + 1)
                    runCatching {
                        RandomAccessFile(video, "r").use { raf ->
                            raf.seek(start)
                            val buf = ByteArray(perConnection)
                            var left = end - start + 1
                            ex.responseBody.use { out ->
                                while (left > 0) {
                                    val n = raf.read(buf, 0, minOf(16 * 1024L, left).toInt())
                                    if (n < 0) break
                                    take(n)
                                    out.write(buf, 0, n)
                                    left -= n
                                }
                            }
                        }
                    }
                } finally {
                    active.decrementAndGet()
                }
            }
            start()
        }

        val player = VlcPlayer.create()
        org.junit.Assume.assumeTrue("VLC not found", player != null)
        player!!
        val preview = player.createPreview()
        try {
            // CLOUDSTREAM_VLC_DIRECT=1 plays the file from disk, to tell codec problems from network ones
            val url = if (System.getenv("CLOUDSTREAM_VLC_DIRECT") == "1") video.toPath().toUri().toString()
            else StreamProxy.proxiedUrl("http://127.0.0.1:${server.address.port}/movie.${video.extension}", null, emptyMap(), parallel = true)
            say("Opening $url")
            player.open(com.lagradost.cloudstream4.desktop.player.StreamSource(url))

            var playingAt: Long? = null
            // CLOUDSTREAM_VLC_EARLY_PREVIEW=1: open the preview on VLC's first "playing" event, before any frame
            val earlyPreview = System.getenv("CLOUDSTREAM_VLC_EARLY_PREVIEW") == "1"
            var previewOpened = false
            while (now() < 90_000) {
                val s = player.state.value
                if (earlyPreview && !previewOpened && s.playing) {
                    previewOpened = true
                    say("${now()} ms: opening preview early (time=${s.timeMs})")
                    preview.open("${player.currentUrl ?: url}?preview")
                }
                if (s.playing && s.timeMs > 1_000) { playingAt = now(); break }
                if (now() % 2000 < 250) {
                    val st = StreamProxy.stats.value
                    say("${now()} ms: playing=${s.playing} time=${s.timeMs} buffering=${s.buffering} ${s.bufferPercent}% · ${st.bytesPerSecond / 1024} KB/s · proxy ${st.connections} conns · host ${active.get()} conns · ${st.aheadBytes / 1024 / 1024} MB ahead")
                }
                Thread.sleep(250)
            }
            log.forEach { say(it) }
            say("Playing after ${playingAt ?: "never (90 s)"} ms")
            assertTrue(playingAt != null && playingAt < 20_000, "took ${playingAt ?: "over 90000"} ms to start playing")

            // Like the app: the seek-bar preview opens once playing, at low priority. Playback must go on.
            if (!previewOpened) preview.open("${player.currentUrl ?: url}?preview")
            val t0 = player.state.value.timeMs
            Thread.sleep(5_000)
            val advanced = player.state.value.timeMs - t0
            say("With the preview open, playback advanced ${advanced} ms in 5 s")
            assertTrue(advanced > 3_000, "playback stalled with the preview open: advanced $advanced ms")
        } finally {
            // VLC can hang in stop when it is stuck; don't let that hold up the test run
            val releaser = Thread { preview.release(); player.release() }.apply { isDaemon = true; start() }
            releaser.join(10_000)
            if (releaser.isAlive) say("VLC did not stop within 10 s (stuck)")
            StreamProxy.clear()
            server.stop(0)
        }
    }
}
