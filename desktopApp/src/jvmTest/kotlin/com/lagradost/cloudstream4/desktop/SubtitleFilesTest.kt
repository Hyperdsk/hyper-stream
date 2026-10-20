package com.lagradost.cloudstream4.desktop

import com.lagradost.cloudstream4.desktop.player.SubtitleFiles
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
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
}
