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
}
