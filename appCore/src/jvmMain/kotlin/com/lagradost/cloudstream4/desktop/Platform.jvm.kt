package com.lagradost.cloudstream4.desktop

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.awt.Desktop
import java.io.File
import java.net.URI

actual object Platform {
    private val os = System.getProperty("os.name").orEmpty().lowercase()
    val isMac = os.contains("mac")

    actual val isTv: Boolean = false
    actual val isTouch: Boolean = false
    actual val canOpenFolders: Boolean = true

    actual val description: String
        get() = "Java ${System.getProperty("java.version")} · ${System.getProperty("os.name")} ${System.getProperty("os.arch")}"

    actual fun openUrl(url: String) {
        runCatching { Desktop.getDesktop().browse(URI(url)) }
    }

    actual fun openFolder(folder: File) {
        runCatching { Desktop.getDesktop().open(folder) }
    }

    actual fun revealFile(file: File) {
        runCatching {
            val desktop = Desktop.getDesktop()
            if (desktop.isSupported(Desktop.Action.BROWSE_FILE_DIR)) desktop.browseFileDirectory(file)
            else desktop.open(file.parentFile)
        }
    }

    /** The native folder picker. Returns null when cancelled. */
    actual suspend fun chooseFolder(title: String): File? = withContext(Dispatchers.Main) {
        System.setProperty("apple.awt.fileDialogForDirectories", "true")
        try {
            val dialog = java.awt.FileDialog(null as java.awt.Frame?, title, java.awt.FileDialog.LOAD)
            dialog.directory = com.lagradost.cloudstream4.desktop.download.DownloadManager.folder.path
            dialog.isVisible = true
            dialog.file?.let { File(dialog.directory, it) }
        } finally {
            System.setProperty("apple.awt.fileDialogForDirectories", "false")
        }
    }

    actual fun defaultDownloadFolder(): File = File(System.getProperty("user.home"), "Movies/CloudStream")

    actual fun dataFolder(): File {
        val home = System.getProperty("user.home")
        return when {
            isMac -> File(home, "Library/Application Support/CloudStream")
            os.contains("win") -> File(System.getenv("APPDATA") ?: home, "CloudStream")
            else -> File(System.getenv("XDG_DATA_HOME") ?: "$home/.local/share", "cloudstream")
        }
    }

    actual val playerMissing: PlayerMissing? = PlayerMissing(
        title = "VLC is needed to play videos",
        message = "Install VLC into your Applications folder, then restart CloudStream.",
        action = "Get VLC",
        url = "https://www.videolan.org/vlc/",
    )
}
