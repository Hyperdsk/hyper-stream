package com.lagradost.cloudstream4.desktop

import android.app.Activity
import android.app.UiModeManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import java.lang.ref.WeakReference

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
}
