package com.lagradost.cloudstream4.desktop

import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream4.desktop.data.SettingsStore
import com.lagradost.cloudstream4.desktop.download.DownloadManager
import com.lagradost.cloudstream4.desktop.download.DownloadRequest
import com.lagradost.cloudstream4.desktop.download.DownloadStatus
import com.lagradost.cloudstream4.desktop.player.StreamSource
import com.lagradost.cloudstream4.desktop.player.VlcPlayer
import com.lagradost.cloudstream4.desktop.ui.PlayableEpisode
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Streams that keep their audio in a separate playlist (EXT-X-MEDIA TYPE=AUDIO), as the Disney
 * and Prime Video sources do. The library's downloader refuses them, so the app saves both tracks
 * and local playlists that tie them together.
 */
class SplitAudioHlsTest {
    // MPEG-TS packets start with 0x47
    private val video = List(3) { i -> ByteArray(188 * 40) { if (it % 188 == 0) 0x47 else (i * 17 + it).toByte() } }
    private val audio = List(4) { i -> ByteArray(188 * 10) { if (it % 188 == 0) 0x47 else (i * 5 + it).toByte() } }

    private fun HttpExchange.send(bytes: ByteArray, type: String = "application/octet-stream") {
        responseHeaders.set("Content-Type", type)
        sendResponseHeaders(200, bytes.size.toLong())
        responseBody.use { it.write(bytes) }
    }

    private fun media(names: List<String>, duration: Double, init: String? = null) = buildString {
        appendLine("#EXTM3U")
        appendLine("#EXT-X-VERSION:${if (init != null) 6 else 3}")
        appendLine("#EXT-X-TARGETDURATION:${duration.toInt() + 1}")
        if (init != null) appendLine("#EXT-X-MAP:URI=\"$init\"")
        names.forEach { appendLine("#EXTINF:$duration,"); appendLine(it) }
        appendLine("#EXT-X-ENDLIST")
    }.toByteArray()

    private fun server() = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0).apply {
        createContext("/split/master.m3u8") { ex ->
            val text = """
                #EXTM3U
                #EXT-X-MEDIA:TYPE=SUBTITLES,GROUP-ID="subs",NAME="English",LANGUAGE="eng",URI="subs/eng.m3u8"
                #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="aac",LANGUAGE="eng",NAME="English",DEFAULT=YES,URI="a/0.m3u8"
                #EXT-X-STREAM-INF:BANDWIDTH=400000,AUDIO="aac",RESOLUTION=854x480,SUBTITLES="subs"
                480p/index.m3u8
                #EXT-X-STREAM-INF:BANDWIDTH=900000,AUDIO="aac",RESOLUTION=1280x720,SUBTITLES="subs"
                720p/index.m3u8
            """.trimIndent()
            ex.send(text.toByteArray(), "application/vnd.apple.mpegurl")
        }
        createContext("/split/720p/index.m3u8") { it.send(media(video.indices.map { i -> "v$i.jpg" }, 4.0)) }
        createContext("/split/480p/index.m3u8") { it.send(media(listOf("missing.ts"), 4.0)) }
        video.indices.forEach { i -> createContext("/split/720p/v$i.jpg") { it.send(video[i]) } }
        createContext("/split/a/0.m3u8") { it.send(media(audio.indices.map { i -> "s$i.js" }, 3.0)) }
        audio.indices.forEach { i -> createContext("/split/a/s$i.js") { it.send(audio[i]) } }
        createContext("/split/subs/eng.m3u8") { it.send(media(listOf("eng.vtt"), 99999.0)) }
        createContext("/split/subs/eng.vtt") { it.send("WEBVTT\n\n00:00.000 --> 00:01.000\nHello\n".toByteArray(), "text/vtt") }
        start()
    }

    private class Provider(private val base: String) : MainAPI() {
        override var name = "SplitTest"
        override var mainUrl = base
        override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
            callback(newExtractorLink(name, "Split", "$base/split/master.m3u8", ExtractorLinkType.M3U8) { quality = 720 })
            return true
        }
    }

    @Test
    fun savesVideoAndSeparateAudioAsAPlayablePackage() = runBlocking {
        System.setProperty("cloudstream.dataDir", Files.createTempDirectory("cs-split").toString())
        val folder = Files.createTempDirectory("cs-downloads-split").toFile()
        SettingsStore.update { it.copy(downloadFolder = folder.path) }
        val server = server()
        val base = "http://127.0.0.1:${server.address.port}"
        APIHolder.allProviders.add(Provider(base).apply { sourcePlugin = "test-split" })
        try {
            val episode = PlayableEpisode("movie", null, null, null)
            DownloadManager.enqueue(DownloadRequest("SplitTest", "$base/movie", "Split Movie", null, null, episode))
            val id = DownloadManager.idFor("SplitTest", "movie")
            val entry = withTimeout(60_000) {
                DownloadManager.entries.first { list -> list.any { it.id == id && (it.status == DownloadStatus.Completed || it.status == DownloadStatus.Failed) } }
            }.first { it.id == id }
            assertEquals(DownloadStatus.Completed, entry.status, entry.error)
            assertTrue(entry.isPackage, "saved as ${entry.filePath}")
            val dir = entry.file!!.parentFile

            // The 720p variant (the best), whole, and every audio segment
            assertEquals(video.sumOf { it.size }.toLong(), File(dir, "video.ts").length())
            assertEquals(audio.sumOf { it.size }.toLong(), File(dir, "audio.ts").length())
            val videoPlaylist = File(dir, "video.m3u8").readText()
            assertTrue("#EXT-X-BYTERANGE:${video[0].size}@0" in videoPlaylist, videoPlaylist)
            assertTrue("#EXT-X-BYTERANGE:${video[1].size}@${video[0].size}" in videoPlaylist, videoPlaylist)
            val master = entry.file!!.readText()
            assertTrue("TYPE=AUDIO" in master && "URI=\"audio.m3u8\"" in master && "RESOLUTION=1280x720" in master, master)
            assertEquals(entry.sizeOnDisk, dir.listFiles()!!.sumOf { it.length() })

            // The playlist's subtitles are saved next to it
            assertTrue(entry.subtitles.any { File(it).readText().contains("Hello") }, "subtitles: ${entry.subtitles}")

            // Moving the download carries the whole package, and it is still recognised as one
            val destination = Files.createTempDirectory("cs-split-moved").toFile()
            assertEquals(1, DownloadManager.move(listOf(id), destination).moved)
            val moved = DownloadManager.entry("SplitTest", "movie")!!
            assertTrue(moved.isPackage, "saved as ${moved.filePath}")
            assertTrue(moved.file!!.exists())
            assertTrue(moved.file!!.path.startsWith(destination.canonicalPath), moved.file!!.path)
            assertFalse(dir.exists(), "the old package folder is removed")
            assertFalse(File(folder, "Split Movie").exists(), "the old title folder is empty and removed")

            DownloadManager.delete(id)
            assertFalse(moved.file!!.parentFile!!.exists(), "the moved package folder is removed")
        } finally {
            APIHolder.allProviders.withLock { APIHolder.allProviders.removeAll { it.sourcePlugin == "test-split" } }
            server.stop(0)
        }
    }

    @Test
    fun savesTheInitSegmentsOfAFragmentedMp4Package() = runBlocking {
        System.setProperty("cloudstream.dataDir", Files.createTempDirectory("cs-fmp4").toString())
        val folder = Files.createTempDirectory("cs-downloads-fmp4").toFile()
        SettingsStore.update { it.copy(downloadFolder = folder.path) }
        val videoInit = "ftyp-video-init".encodeToByteArray()
        val audioInit = "ftyp-audio-init".encodeToByteArray()
        val videoSegments = List(2) { i -> ByteArray(256) { (i + it).toByte() } }
        val audioSegments = List(2) { i -> ByteArray(192) { (i * 3 + it).toByte() } }
        val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0).apply {
            createContext("/fmp4/master.m3u8") { ex ->
                val text = """
                    #EXTM3U
                    #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="aac",LANGUAGE="eng",NAME="English",DEFAULT=YES,URI="audio.m3u8"
                    #EXT-X-STREAM-INF:BANDWIDTH=900000,AUDIO="aac",RESOLUTION=1280x720
                    720p/index.m3u8
                """.trimIndent()
                ex.send(text.toByteArray(), "application/vnd.apple.mpegurl")
            }
            createContext("/fmp4/720p/index.m3u8") {
                it.send(media(videoSegments.indices.map { i -> "v$i.m4s" }, 4.0, init = "init.mp4"))
            }
            createContext("/fmp4/720p/init.mp4") { it.send(videoInit) }
            videoSegments.indices.forEach { i -> createContext("/fmp4/720p/v$i.m4s") { it.send(videoSegments[i]) } }
            createContext("/fmp4/audio.m3u8") {
                it.send(media(audioSegments.indices.map { i -> "s$i.m4s" }, 3.0, init = "init.mp4"))
            }
            createContext("/fmp4/audio/init.mp4") { it.send(audioInit) }
            // The audio playlist's relative init segment resolves to /fmp4/init.mp4
            createContext("/fmp4/init.mp4") { it.send(audioInit) }
            audioSegments.indices.forEach { i -> createContext("/fmp4/s$i.m4s") { it.send(audioSegments[i]) } }
            start()
        }
        val base = "http://127.0.0.1:${server.address.port}"
        val provider = object : MainAPI() {
            override var name = "Fmp4Test"
            override var mainUrl = base
            override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
                callback(newExtractorLink(name, "Fmp4", "$base/fmp4/master.m3u8", ExtractorLinkType.M3U8) { quality = 720 })
                return true
            }
        }.apply { sourcePlugin = "test-fmp4" }
        APIHolder.allProviders.add(provider)
        try {
            val episode = PlayableEpisode("fmp4", null, null, null)
            DownloadManager.enqueue(DownloadRequest("Fmp4Test", "$base/movie", "Fmp4 Movie", null, null, episode))
            val id = DownloadManager.idFor("Fmp4Test", "fmp4")
            val entry = withTimeout(60_000) {
                DownloadManager.entries.first { list -> list.any { it.id == id && (it.status == DownloadStatus.Completed || it.status == DownloadStatus.Failed) } }
            }.first { it.id == id }
            assertEquals(DownloadStatus.Completed, entry.status, entry.error)
            val dir = entry.file!!.parentFile

            assertEquals(videoInit.toList(), File(dir, "video.init").readBytes().toList())
            assertEquals(audioInit.toList(), File(dir, "audio.init").readBytes().toList())
            assertEquals(videoSegments.sumOf { it.size }.toLong(), File(dir, "video.mp4").length())
            assertEquals(audioSegments.sumOf { it.size }.toLong(), File(dir, "audio.mp4").length())
            val videoPlaylist = File(dir, "video.m3u8").readText()
            assertTrue("#EXT-X-MAP:URI=\"video.init\"" in videoPlaylist, videoPlaylist)
            assertTrue("#EXT-X-BYTERANGE:${videoSegments[0].size}@0" in videoPlaylist, videoPlaylist)
            assertTrue("#EXT-X-MAP:URI=\"audio.init\"" in File(dir, "audio.m3u8").readText())

            DownloadManager.delete(id)
        } finally {
            APIHolder.allProviders.withLock { APIHolder.allProviders.removeAll { it.sourcePlugin == "test-fmp4" } }
            server.stop(0)
        }
    }

    /**
     * Downloads a real split stream and keeps the package, printing where it is. Opt-in:
     * CLOUDSTREAM_SPLIT_URL is the master playlist, CLOUDSTREAM_SPLIT_OUT the download folder.
     */
    @Test
    fun downloadsARealSplitStream() = runBlocking {
        val url = System.getenv("CLOUDSTREAM_SPLIT_URL") ?: return@runBlocking
        System.setProperty("cloudstream.dataDir", Files.createTempDirectory("cs-split-real").toString())
        SettingsStore.update { it.copy(downloadFolder = System.getenv("CLOUDSTREAM_SPLIT_OUT") ?: Files.createTempDirectory("cs-split-out").toString()) }
        val provider = object : MainAPI() {
            override var name = "SplitReal"
            override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
                callback(newExtractorLink(name, "Split", url, ExtractorLinkType.M3U8) { quality = 720 })
                return true
            }
        }.apply { sourcePlugin = "test-split-real" }
        APIHolder.allProviders.add(provider)
        try {
            DownloadManager.enqueue(DownloadRequest("SplitReal", "real", "Real Split", null, null, PlayableEpisode("real", null, null, null)))
            val id = DownloadManager.idFor("SplitReal", "real")
            val entry = withTimeout(300_000) {
                DownloadManager.entries.first { list -> list.any { it.id == id && (it.status == DownloadStatus.Completed || it.status == DownloadStatus.Failed) } }
            }.first { it.id == id }
            assertEquals(DownloadStatus.Completed, entry.status, entry.error)
            println("PACKAGE ${entry.filePath}")
        } finally {
            APIHolder.allProviders.withLock { APIHolder.allProviders.removeAll { it.sourcePlugin == "test-split-real" } }
        }
    }

    /**
     * Plays a real package with VLC, with sound. Opt-in: CLOUDSTREAM_VLC_HLSPKG points at a
     * master.m3u8 made by the downloader.
     */
    @Test
    fun vlcPlaysAPackageWithItsAudio() = runBlocking {
        val path = System.getenv("CLOUDSTREAM_VLC_HLSPKG") ?: return@runBlocking
        val player = VlcPlayer.create() ?: return@runBlocking println("No VLC")
        try {
            player.open(StreamSource(File(path).toPath().toUri().toString()), startMs = 0)
            val playing = withTimeoutOrNull(20_000) { player.state.first { it.timeMs > 2_000 } }
            println("VLC state: $playing")
            assertTrue(playing != null, "VLC played the package")
            val withAudio = withTimeoutOrNull(5_000) { player.state.first { s -> s.audioTracks.any { it.id >= 0 } } }
            println("Audio tracks: ${withAudio?.audioTracks}")
            assertTrue(withAudio != null, "VLC found the audio track")
            player.seekTo(6_000)
            val seeked = withTimeoutOrNull(10_000) { player.state.first { it.timeMs >= 7_000 } }
            assertTrue(seeked != null, "seeking works")
        } finally {
            player.release()
        }
    }
}
