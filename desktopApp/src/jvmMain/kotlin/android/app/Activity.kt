package android.app

import android.content.Context

/** Desktop stand-in so `context as? Activity` checks in extensions resolve. */
open class Activity : Context()
