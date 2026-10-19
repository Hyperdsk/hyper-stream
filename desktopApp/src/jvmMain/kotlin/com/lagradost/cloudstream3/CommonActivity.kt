package com.lagradost.cloudstream3

import android.app.Activity
import com.lagradost.cloudstream4.desktop.DesktopToasts

/**
 * Desktop version of the Android app's CommonActivity, with the same JVM signatures for the
 * parts extensions use. [activity] is never null here: it is the desktop activity, which only
 * supports what the desktop Context supports, such as getSharedPreferences.
 */
object CommonActivity {
    var activity: Activity? = com.lagradost.cloudstream4.desktop.plugins.DesktopActivity.instance

    fun setActivityInstance(newActivity: Activity?) {
        if (newActivity != null) activity = newActivity
    }

    val screenWidth: Int get() = java.awt.Toolkit.getDefaultToolkit().screenSize.width
    val screenHeight: Int get() = java.awt.Toolkit.getDefaultToolkit().screenSize.height
    val screenWidthWithOrientation: Int get() = screenWidth
    val screenHeightWithOrientation: Int get() = screenHeight

    var isPipDesired: Boolean = false
    var isInPIPMode: Boolean = false
    var appliedTheme: Int = 0
    var appliedColor: Int = 0

    // Toasts from extensions show as messages in the app window
    fun showToast(message: Int, duration: Int? = null) = Unit
    fun showToast(message: String?, duration: Int? = null) {
        DesktopToasts.show(message)
    }

    fun showToast(act: Activity?, message: Int, duration: Int? = null) = Unit
    fun showToast(act: Activity?, message: String?, duration: Int? = null) {
        DesktopToasts.show(message)
    }
}
