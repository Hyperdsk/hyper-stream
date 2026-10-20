package com.lagradost.cloudstream4.desktop.player

import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream4.desktop.data.SettingsStore
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import okhttp3.ConnectionPool
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Protocol
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit
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
/** What the player shows while streaming. */
data class StreamStats(
    /** Download speed from the hosts, smoothed. */
    val bytesPerSecond: Long = 0,
    /** Upstream connections open right now. */
    val connections: Int = 0,
    /** Video already fetched and waiting in memory ahead of the player. */
    val aheadBytes: Long = 0,
    val totalBytes: Long = 0,
)

object StreamProxy {
    private val sessions = ConcurrentHashMap<String, Map<String, String>>()

    /** Counts bytes and open connections to the hosts, for [stats]. */
    internal object meter {
        val bytes = AtomicLong()
        val connections = AtomicInteger()
        fun add(n: Int) { bytes.addAndGet(n.toLong()) }
        fun open() { connections.incrementAndGet() }
        fun close() { connections.decrementAndGet() }
    }

    private val _stats = MutableStateFlow(StreamStats())
    val stats: StateFlow<StreamStats> by lazy {
        // Twice a second: speed as a moving average, so the number doesn't jump around
        segmentScope.launch {
            var lastBytes = meter.bytes.get()
            var lastTime = System.nanoTime()
            var speed = 0.0
            while (true) {
                delay(500)
                val now = System.nanoTime()
                val bytes = meter.bytes.get()
                val instant = (bytes - lastBytes) * 1e9 / (now - lastTime).coerceAtLeast(1)
                speed = if (speed == 0.0) instant else speed * 0.6 + instant * 0.4
                lastBytes = bytes
                lastTime = now
                _stats.value = StreamStats(speed.toLong(), meter.connections.get().coerceAtLeast(0), aheadBytes(), bytes)
            }
        }
        _stats.asStateFlow()
    }

    /** The stream or HLS position the player read last, for the "ahead" figure. */
    @Volatile
    private var lastStream: ParallelStream? = null
    @Volatile
    private var lastSegment: Pair<List<String>, Int>? = null

    private fun aheadBytes(): Long {
        lastStream?.let { return it.aheadBytes() }
        val (order, position) = lastSegment ?: return 0
        var bytes = 0L
        synchronized(segments) {
            for (url in order.drop(position + 1)) {
                val segment = segments[url] ?: break
                if (!segment.isCompleted || segment.isCancelled) break
                bytes += runCatching { segment.getCompleted().bytes.size.toLong() }.getOrDefault(0)
            }
        }
        return bytes
    }

    /** Sessions whose main url is a single video file, streamed over several connections. */
    private val parallelSessions = ConcurrentHashMap.newKeySet<String>()

    /** "session|url" to its stream, or [NoStream] when the host can't do ranges. */
    private val streams = ConcurrentHashMap<String, Any>()
    private object NoStream

    /** For each session's HLS media playlists: segment url to (all segment urls, its position). */
    private val segmentOrder = ConcurrentHashMap<String, ConcurrentHashMap<String, Pair<List<String>, Int>>>()
    private class Segment(val contentType: String?, val bytes: ByteArray)
    private val segmentScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val segmentPermits = Semaphore(16)
    private val segments = object : LinkedHashMap<String, Deferred<Segment>>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Deferred<Segment>>) = size > 40
    }

    /**
     * Separate HTTP/1.1 connections, like the downloader: over HTTP/2 every parallel request
     * would share one connection, and per-connection speed limits are what this gets around.
     */
    private val streamClient by lazy {
        app.baseClient.newBuilder()
            .protocols(listOf(Protocol.HTTP_1_1))
            .connectionPool(ConnectionPool(48, 2, TimeUnit.MINUTES))
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    private val connections: Int get() = SettingsStore.state.value.streamConnections
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

    /**
     * Registers a set of headers and returns the local url VLC should open for [url].
     * [parallel]: [url] is a single video file, stream it over several connections.
     */
    fun proxiedUrl(url: String, referer: String?, headers: Map<String, String>, parallel: Boolean = false): String {
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
        if (parallel) parallelSessions += session
        return local(session, url)
    }

    fun clear() {
        sessions.clear()
        parallelSessions.clear()
        streams.values.forEach { (it as? ParallelStream)?.close() }
        streams.clear()
        segmentOrder.clear()
        synchronized(segments) { segments.clear() }
        lastStream = null
        lastSegment = null
    }

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

        val range = exchange.requestHeaders.getFirst("Range")
        if (connections > 1 && exchange.requestMethod == "GET") {
            // The seek-bar preview player asks with "?preview": it must not slow the main player
            val lowPriority = exchange.requestURI.rawQuery == "preview"
            if (session in parallelSessions && target == original && serveParallel(exchange, session, target, headers, range, lowPriority)) return
            if (range == null && serveSegment(exchange, session, target, headers)) return
        }

        val request = Request.Builder().url(target).apply {
            headers.forEach { (k, v) -> header(k, v) }
            range?.let { header("Range", it) }
            if (exchange.requestMethod == "HEAD") head()
        }.build()

        meter.open()
        try {
            passThrough(exchange, request, session)
        } finally {
            meter.close()
        }
    }

    private fun passThrough(exchange: HttpExchange, request: Request, session: String) {
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
            exchange.responseBody.use { out ->
                body.byteStream().use { input ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        meter.add(n)
                        out.write(buffer, 0, n)
                    }
                }
            }
        }
    }

    /** Serves a video file through [ParallelStream]. False when the host can't do ranges. */
    private fun serveParallel(
        exchange: HttpExchange,
        session: String,
        target: String,
        headers: Map<String, String>,
        range: String?,
        lowPriority: Boolean = false,
    ): Boolean {
        val stream = streams.computeIfAbsent("$session|$target") {
            runCatching {
                val probe = Request.Builder().url(target).apply {
                    headers.forEach { (k, v) -> header(k, v) }
                    header("Range", "bytes=0-0")
                }.build()
                streamClient.newCall(probe).execute().use { r ->
                    val total = r.header("Content-Range")?.substringAfter('/')?.toLongOrNull()
                    val type = r.header("Content-Type")
                    StreamLog.log(
                        "probe ${StreamLog.host(target)} -> HTTP ${r.code} ${r.protocol} type=$type total=$total " +
                            "range=${r.header("Content-Range")} final=${StreamLog.host(r.request.url.toString())}"
                    )
                    // Small files and pages aren't worth splitting
                    if (r.code == 206 && total != null && total >= 4L shl 20 && type?.startsWith("text/") != true) {
                        ParallelStream(streamClient, target, headers, total, type, connections)
                    } else NoStream
                }
            }.getOrDefault(NoStream)
        } as? ParallelStream ?: run {
            StreamLog.log("request ${range ?: "whole file"} -> one connection (no ranges)")
            return false
        }
        StreamLog.log("request ${range ?: "whole file"}${if (lowPriority) " (preview)" else ""}")

        val total = stream.total
        val (start, end) = parseRange(range, total) ?: run {
            exchange.responseHeaders.set("Content-Range", "bytes */$total")
            exchange.sendResponseHeaders(416, -1)
            return true
        }
        if (!lowPriority) {
            lastStream = stream
            lastSegment = null
        }
        stream.contentType?.let { exchange.responseHeaders.set("Content-Type", it) }
        exchange.responseHeaders.set("Accept-Ranges", "bytes")
        if (range != null) exchange.responseHeaders.set("Content-Range", "bytes $start-$end/$total")
        exchange.sendResponseHeaders(if (range != null) 206 else 200, end - start + 1)
        val t = System.nanoTime()
        try {
            exchange.responseBody.use { stream.serve(start, end, it, lowPriority) }
        } catch (e: IOException) {
            // The player hangs up on every seek; anything else is worth knowing
            if (e.message?.contains("Broken pipe") != true && e.message?.contains("closed") != true) {
                StreamLog.log("request $start- ended after ${(System.nanoTime() - t) / 1_000_000} ms: $e")
            }
        } catch (e: Throwable) {
            StreamLog.log("request $start- failed: $e")
            throw e
        }
        return true
    }

    /** "bytes=a-b", "bytes=a-" or "bytes=-n" to an inclusive range, or null when it's past the end. */
    internal fun parseRange(range: String?, total: Long): Pair<Long, Long>? {
        if (range == null) return 0L to total - 1
        val spec = range.removePrefix("bytes=").substringBefore(',').trim()
        val a = spec.substringBefore('-').trim()
        val b = spec.substringAfter('-', "").trim()
        val (start, end) = when {
            a.isEmpty() -> (total - (b.toLongOrNull() ?: return null)).coerceAtLeast(0) to total - 1
            else -> (a.toLongOrNull() ?: return null) to (b.toLongOrNull()?.coerceAtMost(total - 1) ?: (total - 1))
        }
        return if (start in 0..end && start < total) start to end else null
    }

    /**
     * Serves an HLS segment, fetching the next few in parallel while the player plays this one.
     * False when [target] isn't a known segment or fetching failed, so it goes the normal way.
     */
    private fun serveSegment(exchange: HttpExchange, session: String, target: String, headers: Map<String, String>): Boolean {
        val (order, position) = segmentOrder[session]?.get(target) ?: return false
        lastSegment = order to position
        lastStream = null
        val ahead = (connections / 2).coerceIn(2, 8)
        for (next in order.subList(position + 1, minOf(order.size, position + 1 + ahead))) segment(next, headers)
        val segment = try {
            runBlocking { withTimeout(60_000) { segment(target, headers).await() } }
        } catch (_: Throwable) {
            synchronized(segments) { segments.remove(target) }
            return false
        }
        segment.contentType?.let { exchange.responseHeaders.set("Content-Type", it) }
        exchange.sendResponseHeaders(200, segment.bytes.size.toLong())
        try {
            exchange.responseBody.use { it.write(segment.bytes) }
        } catch (_: IOException) {
        }
        return true
    }

    private fun segment(url: String, headers: Map<String, String>): Deferred<Segment> = synchronized(segments) {
        segments.getOrPut(url) { segmentScope.async { segmentPermits.withPermit { fetchSegment(url, headers) } } }
    }

    private fun countedBytes(input: java.io.InputStream): ByteArray = input.use {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val n = it.read(buffer)
            if (n < 0) break
            meter.add(n)
            out.write(buffer, 0, n)
        }
        out.toByteArray()
    }

    private suspend fun fetchSegment(url: String, headers: Map<String, String>): Segment {
        var attempt = 0
        while (true) {
            try {
                val request = Request.Builder().url(url).apply { headers.forEach { (k, v) -> header(k, v) } }.build()
                meter.open()
                try {
                    streamClient.newCall(request).execute().use { r ->
                        if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
                        return Segment(r.header("Content-Type"), countedBytes(r.body.byteStream()))
                    }
                } finally {
                    meter.close()
                }
            } catch (e: IOException) {
                if (++attempt >= 3) throw e
                kotlinx.coroutines.delay(500L * attempt)
            }
        }
    }

    private val uriAttribute = Regex("URI=\"([^\"]+)\"")

    /** Points every uri in the playlist at this proxy, resolved against the playlist's own url. */
    internal fun rewritePlaylist(text: String, playlistUrl: String, session: String): String {
        val base = URI(playlistUrl)
        fun resolve(ref: String): String = local(session, base.resolve(ref.trim()).toString())

        // A media playlist: remember the segment order, for fetching ahead
        if (text.contains("#EXTINF")) {
            val order = text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
                .mapNotNull { runCatching { base.resolve(it).toString() }.getOrNull() }.toList()
            val index = segmentOrder.getOrPut(session) { ConcurrentHashMap() }
            order.forEachIndexed { i, url -> index[url] = order to i }
        }

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
