package com.lagradost.cloudstream4.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.lagradost.cloudstream4.desktop.player.VlcPlayer
import com.lagradost.cloudstream4.desktop.ui.SeekBar
import com.lagradost.cloudstream4.theme.CloudStreamTheme
import com.lagradost.cloudstream4.theme.CloudStreamThemeMode
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import kotlin.test.Test

/** Renders the seek bar with a hover thumbnail from a real video. Needs CS_VLC_FILE and CS_RENDER_DIR. */
class SeekBarRenderTest {
    @OptIn(ExperimentalComposeUiApi::class)
    @Test
    fun rendersHoverThumbnail() = runBlocking {
        val path = System.getenv("CS_VLC_FILE") ?: return@runBlocking
        val dir = System.getenv("CS_RENDER_DIR")?.let(::File) ?: return@runBlocking
        val player = VlcPlayer.create() ?: return@runBlocking
        val preview = player.createPreview()
        preview.open(File(path).toPath().toUri().toString())
        val scene = ImageComposeScene(1200, 360, Density(1.5f)) {
            CloudStreamTheme(mode = CloudStreamThemeMode.Dark) {
                Box(Modifier.fillMaxSize().background(Color(0xFF202020))) {
                    SeekBar(0.1f, 5_251_264L, false, preview, {}, {}, Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 150.dp))
                }
            }
        }
        var t = 0L
        fun frame() = scene.render(t).also { t += 16_000_000L }
        repeat(3) { frame() }
        scene.sendPointerEvent(PointerEventType.Enter, Offset(480f, 250f))
        scene.sendPointerEvent(PointerEventType.Move, Offset(480f, 250f))
        repeat(150) { frame(); delay(20) }
        dir.mkdirs()
        File(dir, "seekbar-preview.png").writeBytes(frame().encodeToData(EncodedImageFormat.PNG)!!.bytes)
        scene.close()
        preview.release()
        player.release()
    }
}
