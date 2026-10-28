package com.lagradost.cloudstream4.desktop

import com.lagradost.cloudstream4.desktop.player.ScreenAwake
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** While a video plays the Mac's display stays awake, and it may sleep again once it stops. */
class ScreenAwakeTest {
    private fun assertions() = ProcessBuilder("pmset", "-g", "assertions").redirectErrorStream(true).start()
        .inputStream.bufferedReader().readText()

    @Test
    fun holdsADisplayAssertionWhilePlaying() {
        if (!System.getProperty("os.name").lowercase().contains("mac")) return
        ScreenAwake.set(true)
        ScreenAwake.settle()
        try {
            assertTrue(ScreenAwake.active)
            assertTrue("Hyper Stream is playing a video" in assertions(), "the system sees the assertion")
        } finally {
            ScreenAwake.set(false)
            ScreenAwake.settle()
        }
        assertFalse("Hyper Stream is playing a video" in assertions(), "and it's gone once playback stops")
    }
}
