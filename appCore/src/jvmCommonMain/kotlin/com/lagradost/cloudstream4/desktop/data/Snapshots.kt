package com.lagradost.cloudstream4.desktop.data

import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import java.io.File
import java.util.UUID

/** Encodes a picture as JPEG, or returns null if that fails. */
expect fun ImageBitmap.encodeJpeg(quality: Int): ByteArray?

/**
 * Small pictures of where the user stopped watching, for Continue watching cards. One JPEG per
 * episode, replaced as playback moves on.
 */
object Snapshots {
    private const val WIDTH = 480
    private val dir by lazy { File(AppDirs.data, "snapshots").apply { mkdirs() } }


    /**
     * Saves [frame] scaled down. Returns the file, or null when the frame is too dark to be
     * useful (a fade or black credits), so the previous picture is kept instead.
     */
    fun save(apiName: String, episodeData: String, frame: ImageBitmap): File? = runCatching {
        val height = (WIDTH * frame.height / frame.width.coerceAtLeast(1)).coerceAtLeast(1)
        val small = scaled(frame, IntOffset.Zero, IntSize(frame.width, frame.height), IntSize(WIDTH, height))
        val pixels = IntArray(WIDTH * height).also { small.readPixels(it) }
        if (isTooDark(pixels)) return null
        val trimmed = trimLetterbox(small, pixels)
        val bytes = trimmed.encodeJpeg(82) ?: return null
        // A random name each time: other apps can open these through the TV home screen's
        // provider, and a name made from the title would let them check what was watched
        val file = File(dir, UUID.randomUUID().toString() + ".jpg")
        val tmp = File(file.path + ".tmp")
        tmp.writeBytes(bytes)
        if (!tmp.renameTo(file)) {
            file.delete()
            tmp.renameTo(file)
        }
        // The episode's previous picture isn't needed any more
        LibraryStore.state.value.history
            .firstOrNull { it.apiName == apiName && it.episodeData == episodeData }
            ?.snapshotPath?.takeIf { it != file.path }?.let { File(it).delete() }
        file
    }.getOrNull()

    private fun scaled(source: ImageBitmap, offset: IntOffset, size: IntSize, to: IntSize): ImageBitmap {
        val out = ImageBitmap(to.width, to.height)
        Canvas(out).drawImageRect(source, offset, size, IntOffset.Zero, to, Paint().apply { filterQuality = FilterQuality.Medium })
        return out
    }

    private fun brightness(pixel: Int) = ((pixel shr 16) and 255) + ((pixel shr 8) and 255) + (pixel and 255)

    /** Cuts black bars above and below a widescreen picture, so cards fill edge to edge. */
    private fun trimLetterbox(bitmap: ImageBitmap, pixels: IntArray): ImageBitmap {
        val w = bitmap.width
        fun rowIsBlack(y: Int): Boolean {
            var sum = 0L
            var x = 0
            while (x < w) {
                sum += brightness(pixels[y * w + x])
                x += 4
            }
            return sum / (3L * (w / 4).coerceAtLeast(1)) < 18
        }
        var top = 0
        while (top < bitmap.height / 3 && rowIsBlack(top)) top++
        var bottom = bitmap.height - 1
        while (bottom > bitmap.height * 2 / 3 && rowIsBlack(bottom)) bottom--
        // Only trim real bars, a few rows of dark picture are left alone
        if (top < 4 && bitmap.height - 1 - bottom < 4) return bitmap
        val size = IntSize(w, bottom + 1 - top)
        return scaled(bitmap, IntOffset(0, top), size, size)
    }

    private fun isTooDark(pixels: IntArray): Boolean {
        var sum = 0L
        var count = 0
        // Sample every 16th pixel, enough to tell a dark frame
        var i = 0
        while (i < pixels.size) {
            sum += brightness(pixels[i])
            count += 3
            i += 16
        }
        return count > 0 && sum / count < 14
    }
}
