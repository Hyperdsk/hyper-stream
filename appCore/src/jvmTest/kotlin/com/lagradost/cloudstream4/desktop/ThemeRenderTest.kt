package com.lagradost.cloudstream4.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import com.lagradost.cloudstream4.desktop.ui.HyperStreamTheme
import com.lagradost.cloudstream4.desktop.ui.LocalNavigator
import com.lagradost.cloudstream4.desktop.ui.Navigator
import com.lagradost.cloudstream4.desktop.ui.SettingsScreen
import com.lagradost.cloudstream4.desktop.ui.Sidebar
import com.lagradost.cloudstream4.theme.CloudStreamPrimaryColor
import com.lagradost.cloudstream4.theme.CloudStreamThemeMode
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import java.nio.file.Files
import kotlin.test.Test

/** Renders the sidebar and Settings in the Hyper Stream theme, for checking by eye. Opt-in: CS_THEME_OUT. */
class ThemeRenderTest {
    @Test
    fun rendersTheme() {
        val out = System.getenv("CS_THEME_OUT")?.let(::File) ?: return
        System.setProperty("cloudstream.dataDir", Files.createTempDirectory("cs-theme").toString())
        val nav = Navigator()
        val scene = ImageComposeScene(1200, 760, Density(1f)) {
            HyperStreamTheme(CloudStreamThemeMode.Dark, CloudStreamPrimaryColor.NORMAL) {
                CompositionLocalProvider(LocalNavigator provides nav) {
                    Surface(color = MaterialTheme.colorScheme.background, contentColor = MaterialTheme.colorScheme.onBackground) {
                        Row(Modifier.fillMaxSize()) {
                            Sidebar(nav)
                            Box(Modifier.weight(1f)) { SettingsScreen(vlcAvailable = true) }
                        }
                    }
                }
            }
        }
        repeat(3) { scene.render() }
        out.parentFile?.mkdirs()
        out.writeBytes(scene.render().encodeToData(EncodedImageFormat.PNG)!!.bytes)
        scene.close()
    }
}
