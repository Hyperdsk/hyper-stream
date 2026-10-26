package com.lagradost.cloudstream4.desktop

import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream4.desktop.player.SubtitleFiles
import com.lagradost.cloudstream4.desktop.plugins.PluginLoader
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.nio.file.Files
import kotlin.test.Test

/**
 * Diagnostic: loads a real extension, asks it for a title's links, and tries every subtitle
 * it returns. Opt in: CLOUDSTREAM_PROBE_CS3=/path/to.cs3, CLOUDSTREAM_PROBE_API=provider name,
 * CLOUDSTREAM_PROBE_DATA=file with the loadLinks data.
 */
class SubtitleProbeTest {
    @Test
    fun probeSubtitles() {
        val cs3 = System.getenv("CLOUDSTREAM_PROBE_CS3")
        org.junit.Assume.assumeTrue("CLOUDSTREAM_PROBE_CS3 not set", cs3 != null)
        cs3!!
        System.setProperty("cloudstream.dataDir", Files.createTempDirectory("cs-probe").toString())
        LibrarySetup.init()
        PluginLoader.load(File(cs3))
        val api = APIHolder.allProviders.first { it.name == System.getenv("CLOUDSTREAM_PROBE_API") }
        val data = File(System.getenv("CLOUDSTREAM_PROBE_DATA")).readText()
        val subs = mutableListOf<SubtitleFile>()
        runBlocking {
            withTimeoutOrNull(300_000) { api.loadLinks(data, false, { synchronized(subs) { subs += it } }, { }) }
        }
        val out = File("/tmp/cs-subprobe.txt")
        out.writeText("${subs.size} subtitles\n")
        synchronized(subs) { subs.toList() }.forEach { sub ->
            val t = System.nanoTime()
            val result = runCatching { runBlocking { SubtitleFiles.fetch(sub) } }
            val ms = (System.nanoTime() - t) / 1_000_000
            out.appendText("${sub.lang} | ${sub.url} | headers=${sub.headers?.keys} | ${result.fold({ "OK ${it.extension} ${it.length()}B" }, { "FAIL ${it.javaClass.simpleName}: ${it.message}" })} | ${ms}ms\n")
        }
    }
}
