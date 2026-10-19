package android.util

/**
 * Desktop stand-in for android.util.Log, so extensions that log through the Android API
 * keep working. Signatures match the Android static methods.
 */
object Log {
    private fun print(level: String, tag: String?, msg: String?, tr: Throwable? = null): Int {
        println("$level ${tag ?: ""}: ${msg ?: ""}")
        tr?.printStackTrace()
        return 0
    }

    @JvmStatic fun v(tag: String?, msg: String): Int = print("VERBOSE", tag, msg)
    @JvmStatic fun v(tag: String?, msg: String?, tr: Throwable?): Int = print("VERBOSE", tag, msg, tr)
    @JvmStatic fun d(tag: String?, msg: String): Int = print("DEBUG", tag, msg)
    @JvmStatic fun d(tag: String?, msg: String?, tr: Throwable?): Int = print("DEBUG", tag, msg, tr)
    @JvmStatic fun i(tag: String?, msg: String): Int = print("INFO", tag, msg)
    @JvmStatic fun i(tag: String?, msg: String?, tr: Throwable?): Int = print("INFO", tag, msg, tr)
    @JvmStatic fun w(tag: String?, msg: String): Int = print("WARNING", tag, msg)
    @JvmStatic fun w(tag: String?, msg: String?, tr: Throwable?): Int = print("WARNING", tag, msg, tr)
    @JvmStatic fun w(tag: String?, tr: Throwable?): Int = print("WARNING", tag, null, tr)
    @JvmStatic fun e(tag: String?, msg: String): Int = print("ERROR", tag, msg)
    @JvmStatic fun e(tag: String?, msg: String?, tr: Throwable?): Int = print("ERROR", tag, msg, tr)
    @JvmStatic fun getStackTraceString(tr: Throwable?): String = tr?.stackTraceToString() ?: ""
}
