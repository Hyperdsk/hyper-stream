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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.lagradost.cloudstream4.desktop.Platform
import com.lagradost.cloudstream4.desktop.data.SettingsStore
import com.lagradost.cloudstream4.desktop.download.DownloadManager
import com.lagradost.cloudstream4.desktop.player.VlcPlayer
import com.lagradost.cloudstream4.desktop.plugins.ExtensionManager
import com.lagradost.cloudstream4.desktop.ui.CloudStreamApp
import com.lagradost.cloudstream4.desktop.ui.HyperStreamTheme
import com.lagradost.cloudstream4.desktop.ui.LocalFullscreen
import com.lagradost.cloudstream4.desktop.ui.WindowFullscreen
import com.lagradost.cloudstream4.desktop.ui.ModelStore
import com.lagradost.cloudstream4.desktop.ui.Route
import com.lagradost.cloudstream4.desktop.ui.rememberNavigator

fun main() {
    // Name in the macOS menu bar and Dock
    System.setProperty("apple.awt.application.name", "Hyper Stream")
    System.setProperty("apple.laf.useScreenMenuBar", "true")

    // Dock icon when run with ./gradlew run (the packaged .app uses its icns)
    runCatching {
        val image = javax.imageio.ImageIO.read(Thread.currentThread().contextClassLoader.getResource("app-icon.png"))
        if (java.awt.Taskbar.isTaskbarSupported() && java.awt.Taskbar.getTaskbar().isSupported(java.awt.Taskbar.Feature.ICON_IMAGE)) {
            java.awt.Taskbar.getTaskbar().iconImage = image
        }
    }

    ExtensionManager.start()
    // Optional sync across devices; does nothing until signed in under Settings
    com.lagradost.cloudstream4.desktop.sync.SyncManager.start()
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
        var confirmQuit by remember { mutableStateOf(false) }
        fun quit() {
            vlc?.release()
            // Watch progress saved in the background a moment ago, not lost on the way out
            com.lagradost.cloudstream4.desktop.data.JsonFile.flushAll()
            exitApplication()
        }

        Window(
            onCloseRequest = {
                // Closing stops downloads (they continue, paused, next time): say so first
                if (DownloadManager.anyActive) confirmQuit = true else quit()
            },
            title = "Hyper Stream",
            icon = appIcon,
            state = windowState,
        ) {
            window.minimumSize = java.awt.Dimension(900, 600)
            // Cmd on a Mac; Windows and Linux use Ctrl (their Meta key is the Windows/Super key, taken by the system)
            fun shortcut(key: Key, shift: Boolean = false) =
                if (Platform.isMac) KeyShortcut(key, meta = true, shift = shift) else KeyShortcut(key, ctrl = true, shift = shift)
            MenuBar {
                Menu("Go") {
                    Item("Home", shortcut = shortcut(Key.One)) { nav.open(Route.Home) }
                    // Movies, TV Shows and Anime, when an installed extension has them
                    com.lagradost.cloudstream4.desktop.ui.rememberAvailableSections().forEachIndexed { i, section ->
                        val key = listOf(Key.One, Key.Two, Key.Three)[i]
                        Item(section.label, shortcut = shortcut(key, shift = true)) {
                            nav.open(Route.Section(section))
                        }
                    }
                    Item("Search", shortcut = shortcut(Key.F)) { nav.open(Route.Search) }
                    Item("Library", shortcut = shortcut(Key.Two)) { nav.open(Route.Library) }
                    Item("Downloads", shortcut = shortcut(Key.Three)) { nav.open(Route.Downloads) }
                    Item("Extensions", shortcut = shortcut(Key.Four)) { nav.open(Route.Extensions) }
                    Item("Settings", shortcut = shortcut(Key.Comma)) { nav.open(Route.Settings) }
                    Separator()
                    Item("Back", shortcut = shortcut(Key.LeftBracket)) { nav.back() }
                }
            }

            val settings by SettingsStore.state.collectAsState()
            HyperStreamTheme(mode = settings.themeMode, primaryColor = settings.primaryColor) {
                CompositionLocalProvider(
                    LocalFocusOutlineDefault provides false,
                    LocalFullscreen provides remember(windowState) { WindowFullscreen(windowState) },
                ) {
                    Surface(color = MaterialTheme.colorScheme.background, contentColor = MaterialTheme.colorScheme.onBackground) {
                        CloudStreamApp(nav, store, vlc)
                        if (confirmQuit) {
                            androidx.compose.material3.AlertDialog(
                                onDismissRequest = { confirmQuit = false },
                                title = { Text("Downloads are still running") },
                                text = { Text("Quitting pauses them. They go on from where they stopped when you resume them.") },
                                confirmButton = { TextButton(onClick = { DownloadManager.pauseEverything(); quit() }) { Text("Quit") } },
                                dismissButton = { TextButton(onClick = { confirmQuit = false }) { Text("Keep downloading") } },
                            )
                        }
                    }
                }
            }
        }
    }
}
