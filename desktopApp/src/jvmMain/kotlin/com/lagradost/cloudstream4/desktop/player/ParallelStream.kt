package com.lagradost.cloudstream4.desktop.player

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicInteger

/** The host answered a Range request with the whole file, so it can only be streamed in one piece. */
internal class NoRangeSupport : IOException("The host doesn't support range requests.")

/** The host is refusing extra connections (429 or 503). */
private class Throttled(code: Int) : IOException("HTTP $code")

/** A fetch stopped on purpose because no reader needs that chunk any more. */
private class Abandoned : IOException("No longer needed")

/**
 * Streams one video file over several connections at once, the way the multi-part downloader
 * downloads it.
 *
 * The file is split into 2 MB chunks. A small pool of workers fetches them with HTTP Range
 * requests for everything the player is reading:
 *
 * - The chunk a reader is waiting on always goes first, so a seek never queues behind
 *   read-ahead for a position the player has left.
 * - Then the nearest chunk ahead of any reader, up to 32 MB ahead.
 * - Queued chunks no reader will reach any more (after a seek, or when the player hangs up)
 *   are dropped instead of fetched.
 *
 * The number of connections adapts like a download accelerator: it starts at 4, grows by one
 * while chunks keep arriving quickly, halves when the host refuses (429/503) or errors, and
 * shrinks when chunks crawl, because many hosts throttle clients that open too many connections.
 */
internal class ParallelStream(
    private val client: OkHttpClient,
    private val url: String,
    private val headers: Map<String, String>,
    val total: Long,
    val contentType: String?,
    maxConnections: Int,
) {
    private val chunkCount = ((total + CHUNK - 1) / CHUNK).toInt()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val maxConnections = maxConnections.coerceAtLeast(1)

    /** Connections allowed right now, adjusted as chunks arrive. */
    @Volatile
    var connectionLimit = minOf(START_CONNECTIONS, this.maxConnections)
        private set
    private val workers = AtomicInteger(0)

    /**
     * The most connections this host has accepted. Set when it refuses (429/503), so the pool
     * doesn't climb straight back into the refusals. Rises by one after a quiet minute.
     */
    @Volatile
    private var ceiling = this.maxConnections
    @Volatile
    private var lastRefusal = 0L

    /** One per open player request. */
    private class Reader {
        @Volatile var chunk = 0
    }
    private val readers = CopyOnWriteArraySet<Reader>()

    private val lock = Any()
    /** Requested chunks, fetched or not. Access-ordered so the least recently used goes first. */
    private val chunks = object : LinkedHashMap<Int, CompletableDeferred<ByteArray>>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, CompletableDeferred<ByteArray>>) =
            size > MAX_CACHED && eldest.value.isCompleted
    }
    private val queued = LinkedHashSet<Int>()
    private val running = HashSet<Int>()
    /** Open requests of running fetches, so a fetch nobody needs any more can be stopped. */
    private val calls = HashMap<Int, Call>()
    private val abandoned = HashSet<Int>()

    /** Where the player read last, for [aheadBytes]. */
    @Volatile
    private var readPosition = 0L

    private fun request(index: Int): CompletableDeferred<ByteArray> {
        val deferred = synchronized(lock) {
            chunks[index] ?: CompletableDeferred<ByteArray>().also {
                chunks[index] = it
                queued += index
            }
        }
        startWorkers()
        return deferred
    }

    private fun startWorkers() {
        while (true) {
            val current = workers.get()
            val (hasWork, blocked) = synchronized(lock) {
                val positions = readers.map { it.chunk }
                queued.isNotEmpty() to queued.count { it in positions }
            }
            // A reader blocked on a chunk nobody has started gets a connection beyond the limit,
            // so a seek never waits for read-ahead to finish
            if (!hasWork || current >= connectionLimit + blocked) return
            if (workers.compareAndSet(current, current + 1)) scope.launch { work() }
        }
    }

    private suspend fun work() {
        try {
            while (true) {
                val blocked = synchronized(lock) { readers.count { it.chunk in queued } }
                if (workers.get() > connectionLimit + blocked) return
                val index = synchronized(lock) { pickNext() } ?: return
                val deferred = synchronized(lock) { chunks[index] }
                try {
                    val started = System.nanoTime()
                    val bytes = fetch(index)
                    adapt(bytes.size * 1e9 / (System.nanoTime() - started).coerceAtLeast(1))
                    deferred?.complete(bytes)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    deferred?.completeExceptionally(e)
                    // Forget it, so a reader asking again fetches it again
                    synchronized(lock) { if (chunks[index] === deferred) chunks.remove(index) }
                } finally {
                    synchronized(lock) {
                        running -= index
                        abandoned -= index
                    }
                }
            }
        } finally {
            workers.decrementAndGet()
            // Someone may have queued work while this worker was finishing
            startWorkers()
        }
    }

    /** The next chunk to fetch, under [lock]. Drops chunks no reader is heading for. */
    private fun pickNext(): Int? {
        dropStale()
        val positions = readers.map { it.chunk }
        if (positions.isEmpty()) return null
        // A chunk a reader is blocked on first, then the closest one ahead of any reader
        val next = queued.firstOrNull { it in positions }
            ?: queued.minByOrNull { q -> positions.filter { q >= it }.minOf { q - it } }
            ?: return null
        queued -= next
        running += next
        return next
    }

    /** Ramps up while chunks arrive fast, backs off when they crawl. */
    private fun adapt(bytesPerSecond: Double) {
        synchronized(lock) {
            if (ceiling < maxConnections && System.currentTimeMillis() - lastRefusal > 60_000) {
                ceiling++
                lastRefusal = System.currentTimeMillis()
            }
            when {
                bytesPerSecond >= FAST && connectionLimit < ceiling -> connectionLimit++
                bytesPerSecond < SLOW && connectionLimit > 2 -> connectionLimit--
            }
        }
        startWorkers()
    }

    /** An error: fewer connections. A refusal also caps how far the pool may grow again. */
    private fun backOff(refused: Boolean) = synchronized(lock) {
        if (refused) {
            // The host accepted the ones already open, so stay just under that
            ceiling = maxOf(1, minOf(ceiling, calls.size.coerceAtLeast(2) - 1))
            lastRefusal = System.currentTimeMillis()
        }
        connectionLimit = maxOf(1, minOf(connectionLimit / 2, ceiling))
    }

    private suspend fun fetch(index: Int): ByteArray {
        val start = index * CHUNK
        val length = minOf(CHUNK, total - start).toInt()
        var attempt = 0
        while (true) {
            try {
                StreamProxy.meter.open()
                try {
                    val request = Request.Builder().url(url).apply {
                        headers.forEach { (k, v) -> header(k, v) }
                        header("Range", "bytes=$start-${start + length - 1}")
                    }.build()
                    val call = client.newCall(request)
                    synchronized(lock) {
                        if (index in abandoned) throw Abandoned()
                        calls[index] = call
                    }
                    call.execute().use { response ->
                        if (response.code == 200) throw NoRangeSupport()
                        if (response.code == 429 || response.code == 503) throw Throttled(response.code)
                        if (response.code != 206) throw IOException("HTTP ${response.code}")
                        val bytes = ByteArray(length)
                        response.body.byteStream().use { input ->
                            var read = 0
                            while (read < length) {
                                val n = input.read(bytes, read, length - read)
                                if (n < 0) throw IOException("Connection closed early")
                                read += n
                                StreamProxy.meter.add(n)
                            }
                        }
                        return bytes
                    }
                } finally {
                    StreamProxy.meter.close()
                    synchronized(lock) { calls.remove(index) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: NoRangeSupport) {
                throw e
            } catch (e: IOException) {
                // Stopped on purpose: not the host's fault, don't slow down or retry
                if (e is Abandoned || synchronized(lock) { index in abandoned }) throw Abandoned()
                // Fewer connections: the host is refusing or failing under the load
                backOff(refused = e is Throttled)
                if (++attempt >= 4) throw e
                delay(if (e is Throttled) 2_000L * attempt else 500L * attempt)
            }
        }
    }

    /** Bytes already fetched and waiting in memory past where the player is reading. */
    fun aheadBytes(): Long = synchronized(lock) {
        var index = (readPosition / CHUNK).toInt()
        var bytes = 0L
        while (index < chunkCount) {
            val chunk = chunks[index] ?: break
            if (!chunk.isCompleted || chunk.isCancelled) break
            bytes += minOf(CHUNK, total - index * CHUNK)
            index++
        }
        (bytes - (readPosition - (readPosition / CHUNK) * CHUNK)).coerceAtLeast(0)
    }

    /**
     * Writes bytes [start]..[end] (inclusive) to [out], fetching ahead in parallel. Returns when done
     * or throws when the player hangs up (it does on every seek) or the host keeps failing.
     */
    fun serve(start: Long, end: Long, out: OutputStream) {
        val reader = Reader().apply { chunk = (start / CHUNK).toInt() }
        readers += reader
        try {
            var position = start
            while (position <= end) {
                readPosition = position
                val index = (position / CHUNK).toInt()
                reader.chunk = index
                val needed = request(index)
                // Keep the window ahead of the reader requested
                for (k in index + 1 until minOf(index + WINDOW, chunkCount)) request(k)
                val bytes = runBlocking {
                    withTimeoutOrNull(120_000) { needed.await() }
                } ?: throw IOException("The host sent nothing for two minutes")
                val offset = (position - index * CHUNK).toInt()
                val count = minOf(bytes.size - offset.toLong(), end - position + 1).toInt()
                out.write(bytes, offset, count)
                position += count
            }
            out.flush()
        } finally {
            readers -= reader
            // Drop read-ahead nobody will use now
            synchronized(lock) { dropStale() }
        }
    }

    /**
     * Under [lock]: forgets queued chunks no reader is heading for, and stops running fetches of
     * chunks outside every reader's window, freeing their connections for what is needed now.
     */
    private fun dropStale() {
        val positions = readers.map { it.chunk }
        fun wanted(q: Int) = positions.any { q >= it && q < it + WINDOW }
        queued.filter { !wanted(it) || chunks[it] == null }.forEach { q ->
            queued -= q
            chunks[q]?.takeIf { !it.isCompleted }?.let { chunks.remove(q); it.cancel() }
        }
        running.filter { !wanted(it) && it !in abandoned }.forEach { q ->
            abandoned += q
            calls[q]?.cancel()
        }
    }

    fun close() = scope.cancel()

    companion object {
        const val CHUNK = 2L shl 20
        /** Chunks fetched ahead of a reader: 32 MB. */
        const val WINDOW = 16
        /** Chunks kept in memory: 96 MB. */
        const val MAX_CACHED = 48
        const val START_CONNECTIONS = 4
        /** A chunk faster than this lets the pool grow by one connection. */
        const val FAST = 512.0 * 1024
        /** A chunk slower than this shrinks it. */
        const val SLOW = 64.0 * 1024
    }
}
