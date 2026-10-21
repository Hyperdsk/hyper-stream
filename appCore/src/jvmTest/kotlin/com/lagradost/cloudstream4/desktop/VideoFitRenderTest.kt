package com.lagradost.cloudstream4.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Density
import com.lagradost.cloudstream4.desktop.player.StreamSource
import com.lagradost.cloudstream4.desktop.player.VideoFit
import com.lagradost.cloudstream4.desktop.player.VlcPlayer
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import kotlin.test.Test

/**
 * Renders a playing video in every picture mode, for checking by eye. Opt-in: CLOUDSTREAM_FIT_VIDEO
 * is a video VLC can play, CLOUDSTREAM_FIT_OUT the folder for the PNGs.
 */
class VideoFitRenderTest {
    @Test
    fun rendersEachMode() = runBlocking {
        val video = System.getenv("CLOUDSTREAM_FIT_VIDEO") ?: return@runBlocking
        val out = File(System.getenv("CLOUDSTREAM_FIT_OUT") ?: "/tmp/cs-fit").apply { mkdirs() }
        val player = VlcPlayer.create() ?: return@runBlocking println("No VLC")
        try {
            player.open(StreamSource(File(video).toPath().toUri().toString()))
            withTimeoutOrNull(15_000) { player.state.first { it.timeMs > 1_500 } }
            player.togglePause()
            val cases = VideoFit.entries.map { it to 1f } + listOf(VideoFit.Fit to 2f)
            for ((fit, zoom) in cases) {
                // A tall area, so letterboxing and cropping both show
                val scene = ImageComposeScene(600, 600, Density(1f)) {
                    Box(Modifier.fillMaxSize().background(Color.DarkGray)) {
                        player.Video(Modifier.fillMaxSize(), fit, zoom, Offset.Zero)
                    }
                }
                repeat(2) { scene.render() }
                File(out, "${fit.name}-${(zoom * 100).toInt()}.png").writeBytes(scene.render().encodeToData(EncodedImageFormat.PNG)!!.bytes)
                scene.close()
            }
        } finally {
            player.release()
        }
    }
}
