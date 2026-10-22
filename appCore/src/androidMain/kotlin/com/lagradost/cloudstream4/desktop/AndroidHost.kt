package com.lagradost.cloudstream4.desktop

import android.app.Activity
import android.app.UiModeManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.lang.ref.WeakReference
import kotlin.coroutines.resume

/**
 * The Android app's process-wide context and current activity. The app calls [init] from its
 * Application and [attach] from its activity, before anything else in the shared code runs.
 */
object AndroidHost {
    lateinit var context: Context
        private set

    private var activityRef: WeakReference<Activity>? = null
    val activity: Activity? get() = activityRef?.get()

    fun init(context: Context) {
        this.context = context.applicationContext
    }

    fun attach(activity: Activity) {
        activityRef = WeakReference(activity)
    }

    /** Android TV, Google TV and Fire TV: a TV ui mode or the leanback feature. */
    val isTv: Boolean by lazy {
        val uiMode = context.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager
        uiMode?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION ||
            context.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK) ||
            context.packageManager.hasSystemFeature("amazon.hardware.fire_tv")
    }

    private val _storageRevision = MutableStateFlow(0)

    /** Bumped when the app comes back to the front, so access to Downloads can be re-checked. */
    val storageRevision: StateFlow<Int> = _storageRevision.asStateFlow()

    fun storageChanged() = _storageRevision.update { it + 1 }

    /** Set by the activity: opens the system folder picker. */
    var openFolderPicker: (() -> Unit)? = null

    private var pendingFolder: ((Uri?) -> Unit)? = null

    /** Opens the system folder picker and waits for a tree URI, or null when cancelled. */
    suspend fun pickFolder(): Uri? = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { continuation ->
            val open = openFolderPicker
            if (open == null) {
                continuation.resume(null)
                return@suspendCancellableCoroutine
            }
            // A second picker replaces the first, which counts as cancelled
            pendingFolder?.invoke(null)
            pendingFolder = { uri ->
                pendingFolder = null
                if (continuation.isActive) continuation.resume(uri)
            }
            continuation.invokeOnCancellation {
                if (pendingFolder != null) pendingFolder = null
            }
            if (runCatching { open() }.isFailure) {
                pendingFolder = null
                if (continuation.isActive) continuation.resume(null)
            }
        }
    }

    /** Called by the activity with the folder picker's result. */
    fun folderPicked(uri: Uri?) {
        pendingFolder?.invoke(uri)
    }
}
