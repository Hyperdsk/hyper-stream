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
        // Read settings and history, and set up the JSON parser, while the first screen is being
        // created; the screen needs them soon after, and doing it on the UI thread held it up
        Thread {
            runCatching {
                com.lagradost.cloudstream4.desktop.data.SettingsStore.state
                com.lagradost.cloudstream4.desktop.data.LibraryStore.state
            }
        }.apply { name = "warm-up"; priority = Thread.NORM_PRIORITY }.start()
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
