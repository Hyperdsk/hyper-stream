package com.lagradost.cloudstream4.desktop

import com.lagradost.cloudstream4.desktop.player.ParallelStream
import com.sun.net.httpserver.HttpServer
import okhttp3.OkHttpClient
import okhttp3.Protocol
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertTrue

/** Hosts that answer each Range request with only part of it, or with the wrong part. */
class ParallelStreamCappedHostTest {
    private val file = Random(11).nextBytes(6 * 1024 * 1024 + 12_345)
    private val cap = 256 * 1024
    private val requests = AtomicInteger()

    private val client = OkHttpClient.Builder()
        .protocols(listOf(Protocol.HTTP_1_1))
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private enum class Mode {
        /** A proper partial answer: only the first 256 KB of what was asked, and says so. */
        SHORT,
        /** Promises the whole range, sends 256 KB and hangs up. */
        HANG_UP,
        /** Always answers from the start of the file, whatever was asked. */
        WRONG_START,
    }

    private fun server(mode: Mode) = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0).apply {
        executor = Executors.newCachedThreadPool()
        createContext("/f") { ex ->
            requests.incrementAndGet()
            val spec = ex.requestHeaders.getFirst("Range")!!.removePrefix("bytes=")
            val start = spec.substringBefore('-').toInt()
            val end = spec.substringAfter('-').toInt()
            when (mode) {
                Mode.SHORT -> {
                    val last = minOf(end, start + cap - 1)
                    ex.responseHeaders.set("Content-Range", "bytes $start-$last/${file.size}")
                    ex.sendResponseHeaders(206, (last - start + 1).toLong())
                    ex.responseBody.use { it.write(file, start, last - start + 1) }
                }
                Mode.HANG_UP -> {
                    ex.responseHeaders.set("Content-Range", "bytes $start-$end/${file.size}")
                    ex.sendResponseHeaders(206, (end - start + 1).toLong())
                    runCatching { ex.responseBody.write(file, start, minOf(cap, end - start + 1)); ex.responseBody.flush() }
                    ex.close()
                }
                Mode.WRONG_START -> {
                    val length = end - start + 1
                    ex.responseHeaders.set("Content-Range", "bytes 0-${length - 1}/${file.size}")
                    ex.sendResponseHeaders(206, length.toLong())
                    ex.responseBody.use { it.write(file, 0, length) }
                }
            }
        }
        start()
    }

    private fun stream(server: HttpServer) =
        ParallelStream(client, "http://127.0.0.1:${server.address.port}/f", emptyMap(), file.size.toLong(), "video/mp4", 4)

    private fun streamsTheWholeFile(mode: Mode) {
        val server = server(mode)
        try {
            val stream = stream(server)
            val out = ByteArrayOutputStream()
            stream.serve(0, file.size - 1L, out)
            stream.close()
            assertContentEquals(file, out.toByteArray())
            // Each 2 MB chunk took several 256 KB answers
            assertTrue(requests.get() >= file.size / cap, "only ${requests.get()} requests")
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun aHostThatCapsEveryAnswerStillStreamsTheWholeFile() = streamsTheWholeFile(Mode.SHORT)

    @Test
    fun aHostThatHangsUpAfterEveryFewHundredKilobytesStillStreamsTheWholeFile() = streamsTheWholeFile(Mode.HANG_UP)

    @Test
    fun bytesFromTheWrongPlaceAreNeverServed() {
        val server = server(Mode.WRONG_START)
        try {
            val stream = stream(server)
            val out = ByteArrayOutputStream()
            val result = runCatching { stream.serve(0, file.size - 1L, out) }
            stream.close()
            val got = out.toByteArray()
            // The first chunk is right; the second can't be had, so the request fails there
            assertTrue(result.isFailure, "served ${got.size} bytes, some from the wrong place")
            assertContentEquals(file.copyOf(got.size), got)
        } finally {
            server.stop(0)
        }
    }
}
