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
        assertEquals("It\u2019s here \u2013 now\u2026", plainText("It&#8217;s here &ndash; now&hellip;"))
        assertEquals("First paragraph.\nSecond.", plainText("<p>First paragraph.</p><p>Second.</p>"))
        assertEquals("Shown", plainText("<style>.x{color:red}</style><span class=\"x\">Shown</span>"))
        assertEquals("Season 1 &", plainText("Season&nbsp;1 &amp;"))
    }
}
