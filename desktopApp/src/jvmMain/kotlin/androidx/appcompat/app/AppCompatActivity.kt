package androidx.appcompat.app

import android.content.Context

/**
 * Desktop stand-in so that the common extension pattern `context as? AppCompatActivity`
 * resolves. The desktop context is never an activity, so such code takes its non-Android path.
 */
open class AppCompatActivity : Context()
