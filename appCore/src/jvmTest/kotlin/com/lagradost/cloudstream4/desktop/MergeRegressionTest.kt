package com.lagradost.cloudstream4.desktop

import com.lagradost.cloudstream4.desktop.player.StreamProxy
import com.lagradost.cloudstream4.desktop.plugins.ExtensionManager
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MergeRegressionTest {
    @Test
    fun installedExtensionsFollowTheMovedDataFolder() {
        val base = Files.createTempDirectory("moved").toFile()
        try {
            // Listed under the old CloudStream folder, now in HyperStream after the move
            val extensions = File(base, "HyperStream/Extensions").apply { mkdirs() }
            val moved = File(extensions, "repo_abc/MyProvider.cs3").apply { parentFile.mkdirs(); writeText("x") }
            val old = File(base, "CloudStream/Extensions/repo_abc/MyProvider.cs3").path
            assertEquals(moved.absolutePath, ExtensionManager.inExtensionsFolder(old, extensions))
            // Still there, or nowhere to be found: left as it is
            assertEquals(moved.absolutePath, ExtensionManager.inExtensionsFolder(moved.absolutePath, extensions))
            val missing = File(base, "CloudStream/Extensions/repo_abc/Gone.cs3").path
            assertEquals(missing, ExtensionManager.inExtensionsFolder(missing, extensions))
        } finally {
            base.deleteRecursively()
        }
    }

    @Test
    fun aSubtitleTrackWithoutAnEndMarkerDoesNotMakeAFilmLive() {
        val session = "regression"
        val master = """
            #EXTM3U
            #EXT-X-MEDIA:TYPE=SUBTITLES,GROUP-ID="subs",NAME="English",URI="http://127.0.0.1:1/subs/en.m3u8"
            #EXT-X-STREAM-INF:BANDWIDTH=1000000
            http://127.0.0.1:1/video/1080p.m3u8
        """.trimIndent()
        try {
            StreamProxy.rewritePlaylist(master, "http://127.0.0.1:1/master.m3u8", session)
            // No #EXT-X-ENDLIST, as some hosts serve them for films
            StreamProxy.rewritePlaylist("#EXTM3U\n#EXT-X-TARGETDURATION:600\n#EXTINF:600,\nen.vtt\n", "http://127.0.0.1:1/subs/en.m3u8", session)
            assertFalse(StreamProxy.isLive("http://127.0.0.1:1/s/$session/x/master.m3u8"))
            // The video's own playlist without one is live
            StreamProxy.rewritePlaylist("#EXTM3U\n#EXT-X-TARGETDURATION:6\n#EXTINF:6,\nseg1.ts\n", "http://127.0.0.1:1/video/1080p.m3u8", session)
            assertTrue(StreamProxy.isLive("http://127.0.0.1:1/s/$session/x/master.m3u8"))
        } finally {
            StreamProxy.clear()
        }
    }
}
