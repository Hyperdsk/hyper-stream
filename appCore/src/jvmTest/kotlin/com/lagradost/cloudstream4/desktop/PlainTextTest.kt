package com.lagradost.cloudstream4.desktop

import com.lagradost.cloudstream4.desktop.ui.plainText
import kotlin.test.Test
import kotlin.test.assertEquals

class PlainTextTest {
    @Test
    fun htmlInDescriptionsBecomesText() {
        assertEquals("IMDB Ratings: 6.1/10\nDirected: Karan Rawal", plainText("IMDB Ratings: 6.1/10 <br>Directed: Karan Rawal"))
        assertEquals("Tom & Jerry \"live\"", plainText("<b>Tom &amp; Jerry</b> &quot;live&quot;"))
        assertEquals("Plain text stays as it is.", plainText("Plain text stays as it is."))
    }
}
