package com.lagradost.cloudstream4.desktop

import com.lagradost.cloudstream4.desktop.player.StreamProxy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** A playlist can name any host: the extension's cookies and credentials go only to its own site. */
class StreamProxyHeadersTest {
    private val headers = mapOf("Referer" to "https://video.example.com/", "cookie" to "session=secret", "Authorization" to "Bearer secret")

    @Test
    fun cookiesStayWithTheSessionsSite() {
        assertTrue(StreamProxy.sameSite("video.example.com", "cdn.example.com"))
        assertTrue(StreamProxy.sameSite("example.com", "CDN.Example.com"))
        assertFalse(StreamProxy.sameSite("video.example.com", "ads.tracker.net"))
        assertFalse(StreamProxy.sameSite("10.0.0.1", "10.0.0.2"))

        // Its own site and subdomains get everything
        assertEquals(headers, StreamProxy.headersFor(headers, "video.example.com", "https://cdn.example.com/seg1.ts"))
        // Another host still gets the Referer, which CDNs check, but not the cookie or credentials
        assertEquals(
            mapOf("Referer" to "https://video.example.com/"),
            StreamProxy.headersFor(headers, "video.example.com", "https://ads.tracker.net/ad.ts"),
        )
    }
}
