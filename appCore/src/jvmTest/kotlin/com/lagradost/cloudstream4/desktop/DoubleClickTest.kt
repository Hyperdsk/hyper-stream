package com.lagradost.cloudstream4.desktop

import androidx.compose.ui.geometry.Offset
import com.lagradost.cloudstream4.desktop.ui.DoubleClick
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DoubleClickTest {
    private val timeout = 300L
    private val slop = 36f

    @Test
    fun aQuickSecondClickInPlaceIsADoubleClick() {
        val clicks = DoubleClick()
        assertFalse(clicks.isSecond(Offset(100f, 100f), 1_000, timeout, slop), "the first click is a single")
        clicks.clicked(Offset(100f, 100f), 1_000)
        assertTrue(clicks.isSecond(Offset(110f, 105f), 1_200, timeout, slop))
    }

    @Test
    fun aSlowOrDistantSecondClickIsNot() {
        val clicks = DoubleClick()
        clicks.clicked(Offset(100f, 100f), 1_000)
        assertFalse(clicks.isSecond(Offset(100f, 100f), 1_400, timeout, slop), "too late")
        assertFalse(clicks.isSecond(Offset(400f, 100f), 1_100, timeout, slop), "too far")
    }

    @Test
    fun aThirdClickStartsOver() {
        // After a double click toggled fullscreen, a quick third click must not toggle it back
        val clicks = DoubleClick()
        clicks.clicked(Offset.Zero, 1_000)
        assertTrue(clicks.isSecond(Offset.Zero, 1_100, timeout, slop))
        clicks.reset()
        assertFalse(clicks.isSecond(Offset.Zero, 1_200, timeout, slop))
    }
}
