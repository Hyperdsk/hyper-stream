package com.lagradost.cloudstream4.desktop.sync

/** Android: the link returns to hyperstream://auth, which opens the app (see MainActivity). */
actual object SignInRedirect {
    actual val url: String? = "hyperstream://auth"
    actual fun prepare() = Unit
}
