package com.lagradost.cloudstream4.desktop.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.composed
import androidx.compose.ui.input.pointer.pointerInput

/** Shows [text] near the pointer after hovering a moment. Touch screens and TVs have no hover, so there it's just [content]. */
@Composable
expect fun HoverTooltip(text: String, content: @Composable () -> Unit)

/** Fullscreen for the player: the window on desktop, the system bars on Android. */
interface FullscreenControl {
    val isFullscreen: Boolean
    fun setFullscreen(on: Boolean)
}

val LocalFullscreen = staticCompositionLocalOf<FullscreenControl?> { null }

/** Calls [onEvent] for every pointer event over this element (moves, enter, exit), in [pass]. Works on every platform. */
fun androidx.compose.ui.Modifier.onPointerEvents(
    pass: androidx.compose.ui.input.pointer.PointerEventPass = androidx.compose.ui.input.pointer.PointerEventPass.Main,
    onEvent: (androidx.compose.ui.input.pointer.PointerEvent) -> Unit,
): androidx.compose.ui.Modifier = this.then(
    androidx.compose.ui.Modifier.composed {
        val latest = androidx.compose.runtime.rememberUpdatedState(onEvent)
        androidx.compose.ui.Modifier.pointerInput(pass) {
            awaitPointerEventScope {
                while (true) latest.value(awaitPointerEvent(pass))
            }
        }
    }
)

/** Android's back button and gesture while [enabled]. Desktop has none, so it does nothing there. */
@Composable
expect fun PlatformBackHandler(enabled: Boolean = true, onBack: () -> Unit)
