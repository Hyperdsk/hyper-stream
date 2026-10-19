package com.lagradost.cloudstream4.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream4.desktop.meta.Imdb
import com.lagradost.cloudstream4.desktop.ui.ImdbBadges
import com.lagradost.cloudstream4.desktop.ui.InformationSection
import com.lagradost.cloudstream4.desktop.ui.TvTypography
import com.lagradost.cloudstream4.theme.CloudStreamTheme
import com.lagradost.cloudstream4.theme.CloudStreamThemeMode
import kotlinx.coroutines.runBlocking
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import kotlin.test.Test

/** Renders the IMDb badges and Information section with live data. Needs CS_RENDER_DIR and a connection. */
class ImdbRenderTest {
    @Test
    fun rendersInformation() = runBlocking {
        val dir = System.getenv("CS_RENDER_DIR")?.let(::File) ?: return@runBlocking
        val id = Imdb.findId("Back To The Future Part II", 1989, TvType.Movie) ?: return@runBlocking
        val info = Imdb.fetch(id) ?: return@runBlocking
        val scene = ImageComposeScene(1300, 720, Density(1.5f)) {
            CloudStreamTheme(mode = CloudStreamThemeMode.Dark) {
                MaterialTheme(colorScheme = MaterialTheme.colorScheme, typography = TvTypography) {
                    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(top = 16.dp)) {
                        Row(Modifier.padding(start = 48.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) { ImdbBadges(info) }
                        InformationSection(info)
                    }
                }
            }
        }
        repeat(3) { scene.render(it * 100_000_000L) }
        dir.mkdirs()
        File(dir, "imdb-info.png").writeBytes(scene.render(1_000_000_000L).encodeToData(EncodedImageFormat.PNG)!!.bytes)
        scene.close()
    }
}
