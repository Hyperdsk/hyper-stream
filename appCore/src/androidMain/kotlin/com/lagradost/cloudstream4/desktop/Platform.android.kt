package com.lagradost.cloudstream4.desktop

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.Settings
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.StateFlow
import java.io.File

actual object Platform {
    actual val isTv: Boolean get() = AndroidHost.isTv
    actual val isTouch: Boolean get() = !AndroidHost.isTv
    actual val canOpenFolders: Boolean = false

    actual val description: String
        get() = "Android ${Build.VERSION.RELEASE} · ${Build.MANUFACTURER} ${Build.MODEL}"

    actual fun openUrl(url: String): Boolean = runCatching {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            .addCategory(Intent.CATEGORY_BROWSABLE)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        (AndroidHost.activity ?: AndroidHost.context).startActivity(intent)
    }.isSuccess

    actual fun openFolder(folder: File) = Unit

    actual fun revealFile(file: File) = Unit

    /**
     * Opens the system folder picker. Android only lets apps write to shared folders such as
     * Downloads after "all files access" (Android 11+) or the storage permission (Android 9 and
     * older), so that is asked for first.
     */
    actual suspend fun chooseFolder(title: String): File? {
        if (!hasDownloadFolderAccess()) {
            if (requestDownloadFolderAccess()) {
                DesktopToasts.show("Allow access to files, then choose the folder again.")
            } else {
                DesktopToasts.show("This version of Android doesn't allow choosing a folder. Downloads stay in the app's own storage.")
            }
            return null
        }
        val uri = AndroidHost.pickFolder() ?: return null
        // Keep access across restarts for the folder the user picked
        runCatching {
            AndroidHost.context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }
        return fileForTreeUri(uri)?.takeIf { it.isDirectory && it.canWrite() } ?: run {
            DesktopToasts.show("Choose a folder on this device, not a cloud drive.")
            null
        }
    }

    /** Public Downloads/hyperstream when Android allows it, otherwise the app's own Downloads folder. */
    actual fun defaultDownloadFolder(): File {
        val publicDownloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        if (hasDownloadFolderAccess() && publicDownloads != null) {
            return File(publicDownloads, "hyperstream")
        }
        val own = AndroidHost.context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            ?: File(AndroidHost.context.filesDir, "Downloads")
        return File(own, "hyperstream")
    }

    actual val downloadFolderNeedsAccess: Boolean
        get() = when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> !Environment.isExternalStorageManager()
            // Android 10 can't be granted all-files access, so it uses the app's own folder
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> false
            else -> !hasDownloadFolderAccess()
        }

    actual fun requestDownloadFolderAccess(): Boolean {
        val activity = AndroidHost.activity ?: return false
        return when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> runCatching {
                activity.startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.parse("package:${activity.packageName}"),
                    )
                )
            }.recoverCatching {
                activity.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            }.isSuccess
            Build.VERSION.SDK_INT <= Build.VERSION_CODES.P -> {
                activity.requestPermissions(
                    arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE),
                    4001,
                )
                true
            }
            else -> false
        }
    }

    actual val storageRevision: StateFlow<Int> get() = AndroidHost.storageRevision

    actual fun dataFolder(): File = AndroidHost.context.filesDir

    actual val playerMissing: PlayerMissing? = null

    private fun hasDownloadFolderAccess(): Boolean = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> Environment.isExternalStorageManager()
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> false
        else -> ContextCompat.checkSelfPermission(AndroidHost.context, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(AndroidHost.context, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
    }

    /** Turns a SAF tree into a normal path, for the file-based downloader and player. */
    private fun fileForTreeUri(uri: Uri): File? = runCatching {
        val id = DocumentsContract.getTreeDocumentId(uri)
        val root = when (uri.authority) {
            "com.android.externalstorage.documents" -> {
                val volume = id.substringBefore(':')
                val relative = id.substringAfter(':', "")
                val rootDir = if (volume.equals("primary", ignoreCase = true)) {
                    Environment.getExternalStorageDirectory()
                } else {
                    // Removable storage: its volume id is its folder name under /storage
                    File("/storage", volume)
                }
                if (relative.isBlank()) rootDir else File(rootDir, relative)
            }
            // Some file managers return Downloads as a raw path
            "com.android.providers.downloads.documents" -> File(id.removePrefix("raw:"))
            else -> null
        }
        root?.takeIf { it.isDirectory }
    }.getOrNull()
}
