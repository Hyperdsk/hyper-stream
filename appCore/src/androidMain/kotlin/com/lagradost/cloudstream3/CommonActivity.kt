package com.lagradost.cloudstream3

import android.app.Activity
import com.lagradost.cloudstream4.desktop.AndroidHost
import com.lagradost.cloudstream4.desktop.DesktopToasts

/** The parts of the Android app's CommonActivity that extensions use, with the same signatures. */
object CommonActivity {
    var activity: Activity?
        get() = AndroidHost.activity
        set(value) {
            if (value != null) AndroidHost.attach(value)
        }

    fun setActivityInstance(newActivity: Activity?) {
        if (newActivity != null) AndroidHost.attach(newActivity)
    }

    private val metrics get() = AndroidHost.context.resources.displayMetrics
    val screenWidth: Int get() = minOf(metrics.widthPixels, metrics.heightPixels)
    val screenHeight: Int get() = maxOf(metrics.widthPixels, metrics.heightPixels)
    val screenWidthWithOrientation: Int get() = metrics.widthPixels
    val screenHeightWithOrientation: Int get() = metrics.heightPixels

    var isPipDesired: Boolean = false
    var isInPIPMode: Boolean = false
    var appliedTheme: Int = 0
    var appliedColor: Int = 0

    // Toasts from extensions show as messages at the bottom of the app
    fun showToast(message: Int, duration: Int? = null) {
        DesktopToasts.show(runCatching { AndroidHost.context.getString(message) }.getOrNull())
    }

    fun showToast(message: String?, duration: Int? = null) {
        DesktopToasts.show(message)
    }

    fun showToast(act: Activity?, message: Int, duration: Int? = null) = showToast(message, duration)
    fun showToast(act: Activity?, message: String?, duration: Int? = null) = showToast(message, duration)
}
