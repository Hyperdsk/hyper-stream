package com.lagradost.cloudstream4.android

import android.app.Application
import com.lagradost.cloudstream4.desktop.AndroidHost
import com.lagradost.cloudstream4.desktop.player.ExoVideoEngine
import com.lagradost.cloudstream4.desktop.plugins.ExtensionManager

class CloudStreamApplication : Application() {
    /** One player for the whole app, like the Mac app's single VLC player. */
    val engine: ExoVideoEngine by lazy { ExoVideoEngine(this) }

    private var started = false

    override fun onCreate() {
        super.onCreate()
        AndroidHost.init(this)
    }

    /**
     * Loads extensions once the activity exists: many cast the context they get to
     * AppCompatActivity, as upstream's app passes its main activity.
     */
    fun startExtensions() {
        if (started) return
        started = true
        ExtensionManager.start()
    }
}
