package com.lagradost.cloudstream4.desktop

import com.lagradost.cloudstream4.desktop.download.SegmentedDownload
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.Collections
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertTrue

/** Multi-connection downloads against a local server that limits speed per connection, as many hosts do. */
class SegmentedDownloadTest {
    private val size = 48 * 1024 * 1024
    private val data = Random(7).nextBytes(size)
    private val perConnectionBytesPerSecond = 4 * 1024 * 1024
    private val active = AtomicInteger()
    private val maxActive = AtomicInteger()
    private val requestedStarts = Collections.synchronizedList(mutableListOf<Long>())

    private fun server() = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0).apply {
        executor = Executors.newCachedThreadPool()
        createContext("/big.mkv") { ex ->
            val range = ex.requestHeaders.getFirst("Range")?.removePrefix("bytes=")
            val start = range?.substringBefore('-')?.toLong() ?: 0L
            val end = range?.substringAfter('-')?.takeIf { it.isNotEmpty() }?.toLong() ?: (size - 1L)
            if (range != null && end > 0) requestedStarts += start
            ex.responseHeaders.set("Content-Type", "video/x-matroska")
            ex.responseHeaders.set("Accept-Ranges", "bytes")
            if (range != null) ex.responseHeaders.set("Content-Range", "bytes $start-$end/$size")
            ex.sendResponseHeaders(if (range != null) 206 else 200, end - start + 1)
            val now = active.incrementAndGet()
            maxActive.accumulateAndGet(now) { a, b -> maxOf(a, b) }
            runCatching {
                ex.responseBody.use { out ->
                    var pos = start.toInt()
                    val step = 64 * 1024
                    while (pos <= end) {
                        val n = minOf(step, (end - pos + 1).toInt())
                        out.write(data, pos, n)
                        pos += n
                        // Throttle each connection
                        Thread.sleep(1000L * step / perConnectionBytesPerSecond)
                    }
                }
            }
            active.decrementAndGet()
        }
        start()
    }

    private val client = OkHttpClient.Builder().protocols(listOf(Protocol.HTTP_1_1)).build()

    @Test
    fun manyConnectionsAreFasterAndResumeSkipsFinishedParts() = runBlocking {
        val server = server()
        val url = "http://127.0.0.1:${server.address.port}/big.mkv"
        val file = File(Files.createTempDirectory("seg").toFile(), "big.mkv.part")
        try {
            val probe = SegmentedDownload.probe(client, url, emptyMap())
            assertTrue(probe.acceptsRanges)
            val total = probe.total!!
            val chunk = SegmentedDownload.chunkSizeFor(total)

            // Start, stop part way, then resume with the parts that finished
            val first = SegmentedDownload(client, url, emptyMap(), file, total, chunk, 8, emptyList())
            val job = async { runCatching { first.run() } }
            while (first.doneChunks.size < 2) delay(50)
            job.cancelAndJoin()
            val finished = first.doneChunks
            println("Stopped with ${finished.size} of ${first.chunkCount} parts done")

            requestedStarts.clear()
            val started = System.currentTimeMillis()
            val second = SegmentedDownload(client, url, emptyMap(), file, total, chunk, 8, finished)
            second.run()
            val seconds = (System.currentTimeMillis() - started) / 1000.0

            assertContentEquals(data, file.readBytes())
            // Finished parts are not fetched again
            assertTrue(finished.none { (it * chunk) in requestedStarts }, "refetched finished parts")
            assertTrue(maxActive.get() > 1, "only one connection was used")
            // One throttled connection would need size / rate seconds for the whole file
            val singleConnectionSeconds = size.toDouble() / perConnectionBytesPerSecond
            println("Resumed rest in %.1fs with up to %d connections, one connection would need %.0fs for all".format(seconds, maxActive.get(), singleConnectionSeconds))
            assertTrue(seconds < singleConnectionSeconds / 2, "not faster than a single connection")
        } finally {
            server.stop(0)
        }
    }
}
