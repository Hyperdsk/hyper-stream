package com.lagradost.cloudstream4.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.lagradost.cloudstream4.desktop.ui.reclaimFocus
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The player's keys keep working after the focused control goes away, as when a clicked pause
 * button fades out with the controls.
 */
class ReclaimFocusTest {
    @OptIn(ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)
    @Test
    fun keysStillArriveAfterTheFocusedButtonHides() = runBlocking {
        var spaces = 0
        var controlsVisible by mutableStateOf(true)
        val button = FocusRequester()
        val scene = ImageComposeScene(400, 300, Density(1f)) {
            val focus = remember { FocusRequester() }
            Box(
                Modifier.fillMaxSize()
                    .reclaimFocus(focus)
                    .onPreviewKeyEvent { e ->
                        if (e.type == KeyEventType.KeyDown && e.key == Key.Spacebar) { spaces++; true } else false
                    },
            ) {
                if (controlsVisible) {
                    Button(onClick = {}, modifier = Modifier.size(80.dp).focusRequester(button)) { Text("Pause") }
                    LaunchedEffect(Unit) { button.requestFocus() }
                }
            }
        }
        var t = 0L
        suspend fun frames(n: Int) = repeat(n) { scene.render(t); t += 16_000_000L; delay(10) }
        fun space() {
            scene.sendKeyEvent(androidx.compose.ui.input.key.KeyEvent(Key.Spacebar, KeyEventType.KeyDown))
            scene.sendKeyEvent(androidx.compose.ui.input.key.KeyEvent(Key.Spacebar, KeyEventType.KeyUp))
        }

        frames(10)
        space()
        assertEquals(1, spaces, "Space works while the button has focus")

        // The controls hide, taking the focused button with them
        controlsVisible = false
        frames(30)
        space()
        assertEquals(2, spaces, "Space still works after the focused button went away")
        scene.close()
    }
}
