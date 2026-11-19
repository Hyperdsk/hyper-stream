package com.lagradost.cloudstream4.desktop.sync

/**
 * Where an emailed sign-in link returns to, so opening it on this device signs the app in by
 * itself: a small page the Mac app serves on this computer, or the hyperstream://auth link the
 * Android app opens. Each address must be in Supabase's allowed redirect URLs.
 */
expect object SignInRedirect {
    /** The address to send with the sign-in email, or null when this device can't receive it. */
    val url: String?

    /** Gets ready to receive the link's return, just before the email is sent. */
    fun prepare()

    /** No link is expected any more (signed in, cancelled, or it expired): stop listening. */
    fun stop()
}
