package com.lagradost.cloudstream4.desktop

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream4.desktop.player.PlaybackState
import com.lagradost.cloudstream4.desktop.player.SeekPreview
import com.lagradost.cloudstream4.desktop.player.StreamSource
import com.lagradost.cloudstream4.desktop.player.VideoEngine
import com.lagradost.cloudstream4.desktop.player.VideoFit
import com.lagradost.cloudstream4.desktop.ui.PlayRequest
import com.lagradost.cloudstream4.desktop.ui.PlayableEpisode
import com.lagradost.cloudstream4.desktop.ui.PlayerModel
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The player model with a fake engine and fake extensions: races and failures that lost the user's place. */
class PlayerModelTest {
    /** Records what it was asked to open; the first [failFirst] opens report an error. */
    private class FakeEngine(private val failFirst: Int = 0) : VideoEngine {
        override val state = MutableStateFlow(PlaybackState())
        override var currentUrl: String? = null
        override val startBufferMs = 0L
        val opens = mutableListOf<Long>()

        override fun open(source: StreamSource, startMs: Long, subtitles: List<String>) {
            opens += startMs
            currentUrl = source.url
            state.value = PlaybackState(timeMs = 0, buffering = true)
            if (opens.size <= failFirst) state.update { it.copy(error = "broken ${opens.size}") }
        }
        override fun togglePause() = Unit
        override fun seekTo(ms: Long) = Unit
        override fun setVolume(volume: Int) = Unit
        override fun toggleMute() = Unit
        override fun setRate(rate: Float) = Unit
        val audioPicks = mutableListOf<Int>()
        override fun setAudioTrack(id: Int) { audioPicks += id; state.update { it.copy(audioTrack = id) } }
        override fun addSubtitle(uri: String) = Unit
        override fun setSubtitleTrack(id: Int) = Unit
        override fun stop() { state.value = PlaybackState() }
        override fun release() = Unit
        override fun captureFrame(): ImageBitmap? = null
        override fun createPreview(): SeekPreview? = null
        @Composable override fun Video(modifier: Modifier, fit: VideoFit, zoom: Float, pan: Offset) = Unit
    }

    private class FakeApi(
        override var name: String,
        override val instantLinkLoading: Boolean = false,
        val subtitles: List<SubtitleFile> = emptyList(),
        val links: suspend (String, (ExtractorLink) -> Unit) -> Unit,
    ) : MainAPI() {
        override var mainUrl = "https://example.invalid"
        override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
            subtitles.forEach(subtitleCallback)
            links(data, callback)
            return true
        }
    }

    private suspend fun link(name: String, quality: Int) =
        newExtractorLink(name, name, "https://example.invalid/$name.mp4", ExtractorLinkType.VIDEO) { this.quality = quality }

    private fun request(api: String, startMs: Long = 0, episodes: Int = 1) = PlayRequest(
        api, "https://example.invalid/show", "Show", null, null,
        (1..episodes).map { PlayableEpisode("$api-ep$it", null, null, null) }, 0, startMs,
    )

    private fun setUp() {
        System.setProperty("cloudstream.dataDir", Files.createTempDirectory("cs-player").toString())
    }

    @Test
    fun aLinkFromThePreviousEpisodeDoesNotJoinTheNextOne() = runBlocking {
        setUp()
        APIHolder.allProviders.add(FakeApi("LateLinks") { data, callback ->
            // Episode 1's site answers late and ignores cancellation, as many do
            if (data.endsWith("ep1")) withContext(NonCancellable) { delay(600); callback(link("ep1", 720)) }
            else callback(link("ep2", 720))
        })
        val model = PlayerModel(request("LateLinks", episodes = 2), FakeEngine())
        delay(150)
        model.next()
        delay(1_500)
        assertEquals(listOf("https://example.invalid/ep2.mp4"), model.state.value.links.map { it.url })
        model.dispose()
    }

    @Test
    fun switchingSourceDoesNotMarkSubtitlesBeingCheckedAsBroken() = runBlocking {
        setUp()
        // A slow subtitle host: the check is still waiting when the user switches source
        val server = com.sun.net.httpserver.HttpServer.create(java.net.InetSocketAddress(java.net.InetAddress.getLoopbackAddress(), 0), 0).apply {
            createContext("/en.vtt") { ex ->
                Thread.sleep(800)
                val body = "WEBVTT\n\n00:00.000 --> 00:01.000\nHello\n".toByteArray()
                ex.sendResponseHeaders(200, body.size.toLong())
                ex.responseBody.use { it.write(body) }
            }
            start()
        }
        try {
            val url = "http://127.0.0.1:${server.address.port}/en.vtt"
            APIHolder.allProviders.add(FakeApi("SubSwitch", subtitles = listOf(com.lagradost.cloudstream3.newSubtitleFile("English", url))) { _, callback ->
                callback(link("one", 1080))
                callback(link("two", 720))
            })
            val engine = FakeEngine()
            val model = PlayerModel(request("SubSwitch"), engine)
            delay(500)
            // The first frame starts the subtitle check; then the other source is picked
            engine.state.update { it.copy(timeMs = 1_000, buffering = false) }
            delay(200)
            model.playLink(1)
            delay(1_500)
            val load = model.state.value.loadedSubtitles[url]
            assertTrue(load !is com.lagradost.cloudstream4.desktop.ui.SubtitleLoad.Failed, "a cancelled check isn't a broken subtitle: $load")
            model.dispose()
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun aFailedFirstSourceKeepsTheResumePoint() = runBlocking {
        setUp()
        APIHolder.allProviders.add(FakeApi("Resume") { _, callback ->
            callback(link("first", 1080))
            callback(link("second", 720))
        })
        val engine = FakeEngine(failFirst = 1)
        val model = PlayerModel(request("Resume", startMs = 42_000), engine)
        delay(1_500)
        assertEquals(listOf(42_000L, 42_000L), engine.opens, "the fallback starts where the user stopped, not at 0")
        model.dispose()
    }

    @Test
    fun aSourceArrivingAfterEveryEarlierOneFailedStillPlays() = runBlocking {
        setUp()
        APIHolder.allProviders.add(FakeApi("SlowSecond", instantLinkLoading = true) { _, callback ->
            callback(link("broken", 1080))
            delay(1_200)
            callback(link("works", 720))
        })
        val engine = FakeEngine(failFirst = 1)
        val model = PlayerModel(request("SlowSecond"), engine)
        delay(2_500)
        val ui = model.state.value
        assertEquals(2, engine.opens.size, "the source that arrived later was tried")
        assertEquals("https://example.invalid/works.mp4", ui.links.getOrNull(ui.current ?: -1)?.url)
        assertNull(ui.error)
        model.dispose()
    }

    @Test
    fun searchingAgainOfflineKeepsTheSources() = runBlocking {
        setUp()
        var offline = false
        APIHolder.allProviders.add(FakeApi("Offline") { _, callback ->
            if (offline) throw java.io.IOException("No network")
            callback(link("one", 1080))
            callback(link("two", 720))
        })
        val model = PlayerModel(request("Offline"), FakeEngine())
        delay(1_000)
        assertEquals(2, model.state.value.links.size)
        offline = true
        model.searchAgain()
        delay(1_000)
        assertEquals(2, model.state.value.links.size, "the sources found before are kept")
        assertTrue(!model.state.value.refreshing)
        model.dispose()
    }

    @Test
    fun theAudioLanguagePickedOnceIsChosenNextTime() = runBlocking {
        setUp()
        APIHolder.allProviders.add(FakeApi("Dubbed") { _, callback -> callback(link("dubbed", 1080)) })
        val engine = FakeEngine()
        val model = PlayerModel(request("Dubbed"), engine)
        delay(800)
        // The user picks Hindi on this video
        model.pickAudio(com.lagradost.cloudstream4.desktop.player.Track(2, "Track 2 - [Hindi]"))
        assertEquals("hindi", com.lagradost.cloudstream4.desktop.data.SettingsStore.state.value.audioLanguage)
        model.dispose()

        // The next video has English first and Hindi second: Hindi is chosen by itself
        val next = FakeEngine()
        val again = PlayerModel(request("Dubbed"), next)
        delay(800)
        next.state.update {
            it.copy(audioTracks = kotlinx.collections.immutable.persistentListOf(
                com.lagradost.cloudstream4.desktop.player.Track(1, "English AAC 5.1"),
                com.lagradost.cloudstream4.desktop.player.Track(7, "Hindi AAC 5.1"),
            ), audioTrack = 1)
        }
        delay(500)
        assertEquals(listOf(7), next.audioPicks)
        again.dispose()
    }
}
