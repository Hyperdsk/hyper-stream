package com.lagradost.cloudstream3

import android.content.Context
import com.lagradost.cloudstream3.utils.DataStore.getSharedPrefs
import com.lagradost.cloudstream4.desktop.AndroidHost

/**
 * The parts of the CloudStream Android app's application class that extensions use: its context,
 * and saving values (extensions read them back with the app's inline getKey, which reads the same
 * preferences through DataStore.getSharedPrefs).
 */
class CloudStreamApp {
    companion object {
        val context: Context? get() = AndroidHost.activity ?: AndroidHost.context

        fun <T> setKey(path: String, value: T) {
            val ctx = context ?: return
            ctx.getSharedPrefs().edit().putString(path, mapper.writeValueAsString(value)).apply()
        }

        fun removeKey(path: String) {
            context?.getSharedPrefs()?.edit()?.remove(path)?.apply()
        }

        fun <T> setKey(folder: String, path: String, value: T) = setKey("$folder/$path", value)
        fun removeKey(folder: String, path: String) = removeKey("$folder/$path")
    }
}
