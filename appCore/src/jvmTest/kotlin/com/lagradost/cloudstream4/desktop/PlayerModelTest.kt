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
        override fun setAudioTrack(id: Int) = Unit
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
        val links: suspend (String, (ExtractorLink) -> Unit) -> Unit,
    ) : MainAPI() {
        override var mainUrl = "https://example.invalid"
        override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
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
}
