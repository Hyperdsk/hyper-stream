package com.lagradost.cloudstream4.desktop

import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import com.lagradost.cloudstream4.desktop.ui.RecentSearches
import kotlin.test.Test

/** Recent searches show any query, including one that matches the header's own list key. */
class RecentSearchesTest {
    @Test
    fun aSearchForTitleDoesNotCrashTheList() {
        val scene = ImageComposeScene(600, 400, Density(1f)) {
            RecentSearches(listOf("Title", "title ", "Dune"), onSearch = {}, onRemove = {}, onClear = {})
        }
        try {
            scene.render(0)
            scene.render(16_000_000)
        } finally {
            scene.close()
        }
    }
}
