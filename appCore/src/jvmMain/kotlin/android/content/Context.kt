package android.content

import com.lagradost.cloudstream4.desktop.data.DesktopSharedPreferences

/**
 * Desktop stand-in for android.content.Context.
 *
 * CloudStream extensions are compiled against the Android app, so their plugin entry point
 * is `load(context: Context)`. The desktop app passes an instance of this class so those
 * extensions load without recompiling. Only the parts extensions commonly use are provided.
 */
open class Context {
    /** Extensions use this for their own settings. Stored as JSON files in the app data folder. */
    open fun getSharedPreferences(name: String, mode: Int): SharedPreferences = DesktopSharedPreferences.get(name)

    open fun getApplicationContext(): Context = this

    companion object {
        const val MODE_PRIVATE = 0
    }
}
