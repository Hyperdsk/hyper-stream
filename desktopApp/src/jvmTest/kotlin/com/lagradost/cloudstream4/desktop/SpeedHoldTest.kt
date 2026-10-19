package com.lagradost.cloudstream4.desktop

import com.lagradost.cloudstream4.desktop.player.VlcPlayer
import com.lagradost.cloudstream4.desktop.ui.SpeedHold
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Hold for 2x with the real VLC player. Runs only when CS_VLC_FILE points at a video. */
class SpeedHoldTest {
    @Test
    fun holdingPlaysAt2xAndTappingDoesNot() = runBlocking {
        val path = System.getenv("CS_VLC_FILE") ?: return@runBlocking
        val player = VlcPlayer.create() ?: return@runBlocking
        try {
            player.open(File(path).toPath().toUri().toString())
            withTimeout(10_000) { player.state.first { it.playing } }
            val hold = SpeedHold(this)

            // Tap: no speed change, reported as a tap
            hold.press(player)
            delay(100)
            assertFalse(hold.release(player))
            assertEquals(1f, player.state.value.rate)

            // Hold: 2x while held, back to normal after
            hold.press(player)
            delay(600)
            assertTrue(hold.boosting)
            assertEquals(2f, player.state.value.rate)
            assertTrue(hold.release(player))
            assertFalse(hold.boosting)
            assertEquals(1f, player.state.value.rate)
            println("Hold for 2x works")
        } finally {
            player.release()
        }
    }
}
