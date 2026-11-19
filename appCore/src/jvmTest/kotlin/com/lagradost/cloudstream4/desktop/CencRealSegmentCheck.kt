package com.lagradost.cloudstream4.desktop

import com.lagradost.cloudstream4.desktop.player.Cenc
import com.lagradost.cloudstream4.desktop.player.StreamSource
import com.lagradost.cloudstream4.desktop.player.VlcPlayer
import java.io.File
import kotlin.test.Test

/**
 * Decrypts saved real segments into one file and plays it with VLC, saving a frame to look at.
 * Opt in: HYPER_CENC_DIR with video-init.mp4, video-media.mp4 and key.txt ("kidhex=keyhex").
 */
class CencRealSegmentCheck {
    @Test
    fun decryptedSegmentPlays() {
        val dir = System.getenv("HYPER_CENC_DIR")?.let(::File) ?: return println("Skipping, set HYPER_CENC_DIR")
        val (kid, key) = File(dir, "key.txt").readText().trim().split('=')
        val keys = Cenc.parseKeys(mapOf(kid to key))
        val init = File(dir, "video-init.mp4").readBytes().also { Cenc.process("check", it, keys) }
        val media = File(dir, "video-media.mp4").readBytes().also { Cenc.process("check", it, keys) }
        val out = File(dir, "decrypted.mp4").apply { writeBytes(init + media) }
        val player = VlcPlayer.create() ?: return println("Skipping, VLC not found")
        try {
            player.open(StreamSource(out.toPath().toUri().toString()), 0, emptyList())
            var frames = 0
            var last: Any? = null
            repeat(100) {
                val f = player.frame
                if (f != null && f !== last) { frames++; last = f }
                Thread.sleep(50)
            }
            println("Decrypted file: $frames frames")
            (last as? androidx.compose.ui.graphics.ImageBitmap)?.let { img ->
                val bmp = org.jetbrains.skia.Bitmap().apply { allocN32Pixels(img.width, img.height) }
                val pixels = IntArray(img.width * img.height).also { img.readPixels(it) }
                val bytes = java.nio.ByteBuffer.allocate(pixels.size * 4).order(java.nio.ByteOrder.LITTLE_ENDIAN).apply { asIntBuffer().put(pixels) }.array()
                bmp.installPixels(bytes)
                File(dir, "frame.png").writeBytes(org.jetbrains.skia.Image.makeFromBitmap(bmp).encodeToData(org.jetbrains.skia.EncodedImageFormat.PNG)!!.bytes)
            }
        } finally {
            player.release()
        }
    }
}
