package com.lagradost.cloudstream4.desktop

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import com.lagradost.cloudstream4.desktop.ui.GlassButton
import com.lagradost.cloudstream4.desktop.ui.HyperStreamTheme
import com.lagradost.cloudstream4.desktop.ui.Navigator
import com.lagradost.cloudstream4.desktop.ui.Route
import com.lagradost.cloudstream4.desktop.ui.TopBar
import com.lagradost.cloudstream4.theme.CloudStreamPrimaryColor
import com.lagradost.cloudstream4.theme.CloudStreamThemeMode
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import java.nio.file.Files
import kotlin.test.Test

/** The top bar on Home (with the provider controls) above the bar on Settings, at two widths. Opt-in: CS_TOPBAR_OUT. */
class TopBarRenderTest {
    @Test
    fun tabsStayPut() {
        val out = System.getenv("CS_TOPBAR_OUT")?.let(::File) ?: return
        System.setProperty("cloudstream.dataDir", Files.createTempDirectory("cs-topbar").toString())
        out.mkdirs()
        for (width in listOf(800, 960, 1400)) {
            val home = Navigator()
            val settings = Navigator().apply { open(Route.Settings) }
            val scene = ImageComposeScene(width, 150, Density(1f)) {
                HyperStreamTheme(CloudStreamThemeMode.Dark, CloudStreamPrimaryColor.NORMAL) {
                    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                        Column {
                            TopBar(home, solid = true, sections = com.lagradost.cloudstream4.desktop.ui.HomeSection.entries) {
                                GlassButton(Icons.Default.ArrowDropDown, "Choose provider", text = "MoviesDrive") {}
                                GlassButton(Icons.Default.Refresh, "Reload") {}
                            }
                            TopBar(settings, solid = true, sections = com.lagradost.cloudstream4.desktop.ui.HomeSection.entries)
                        }
                    }
                }
            }
            repeat(3) { scene.render() }
            File(out, "topbar-$width.png").writeBytes(scene.render().encodeToData(EncodedImageFormat.PNG)!!.bytes)
            scene.close()
        }
    }
}
