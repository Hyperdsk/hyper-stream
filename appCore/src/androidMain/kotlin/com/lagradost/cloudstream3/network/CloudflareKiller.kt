package com.lagradost.cloudstream3.network

import android.util.Log
import android.webkit.CookieManager
import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mvvm.safe
import com.lagradost.nicehttp.Requests.Companion.await
import com.lagradost.nicehttp.cookies
import kotlinx.coroutines.runBlocking
import okhttp3.Headers
import okhttp3.Headers.Companion.toHeaders
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import java.net.URI

/**
 * The CloudStream Android app's Cloudflare solver, which extensions add to their requests: when a
 * site answers with a Cloudflare check, a hidden web view (the library's WebViewResolver) passes it
 * and the cookies it gets are reused. Ported from the app with the same name and signatures.
 */
class CloudflareKiller : Interceptor {
    companion object {
        const val TAG = "CloudflareKiller"
        private val ERROR_CODES = listOf(403, 503)
        private val CLOUDFLARE_SERVERS = listOf("cloudflare-nginx", "cloudflare")

        fun parseCookieMap(cookie: String): Map<String, String> =
            cookie.split(";").associate {
                val split = it.split("=")
                (split.getOrNull(0)?.trim() ?: "") to (split.getOrNull(1)?.trim() ?: "")
            }.filter { it.key.isNotBlank() && it.value.isNotBlank() }
    }

    init {
        // New cookies each session; this can throw on devices without a web view
        safe { CookieManager.getInstance().removeAllCookies(null) }
    }

    val savedCookies: MutableMap<String, Map<String, String>> = mutableMapOf()

    /** Headers with the solved cookies and the web view's user agent, for links handed to a player. */
    fun getCookieHeaders(url: String): Headers {
        val userAgent = WebViewResolver.webViewUserAgent?.let { mapOf("user-agent" to it) } ?: emptyMap()
        return headersOf(userAgent, savedCookies[URI(url).host] ?: emptyMap())
    }

    override fun intercept(chain: Interceptor.Chain): Response = runBlocking {
        val request = chain.request()
        when (val cookies = savedCookies[request.url.host]) {
            null -> {
                val response = chain.proceed(request)
                if (!(response.header("Server") in CLOUDFLARE_SERVERS && response.code in ERROR_CODES)) {
                    return@runBlocking response
                }
                response.close()
                bypassCloudflare(request)?.let {
                    Log.d(TAG, "Passed the Cloudflare check: ${request.url}")
                    return@runBlocking it
                }
            }
            else -> return@runBlocking proceed(request, cookies)
        }
        Log.w(TAG, "Couldn't pass the Cloudflare check at ${request.url}")
        chain.proceed(request)
    }

    private fun webViewCookie(url: String): String? = safe { CookieManager.getInstance()?.getCookie(url) }

    /** True when the web view already has the Cloudflare cookie for this site; saves it. */
    private fun trySolveWithSavedCookies(request: Request): Boolean =
        webViewCookie(request.url.toString())?.let { cookie ->
            cookie.contains("cf_clearance").also { solved ->
                if (solved) savedCookies[request.url.host] = parseCookieMap(cookie)
            }
        } ?: false

    private suspend fun proceed(request: Request, cookies: Map<String, String>): Response {
        val userAgent = WebViewResolver.getWebViewUserAgent()?.let { mapOf("user-agent" to it) } ?: emptyMap()
        val headers = headersOf(request.headers.toMap() + userAgent, cookies + request.cookies)
        return app.baseClient.newCall(request.newBuilder().headers(headers).build()).await()
    }

    private suspend fun bypassCloudflare(request: Request): Response? {
        if (!trySolveWithSavedCookies(request)) {
            Log.d(TAG, "Opening a web view to pass the Cloudflare check at ${request.url}")
            WebViewResolver(
                // Never stop early because of the address
                Regex(".^"),
                // Cloudflare wants the web view's own user agent
                userAgent = null,
                useOkhttp = false,
                additionalUrls = listOf(Regex(".")),
            ).resolveUsingWebView(request.url.toString()) { trySolveWithSavedCookies(request) }
        }
        val cookies = savedCookies[request.url.host] ?: return null
        return proceed(request, cookies)
    }

    /** Set headers, then cookies, over the default user agent, as the app's getHeaders does. */
    private fun headersOf(headers: Map<String, String>, cookie: Map<String, String>): Headers {
        val cookieHeader = if (cookie.isNotEmpty()) mapOf("Cookie" to cookie.entries.joinToString(" ") { "${it.key}=${it.value};" }) else emptyMap()
        return (mapOf("user-agent" to USER_AGENT) + headers + cookieHeader).toHeaders()
    }
}
