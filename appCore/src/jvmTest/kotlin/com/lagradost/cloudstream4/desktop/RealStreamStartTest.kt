package com.lagradost.cloudstream4.desktop

import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream4.desktop.player.StreamProxy
import com.lagradost.cloudstream4.desktop.player.StreamSource
import com.lagradost.cloudstream4.desktop.player.VlcLog
import com.lagradost.cloudstream4.desktop.player.VlcPlayer
import com.lagradost.cloudstream4.desktop.plugins.PluginLoader
import kotlinx.coroutines.runBlocking
import java.io.File
import java.util.Collections
import kotlin.test.Test

/**
 * Plays a real title the way the app does: the installed extension finds the link, the proxy and
 * VLC play it, and this times when the first picture appears. Opt in, it uses the network:
 * HYPER_REAL_TEST="/path/to/extension.cs3|Provider name|episode data", with HYPER_REAL_DATA
 * pointing at the app's data folder (for the extension's cookies) and HYPER_REAL_START in ms.
 */
class RealStreamStartTest {
    @Test
    fun firstPictureTime() {
        val spec = System.getenv("HYPER_REAL_TEST") ?: return println("Skipping, set HYPER_REAL_TEST")
        val (cs3, providerName, data) = spec.split('|', limit = 3)
        System.getenv("HYPER_REAL_DATA")?.let { System.setProperty("cloudstream.dataDir", it) }
        val startMs = System.getenv("HYPER_REAL_START")?.toLong() ?: 0L

        PluginLoader.load(File(cs3))
        val api = APIHolder.allProviders.first { it.name == providerName }
        val links = Collections.synchronizedList(mutableListOf<ExtractorLink>())
        val t0 = System.nanoTime()
        fun now() = (System.nanoTime() - t0) / 1_000_000
        val subs = Collections.synchronizedList(mutableListOf<com.lagradost.cloudstream3.SubtitleFile>())
        runBlocking { api.loadLinks(data, false, { subs += it }, { links += it }) }
        println("SUBTITLES from the extension: ${subs.size} ${subs.take(6).map { it.lang }}")
        // HYPER_REAL_MASTER: the host's own playlist, before the proxy changes anything
        System.getenv("HYPER_REAL_MASTER")?.let { out ->
            val l = links.first { it.type == ExtractorLinkType.M3U8 }
            val text = runBlocking { com.lagradost.cloudstream3.app.get(l.url, headers = l.headers + listOfNotNull(l.referer.takeIf { it.isNotBlank() }?.let { "Referer" to it })).text }
            File(out).writeText(text)
            // Each track playlist: how many segments and the longest one
            val hdrs = l.headers + listOfNotNull(l.referer.takeIf { it.isNotBlank() }?.let { "Referer" to it })
            val base = java.net.URI(l.url)
            val uris = Regex("URI=\"([^\"]+)\"").findAll(text).map { it.groupValues[1] }.toList() +
                text.lines().filter { it.isNotBlank() && !it.startsWith("#") }.take(1)
            uris.forEach { u ->
                val pl = runCatching { runBlocking { com.lagradost.cloudstream3.app.get(base.resolve(u).toString(), headers = hdrs).text } }.getOrElse { "ERR $it" }
                val d = Regex("#EXTINF:\\s*([0-9.]+)").findAll(pl).map { it.groupValues[1].toDouble() }.toList()
                println("TRACK ${u.substringBefore('?').takeLast(50)}: ${d.size} segments, longest ${d.maxOrNull()}, total ${d.sum()}, endlist=${"EXT-X-ENDLIST" in pl}")
            }
        }
        println("${now()} ms: ${links.size} links: ${links.map { "${it.name} ${it.type} ${it.quality}" }}")
        val link = links.first { it.type == ExtractorLinkType.M3U8 }

        val player = VlcPlayer.create() ?: return println("Skipping, VLC not found")
        try {
            val url = StreamProxy.proxiedUrl(link.url, link.referer, link.headers)
            val opened = now()
            player.open(StreamSource(url, mimeType = StreamSource.HLS), startMs, emptyList())
            var firstPicture: Long? = null
            var lastFrame: Any? = null
            var frames = 0
            var lastPrint = 0L
            // Frames shown in the 20 seconds after opening: a black screen shows none or one
            var blackNoLoader = 0L
            while (now() - opened < 60_000) {
                val s = player.state.value
                val frame = player.frame
                // The player says it's playing, yet no picture and no loader: the user sees plain black
                if (player.state.value.playing && !player.state.value.buffering && frame == null) blackNoLoader += 40
                if (frame != null && frame !== lastFrame) { frames++; lastFrame = frame }
                if (firstPicture == null && frame != null) firstPicture = now() - opened
                if (now() - lastPrint >= 1000) {
                    println("${now() - opened} ms: playing=${s.playing} loading=${s.buffering} time=${s.timeMs} frames=$frames")
                    lastPrint = now()
                }
                Thread.sleep(40)
            }
            println("PLAYER subtitle tracks ${player.state.value.subtitleTracks.map { it.name }}, live=${StreamProxy.isLive(url)}, length=${player.state.value.lengthMs}")
            println("RESULT blackNoLoader=${blackNoLoader} ms, frames shown $frames, first picture after ${firstPicture ?: "never"} ms, " +
                "audio choices ${StreamProxy.audioChoices(url)}, length ${player.state.value.lengthMs} ms")
            VlcLog.log("real stream test done")
        } finally {
            player.release()
            StreamProxy.clear()
        }
    }
}
