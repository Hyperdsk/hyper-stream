package com.lagradost.cloudstream4.desktop

import org.jetbrains.skia.Color
import org.jetbrains.skia.Data
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Surface
import org.jetbrains.skia.svg.SVGDOM
import java.io.File
import kotlin.test.Test

/**
 * Renders the app icon (desktop-icons/icon.svg, the Android app's cloud logo on Apple's icon grid)
 * to PNGs for an .icns set. Runs only when CS_ICON_DIR is set; see desktopApp/MACOS.md.
 */
class AppIconRenderTest {
    @Test
    fun rendersIconSizes() {
        val out = System.getenv("CS_ICON_DIR")?.let(::File) ?: return
        val svg = File("src/desktop-icons/icon.svg").takeIf { it.exists() } ?: File("desktopApp/src/desktop-icons/icon.svg")
        val dom = SVGDOM(Data.makeFromBytes(svg.readBytes()))
        out.mkdirs()
        for (size in listOf(16, 32, 64, 128, 256, 512, 1024)) {
            val surface = Surface.makeRasterN32Premul(size, size)
            surface.canvas.clear(Color.TRANSPARENT)
            surface.canvas.scale(size / 1024f, size / 1024f)
            dom.setContainerSize(1024f, 1024f)
            dom.render(surface.canvas)
            File(out, "icon_$size.png").writeBytes(surface.makeImageSnapshot().encodeToData(EncodedImageFormat.PNG)!!.bytes)
        }
    }
}
