package com.lagradost.cloudstream4.desktop

import com.lagradost.cloudstream4.desktop.player.Cenc
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** ClearKey decryption of CENC fragmented MP4, as live channels send it. */
class CencTest {
    private val kid = ByteArray(16) { (it + 1).toByte() }
    private val key = ByteArray(16) { (0x40 + it).toByte() }

    private fun box(type: String, vararg parts: ByteArray): ByteArray {
        val body = parts.fold(ByteArray(0)) { a, b -> a + b }
        return ByteBuffer.allocate(8).putInt(8 + body.size).put(type.toByteArray()).array() + body
    }
    private fun int(v: Int) = ByteBuffer.allocate(4).putInt(v).array()
    private fun short(v: Int) = byteArrayOf((v shr 8).toByte(), v.toByte())
    private fun full(version: Int, flags: Int) = byteArrayOf(version.toByte(), (flags shr 16).toByte(), (flags shr 8).toByte(), flags.toByte())

    private fun init(): ByteArray {
        val tenc = box("tenc", full(0, 0), byteArrayOf(0, 0, 1, 8), kid)
        val sinf = box("sinf", box("frma", "avc1".toByteArray()), box("schm", full(0, 0), "cenc".toByteArray(), int(0x10000)), box("schi", tenc))
        val encv = box("encv", ByteArray(78), box("avcC", byteArrayOf(1, 2, 3)), sinf)
        val stsd = box("stsd", full(0, 0), int(1), encv)
        val tkhd = box("tkhd", full(0, 3), int(0), int(0), int(1), ByteArray(68))
        val trak = box("trak", tkhd, box("mdia", box("minf", box("stbl", stsd))))
        val pssh = box("pssh", full(0, 0), ByteArray(16), int(0))
        return box("ftyp", "iso6".toByteArray(), int(0)) + box("moov", pssh, trak)
    }

    /** Two samples; the second has a clear header then encrypted bytes. Returns (fragment, plain samples). */
    private fun fragment(): Pair<ByteArray, List<ByteArray>> {
        val samples = listOf(Random(1).nextBytes(100), Random(2).nextBytes(300))
        val ivs = listOf(ByteArray(8) { 7 }, ByteArray(8) { 9 })
        val subsamples = listOf(listOf(0 to 100), listOf(20 to 280))
        val encrypted = samples.mapIndexed { i, s ->
            val out = s.copyOf()
            val c = Cipher.getInstance("AES/CTR/NoPadding").apply { init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(ivs[i].copyOf(16))) }
            var p = 0
            subsamples[i].forEach { (clear, enc) -> p += clear; c.update(out, p, enc, out, p); p += enc }
            out
        }
        val senc = box("senc", full(0, 2), int(2), *ivs.indices.flatMap { i ->
            listOf(ivs[i], short(subsamples[i].size)) + subsamples[i].map { (c, e) -> short(c) + int(e) }
        }.toTypedArray())
        fun traf(dataOffset: Int) = box("traf",
            box("tfhd", full(0, 0x20000), int(1)),
            box("trun", full(0, 0x201), int(2), int(dataOffset), int(samples[0].size), int(samples[1].size)),
            senc)
        // The data offset counts from the moof's start to the samples inside mdat
        val moofSize = box("moof", box("mfhd", full(0, 0), int(1)), traf(0)).size
        val moof = box("moof", box("mfhd", full(0, 0), int(1)), traf(moofSize + 8))
        val mdat = box("mdat", encrypted[0], encrypted[1])
        return (box("styp", "msdh".toByteArray(), int(0)) + moof + mdat) to samples
    }

    @Test
    fun decryptsClearKeyCencFragments() {
        val keys = Cenc.parseKeys(mapOf(java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(kid) to key.joinToString("") { "%02x".format(it) }))
        assertEquals(1, keys.size, "base64url key id and hex key are both understood")
        // A base64url key with '-' in it (Willow's): the dash is part of the key, not a separator
        val dashed = ByteArray(16) { 0xfb.toByte() }
        val b64 = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(dashed)
        assertTrue('-' in b64)
        assertContentEquals(dashed, Cenc.parseKeys(mapOf(b64 to b64)).values.single())
        // A hex key id written as a UUID
        assertEquals(1, Cenc.parseKeys(mapOf("F3DF7843-080A-E743-BF86-5DC5FDF64C68" to b64)).size)

        val init = init()
        Cenc.process("s", init, keys)
        val text = String(init, Charsets.ISO_8859_1)
        assertTrue("avc1" in text && "encv" !in text, "the sample entry has its format back")
        assertFalse("sinf" in text || "pssh" in text, "protection boxes are hidden")

        val (fragment, plain) = fragment()
        Cenc.process("s", fragment, keys)
        val mdat = ByteArrayOutputStream().apply { plain.forEach { write(it) } }.toByteArray()
        assertContentEquals(mdat, fragment.copyOfRange(fragment.size - mdat.size, fragment.size))
        Cenc.forget("s")
    }
}
