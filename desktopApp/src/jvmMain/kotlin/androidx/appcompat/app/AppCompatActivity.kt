package androidx.appcompat.app

/**
 * Desktop stand-in for AppCompatActivity. Extensions get one as their load context, because many
 * cast the context to it. It has the desktop Context features (getSharedPreferences) and runs
 * runOnUiThread work right away. Android screens extensions build from it are never shown.
 */
open class AppCompatActivity : android.app.Activity() {
    fun runOnUiThread(action: Runnable) = action.run()
}
