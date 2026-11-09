package com.lagradost.cloudstream4.desktop

import com.lagradost.cloudstream4.desktop.player.SubtitleFiles
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class SubtitleFilesTest {
    private val srt = "1\n00:00:01,000 --> 00:00:02,000\nHello\n".toByteArray()

    @Test
    fun detectsFormatsFromContent() {
        assertEquals("srt", SubtitleFiles.formatOf(srt))
        assertEquals("srt", SubtitleFiles.formatOf("﻿1\r\n00:00:01.000 --> 00:00:02.000\r\nHi".toByteArray()))
        assertEquals("vtt", SubtitleFiles.formatOf("WEBVTT\n\n00:01.000 --> 00:02.000\nHi".toByteArray()))
        assertEquals("ass", SubtitleFiles.formatOf("[Script Info]\nTitle: x".toByteArray()))
        assertNull(SubtitleFiles.formatOf("<!doctype html><html>blocked</html>".toByteArray()))
    }

    @Test
    fun unpacksZipAndGzip() {
        val zip = ByteArrayOutputStream().also { out ->
            ZipOutputStream(out).use { z ->
                z.putNextEntry(ZipEntry("readme.txt")); z.write("x".toByteArray()); z.closeEntry()
                z.putNextEntry(ZipEntry("movie.en.srt")); z.write(srt); z.closeEntry()
            }
        }.toByteArray()
        assertContentEquals(srt, SubtitleFiles.unpack(zip))
        val gz = ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(srt) } }.toByteArray()
        assertContentEquals(srt, SubtitleFiles.unpack(gz))
        assertContentEquals(srt, SubtitleFiles.unpack(srt))
    }

    @Test
    fun aSmallFileThatUnpacksHugeIsRefused() {
        // About 40 KB packed, 50 MB unpacked
        val bomb = ByteArrayOutputStream().also { out ->
            GZIPOutputStream(out).use { gz -> val zeros = ByteArray(1 shl 20); repeat(50) { gz.write(zeros) } }
        }.toByteArray()
        assertFailsWith<java.io.IOException> { SubtitleFiles.unpack(bomb) }
    }

    @Test
    fun readsUtf16Subtitles() {
        val text = "1\n00:00:01,000 --> 00:00:02,000\nGrüße\n"
        listOf(Charsets.UTF_16LE to byteArrayOf(0xFF.toByte(), 0xFE.toByte()), Charsets.UTF_16BE to byteArrayOf(0xFE.toByte(), 0xFF.toByte()))
            .forEach { (charset, bom) ->
                val utf8 = SubtitleFiles.utf8(bom + text.toByteArray(charset))
                assertEquals("srt", SubtitleFiles.formatOf(utf8))
                assertEquals(text, String(utf8, Charsets.UTF_8).removePrefix("\uFEFF"))
            }
        // UTF-8 is left as it is
        assertContentEquals(srt, SubtitleFiles.utf8(srt))
    }
}
