package com.lagradost.cloudstream4.desktop.player

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
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
    private class Reader(val lowPriority: Boolean) {
        @Volatile var chunk = 0
        /** Bytes this request has delivered, which sets how far ahead to fetch for it. */
        @Volatile var delivered = 0L

        /**
         * Read-ahead grows as the request keeps reading, like TCP slow start. VLC probes the
         * header and the index with short requests it then drops, so those get the bandwidth
         * for themselves instead of sharing it with read-ahead that is thrown away.
         */
        val window: Int
            get() = when {
                lowPriority -> 1
                delivered < 1L shl 20 -> 1
                delivered < 8L shl 20 -> 4
                else -> WINDOW
            }
    }

    /** A 2 MB piece, filled as it downloads so the player gets bytes as soon as they arrive. */
    private class Chunk(val length: Int) {
        val bytes = ByteArray(length)
        val filled = MutableStateFlow(0)
        val result = CompletableDeferred<Unit>()
        val ready get() = result.isCompleted && !result.isCancelled && filled.value == length
    }
    private val readers = CopyOnWriteArraySet<Reader>()

    private val lock = Any()
    /** Requested chunks, fetched or not. Access-ordered so the least recently used goes first. */
    private val chunks = object : LinkedHashMap<Int, Chunk>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, Chunk>) =
            size > MAX_CACHED && eldest.value.result.isCompleted
    }
    private val queued = LinkedHashSet<Int>()
    private val running = HashSet<Int>()
    /** Open requests of running fetches, so a fetch nobody needs any more can be stopped. */
    private val calls = HashMap<Int, Call>()
    private val abandoned = HashSet<Int>()

    /** Where the player read last, for [aheadBytes]. */
    @Volatile
    private var readPosition = 0L

    private fun request(index: Int): Chunk {
        val chunk = synchronized(lock) {
            chunks[index] ?: Chunk(minOf(CHUNK, total - index * CHUNK).toInt()).also {
                chunks[index] = it
                queued += index
            }
        }
        startWorkers()
        return chunk
    }

    private fun startWorkers() {
        while (true) {
            val current = workers.get()
            val (hasWork, blocked) = synchronized(lock) {
                val positions = readers.filter { !it.lowPriority }.map { it.chunk }
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
                val blocked = synchronized(lock) { readers.count { !it.lowPriority && it.chunk in queued } }
                if (workers.get() > connectionLimit + blocked) return
                val index = synchronized(lock) { pickNext() } ?: return
                val chunk = synchronized(lock) { chunks[index] } ?: continue
                try {
                    val started = System.nanoTime()
                    val before = chunk.filled.value
                    fetch(index, chunk)
                    adapt((chunk.length - before) * 1e9 / (System.nanoTime() - started).coerceAtLeast(1))
                    chunk.result.complete(Unit)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    chunk.result.completeExceptionally(e)
                    // Forget it, so a reader asking again fetches it again
                    synchronized(lock) { if (chunks[index] === chunk) chunks.remove(index) }
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
        if (readers.isEmpty()) return null
        val main = readers.filter { !it.lowPriority }.map { it.chunk }
        val all = readers.map { it.chunk }
        // A chunk a player request is blocked on first, then the closest one ahead of any reader
        val next = queued.firstOrNull { it in main }
            ?: queued.firstOrNull { it in all }
            ?: queued.minByOrNull { q -> all.filter { q >= it }.minOfOrNull { q - it } ?: Int.MAX_VALUE }
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
            val before = connectionLimit
            when {
                bytesPerSecond >= FAST && connectionLimit < ceiling -> connectionLimit++
                bytesPerSecond < SLOW && connectionLimit > 2 -> connectionLimit--
            }
            if (before != connectionLimit) StreamLog.log("connections $before -> $connectionLimit (chunk at ${(bytesPerSecond / 1024).toInt()} KB/s)")
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

    /** Fills [chunk], resuming after what it already holds. */
    private suspend fun fetch(index: Int, chunk: Chunk) {
        val chunkStart = index * CHUNK
        val length = chunk.length
        var attempt = 0
        while (true) {
            try {
                StreamProxy.meter.open()
                try {
                    val from = chunk.filled.value
                    if (from >= length) return
                    val request = Request.Builder().url(url).apply {
                        headers.forEach { (k, v) -> header(k, v) }
                        header("Range", "bytes=${chunkStart + from}-${chunkStart + length - 1}")
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
                        response.body.byteStream().use { input ->
                            var read = chunk.filled.value
                            while (read < length) {
                                val n = input.read(chunk.bytes, read, minOf(64 * 1024, length - read))
                                if (n < 0) throw IOException("Connection closed early")
                                read += n
                                chunk.filled.value = read
                                StreamProxy.meter.add(n)
                            }
                        }
                        return
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
                StreamLog.log("chunk $index attempt ${attempt + 1} failed: $e (connections ${connectionLimit})")
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
            bytes += chunk.filled.value
            if (!chunk.ready) break
            index++
        }
        (bytes - (readPosition - (readPosition / CHUNK) * CHUNK)).coerceAtLeast(0)
    }

    /**
     * Writes bytes [start]..[end] (inclusive) to [out], fetching ahead in parallel. Returns when done
     * or throws when the player hangs up (it does on every seek) or the host keeps failing.
     */
    fun serve(start: Long, end: Long, out: OutputStream, lowPriority: Boolean = false) {
        val reader = Reader(lowPriority).apply { chunk = (start / CHUNK).toInt() }
        readers += reader
        try {
            var position = start
            while (position <= end) {
                readPosition = position
                val index = (position / CHUNK).toInt()
                reader.chunk = index
                val chunk = request(index)
                // Read-ahead for this request, as far as its window allows
                for (k in index + 1 until minOf(index + reader.window, chunkCount)) request(k)
                val offset = (position - index * CHUNK).toInt()
                // Wait for at least one new byte, not the whole chunk
                val available = runBlocking {
                    withTimeoutOrNull(120_000) {
                        while (chunk.filled.value <= offset) {
                            if (chunk.result.isCompleted) {
                                chunk.result.await() // throws when the fetch failed
                                if (chunk.filled.value <= offset) throw IOException("Chunk ended early")
                            }
                            withTimeoutOrNull(250) { chunk.filled.first { it > offset } }
                        }
                        chunk.filled.value
                    }
                } ?: run {
                    StreamLog.log("reader at chunk $index waited 2 minutes; ${chunk.filled.value} of ${chunk.length} bytes")
                    throw IOException("The host sent nothing for two minutes")
                }
                val count = minOf((available - offset).toLong(), end - position + 1).toInt()
                out.write(chunk.bytes, offset, count)
                out.flush()
                position += count
                reader.delivered += count
            }
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
        val windows = readers.map { it.chunk to it.window }
        fun wanted(q: Int) = windows.any { (at, size) -> q >= at && q < at + size }
        queued.filter { !wanted(it) || chunks[it] == null }.forEach { q ->
            queued -= q
            chunks[q]?.takeIf { !it.result.isCompleted }?.let { chunks.remove(q); it.result.cancel() }
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
