package com.lagradost.cloudstream4.android

import android.app.Application
import coil3.request.allowRgb565
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
        // Pictures: a smaller memory cache than Coil's default, which sizes itself from the large
        // heap (a quarter of 224 MB on the TV). On TVs and low-memory devices pictures also use
        // 16-bit colour, half the memory, where the image has no transparency
        val tight = com.lagradost.cloudstream4.desktop.Platform.isTv ||
            getSystemService(android.app.ActivityManager::class.java).isLowRamDevice
        coil3.SingletonImageLoader.setSafe { context ->
            coil3.ImageLoader.Builder(context)
                .memoryCache { coil3.memory.MemoryCache.Builder().maxSizePercent(context, if (tight) 0.12 else 0.2).build() }
                .apply { if (tight) allowRgb565(true) }
                .build()
        }
        // The library's web view helpers (WebViewResolver, used by CloudflareKiller and many extensions) need a context
        com.lagradost.api.setContext(this)
        // Continue watching on the TV home screen
        com.lagradost.cloudstream4.desktop.WatchNext.start()
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
        // Optional sync across devices; does nothing until signed in under Settings
        com.lagradost.cloudstream4.desktop.sync.SyncManager.start()
    }
}
