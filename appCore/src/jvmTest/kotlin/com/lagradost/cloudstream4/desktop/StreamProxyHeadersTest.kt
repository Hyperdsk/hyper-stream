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
        // Public suffixes with two labels: different people's sites
        assertFalse(StreamProxy.sameSite("video.site.co.uk", "evil.co.uk"))
        assertTrue(StreamProxy.sameSite("video.site.co.uk", "cdn.site.co.uk"))
        assertFalse(StreamProxy.sameSite("me.github.io", "you.github.io"))

        // Its own site and subdomains get everything
        assertEquals(headers, StreamProxy.headersFor(headers, "video.example.com", "https://cdn.example.com/seg1.ts"))
        // Another host still gets the Referer, which CDNs check, but not the cookie or credentials
        assertEquals(
            mapOf("Referer" to "https://video.example.com/"),
            StreamProxy.headersFor(headers, "video.example.com", "https://ads.tracker.net/ad.ts"),
        )
    }

    @Test
    fun redirectToAnotherSiteDropsTheCookie() {
        val seen = java.util.concurrent.ConcurrentHashMap<String, String>()
        fun server(handle: (com.sun.net.httpserver.HttpExchange) -> Unit) =
            com.sun.net.httpserver.HttpServer.create(java.net.InetSocketAddress("127.0.0.1", 0), 0).apply {
                createContext("/") { ex -> try { handle(ex) } finally { ex.close() } }
                start()
            }
        val other = server { ex ->
            seen["other-cookie"] = ex.requestHeaders.getFirst("Cookie") ?: "none"
            seen["other-referer"] = ex.requestHeaders.getFirst("Referer") ?: "none"
            ex.sendResponseHeaders(200, 2)
            ex.responseBody.write("ok".toByteArray())
        }
        // 127.0.0.1 and localhost: two different hosts, as two sites would be
        val home = server { ex ->
            seen["home-cookie"] = ex.requestHeaders.getFirst("Cookie") ?: "none"
            ex.responseHeaders.set("Location", "http://localhost:${other.address.port}/seg.ts")
            ex.sendResponseHeaders(302, -1)
        }
        try {
            val client = okhttp3.OkHttpClient.Builder().addNetworkInterceptor(StreamProxy.crossSiteRedirects).build()
            val request = okhttp3.Request.Builder().url("http://127.0.0.1:${home.address.port}/seg.ts")
                .header("Cookie", "session=secret").header("Referer", "https://video.example.com/").build()
            client.newCall(request).execute().use { assertEquals("ok", it.body.string()) }
            assertEquals("session=secret", seen["home-cookie"])
            assertEquals("none", seen["other-cookie"])
            assertEquals("https://video.example.com/", seen["other-referer"])
        } finally {
            home.stop(0)
            other.stop(0)
        }
    }
}
