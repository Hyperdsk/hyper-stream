package com.lagradost.cloudstream4.desktop

import kotlinx.coroutines.flow.StateFlow
import java.io.File

/** The release builds that have their own downloadable update. */
enum class UpdateTarget {
    Android,
    MacAppleSilicon,
    MacIntel,
    Windows,
    Linux,
}

/** What differs between the Mac app and the Android phone and TV app, outside the player. */
expect object Platform {
    /** Running on a TV, driven by a remote: bigger focus highlights, no touch gestures. */
    val isTv: Boolean

    /** Running on a phone or tablet with a touch screen. */
    val isTouch: Boolean

    /** For the About line in Settings. */
    val description: String

    /** The installed app's version, as shown in the store and the update check. */
    val appVersion: String

    /** Which release asset this build can install. */
    val updateTarget: UpdateTarget

    /** Desktop apps can open folders in the file manager; Android can't. */
    val canOpenFolders: Boolean

    /** Opens a web page in the browser. Returns false when nothing could open it. */
    fun openUrl(url: String): Boolean
    fun openFolder(folder: File)

    /** Shows [file] selected in the file manager, or opens it with another app on Android. */
    fun revealFile(file: File)

    /** Starts the downloaded update's installer, or opens the system prompt that allows it. */
    suspend fun installUpdate(file: File): Boolean

    /** Asks for a folder, or returns null when cancelled or not supported. */
    suspend fun chooseFolder(title: String): File?

    /** Where downloads go unless the user picks another folder. */
    fun defaultDownloadFolder(): File

    /** True when Android needs extra access before the default Downloads folder can be used. */
    val downloadFolderNeedsAccess: Boolean

    /** Asks the system for access to the default Downloads folder. Returns true when a prompt was shown. */
    fun requestDownloadFolderAccess(): Boolean

    /** Changes when folder access may have changed, so screens showing the download folder refresh. */
    val storageRevision: StateFlow<Int>

    /** The app's data folder: settings, history, extensions. */
    fun dataFolder(): File

    /** Shown in the player when no video player could be started. Null when that can't happen. */
    val playerMissing: PlayerMissing?
}

data class PlayerMissing(val title: String, val message: String, val action: String, val url: String)
