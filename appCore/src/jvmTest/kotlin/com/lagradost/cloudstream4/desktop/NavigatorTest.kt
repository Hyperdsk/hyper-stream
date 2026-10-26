package com.lagradost.cloudstream4.desktop

import com.lagradost.cloudstream4.desktop.ui.Navigator
import com.lagradost.cloudstream4.desktop.ui.Route
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NavigatorTest {
    private val title = Route.Details("Prov", "https://example.invalid/t", "Title")

    @Test
    fun eachTabKeepsItsScreens() {
        val nav = Navigator()
        nav.open(title)
        nav.open(Route.Search)
        assertEquals(Route.Search, nav.current)
        nav.open(Route.Home)
        assertEquals(title, nav.current, "the title opened from Home is still there")
        assertTrue(title in nav.allRoutes)
    }

    @Test
    fun choosingTheCurrentTabGoesToItsTop() {
        val nav = Navigator()
        nav.open(title)
        nav.open(Route.Home)
        assertEquals(Route.Home, nav.current)
        assertFalse(title in nav.allRoutes)
    }

    @Test
    fun backLeavesScreensThenReturnsToThePreviousTab() {
        val nav = Navigator()
        nav.open(Route.Library)
        nav.open(title)
        assertTrue(nav.back())
        assertEquals(Route.Library, nav.current)
        assertTrue(nav.back(), "from a tab's first screen, back to the tab used before")
        assertEquals(Route.Home, nav.current)
        assertFalse(nav.back(), "nothing left: the app may close")
    }
}
