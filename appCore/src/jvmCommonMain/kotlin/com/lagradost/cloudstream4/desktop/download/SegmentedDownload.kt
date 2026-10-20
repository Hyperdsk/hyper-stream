package com.lagradost.cloudstream4.desktop.download

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.coroutineContext

/** What a host says about a file before downloading it. */
data class Probe(val total: Long?, val acceptsRanges: Boolean, val contentType: String?)

/** The host ignored a Range request, so the file can only be downloaded in one piece. */
class RangesNotSupported : IOException("The host doesn't support downloading in parts.")

/**
 * Downloads one file over several connections at once, like Internet Download Manager.
 *
 * The file is split into fixed-size chunks. [connections] workers each take the next chunk that
 * isn't done, fetch it with an HTTP Range request and write it straight into its place in the
 * file. Finished chunks are reported, so a paused download resumes without fetching them again.
 * Each chunk is retried a few times, so one dropped connection doesn't fail the download.
 */
internal class SegmentedDownload(
    private val client: OkHttpClient,
    private val url: String,
    private val headers: Map<String, String>,
    private val file: File,
    private val total: Long,
    val chunkSize: Long,
    private val connections: Int,
    alreadyDone: Collection<Int>,
) {
    val chunkCount: Int = ((total + chunkSize - 1) / chunkSize).toInt()
    private val done: MutableSet<Int> = ConcurrentHashMap.newKeySet<Int>().apply { addAll(alreadyDone.filter { it in 0 until chunkCount }) }
    private val inFlight = AtomicLong(0)
    private val next = AtomicInteger(0)
    private val active = AtomicInteger(0)

    /** Bytes on disk that count: finished chunks plus what running chunks have written so far. */
    val bytesDone: Long get() = done.sumOf { chunkLength(it) } + inFlight.get()
    val doneChunks: List<Int> get() = done.sorted()
    val activeConnections: Int get() = active.get()

    private fun chunkStart(i: Int) = i * chunkSize
    private fun chunkLength(i: Int) = minOf(chunkSize, total - chunkStart(i))

    suspend fun run() = withContext(Dispatchers.IO) {
        // Full size up front, so every connection can write at its own offset
        RandomAccessFile(file, "rw").use { if (it.length() != total) it.setLength(total) }
        FileChannel.open(file.toPath(), StandardOpenOption.WRITE).use { channel ->
            coroutineScope {
                repeat(connections.coerceIn(1, chunkCount)) {
                    launch { worker(channel) }
                }
            }
        }
        if (done.size != chunkCount) throw IOException("Download stopped before all parts arrived.")
    }

    private suspend fun worker(channel: FileChannel) {
        active.incrementAndGet()
        try {
            while (coroutineContext.isActive) {
                val chunk = nextChunk() ?: return
                var attempt = 0
                while (true) {
                    try {
                        fetch(channel, chunk)
                        done += chunk
                        break
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: RangesNotSupported) {
                        throw e
                    } catch (e: IOException) {
                        if (++attempt >= 5) throw IOException("Part ${chunk + 1} of $chunkCount kept failing: ${e.message}", e)
                        // Back off, hosts often refuse extra connections for a moment
                        delay(1_000L * attempt)
                    }
                }
            }
        } finally {
            active.decrementAndGet()
        }
    }

    private fun nextChunk(): Int? {
        while (true) {
            val i = next.getAndIncrement()
            if (i >= chunkCount) return null
            if (i !in done) return i
        }
    }

    private suspend fun fetch(channel: FileChannel, chunk: Int) {
        val start = chunkStart(chunk)
        val end = start + chunkLength(chunk) - 1
        val request = Request.Builder().url(url).apply {
            headers.forEach { (k, v) -> header(k, v) }
            header("Range", "bytes=$start-$end")
        }.build()
        var written = 0L
        try {
            client.newCall(request).execute().use { response ->
                if (response.code == 200) throw RangesNotSupported()
                if (response.code != 206) throw IOException("HTTP ${response.code}")
                val buffer = ByteArray(128 * 1024)
                response.body.byteStream().use { input ->
                    var position = start
                    while (position <= end) {
                        coroutineContext.ensureActive()
                        val read = input.read(buffer, 0, minOf(buffer.size.toLong(), end - position + 1).toInt())
                        if (read < 0) break
                        val bb = ByteBuffer.wrap(buffer, 0, read)
                        while (bb.hasRemaining()) channel.write(bb, position + bb.position())
                        position += read
                        written += read
                        inFlight.addAndGet(read.toLong())
                    }
                    if (position <= end) throw IOException("Connection closed early")
                }
            }
        } finally {
            // Either the chunk is now counted in done, or it will be fetched again from its start
            inFlight.addAndGet(-written)
        }
    }

    companion object {
        /** Chunks of at least 8 MB, and no more than 4096 of them. */
        fun chunkSizeFor(total: Long): Long = maxOf(8L shl 20, (total + 4095) / 4096)

        /** Asks for the first byte only, to learn the size and whether ranges work. */
        fun probe(client: OkHttpClient, url: String, headers: Map<String, String>): Probe {
            val request = Request.Builder().url(url).apply {
                headers.forEach { (k, v) -> header(k, v) }
                header("Range", "bytes=0-0")
            }.build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IOException("The host answered with HTTP ${response.code}.")
                val type = response.header("Content-Type")
                return if (response.code == 206) {
                    Probe(response.header("Content-Range")?.substringAfter('/')?.toLongOrNull(), true, type)
                } else {
                    Probe(response.body.contentLength().takeIf { it > 0 }, false, type)
                }
            }
        }
    }
}
