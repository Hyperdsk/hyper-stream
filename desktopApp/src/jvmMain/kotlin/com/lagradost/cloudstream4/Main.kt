package com.lagradost.cloudstream4

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyShortcut
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.MenuBar
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.lagradost.cloudstream4.compose.LocalFocusOutlineDefault
import com.lagradost.cloudstream4.desktop.data.SettingsStore
import com.lagradost.cloudstream4.desktop.player.VlcPlayer
import com.lagradost.cloudstream4.desktop.plugins.ExtensionManager
import com.lagradost.cloudstream4.desktop.ui.CloudStreamApp
import com.lagradost.cloudstream4.desktop.ui.LocalWindowState
import com.lagradost.cloudstream4.desktop.ui.ModelStore
import com.lagradost.cloudstream4.desktop.ui.Route
import com.lagradost.cloudstream4.desktop.ui.rememberNavigator
import com.lagradost.cloudstream4.generated.resources.Res
import com.lagradost.cloudstream4.generated.resources.app_name
import com.lagradost.cloudstream4.theme.CloudStreamTheme
import org.jetbrains.compose.resources.stringResource

fun main() {
    // Name in the macOS menu bar and Dock
    System.setProperty("apple.awt.application.name", "CloudStream")
    System.setProperty("apple.laf.useScreenMenuBar", "true")

    // Dock icon when run with ./gradlew run (the packaged .app uses its icns)
    runCatching {
        val image = javax.imageio.ImageIO.read(Thread.currentThread().contextClassLoader.getResource("app-icon.png"))
        if (java.awt.Taskbar.isTaskbarSupported() && java.awt.Taskbar.getTaskbar().isSupported(java.awt.Taskbar.Feature.ICON_IMAGE)) {
            java.awt.Taskbar.getTaskbar().iconImage = image
        }
    }

    ExtensionManager.start()
    val vlc = VlcPlayer.create()

    application {
        val windowState = rememberWindowState(size = DpSize(1280.dp, 820.dp))
        val appIcon = remember {
            androidx.compose.ui.graphics.painter.BitmapPainter(
                javax.imageio.ImageIO.read(Thread.currentThread().contextClassLoader.getResource("app-icon.png")).toComposeImageBitmap()
            )
        }
        val nav = rememberNavigator()
        val store = remember { ModelStore() }

        Window(
            onCloseRequest = {
                vlc?.release()
                exitApplication()
            },
            title = stringResource(Res.string.app_name),
            icon = appIcon,
            state = windowState,
        ) {
            window.minimumSize = java.awt.Dimension(900, 600)
            MenuBar {
                Menu("Go") {
                    Item("Home", shortcut = KeyShortcut(Key.One, meta = true)) { nav.open(Route.Home) }
                    Item("Search", shortcut = KeyShortcut(Key.F, meta = true)) { nav.open(Route.Search) }
                    Item("Library", shortcut = KeyShortcut(Key.Two, meta = true)) { nav.open(Route.Library) }
                    Item("Downloads", shortcut = KeyShortcut(Key.Three, meta = true)) { nav.open(Route.Downloads) }
                    Item("Extensions", shortcut = KeyShortcut(Key.Four, meta = true)) { nav.open(Route.Extensions) }
                    Item("Settings", shortcut = KeyShortcut(Key.Comma, meta = true)) { nav.open(Route.Settings) }
                    Separator()
                    Item("Back", shortcut = KeyShortcut(Key.LeftBracket, meta = true)) { nav.back() }
                }
            }

            val settings by SettingsStore.state.collectAsState()
            CloudStreamTheme(mode = settings.themeMode, primaryColor = settings.primaryColor) {
                CompositionLocalProvider(
                    LocalFocusOutlineDefault provides false,
                    LocalWindowState provides windowState,
                ) {
                    Surface(color = MaterialTheme.colorScheme.background, contentColor = MaterialTheme.colorScheme.onBackground) {
                        CloudStreamApp(nav, store, vlc)
                    }
                }
            }
        }
    }
}
