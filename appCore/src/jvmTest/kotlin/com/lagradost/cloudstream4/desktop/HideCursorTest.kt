package com.lagradost.cloudstream4.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.ComposeWindow
import com.lagradost.cloudstream4.desktop.ui.hideCursor
import java.awt.Component
import java.awt.Container
import java.awt.GraphicsEnvironment
import java.awt.event.MouseEvent
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The cursor goes when the player's controls hide, while the mouse stays still over the video,
 * and comes back when they show. A real (small, unfocused) window, with a synthetic mouse move.
 */
class HideCursorTest {
    private fun cursors(c: Component): List<String> =
        listOf(c.cursor.name) + ((c as? Container)?.components?.flatMap { cursors(it) } ?: emptyList())

    @Test
    fun theCursorHidesWithTheControlsWithoutMoving() {
        if (GraphicsEnvironment.isHeadless()) return println("Skipping, no display")
        val hidden = mutableStateOf(false)
        lateinit var window: ComposeWindow
        SwingUtilities.invokeAndWait {
            window = ComposeWindow().apply {
                focusableWindowState = false
                setBounds(0, 0, 240, 160)
                setContent { Box(Modifier.fillMaxSize().hideCursor(hidden.value)) }
                isVisible = true
            }
        }
        fun waitFor(what: String, check: () -> Boolean) {
            val until = System.currentTimeMillis() + 5_000
            while (!check()) {
                assertTrue(System.currentTimeMillis() < until, "$what: ${cursors(window).distinct()}")
                Thread.sleep(50)
            }
        }
        fun moveMouse() = SwingUtilities.invokeAndWait {
            val target = SwingUtilities.getDeepestComponentAt(window.contentPane, 120, 80) ?: return@invokeAndWait
            val at = SwingUtilities.convertPoint(window.contentPane, 120, 80, target)
            target.dispatchEvent(MouseEvent(target, MouseEvent.MOUSE_ENTERED, System.currentTimeMillis(), 0, at.x, at.y, 0, false))
            target.dispatchEvent(MouseEvent(target, MouseEvent.MOUSE_MOVED, System.currentTimeMillis(), 0, at.x + 1, at.y, 0, false))
        }
        try {
            // The mouse comes over the video (again until the window is ready to take it): with
            // the controls hidden, that's when the cursor goes
            hidden.value = true
            waitFor("the cursor never hid over the video") { moveMouse(); "hidden" in cursors(window) }
            hidden.value = false
            waitFor("it didn't come back with the controls") { "hidden" !in cursors(window) }
            // The controls hide again, and the mouse doesn't move
            hidden.value = true
            waitFor("the cursor didn't hide with the controls while the mouse stayed still") { "hidden" in cursors(window) }
        } finally {
            SwingUtilities.invokeAndWait { window.dispose() }
        }
    }
}
