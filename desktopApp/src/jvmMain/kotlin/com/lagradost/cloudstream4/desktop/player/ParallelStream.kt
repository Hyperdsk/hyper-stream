package com.lagradost.cloudstream4.desktop.player

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.io.OutputStream

/** The host answered a Range request with the whole file, so it can only be streamed in one piece. */
internal class NoRangeSupport : IOException("The host doesn't support range requests.")

/**
 * Streams one video file over several connections at once, the way the multi-part downloader
 * downloads it.
 *
 * The file is split into fixed chunks. While the player reads, the chunks ahead of it are
 * fetched in parallel with HTTP Range requests, so playback isn't limited to what a single
 * connection gets (many hosts throttle each connection). Recent chunks stay in memory, so short
 * seeks back and the seek-bar thumbnails are served without fetching again.
 */
internal class ParallelStream(
    private val client: OkHttpClient,
    private val url: String,
    private val headers: Map<String, String>,
    val total: Long,
    val contentType: String?,
    connections: Int,
) {
    private val chunkCount = ((total + CHUNK - 1) / CHUNK).toInt()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val permits = Semaphore(connections.coerceAtLeast(1))

    /** Access-ordered, so the least recently read chunk is dropped first. */
    private val chunks = object : LinkedHashMap<Int, Deferred<ByteArray>>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, Deferred<ByteArray>>) = size > MAX_CACHED
    }

    private fun chunk(i: Int): Deferred<ByteArray> = synchronized(chunks) {
        chunks.getOrPut(i) { scope.async { permits.withPermit { fetch(i) } } }
    }

    private suspend fun fetch(i: Int): ByteArray {
        val start = i * CHUNK
        val length = minOf(CHUNK, total - start).toInt()
        var attempt = 0
        while (true) {
            try {
                val request = Request.Builder().url(url).apply {
                    headers.forEach { (k, v) -> header(k, v) }
                    header("Range", "bytes=$start-${start + length - 1}")
                }.build()
                StreamProxy.meter.open()
                try {
                client.newCall(request).execute().use { response ->
                    if (response.code == 200) throw NoRangeSupport()
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
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: NoRangeSupport) {
                throw e
            } catch (e: IOException) {
                if (++attempt >= 4) throw e
                // Hosts often refuse extra connections for a moment
                delay(500L * attempt)
            }
        }
    }

    /**
     * Writes bytes [start]..[end] (inclusive) to [out], fetching ahead in parallel. Returns when done
     * or throws when the player hangs up (it does on every seek) or the host keeps failing.
     */
    /** Where the player is reading, for [aheadBytes]. */
    @Volatile
    private var readPosition = 0L

    /** Bytes already fetched and waiting in memory past where the player is reading. */
    fun aheadBytes(): Long = synchronized(chunks) {
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

    fun serve(start: Long, end: Long, out: OutputStream) {
        var position = start
        while (position <= end) {
            readPosition = position
            val index = (position / CHUNK).toInt()
            // Keep the window ahead of the reader in flight
            for (k in index until minOf(index + WINDOW, chunkCount)) chunk(k)
            val bytes = try {
                runBlocking { withTimeout(60_000) { chunk(index).await() } }
            } catch (e: Throwable) {
                // Forget the failed chunk, so a retry fetches it again
                synchronized(chunks) { chunks.remove(index) }
                throw e
            }
            val offset = (position - index * CHUNK).toInt()
            val count = minOf(bytes.size - offset.toLong(), end - position + 1).toInt()
            out.write(bytes, offset, count)
            position += count
        }
        out.flush()
    }

    fun close() = scope.cancel()

    companion object {
        const val CHUNK = 2L shl 20
        /** Chunks fetched ahead of the reader: 32 MB. */
        const val WINDOW = 16
        /** Chunks kept in memory: 96 MB. */
        const val MAX_CACHED = 48
    }
}
