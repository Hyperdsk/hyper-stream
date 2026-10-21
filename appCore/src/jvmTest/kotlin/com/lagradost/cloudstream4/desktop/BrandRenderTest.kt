package com.lagradost.cloudstream4.desktop

import org.jetbrains.skia.Color
import org.jetbrains.skia.Data
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Font
import org.jetbrains.skia.FontMgr
import org.jetbrains.skia.FontStyle
import org.jetbrains.skia.Paint
import org.jetbrains.skia.Surface
import org.jetbrains.skia.TextLine
import org.jetbrains.skia.svg.SVGDOM
import java.io.File
import kotlin.test.Test

/**
 * Makes the Hyper Stream icon images from the SVGs in branding/ and desktop-icons/. Runs only
 * when CS_BRAND_JOBS is set: "svg|out.png|width" entries separated by ';'. CS_BRAND_BANNER
 * makes the Android TV banner. See branding/README.md.
 */
class BrandRenderTest {
    private fun render(svg: File, width: Int, height: Int = width, x: Float = 0f, y: Float = 0f, canvas: org.jetbrains.skia.Canvas) {
        val dom = SVGDOM(Data.makeFromBytes(svg.readBytes()))
        val viewBox = Regex("""viewBox="0 0 (\d+) (\d+)"""").find(svg.readText())!!.groupValues
        val w = viewBox[1].toFloat()
        val h = viewBox[2].toFloat()
        canvas.save()
        canvas.translate(x, y)
        canvas.scale(width / w, height / h)
        dom.setContainerSize(w, h)
        dom.render(canvas)
        canvas.restore()
    }

    @Test
    fun renders() {
        System.getenv("CS_BRAND_JOBS")?.split(';')?.filter { it.isNotBlank() }?.forEach { job ->
            val (svg, out, size) = job.split('|')
            val surface = Surface.makeRasterN32Premul(size.toInt(), size.toInt())
            surface.canvas.clear(Color.TRANSPARENT)
            render(File(svg), size.toInt(), canvas = surface.canvas)
            File(out).apply { parentFile?.mkdirs() }.writeBytes(surface.makeImageSnapshot().encodeToData(EncodedImageFormat.PNG)!!.bytes)
        }
        // Android TV banner, 320x180: the monogram and the name on the brand blue
        System.getenv("CS_BRAND_BANNER")?.let { spec ->
            val (monogram, out) = spec.split('|')
            val surface = Surface.makeRasterN32Premul(320, 180)
            val canvas = surface.canvas
            canvas.clear(0xFF0A47F8.toInt())
            render(File(monogram), 112, x = 36f, y = 34f, canvas = canvas)
            val typeface = FontMgr.default.matchFamilyStyle("Helvetica Neue", FontStyle.BOLD) ?: FontMgr.default.legacyMakeTypeface("", FontStyle.BOLD)
            val font = Font(typeface, 34f)
            val paint = Paint().apply { color = Color.WHITE; isAntiAlias = true }
            canvas.drawTextLine(TextLine.make("Hyper", font), 152f, 84f, paint)
            canvas.drawTextLine(TextLine.make("Stream", font), 152f, 122f, paint)
            File(out).apply { parentFile?.mkdirs() }.writeBytes(surface.makeImageSnapshot().encodeToData(EncodedImageFormat.PNG)!!.bytes)
        }
    }
}
