package com.lagradost.cloudstream4.desktop

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asSkiaBitmap
import com.lagradost.cloudstream4.desktop.player.VlcPlayer
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import java.io.File
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Seek bar thumbnails from the real VLC. Runs only when CS_VLC_FILE points at a video. */
class SeekPreviewTest {
    @Test
    fun grabsDifferentFramesForDifferentTimes() = runBlocking {
        val path = System.getenv("CS_VLC_FILE") ?: return@runBlocking
        val out = System.getenv("CS_RENDER_DIR")?.let(::File)
        val player = VlcPlayer.create() ?: return@runBlocking
        val preview = player.createPreview()
        try {
            preview.open(File(path).toPath().toUri().toString())
            val frames = mutableListOf<ImageBitmap>()
            for (seconds in listOf(60L, 900L, 2400L)) {
                val started = System.currentTimeMillis()
                preview.request(seconds * 1000)
                val frame = withTimeoutOrNull(8_000) {
                    while (preview.thumbnail == null || preview.thumbnail in frames) delay(20)
                    preview.thumbnail
                }
                assertNotNull(frame, "no thumbnail for ${seconds}s")
                println("Thumbnail at ${seconds}s: ${frame.width}x${frame.height} in ${System.currentTimeMillis() - started} ms")
                frames += frame
                out?.let { dir ->
                    dir.mkdirs()
                    val bytes = Image.makeFromBitmap(frame.asSkiaBitmap()).encodeToData(EncodedImageFormat.PNG)!!.bytes
                    File(dir, "thumb-$seconds.png").writeBytes(bytes)
                }
            }
            // Cached: asking again is immediate
            preview.request(60_000)
            assertTrue(preview.thumbnail === frames[0], "cached frame not reused")
        } finally {
            preview.release()
            player.release()
        }
    }
}
