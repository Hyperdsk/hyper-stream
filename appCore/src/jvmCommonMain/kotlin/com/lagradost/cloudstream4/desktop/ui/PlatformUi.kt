package com.lagradost.cloudstream4.desktop.ui

import androidx.compose.ui.focus.FocusDirection
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.focusable
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.border
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
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

/**
 * Holds a text field. On TV the remote stops on this box instead of the field, so moving past
 * doesn't open the keyboard; OK (the remote's centre button) moves into the field and opens it,
 * and moving away leaves it. [content] gets the modifier for the field. Elsewhere it's just the field.
 */
@Composable
fun TvTextFieldBox(
    modifier: androidx.compose.ui.Modifier = androidx.compose.ui.Modifier,
    /** Focuses the field (the box on TV), for screens that open ready to type. */
    focusRequester: androidx.compose.ui.focus.FocusRequester? = null,
    content: @Composable (androidx.compose.ui.Modifier) -> Unit,
) {
    if (!com.lagradost.cloudstream4.desktop.Platform.isTv) {
        androidx.compose.foundation.layout.Box(modifier) {
            content(androidx.compose.ui.Modifier.fillMaxWidth().then(focusRequester?.let { androidx.compose.ui.Modifier.focusRequester(it) } ?: androidx.compose.ui.Modifier))
        }
        return
    }
    var editing by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    var boxFocused by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    val field = androidx.compose.runtime.remember { androidx.compose.ui.focus.FocusRequester() }
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    androidx.compose.foundation.layout.Box(
        modifier
            .then(focusRequester?.let { androidx.compose.ui.Modifier.focusRequester(it) } ?: androidx.compose.ui.Modifier)
            .onFocusChanged { boxFocused = it.isFocused }
            .onPreviewKeyEvent { e ->
                // Up or down while typing leaves the field, so the remote can reach the button below
                val vertical = e.key == Key.DirectionDown || e.key == Key.DirectionUp
                if (editing && vertical) {
                    if (e.type == KeyEventType.KeyDown) {
                        val moved = focusManager.moveFocus(if (e.key == Key.DirectionDown) FocusDirection.Down else FocusDirection.Up)
                        if (moved) {
                            keyboard?.hide()
                            editing = false
                        }
                    }
                    return@onPreviewKeyEvent true
                }
                val ok = e.key == Key.DirectionCenter || e.key == Key.Enter || e.key == Key.NumPadEnter
                if (!ok) return@onPreviewKeyEvent false
                // OK in the field after the keyboard was closed brings it back
                if (e.type == KeyEventType.KeyUp) if (editing) keyboard?.show() else editing = true
                true
            }
            .focusable(),
    ) {
        content(
            androidx.compose.ui.Modifier.fillMaxWidth()
                .focusRequester(field)
                .focusProperties { canFocus = editing }
                // Leaving the field with the remote ends editing, so the next pass skips the keyboard again
                .onFocusChanged { if (editing && !it.hasFocus) editing = false },
        )
        if (boxFocused && !editing) {
            androidx.compose.foundation.layout.Box(
                androidx.compose.ui.Modifier.matchParentSize().border(3.dp, focusRingColor, RoundedCornerShape(12.dp))
            )
        }
    }
    androidx.compose.runtime.LaunchedEffect(editing) {
        if (editing) {
            runCatching { field.requestFocus() }
            keyboard?.show()
        }
    }
}

/**
 * Makes this the keyboard's target, taking focus back for [focus] whenever nothing inside it has
 * focus, unless [paused] (a menu or dialog has it on purpose). Compose drops focus when the focused
 * element goes away, such as a clicked button that hides, and then no key reaches the screen.
 */
@Composable
fun androidx.compose.ui.Modifier.reclaimFocus(
    focus: androidx.compose.ui.focus.FocusRequester,
    paused: Boolean = false,
): androidx.compose.ui.Modifier {
    var hasFocus by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(hasFocus, paused) {
        if (hasFocus || paused) return@LaunchedEffect
        kotlinx.coroutines.delay(100)
        runCatching { focus.requestFocus() }
    }
    return onFocusChanged { hasFocus = it.hasFocus }.focusRequester(focus).focusable()
}
