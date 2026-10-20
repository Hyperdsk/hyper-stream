package com.lagradost.cloudstream4.desktop.player

import com.lagradost.cloudstream4.desktop.data.AppDirs
import java.io.File
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/**
 * A small log of what the stream proxy does: how the host answers, what the player asks for,
 * failures and connection changes. Kept in the app data folder (logs/stream.log, about 1 MB,
 * the previous one in stream.log.1), so a stream that won't start can be diagnosed afterwards.
 * Urls are shortened to their host, so it holds no tokens.
 */
object StreamLog : RotatingLog("stream.log")

/** VLC's own warnings and errors plus player events, in logs/vlc.log. */
object VlcLog : RotatingLog("vlc.log")

open class RotatingLog(name: String) {
    private val file by lazy { File(AppDirs.root, "logs").apply { mkdirs() }.resolve(name) }
    private val time = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")

    @Synchronized
    fun log(message: String) {
        runCatching {
            if (file.length() > 1_000_000) file.renameTo(File(file.path + ".1"))
            file.appendText("${LocalTime.now().format(time)} $message\n")
        }
    }

    fun host(url: String): String = runCatching { java.net.URI(url).host }.getOrNull() ?: "?"
}
