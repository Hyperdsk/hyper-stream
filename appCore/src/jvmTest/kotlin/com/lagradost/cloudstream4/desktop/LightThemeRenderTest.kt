package com.lagradost.cloudstream4.desktop

import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream4.desktop.ui.GlassButton
import com.lagradost.cloudstream4.desktop.ui.HeroCarousel
import com.lagradost.cloudstream4.desktop.ui.HeroItem
import com.lagradost.cloudstream4.desktop.ui.HomeSection
import com.lagradost.cloudstream4.desktop.ui.HyperStreamTheme
import com.lagradost.cloudstream4.desktop.ui.LocalNavigator
import com.lagradost.cloudstream4.desktop.ui.Navigator
import com.lagradost.cloudstream4.desktop.ui.TopBar
import com.lagradost.cloudstream4.theme.CloudStreamPrimaryColor
import com.lagradost.cloudstream4.theme.CloudStreamThemeMode
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import java.nio.file.Files
import kotlin.test.Test

/** The top bar and home banner in the light theme, whose white text sat on white. Opt-in: CS_LIGHT_OUT. */
class LightThemeRenderTest {
    @Test
    fun bannerAndBarAreReadable() {
        val out = System.getenv("CS_LIGHT_OUT")?.let(::File) ?: return
        System.setProperty("cloudstream.dataDir", Files.createTempDirectory("cs-light").toString())
        val api = object : MainAPI() { override var name = "Demo" }
        val item = HeroItem(api.newMovieSearchResponse("Revenge of Others", "https://example.invalid/r", TvType.TvSeries))
        val nav = Navigator()
        val scene = ImageComposeScene(1200, 640, Density(1f)) {
            HyperStreamTheme(CloudStreamThemeMode.Light, CloudStreamPrimaryColor.NORMAL) {
                CompositionLocalProvider(LocalNavigator provides nav) {
                    Surface(color = MaterialTheme.colorScheme.background) {
                        Column {
                            TopBar(nav, solid = true, sections = HomeSection.entries) {
                                GlassButton(Icons.Default.ArrowDropDown, "Choose provider", text = "MoviesDrive") {}
                                GlassButton(Icons.Default.Refresh, "Reload") {}
                            }
                            HeroCarousel(listOf(item))
                        }
                    }
                }
            }
        }
        repeat(5) { scene.render() }
        out.parentFile?.mkdirs()
        out.writeBytes(scene.render().encodeToData(EncodedImageFormat.PNG)!!.bytes)
        scene.close()
    }
}
