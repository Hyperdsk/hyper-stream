package com.lagradost.cloudstream3.plugins

import android.content.Context
import android.content.res.Resources

/**
 * The Android app's `Plugin` class, which every extension's entry class extends. It lives in the
 * CloudStream app, not in the library, so this app provides it with the same name and signatures.
 */
abstract class Plugin : BasePlugin() {
    /** Called when your Plugin is loaded */
    @Throws(Throwable::class)
    open fun load(context: Context) {
        // If not overridden by an extension then try the cross-platform load()
        load()
    }

    /** The extension's own resources. Not loaded by this app, so this stays null. */
    var resources: Resources? = null

    /** Extensions may set this to show a settings screen. Not shown by this app yet. */
    var openSettings: ((context: Context) -> Unit)? = null
}
