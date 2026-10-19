package com.lagradost.cloudstream4.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asComposeImageBitmap
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.lagradost.cloudstream4.desktop.data.HistoryEntry
import com.lagradost.cloudstream4.desktop.data.Snapshots
import com.lagradost.cloudstream4.desktop.player.VlcPlayer
import com.lagradost.cloudstream4.desktop.ui.ContinueWatchingCard
import com.lagradost.cloudstream4.theme.CloudStreamTheme
import com.lagradost.cloudstream4.theme.CloudStreamThemeMode
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ContinueWatchingTest {
    @Test
    fun skipsDarkFrames() {
        System.setProperty("cloudstream.dataDir", Files.createTempDirectory("cs-snap").toString())
        val black = Bitmap().apply { allocN32Pixels(1280, 720); erase(0xFF000000.toInt()) }.asComposeImageBitmap()
        assertNull(Snapshots.save("Api", "dark", black))
    }

    /** Saves a frame from a real video and renders the card, normal and hovered. Needs CS_VLC_FILE and CS_RENDER_DIR. */
    @OptIn(ExperimentalComposeUiApi::class)
    @Test
    fun savesFrameAndRendersCard() = runBlocking {
        val path = System.getenv("CS_VLC_FILE") ?: return@runBlocking
        val dir = System.getenv("CS_RENDER_DIR")?.let(::File) ?: return@runBlocking
        System.setProperty("cloudstream.dataDir", Files.createTempDirectory("cs-snap").toString())
        val player = VlcPlayer.create() ?: return@runBlocking
        val snapshot = try {
            player.open(File(path).toPath().toUri().toString(), startMs = 900_000)
            withTimeout(10_000) { player.state.first { it.playing } }
            delay(1500)
            Snapshots.save("Api", "ep", player.frame!!)
        } finally {
            player.release()
        }
        assertNotNull(snapshot, "frame was not saved")
        assertEquals(480, Image.makeFromEncoded(snapshot.readBytes()).width)

        val entry = HistoryEntry(
            parentUrl = "u", apiName = "Api", name = "Blue", posterUrl = null, episodeData = "ep",
            episodeName = null, season = null, episode = null, positionMs = 900_000, durationMs = 5_251_000,
            snapshotPath = snapshot.path,
        )
        for (hover in listOf(false, true)) {
            val scene = ImageComposeScene(760, 420, Density(1.5f)) {
                CloudStreamTheme(mode = CloudStreamThemeMode.Dark) {
                    Box(Modifier.fillMaxSize().background(Color(0xFF111111)).padding(24.dp)) {
                        ContinueWatchingCard(entry, {}, {}, {})
                    }
                }
            }
            var t = 0L
            fun frame() = scene.render(t).also { t += 50_000_000L }
            repeat(5) { frame(); delay(100) }
            if (hover) {
                scene.sendPointerEvent(PointerEventType.Enter, Offset(300f, 160f))
                scene.sendPointerEvent(PointerEventType.Move, Offset(300f, 160f))
            }
            repeat(20) { frame(); delay(30) }
            File(dir, if (hover) "card-hover.png" else "card.png").writeBytes(frame().encodeToData(EncodedImageFormat.PNG)!!.bytes)
            scene.close()
        }
    }
}
