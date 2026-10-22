package com.lagradost.cloudstream4.desktop.download

import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import kotlin.math.ceil

/**
 * A downloaded HLS stream whose audio is in its own playlist, as many sites serve them. There is
 * no muxer on either platform, so the tracks stay apart: each track's segments are joined into
 * one file, and local playlists address every segment by byte range. Both VLC and ExoPlayer
 * play the folder's master.m3u8 offline, with seeking.
 *
 * Folder layout: `video.ts`, `audio.ts` (or .aac, .mp4), an optional `.init` file for fragmented
 * MP4 streams, an `.idx` file per track listing each segment's duration and size (so an
 * interrupted download resumes), and the playlists.
 */
internal class HlsTrack(private val dir: File, private val name: String) {
    data class Segment(val duration: Double, val length: Long)

    private val index = File(dir, "$name.idx")
    private val initFile = File(dir, "$name.init")
    val segments = mutableListOf<Segment>()
    private var data: File? = null

    val bytes: Long get() = segments.sumOf { it.length }
    val duration: Double get() = segments.sumOf { it.duration }

    /** Picks up a previous run: keeps the segments its index lists and cuts off anything written after. */
    fun load() {
        segments.clear()
        data = dir.listFiles()?.firstOrNull {
            it.name.startsWith("$name.") && it.extension !in setOf("idx", "m3u8", "init")
        }
        if (index.exists()) {
            index.readLines().mapNotNullTo(segments) { line ->
                val (d, l) = line.split(' ').takeIf { it.size == 2 } ?: return@mapNotNullTo null
                Segment(d.toDoubleOrNull() ?: return@mapNotNullTo null, l.toLongOrNull() ?: return@mapNotNullTo null)
            }
        }
        val file = data
        if (file == null) {
            segments.clear()
            index.delete()
        } else {
            RandomAccessFile(file, "rw").use { it.setLength(bytes) }
        }
    }

    /** Saves the fMP4 initialization segment, without which its media segments can't be decoded. */
    fun saveInit(chunk: ByteArray) {
        if (chunk.isNotEmpty()) initFile.writeBytes(chunk)
    }

    fun append(chunk: ByteArray, duration: Double) {
        val file = data ?: File(dir, "$name.${extensionOf(chunk)}").also { data = it }
        FileOutputStream(file, true).use { it.write(chunk) }
        // The index is written after the data, so a crash in between only leaves bytes that load() cuts off
        index.appendText("$duration ${chunk.size}\n")
        segments += Segment(duration, chunk.size.toLong())
    }

    /** Writes `name.m3u8`, every segment a byte range of the track file. */
    fun writePlaylist(): String {
        val file = checkNotNull(data) { "No $name data was downloaded." }
        val hasInit = initFile.exists() && initFile.length() > 0
        val text = buildString {
            appendLine("#EXTM3U")
            // EXT-X-MAP was added in version 5; keep older TS playlists at version 4
            appendLine("#EXT-X-VERSION:${if (hasInit) 6 else 4}")
            appendLine("#EXT-X-PLAYLIST-TYPE:VOD")
            appendLine("#EXT-X-TARGETDURATION:${ceil(segments.maxOfOrNull { it.duration } ?: 10.0).toInt().coerceAtLeast(1)}")
            appendLine("#EXT-X-MEDIA-SEQUENCE:0")
            if (hasInit) appendLine("#EXT-X-MAP:URI=\"${initFile.name}\"")
            var offset = 0L
            for (s in segments) {
                appendLine("#EXTINF:${s.duration},")
                appendLine("#EXT-X-BYTERANGE:${s.length}@$offset")
                appendLine(file.name)
                offset += s.length
            }
            appendLine("#EXT-X-ENDLIST")
        }
        File(dir, "$name.m3u8").writeText(text)
        return "$name.m3u8"
    }

    private fun extensionOf(chunk: ByteArray): String = when {
        // MPEG-TS packets start with the sync byte 0x47
        chunk.isNotEmpty() && chunk[0] == 0x47.toByte() -> "ts"
        // ADTS AAC frames start with 0xFFF
        chunk.size > 1 && chunk[0] == 0xFF.toByte() && (chunk[1].toInt() and 0xF0) == 0xF0 -> "aac"
        else -> "mp4"
    }
}

internal object HlsPackage {
    const val MASTER = "master.m3u8"

    /** The folder a split-track download is saved in, next to where a single file would go. */
    fun folderFor(base: File) = File(base.path + ".hls")

    fun isPackage(file: File) = file.name == MASTER && file.parentFile?.name?.endsWith(".hls") == true

    /** Writes the master playlist that pairs the video with its audio. Returns the master file. */
    fun writeMaster(dir: File, video: HlsTrack, audio: HlsTrack, audioName: String, audioLanguage: String?, width: Int?, height: Int?): File {
        val videoPlaylist = video.writePlaylist()
        val audioPlaylist = audio.writePlaylist()
        val seconds = video.duration.coerceAtLeast(1.0)
        val bandwidth = ((video.bytes + audio.bytes) * 8 / seconds).toLong().coerceAtLeast(1)
        fun quoted(s: String) = s.replace("\"", "'")
        val text = buildString {
            appendLine("#EXTM3U")
            appendLine("#EXT-X-VERSION:4")
            append("#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID=\"audio\",NAME=\"${quoted(audioName)}\"")
            if (!audioLanguage.isNullOrBlank()) append(",LANGUAGE=\"${quoted(audioLanguage)}\"")
            appendLine(",DEFAULT=YES,AUTOSELECT=YES,URI=\"$audioPlaylist\"")
            append("#EXT-X-STREAM-INF:BANDWIDTH=$bandwidth")
            if (width != null && height != null && width > 0 && height > 0) append(",RESOLUTION=${width}x$height")
            appendLine(",AUDIO=\"audio\"")
            appendLine(videoPlaylist)
        }
        return File(dir, MASTER).apply { writeText(text) }
    }
}
