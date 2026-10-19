package com.lagradost.cloudstream3.plugins

import android.content.Context
import android.content.res.Resources

/**
 * Desktop copy of the Android app's `Plugin` class.
 *
 * Every published extension's entry class extends `com.lagradost.cloudstream3.plugins.Plugin`,
 * which lives in the Android app module and not in the cross-platform library. This class has
 * the same name and the same JVM signatures, so converted extensions link against it.
 */
abstract class Plugin : BasePlugin() {
    /**
     * Called when your Plugin is loaded
     * @param context Context
     */
    @Throws(Throwable::class)
    open fun load(context: Context) {
        // If not overridden by an extension then try the cross-platform load()
        load()
    }

    /** Extension resources are not available on desktop, so this stays null. */
    var resources: Resources? = null

    /** Extensions may set this to show a settings screen. Android-only, ignored on desktop. */
    var openSettings: ((context: Context) -> Unit)? = null
}
