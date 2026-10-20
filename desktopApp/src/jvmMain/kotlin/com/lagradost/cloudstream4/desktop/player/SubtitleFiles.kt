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
    private val folder by lazy { File(AppDirs.root, "subtitles").apply { mkdirs() } }

    suspend fun fetch(sub: SubtitleFile): File = withContext(Dispatchers.IO) {
        val key = MessageDigest.getInstance("SHA-1").digest(sub.url.toByteArray()).joinToString("") { "%02x".format(it) }.take(16)
        folder.listFiles()?.firstOrNull { it.nameWithoutExtension == key }?.let { return@withContext it }

        val request = Request.Builder().url(sub.url).apply {
            header("User-Agent", USER_AGENT)
            sub.headers?.forEach { (k, v) -> header(k, v) }
        }.build()
        val raw = app.baseClient.newCall(request).execute().use { r ->
            if (!r.isSuccessful) throw IOException("The subtitle host answered with HTTP ${r.code}.")
            val bytes = r.body.bytes()
            if (bytes.size > 20 shl 20) throw IOException("The subtitle file is too large.")
            bytes
        }
        val bytes = unpack(raw)
        val ext = formatOf(bytes) ?: throw IOException("That isn't a subtitle file VLC can read.")
        File(folder, "$key.$ext").apply { writeBytes(bytes) }
    }

    /** Zip (first subtitle inside) and gzip are unpacked, anything else is returned as is. */
    internal fun unpack(bytes: ByteArray): ByteArray = when {
        bytes.size > 4 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte() ->
            ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
                generateSequence { zip.nextEntry }.firstOrNull { e -> !e.isDirectory && formatOf(e.name) != null }
                    ?.let { zip.readBytes() } ?: throw IOException("No subtitle inside the zip file.")
            }
        bytes.size > 2 && bytes[0] == 0x1f.toByte() && bytes[1] == 0x8b.toByte() ->
            GZIPInputStream(ByteArrayInputStream(bytes)).use { it.readBytes() }
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
