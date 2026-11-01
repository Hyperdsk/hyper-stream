package com.lagradost.cloudstream4.desktop

import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream4.desktop.player.StreamProxy
import com.lagradost.cloudstream4.desktop.plugins.PluginLoader
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PluginLoaderTest {
    init {
        System.setProperty("cloudstream.dataDir", Files.createTempDirectory("cs-test").toString())
    }

    /** ExampleProvider.cs3 is upstream's template extension (recloudstream/TestPlugins), built for Android. */
    private fun exampleCs3(): File {
        val target = Files.createTempDirectory("cs-ext").resolve("ExampleProvider.cs3").toFile()
        javaClass.getResourceAsStream("/ExampleProvider.cs3")!!.use { input ->
            target.outputStream().use { input.copyTo(it) }
        }
        return target
    }

    @Test
    fun loadsAndUnloadsAnAndroidExtension() {
        val file = exampleCs3()
        val plugin = PluginLoader.load(file)

        assertEquals("ExampleProvider", plugin.manifest.name)
        assertTrue(plugin.providerNames.isNotEmpty(), "extension registered no providers")
        assertTrue(APIHolder.allProviders.any { it.sourcePlugin == file.absolutePath })
        println("Loaded providers: ${plugin.providerNames}")

        PluginLoader.unload(file)
        assertFalse(APIHolder.allProviders.any { it.sourcePlugin == file.absolutePath })
    }

    @Test
    fun rewritesHlsPlaylistThroughProxy() {
        val playlist = """
            #EXTM3U
            #EXT-X-KEY:METHOD=AES-128,URI="key.bin"
            #EXTINF:10,
            seg-1.ts
            https://cdn.example.com/abs/seg-2.ts
        """.trimIndent()
        val out = StreamProxy.rewritePlaylist(playlist, "https://host.example.com/path/index.m3u8", "session")
        val lines = out.lines()
        assertTrue(lines[1].contains("URI=\"http://127.0.0.1:"), lines[1])
        assertTrue(lines[3].startsWith("http://127.0.0.1:"), lines[3])
        assertTrue(lines[4].startsWith("http://127.0.0.1:"), lines[4])
        assertEquals("#EXTINF:10,", lines[2])
    }
    @Test
    fun rewritesPlaylistUrlsJavaCantParse() {
        // A real Prime Video playlist: java.net.URI rejects the brackets, which made the whole source unplayable
        val playlist = """
            #EXTM3U
            #EXT-X-MEDIA:TYPE=SUBTITLES,GROUP-ID="subs",NAME="Hindi",URI="https://pv.subscdn.top/subs/0L/hi-in.[CC].m3u8"
            #EXT-X-STREAM-INF:BANDWIDTH=1000000,RESOLUTION=1920x800,SUBTITLES="subs"
            https://s0022.freecdn4.top/files/1080p/1080p.m3u8?in=unknown::kp::t
        """.trimIndent()
        val lines = StreamProxy.rewritePlaylist(playlist, "https://tv.imgcdn.kim/hls/pv/0L.m3u8?in=1", "session").lines()
        val subtitle = Regex("URI=\"([^\"]+)\"").find(lines[1])!!.groupValues[1]
        assertTrue(subtitle.startsWith("http://127.0.0.1:"), subtitle)
        val encoded = subtitle.substringAfter("/s/session/").substringBefore('/')
        assertEquals("https://pv.subscdn.top/subs/0L/hi-in.[CC].m3u8", String(java.util.Base64.getUrlDecoder().decode(encoded)))
        assertTrue(lines[3].startsWith("http://127.0.0.1:"), lines[3])
    }
}
