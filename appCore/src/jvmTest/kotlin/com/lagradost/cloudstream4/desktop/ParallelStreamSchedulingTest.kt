package com.lagradost.cloudstream4.desktop

import com.lagradost.cloudstream4.desktop.player.ParallelStream
import com.sun.net.httpserver.HttpServer
import okhttp3.OkHttpClient
import okhttp3.Protocol
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertTrue

/** How ParallelStream schedules connections: seeks first, stale read-ahead dropped, backing off when refused. */
class ParallelStreamSchedulingTest {
    private val file = Random(3).nextBytes(48 * 1024 * 1024)
    private val active = AtomicInteger()
    private val served = AtomicInteger()
    private val refused = AtomicInteger()

    private val client = OkHttpClient.Builder()
        .protocols(listOf(Protocol.HTTP_1_1))
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    /**
     * [bytesPerSecond] per connection. With [maxConnections], extra connections get 429,
     * like hosts that limit how many a client may open.
     */
    private fun server(bytesPerSecond: Int, maxConnections: Int = Int.MAX_VALUE) =
        HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0).apply {
            executor = Executors.newCachedThreadPool()
            createContext("/f") { ex ->
                if (active.incrementAndGet() > maxConnections) {
                    active.decrementAndGet()
                    refused.incrementAndGet()
                    ex.sendResponseHeaders(429, -1)
                    ex.close()
                    return@createContext
                }
                served.accumulateAndGet(active.get()) { a, b -> maxOf(a, b) }
                try {
                    val spec = ex.requestHeaders.getFirst("Range")!!.removePrefix("bytes=")
                    val start = spec.substringBefore('-').toLong()
                    val end = spec.substringAfter('-').toLong()
                    ex.responseHeaders.set("Content-Range", "bytes $start-$end/${file.size}")
                    ex.sendResponseHeaders(206, end - start + 1)
                    runCatching {
                        ex.responseBody.use { out ->
                            var pos = start.toInt()
                            val step = bytesPerSecond / 20
                            while (pos <= end) {
                                val n = minOf(step, (end - pos + 1).toInt())
                                out.write(file, pos, n)
                                out.flush()
                                pos += n
                                Thread.sleep(50)
                            }
                        }
                    }
                } finally {
                    active.decrementAndGet()
                }
            }
            start()
        }

    /** Like VLC hanging up: fails after the first write. */
    private class HangUp : OutputStream() {
        var wrote = false
        override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)
        override fun write(b: ByteArray, off: Int, len: Int) {
            if (wrote) throw IOException("player hung up")
            wrote = true
        }
    }

    @Test
    fun seekIsServedBeforeAbandonedReadAhead() {
        val server = server(bytesPerSecond = 512 * 1024)
        try {
            val stream = ParallelStream(client, "http://127.0.0.1:${server.address.port}/f", emptyMap(), file.size.toLong(), "video/mp4", 16)
            // The player starts at the beginning, then jumps away (as VLC does to read the index)
            runCatching { stream.serve(0, file.size - 1L, HangUp()) }
            val seekTo = 40L * 1024 * 1024
            val out = ByteArrayOutputStream()
            val t = System.nanoTime()
            stream.serve(seekTo, seekTo + 100_000, out)
            val ms = (System.nanoTime() - t) / 1_000_000
            println("Seek answered in $ms ms (one 2 MB chunk at 512 KB/s takes about 4000 ms)")
            assertContentEquals(file.copyOfRange(seekTo.toInt(), seekTo.toInt() + 100_001), out.toByteArray())
            // Behind abandoned read-ahead it would wait for a whole chunk first: about twice as long
            assertTrue(ms < 6_500, "seek took $ms ms")
            stream.close()
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun backsOffWhenTheHostRefusesConnections() {
        val server = server(bytesPerSecond = 8 * 1024 * 1024, maxConnections = 3)
        try {
            val stream = ParallelStream(client, "http://127.0.0.1:${server.address.port}/f", emptyMap(), file.size.toLong(), "video/mp4", 16)
            val out = ByteArrayOutputStream()
            stream.serve(0, file.size - 1L, out)
            println("Refused ${refused.get()} requests, connection limit ended at ${stream.connectionLimit}, served at most ${served.get()} at once")
            assertContentEquals(file, out.toByteArray())
            assertTrue(stream.connectionLimit <= 4, "limit should have come down, is ${stream.connectionLimit}")
            stream.close()
        } finally {
            server.stop(0)
        }
    }
}
