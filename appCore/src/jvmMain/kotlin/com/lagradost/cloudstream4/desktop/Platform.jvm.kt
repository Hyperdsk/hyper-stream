package com.lagradost.cloudstream4.desktop

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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

    /**
     * Set in the appCore jar's manifest by Gradle, from -PappVersion (see build.gradle.kts). "dev"
     * when it's missing, rather than a made-up release number that would never change.
     */
    actual val appVersion: String
        get() = Platform::class.java.`package`?.implementationVersion?.takeIf { it.isNotBlank() } ?: "dev"

    actual val updateTarget: UpdateTarget by lazy {
        val arch = System.getProperty("os.arch").orEmpty().lowercase()
        // Where the packaged app's launcher is (jpackage sets this); null when run from Gradle
        val launcher = System.getProperty("jpackage.app-path")?.let(::File)
        when {
            isMac && (arch.contains("aarch64") || arch.contains("arm64")) -> UpdateTarget.MacAppleSilicon
            isMac -> UpdateTarget.MacIntel
            os.contains("win") -> {
                // The .msi installs under Program Files; anywhere else is the portable .zip
                val programFiles = listOfNotNull(System.getenv("ProgramFiles"), System.getenv("ProgramW6432"), System.getenv("LOCALAPPDATA"))
                val installed = launcher == null || programFiles.any { launcher.absolutePath.startsWith(it, ignoreCase = true) }
                if (installed) UpdateTarget.Windows else UpdateTarget.WindowsPortable
            }
            else -> {
                // The .deb installs under /opt; an unpacked .tar.gz is anywhere else, and a system
                // without dpkg can't install a .deb at all
                val hasDpkg = File("/usr/bin/dpkg").exists()
                val installed = launcher == null || launcher.absolutePath.startsWith("/opt/")
                if (hasDpkg && installed) UpdateTarget.Linux else UpdateTarget.LinuxPortable
            }
        }
    }

    actual fun openUrl(url: String): Boolean = runCatching { Desktop.getDesktop().browse(URI(url)) }.isSuccess

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

    /**
     * Opens the .dmg/.msi/.deb the update checker downloaded, so the system installer takes over. A
     * portable archive has nothing to run: it's shown in its folder, to unpack over the current copy.
     */
    actual suspend fun installUpdate(file: File): Boolean = withContext(Dispatchers.IO) {
        if (updateTarget.portable) {
            revealFile(file)
            return@withContext true
        }
        val opened = runCatching { Desktop.getDesktop().open(file) }.isSuccess
        if (!opened) runCatching { Desktop.getDesktop().open(file.parentFile) }
        opened
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

    /** ~/Movies on a Mac; Windows and Linux call that folder Videos (an existing ~/Movies/CloudStream is kept). */
    actual fun defaultDownloadFolder(): File {
        val home = System.getProperty("user.home")
        val movies = File(home, "Movies/CloudStream")
        return if (isMac || movies.isDirectory) movies else File(home, "Videos/CloudStream")
    }

    actual val downloadFolderNeedsAccess: Boolean = false

    actual fun requestDownloadFolderAccess(): Boolean = false

    private val storageChanges = MutableStateFlow(0)
    actual val storageRevision: StateFlow<Int> = storageChanges.asStateFlow()

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
        message = when {
            isMac -> "Install VLC into your Applications folder, then restart Hyper Stream."
            os.contains("win") -> "Install VLC (the 64-bit version), then restart Hyper Stream."
            else -> "Install VLC from your system's package manager (for example sudo apt install vlc), then restart Hyper Stream."
        },
        action = "Get VLC",
        url = "https://www.videolan.org/vlc/",
    )
}
