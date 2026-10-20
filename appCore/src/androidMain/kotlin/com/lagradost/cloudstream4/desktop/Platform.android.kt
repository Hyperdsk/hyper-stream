package com.lagradost.cloudstream4.desktop

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import java.io.File

actual object Platform {
    actual val isTv: Boolean get() = AndroidHost.isTv
    actual val isTouch: Boolean get() = !AndroidHost.isTv
    actual val canOpenFolders: Boolean = false

    actual val description: String
        get() = "Android ${Build.VERSION.RELEASE} · ${Build.MANUFACTURER} ${Build.MODEL}"

    actual fun openUrl(url: String) {
        runCatching {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            (AndroidHost.activity ?: AndroidHost.context).startActivity(intent)
        }
    }

    actual fun openFolder(folder: File) = Unit

    actual fun revealFile(file: File) = Unit

    actual suspend fun chooseFolder(title: String): File? = null

    /** The app's own Movies folder: no storage permission needed, removed with the app. */
    actual fun defaultDownloadFolder(): File =
        AndroidHost.context.getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: File(AndroidHost.context.filesDir, "Downloads")

    actual fun dataFolder(): File = AndroidHost.context.filesDir

    actual val playerMissing: PlayerMissing? = null
}
