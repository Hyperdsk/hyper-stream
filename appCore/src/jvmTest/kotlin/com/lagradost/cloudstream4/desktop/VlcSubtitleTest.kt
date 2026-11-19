package com.lagradost.cloudstream4.desktop

import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.graphics.toPixelMap
import com.lagradost.cloudstream4.desktop.player.VlcPlayer
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Real VLC: does an added subtitle file show up in the frames we draw? Compares the bottom of
 * the picture with the subtitle off and on. Opt in: CLOUDSTREAM_VLC_SUBTEST=/path/video, needs
 * CLOUDSTREAM_VLC_SRT=/path/to.srt with a line shown from the start.
 */
class VlcSubtitleTest {
    @Test
    fun subtitleIsDrawnIntoFrames() {
        val video = System.getenv("CLOUDSTREAM_VLC_SUBTEST")
        org.junit.Assume.assumeTrue("CLOUDSTREAM_VLC_SUBTEST not set", video != null)
        video!!
        val srt = File(System.getenv("CLOUDSTREAM_VLC_SRT"))
        val out = File("/tmp/cs-subtest.txt").apply { writeText("") }
        System.setProperty("cloudstream.dataDir", "/tmp/cs-subtest-data")
        val player = VlcPlayer.create() ?: return println("No VLC")
        fun bottomBrightness(): Double {
            val frame = player.frame ?: return -1.0
            val px = frame.toPixelMap()
            // Share of near-white pixels in the bottom fifth: subtitle text is white, the test picture has none
            var white = 0; var n = 0
            for (y in (px.height * 0.8).toInt() until px.height step 2) for (x in 0 until px.width step 2) {
                val c = px[x, y]; if (c.red > 0.95f && c.green > 0.95f && c.blue > 0.95f) white++; n++
            }
            return white.toDouble() / n
        }
        try {
            player.open(com.lagradost.cloudstream4.desktop.player.StreamSource(File(video).toPath().toUri().toString()))
            val t0 = System.currentTimeMillis()
            while (player.state.value.timeMs < 2_000 && System.currentTimeMillis() - t0 < 30_000) Thread.sleep(100)
            player.setSubtitleTrack(-1)
            Thread.sleep(800)
            val off = bottomBrightness()
            player.addSubtitle(srt.toPath().toUri().toString())
            Thread.sleep(2_500)
            val on = bottomBrightness()
            player.frame?.let { f ->
                val bmp = f.asSkiaBitmap()
                val png = org.jetbrains.skia.Image.makeFromBitmap(bmp).encodeToData(org.jetbrains.skia.EncodedImageFormat.PNG)!!
                File("/tmp/cs-subframe.png").writeBytes(png.bytes)
            }
            val s = player.state.value
            out.appendText("off=$off on=$on tracks=${s.subtitleTracks} selected=${s.subtitleTrack} frame=${player.frame?.width}x${player.frame?.height}\n")
            println(out.readText())
            assertTrue(on > off + 0.005, "subtitle not visible: off=$off on=$on")
        } finally {
            val r = Thread { player.release() }.apply { isDaemon = true; start() }
            r.join(5_000)
        }
    }
}
