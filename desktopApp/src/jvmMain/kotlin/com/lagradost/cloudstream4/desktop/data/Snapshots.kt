package com.lagradost.cloudstream4.desktop.data

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asSkiaBitmap
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Canvas
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skia.Rect
import java.io.File
import java.util.UUID

/**
 * Small pictures of where the user stopped watching, for Continue watching cards. One JPEG per
 * episode, replaced as playback moves on.
 */
object Snapshots {
    private const val WIDTH = 480
    private val dir by lazy { File(AppDirs.data, "snapshots").apply { mkdirs() } }

    fun fileFor(apiName: String, episodeData: String): File =
        File(dir, UUID.nameUUIDFromBytes("$apiName|$episodeData".toByteArray()).toString() + ".jpg")

    /**
     * Saves [frame] scaled down. Returns the file, or null when the frame is too dark to be
     * useful (a fade or black credits), so the previous picture is kept instead.
     */
    fun save(apiName: String, episodeData: String, frame: ImageBitmap): File? = runCatching {
        val source = Image.makeFromBitmap(frame.asSkiaBitmap())
        val height = (WIDTH * source.height / source.width.coerceAtLeast(1)).coerceAtLeast(1)
        val small = Bitmap().apply { allocN32Pixels(WIDTH, height) }
        Canvas(small).drawImageRect(source, Rect.makeWH(WIDTH.toFloat(), height.toFloat()))
        if (isTooDark(small)) return null
        val trimmed = trimLetterbox(small)
        val bytes = Image.makeFromBitmap(trimmed).encodeToData(EncodedImageFormat.JPEG, 82)?.bytes ?: return null
        val file = fileFor(apiName, episodeData)
        val tmp = File(file.path + ".tmp")
        tmp.writeBytes(bytes)
        if (!tmp.renameTo(file)) {
            file.delete()
            tmp.renameTo(file)
        }
        file
    }.getOrNull()

    /** Cuts black bars above and below a widescreen picture, so cards fill edge to edge. */
    private fun trimLetterbox(bitmap: Bitmap): Bitmap {
        val info = ImageInfo.makeN32Premul(bitmap.width, bitmap.height)
        val pixels = bitmap.readPixels(info, info.minRowBytes, 0, 0) ?: return bitmap
        val rowBytes = info.minRowBytes
        fun rowIsBlack(y: Int): Boolean {
            var sum = 0L
            var x = 0
            while (x < bitmap.width) {
                val i = y * rowBytes + x * 4
                sum += (pixels[i].toInt() and 255) + (pixels[i + 1].toInt() and 255) + (pixels[i + 2].toInt() and 255)
                x += 4
            }
            return sum / (3L * (bitmap.width / 4).coerceAtLeast(1)) < 18
        }
        var top = 0
        while (top < bitmap.height / 3 && rowIsBlack(top)) top++
        var bottom = bitmap.height - 1
        while (bottom > bitmap.height * 2 / 3 && rowIsBlack(bottom)) bottom--
        // Only trim real bars, a few rows of dark picture are left alone
        if (top < 4 && bitmap.height - 1 - bottom < 4) return bitmap
        val cropped = Bitmap()
        return if (bitmap.extractSubset(cropped, org.jetbrains.skia.IRect.makeLTRB(0, top, bitmap.width, bottom + 1))) cropped else bitmap
    }

    private fun isTooDark(bitmap: Bitmap): Boolean {
        val info = ImageInfo.makeN32Premul(bitmap.width, bitmap.height)
        val pixels = bitmap.readPixels(info, info.minRowBytes, 0, 0) ?: return false
        var sum = 0L
        var count = 0
        // Sample every 16th pixel, enough to tell a dark frame
        var i = 0
        while (i + 3 < pixels.size) {
            sum += (pixels[i].toInt() and 255) + (pixels[i + 1].toInt() and 255) + (pixels[i + 2].toInt() and 255)
            count += 3
            i += 64
        }
        return count > 0 && sum / count < 14
    }
}
