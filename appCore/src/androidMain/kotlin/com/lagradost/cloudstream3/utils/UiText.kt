package com.lagradost.cloudstream3.utils

import android.content.Context

/** Text that the CloudStream app resolves against its resources; here plain text. */
open class UiText(private val text: String = "") {
    open fun asString(context: Context): String = text
    override fun toString(): String = text
}
