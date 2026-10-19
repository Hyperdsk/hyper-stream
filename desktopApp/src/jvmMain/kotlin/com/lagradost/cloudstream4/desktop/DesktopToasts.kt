package com.lagradost.cloudstream4.desktop

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

/** Short messages from extensions (Android toasts), shown as snackbars in the window. */
object DesktopToasts {
    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val messages: SharedFlow<String> = _messages

    fun show(message: String?) {
        if (!message.isNullOrBlank()) _messages.tryEmit(message)
    }
}
