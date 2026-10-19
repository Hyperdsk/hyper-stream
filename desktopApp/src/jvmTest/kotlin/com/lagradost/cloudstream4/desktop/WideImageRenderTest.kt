package com.lagradost.cloudstream4.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.lagradost.cloudstream4.desktop.data.jsonMapper
import com.lagradost.cloudstream4.desktop.ui.WideImage
import com.lagradost.cloudstream4.theme.CloudStreamTheme
import com.lagradost.cloudstream4.theme.CloudStreamThemeMode
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import java.net.URI
import kotlin.test.Test

/** Renders a portrait poster in an Up Next card and a hero-sized frame. Needs CS_RENDER_DIR and a connection. */
class WideImageRenderTest {
    @Test
    fun fitsPortraitPosters() {
        val dir = System.getenv("CS_RENDER_DIR")?.let(::File) ?: return
        val json = jsonMapper.readTree(URI("https://v3.sg.media-imdb.com/suggestion/x/breaking%20bad.json").toURL())
        val poster = json.path("d").first { it.has("i") }.path("i").path("imageUrl").asText()
        val scene = ImageComposeScene(1300, 900, Density(1f)) {
            CloudStreamTheme(mode = CloudStreamThemeMode.Dark) {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    WideImage(poster, null, Modifier.width(320.dp).aspectRatio(16f / 9f))
                    WideImage(poster, null, Modifier.fillMaxWidth().height(560.dp), fitAlignment = BiasAlignment(0.75f, 0f))
                }
            }
        }
        repeat(60) { i -> scene.render(i * 100_000_000L); Thread.sleep(100) }
        dir.mkdirs()
        File(dir, "wide-image.png").writeBytes(scene.render(7_000_000_000L).encodeToData(EncodedImageFormat.PNG)!!.bytes)
        scene.close()
    }
}
