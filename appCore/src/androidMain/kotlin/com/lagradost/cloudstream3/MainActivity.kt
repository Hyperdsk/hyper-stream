package com.lagradost.cloudstream3

import com.lagradost.cloudstream3.utils.Event

/**
 * The CloudStream Android app's main activity, as extensions see it: only its events. (This app's
 * real activity is com.lagradost.cloudstream4.android.MainActivity.)
 */
class MainActivity {
    companion object {
        /** Extensions fire this after changing their settings, so the home page reloads. */
        val reloadHomeEvent = Event<Boolean>()
        val afterPluginsLoadedEvent = Event<Boolean>()
        val mainPluginsLoadedEvent = Event<Boolean>()
    }
}
