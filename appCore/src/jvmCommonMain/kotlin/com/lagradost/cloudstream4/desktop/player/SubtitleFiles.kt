package com.lagradost.cloudstream4.desktop.player

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream4.desktop.data.AppDirs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.zip.GZIPInputStream
import java.util.zip.ZipInputStream

/**
 * Downloads one provider subtitle to a local file VLC can read.
 *
 * Handing VLC the remote subtitle urls makes it fetch every one of them, one after another,
 * before the video starts: with the dozens some providers list, playback sat on "Loading
 * video" for minutes. So subtitles load only when picked, and here, not in VLC. The format is
 * detected from the content (many subtitle urls have no extension), and zip or gzip files are
 * unpacked.
 */
object SubtitleFiles {
    /** Downloaded subtitles not used for this long are deleted, so the folder doesn't grow for ever. */
    private const val KEEP_DAYS = 30L
    /** The most a subtitle may be, packed or unpacked. */
    private const val MAX_BYTES = 20 * 1024 * 1024

    private val folder by lazy {
        File(AppDirs.root, "subtitles").apply {
            mkdirs()
            // Once a run: subtitles not used for a month, and anything a crash left half written
            val old = System.currentTimeMillis() - KEEP_DAYS * 24 * 60 * 60 * 1000
            listFiles()?.filter { it.name.endsWith(".tmp") || it.lastModified() < old }?.forEach { it.delete() }
        }
    }

    /** Short limits: a subtitle host that hangs shouldn't keep the list waiting. */
    private val client by lazy {
        app.baseClient.newBuilder().callTimeout(15, java.util.concurrent.TimeUnit.SECONDS).build()
    }

    private class HostAnswered(val code: Int) : IOException(
        if (code >= 500) "The subtitle site is having problems (HTTP $code)." else "The subtitle site refused it (HTTP $code)."
    )

    /** A plain reason for a failed download. */
    private fun reason(e: IOException): String = when {
        e is java.net.SocketTimeoutException || e is java.io.InterruptedIOException -> "The subtitle site didn't answer."
        e is javax.net.ssl.SSLException -> "Couldn't connect securely to the subtitle site."
        e is java.net.UnknownHostException -> "The subtitle site can't be found."
        e.message?.contains("reset", ignoreCase = true) == true -> "The subtitle site dropped the connection."
        else -> e.message ?: "Couldn't download it."
    }

    /** The site a subtitle comes from, to show in the list. */
    fun host(sub: SubtitleFile): String = runCatching { java.net.URI(sub.url).host.removePrefix("www.") }.getOrNull() ?: ""

    suspend fun fetch(sub: SubtitleFile): File = withContext(Dispatchers.IO) {
        val key = MessageDigest.getInstance("SHA-1").digest(sub.url.toByteArray()).joinToString("") { "%02x".format(it) }.take(16)
        folder.listFiles()?.firstOrNull { it.nameWithoutExtension == key && it.extension != "tmp" }?.let { cached ->
            // Still a subtitle (one cut short by an older version isn't): used now, so it's kept
            if (formatOf(cached.readBytes()) != null) {
                cached.setLastModified(System.currentTimeMillis())
                return@withContext cached
            }
            cached.delete()
        }

        val request = Request.Builder().url(sub.url).apply {
            header("User-Agent", USER_AGENT)
            sub.headers?.forEach { (k, v) -> header(k, v) }
        }.build()
        // One retry for blips (reset connection, failed handshake, timeout); not for answers like 404
        var attempt = 0
        val raw = run {
            while (true) {
                try {
                    return@run client.newCall(request).execute().use { r ->
                        if (!r.isSuccessful) throw HostAnswered(r.code)
                        val bytes = r.body.bytes()
                        if (bytes.size > MAX_BYTES) throw IOException("The file is too large.")
                        bytes
                    }
                } catch (e: HostAnswered) {
                    if (e.code < 500 || ++attempt >= 2) throw IOException(e.message)
                } catch (e: IOException) {
                    if (++attempt >= 2) throw IOException(reason(e), e)
                }
            }
            @Suppress("UNREACHABLE_CODE") ByteArray(0)
        }
        val bytes = utf8(unpack(raw))
        val ext = formatOf(bytes) ?: throw IOException("That isn't a subtitle file VLC can read.")
        // Written whole or not at all: a file cut short would be taken from the cache every time
        val target = File(folder, "$key.$ext")
        val tmp = File(folder, "$key.${System.nanoTime()}.tmp")
        try {
            tmp.writeBytes(bytes)
            java.nio.file.Files.move(tmp.toPath(), target.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        } finally {
            tmp.delete()
        }
        target
    }

    /** UTF-16 subtitles (they start with a byte order mark) as UTF-8, which is what's read; anything else as is. */
    internal fun utf8(bytes: ByteArray): ByteArray {
        val utf16 = bytes.size >= 2 && ((bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) || (bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()))
        return if (utf16) String(bytes, Charsets.UTF_16).toByteArray(Charsets.UTF_8) else bytes
    }

    /** All of [input], refusing more than [MAX_BYTES]: a small packed file can unpack to gigabytes. */
    private fun readLimited(input: java.io.InputStream): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            out.write(buffer, 0, read)
            if (out.size() > MAX_BYTES) throw IOException("The subtitle file is too large once unpacked.")
        }
        return out.toByteArray()
    }

    /** Zip (first subtitle inside) and gzip are unpacked, anything else is returned as is. */
    internal fun unpack(bytes: ByteArray): ByteArray = when {
        bytes.size > 4 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte() ->
            ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
                generateSequence { zip.nextEntry }.firstOrNull { e -> !e.isDirectory && formatOf(e.name) != null }
                    ?.let { readLimited(zip) } ?: throw IOException("No subtitle inside the zip file.")
            }
        bytes.size > 2 && bytes[0] == 0x1f.toByte() && bytes[1] == 0x8b.toByte() ->
            GZIPInputStream(ByteArrayInputStream(bytes)).use { readLimited(it) }
        else -> bytes
    }

    private fun formatOf(name: String): String? =
        name.substringAfterLast('.', "").lowercase().takeIf { it in setOf("srt", "vtt", "ass", "ssa", "sub") }

    /** srt, vtt or ass from the text itself, or null when it looks like something else (a web page). */
    internal fun formatOf(bytes: ByteArray): String? {
        val head = String(bytes, 0, minOf(bytes.size, 4096), Charsets.UTF_8).trimStart('\uFEFF', ' ', '\n', '\r', '\t')
        return when {
            head.startsWith("WEBVTT") -> "vtt"
            head.startsWith("[Script Info]", ignoreCase = true) -> "ass"
            head.startsWith("<") -> null
            Regex("""\d{1,2}:\d{2}:\d{2}[,.]\d{1,3}\s*-->""").containsMatchIn(head) -> "srt"
            else -> null
        }
    }
}
