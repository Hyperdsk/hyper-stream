package com.lagradost.cloudstream4.desktop.player

import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.app
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URI
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * A small HTTP server on 127.0.0.1 that sits between VLC and the stream host.
 *
 * Extensions return links that only play with specific headers (Referer, Origin, cookies,
 * tokens). VLC can only set a user agent and a referrer, so links are routed through this
 * proxy, which adds the headers. HLS playlists are rewritten so every segment, key and
 * variant playlist also goes through the proxy with the same headers.
 *
 * Only listens on the loopback address and only serves urls registered by this app.
 */
object StreamProxy {
    private val sessions = ConcurrentHashMap<String, Map<String, String>>()
    private val encoder = Base64.getUrlEncoder().withoutPadding()
    private val decoder = Base64.getUrlDecoder()

    private val server: HttpServer by lazy {
        HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0).apply {
            executor = Executors.newCachedThreadPool { r -> Thread(r, "stream-proxy").apply { isDaemon = true } }
            createContext("/s/") { exchange ->
                try {
                    handle(exchange)
                } catch (t: Throwable) {
                    runCatching { exchange.sendResponseHeaders(502, -1) }
                } finally {
                    exchange.close()
                }
            }
            start()
        }
    }

    /** Registers a set of headers and returns the local url VLC should open for [url]. */
    fun proxiedUrl(url: String, referer: String?, headers: Map<String, String>): String {
        val all = buildMap {
            put("User-Agent", USER_AGENT)
            if (!referer.isNullOrBlank()) put("Referer", referer)
            // Case-insensitive override of the defaults above
            headers.forEach { (k, v) ->
                keys.firstOrNull { it.equals(k, ignoreCase = true) }?.let { remove(it) }
                put(k, v)
            }
        }
        val session = UUID.randomUUID().toString().replace("-", "")
        sessions[session] = all
        return local(session, url)
    }

    fun clear() = sessions.clear()

    private fun local(session: String, url: String): String {
        // Keep the original file name at the end, so VLC can guess the format from it
        val encoded = encoder.encodeToString(url.toByteArray())
        return "http://127.0.0.1:${server.address.port}/s/$session/$encoded/${fileName(url)}"
    }

    private fun fileName(url: String): String =
        runCatching { URI(url).path.substringAfterLast('/') }.getOrNull()
            ?.takeIf { it.isNotBlank() && it.length < 100 }
            ?.replace(Regex("[^A-Za-z0-9._-]"), "_")
            ?: "media"

    private fun handle(exchange: HttpExchange) {
        // /s/{session}/{base64 url}/{name}
        val parts = exchange.requestURI.rawPath.removePrefix("/s/").split('/')
        val session = parts.getOrNull(0)
        val headers = session?.let { sessions[it] }
        val original = parts.getOrNull(1)?.let { runCatching { String(decoder.decode(it)) }.getOrNull() }
        // A player resolving a relative path (DASH segments, unrewritten refs) against our url changes
        // the trailing name. Resolve that path against the original url instead.
        val rest = parts.drop(2).joinToString("/")
        val target = if (original != null && rest.isNotEmpty() && rest != fileName(original)) {
            val query = exchange.requestURI.rawQuery?.let { "?$it" } ?: ""
            runCatching { URI(original).resolve(rest + query).toString() }.getOrNull()
        } else original
        if (headers == null || target == null || target.toHttpUrlOrNull() == null) {
            exchange.sendResponseHeaders(404, -1)
            return
        }

        val request = Request.Builder().url(target).apply {
            headers.forEach { (k, v) -> header(k, v) }
            exchange.requestHeaders.getFirst("Range")?.let { header("Range", it) }
            if (exchange.requestMethod == "HEAD") head()
        }.build()

        app.baseClient.newCall(request).execute().use { response ->
            val body = response.body
            val contentType = response.header("Content-Type") ?: ""
            val finalUrl = response.request.url.toString()

            val looksLikePlaylist = contentType.contains("mpegurl", ignoreCase = true) ||
                finalUrl.substringBefore('?').endsWith(".m3u8", ignoreCase = true)

            if (looksLikePlaylist && exchange.requestMethod != "HEAD" && response.isSuccessful) {
                val text = body.string()
                if (text.trimStart().startsWith("#EXTM3U")) {
                    val bytes = rewritePlaylist(text, finalUrl, session).toByteArray()
                    exchange.responseHeaders.set("Content-Type", "application/vnd.apple.mpegurl")
                    exchange.sendResponseHeaders(response.code, bytes.size.toLong())
                    exchange.responseBody.use { it.write(bytes) }
                    return
                }
                // Not actually a playlist, pass the text through unchanged
                val bytes = text.toByteArray()
                if (contentType.isNotEmpty()) exchange.responseHeaders.set("Content-Type", contentType)
                exchange.sendResponseHeaders(response.code, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
                return
            }

            listOf("Content-Type", "Content-Range", "Accept-Ranges", "Last-Modified", "ETag").forEach { name ->
                response.header(name)?.let { exchange.responseHeaders.set(name, it) }
            }
            val length = response.header("Content-Length")?.toLongOrNull()
            if (exchange.requestMethod == "HEAD") {
                length?.let { exchange.responseHeaders.set("Content-Length", it.toString()) }
                exchange.sendResponseHeaders(response.code, -1)
                return
            }
            // 0 means chunked when the length is unknown
            exchange.sendResponseHeaders(response.code, length ?: 0)
            exchange.responseBody.use { out -> body.byteStream().use { it.copyTo(out, 64 * 1024) } }
        }
    }

    private val uriAttribute = Regex("URI=\"([^\"]+)\"")

    /** Points every uri in the playlist at this proxy, resolved against the playlist's own url. */
    internal fun rewritePlaylist(text: String, playlistUrl: String, session: String): String {
        val base = URI(playlistUrl)
        fun resolve(ref: String): String = local(session, base.resolve(ref.trim()).toString())

        return text.lineSequence().joinToString("\n") { line ->
            val trimmed = line.trim()
            when {
                trimmed.isEmpty() -> line
                trimmed.startsWith("#") -> uriAttribute.replace(line) { m ->
                    val ref = m.groupValues[1]
                    // Data uris and unsupported key schemes stay as they are
                    if (ref.startsWith("data:") || ref.startsWith("skd:")) m.value
                    else "URI=\"${resolve(ref)}\""
                }

                else -> resolve(trimmed)
            }
        }
    }
}
