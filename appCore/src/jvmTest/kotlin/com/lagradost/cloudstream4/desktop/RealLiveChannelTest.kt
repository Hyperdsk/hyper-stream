package com.lagradost.cloudstream4.desktop

import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.LiveStreamLoadResponse
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.utils.DrmExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream4.desktop.player.StreamProxy
import com.lagradost.cloudstream4.desktop.player.StreamSource
import com.lagradost.cloudstream4.desktop.player.VlcPlayer
import com.lagradost.cloudstream4.desktop.plugins.PluginLoader
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import kotlin.test.Test

/**
 * Finds a live event's channels through an installed extension and plays each like the app,
 * counting the frames shown. Opt in, it uses the network:
 * - HYPER_LIVE_TEST="/path/to/extension.cs3|Provider name|part of the channel's name" (empty: all)
 * - HYPER_LIVE_EVENT: part of the event's name; HYPER_REAL_DATA: the app's data folder
 * - HYPER_REAL_START: open with a saved position; HYPER_LIVE_SECONDS: per channel (20)
 * - HYPER_LIVE_FRAMES: a folder to save each channel's last frame in, as PNG
 */
class RealLiveChannelTest {
    @Test
    fun liveChannelsShowPictures() {
        val spec = System.getenv("HYPER_LIVE_TEST") ?: return println("Skipping, set HYPER_LIVE_TEST")
        val (cs3, providerName, channel) = spec.split('|', limit = 3)
        val event = System.getenv("HYPER_LIVE_EVENT")?.lowercase()
        val seconds = System.getenv("HYPER_LIVE_SECONDS")?.toLong() ?: 20L
        val framesDir = System.getenv("HYPER_LIVE_FRAMES")?.let { File(it).apply { mkdirs() } }
        System.getenv("HYPER_REAL_DATA")?.let { System.setProperty("cloudstream.dataDir", it) }
        PluginLoader.load(File(cs3))
        val api = APIHolder.allProviders.first { providerName in it.name }

        val links: List<ExtractorLink> = runBlocking {
            for (page in api.mainPage) {
                val items = runCatching { api.getMainPage(1, MainPageRequest(page.name, page.data, page.horizontalImages)) }.getOrNull()
                    ?.items?.flatMap { it.list } ?: continue
                for (item in items) {
                    if (event != null && event !in item.name.lowercase()) continue
                    val load = withTimeoutOrNull(20_000) { runCatching { api.load(item.url) }.getOrNull() } as? LiveStreamLoadResponse ?: continue
                    val found = mutableListOf<ExtractorLink>()
                    withTimeoutOrNull(30_000) { runCatching { api.loadLinks(load.dataUrl, false, {}, { found += it }) } }
                    val wanted = found.filter { channel.lowercase() in it.name.lowercase() }
                    if (wanted.isNotEmpty()) {
                        println("Event '${item.name}': ${wanted.size} channels")
                        return@runBlocking wanted
                    }
                }
            }
            error("No channel named like '$channel'")
        }

        val player = VlcPlayer.create() ?: return println("Skipping, VLC not found")
        val results = mutableListOf<String>()
        try {
            links.forEachIndexed { i, link ->
                val drm = link as? DrmExtractorLink
                val keys = drm?.let { d -> listOfNotNull(d.kid?.let { k -> d.key?.let { k to it } }).toMap() } ?: emptyMap()
                val info = "${link.type}" + (drm?.let { " drm(keys=${keys.size} parsed=${com.lagradost.cloudstream4.desktop.player.Cenc.parseKeys(keys).size} kidLen=${keys.keys.firstOrNull()?.length} keyLen=${keys.values.firstOrNull()?.length} kidSample=${keys.keys.firstOrNull()?.take(4)}, license=${it.licenseUrl != null}, uuid=${it.uuid.toString().take(8)})" } ?: "")
                val url = StreamProxy.proxiedUrl(link.url, link.referer, link.headers, clearKeys = keys)
                // HYPER_LIVE_MANIFESTS: save each manifest as the player gets it, to inspect
                System.getenv("HYPER_LIVE_MANIFESTS")?.let { dir ->
                    File(dir).mkdirs()
                    runCatching { File(dir, "$i.txt").writeText(java.net.URI(url).toURL().readText()) }; File(dir, "$i.url").writeText(link.url + "\n" + link.headers.entries.joinToString("\n") { (k, v) -> "$k: $v" })
                    File(dir, "$i.key").writeText(com.lagradost.cloudstream4.desktop.player.Cenc.parseKeys(keys).entries.joinToString("") { (k, v) -> k + "=" + v.joinToString("") { b -> "%02x".format(b) } })
                }
                val mime = if (link.type == ExtractorLinkType.M3U8) StreamSource.HLS else if (link.type == ExtractorLinkType.DASH) StreamSource.DASH else null
                val t0 = System.nanoTime()
                fun now() = (System.nanoTime() - t0) / 1_000_000
                player.open(StreamSource(url, mimeType = mime), System.getenv("HYPER_REAL_START")?.toLong() ?: 0L, emptyList())
                var frames = 0
                var first: Long? = null
                var last: Any? = null
                var saved = 0L
                var blackNoLoader = 0L
                while (now() < seconds * 1000) {
                    val f = player.frame
                // The player says it's playing, yet no picture and no loader: the user sees plain black
                if (player.state.value.playing && !player.state.value.buffering && f == null) blackNoLoader += 40
                    if (f != null && f !== last) { frames++; last = f; if (first == null) first = now() }
                    // A frame every 10 seconds, to tell a black broadcast from a broken picture
                    if (f is androidx.compose.ui.graphics.ImageBitmap && now() / 10_000 > saved) {
                        saved = now() / 10_000
                        framesDir?.let { savePng(f, File(it, "$i-$saved.png")) }
                    }
                    Thread.sleep(40)
                }
                // What the back button does: how long the player takes to stop
                val stopStarted = System.nanoTime()
                StreamProxy.clear()
                // What's still busy 2 s into the stop
                Thread {
                    Thread.sleep(2000)
                    Thread.getAllStackTraces().filter { (th, st) -> st.any { "cloudstream4" in it.className || "vlcj" in it.className || "okhttp" in it.className } }
                        .forEach { (th, st) -> println("BUSY ${th.name}: " + st.take(14).joinToString(" <- ") { "${it.className.substringAfterLast('.')}.${it.methodName}:${it.lineNumber}" }) }
                }.apply { isDaemon = true }.start()
                player.stop()
                println("STOP took ${(System.nanoTime() - stopStarted) / 1_000_000} ms")
                val s = player.state.value
                val line = "${link.name}: $info -> blackNoLoader=${blackNoLoader} ms, frames=$frames first=${first ?: "never"} ms playing=${s.playing} error=${s.error} host=${link.url.substringAfter("://").substringBefore('/')}"
                println("CHANNEL $line")
                results += line
                framesDir?.let { dir -> (last as? androidx.compose.ui.graphics.ImageBitmap)?.let { savePng(it, File(dir, "$i.png")) } }
                StreamProxy.release(url)
            }
        } finally {
            player.release()
            StreamProxy.clear()
        }
        println("SUMMARY\n" + results.joinToString("\n"))
    }

    private fun savePng(img: androidx.compose.ui.graphics.ImageBitmap, file: File) {
        val bmp = org.jetbrains.skia.Bitmap().apply { allocN32Pixels(img.width, img.height) }
        val pixels = IntArray(img.width * img.height).also { img.readPixels(it) }
        val bytes = java.nio.ByteBuffer.allocate(pixels.size * 4).order(java.nio.ByteOrder.LITTLE_ENDIAN).apply { asIntBuffer().put(pixels) }.array()
        bmp.installPixels(bytes)
        file.writeBytes(org.jetbrains.skia.Image.makeFromBitmap(bmp).encodeToData(org.jetbrains.skia.EncodedImageFormat.PNG)!!.bytes)
    }
}
