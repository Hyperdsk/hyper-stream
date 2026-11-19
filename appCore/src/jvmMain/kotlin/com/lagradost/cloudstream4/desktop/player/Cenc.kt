package com.lagradost.cloudstream4.desktop.player

import java.nio.ByteBuffer
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Decrypts Common Encryption (ISO 23001-7) MP4 segments with ClearKey keys, which VLC can't do.
 * Extensions give the keys for live channels (DrmExtractorLink); on Android ExoPlayer would use them.
 *
 * Everything happens in place, so no box changes size and every offset stays valid:
 * - Init segments: the encrypted sample entry (encv, enca) gets its original format back, and the
 *   protection boxes (sinf, pssh) become 'free' boxes, which players skip.
 * - Media segments: each sample in mdat is decrypted with its IV and subsample ranges from senc.
 *   Schemes 'cenc' (AES-CTR) and 'cbcs' (AES-CBC with a crypt:skip block pattern).
 */
internal object Cenc {
    /** What an init segment says about one encrypted track. */
    private class Track(val kid: String, val scheme: String, val ivSize: Int, val constantIv: ByteArray?, val crypt: Int, val skip: Int)

    /** Per session: track id to its encryption, from the init segment the player fetched first. */
    private val tracks = ConcurrentHashMap<String, MutableMap<Int, Track>>()

    private val piffSenc = hex("a2394f525a9b4f14a2446c427c648df4")

    /** Key ids and keys as hex (with or without dashes) or base64/base64url, to hex key id -> key bytes. */
    fun parseKeys(keys: Map<String, String>): Map<String, ByteArray> = keys.entries.mapNotNull { (kid, key) ->
        val k = decode(kid) ?: return@mapNotNull null
        val v = decode(key)?.takeIf { it.size == 16 } ?: return@mapNotNull null
        k.toHex() to v
    }.toMap()

    private fun decode(s: String): ByteArray? {
        val t = s.trim()
        // Dashes only belong to hex ids written as UUIDs; in base64url '-' is a real character
        val hexId = t.replace("-", "")
        if (hexId.length == 32 && hexId.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) return hex(hexId)
        return runCatching { Base64.getUrlDecoder().decode(t.replace('+', '-').replace('/', '_').trimEnd('=')) }.getOrNull()
    }

    fun forget(session: String) { tracks.remove(session) }
    fun clear() = tracks.clear()

    /** Whether [bytes] start like an MP4 file or fragment. */
    fun looksLikeMp4(bytes: ByteArray): Boolean =
        bytes.size >= 8 && String(bytes, 4, 4, Charsets.ISO_8859_1) in setOf("ftyp", "styp", "moov", "moof", "sidx", "emsg", "prft", "free")

    /** Decrypts [bytes] in place with [keys]. Bytes that aren't an encrypted MP4 are left as they are. */
    fun process(session: String, bytes: ByteArray, keys: Map<String, ByteArray>) {
        if (!looksLikeMp4(bytes)) return
        val known = tracks.getOrPut(session) { ConcurrentHashMap() }
        for (box in boxes(bytes, 0, bytes.size)) {
            when (box.type) {
                "moov" -> readInit(bytes, box, known)
                "pssh" -> rename(bytes, box, "free")
            }
        }
        // Each moof is followed by its mdat
        val top = boxes(bytes, 0, bytes.size)
        var decrypted = 0
        top.forEach { if (it.type == "moof") decrypted += decryptFragment(bytes, it, known, keys) }
        // A fragment left encrypted plays as grey noise: say why
        if (decrypted == 0 && top.any { it.type == "moof" }) {
            StreamLog.log("cenc: fragment not decrypted (tracks known ${known.keys}, keys ${keys.size})")
        }
    }

    private class Box(val type: String, val start: Int, val headerSize: Int, val end: Int) {
        val body get() = start + headerSize
    }

    private fun boxes(b: ByteArray, from: Int, to: Int): List<Box> {
        val out = mutableListOf<Box>()
        var p = from
        while (p + 8 <= to) {
            var size = u32(b, p)
            val type = String(b, p + 4, 4, Charsets.ISO_8859_1)
            var header = 8
            if (size == 1L) { if (p + 16 > to) break; size = u64(b, p + 8); header = 16 }
            if (size == 0L) size = (to - p).toLong()
            if (size < header || p + size > to) break
            out += Box(type, p, header, (p + size).toInt())
            p += size.toInt()
        }
        return out
    }

    private fun child(b: ByteArray, box: Box, type: String, skip: Int = 0) = boxes(b, box.body + skip, box.end).firstOrNull { it.type == type }
    private fun children(b: ByteArray, box: Box, type: String, skip: Int = 0) = boxes(b, box.body + skip, box.end).filter { it.type == type }

    private fun rename(b: ByteArray, box: Box, type: String) = type.toByteArray(Charsets.ISO_8859_1).copyInto(b, box.start + 4)

    private fun readInit(b: ByteArray, moov: Box, known: MutableMap<Int, Track>) {
        children(b, moov, "pssh").forEach { rename(b, it, "free") }
        for (trak in children(b, moov, "trak")) {
            val tkhd = child(b, trak, "tkhd") ?: continue
            val trackId = u32(b, tkhd.body + if (b[tkhd.body].toInt() == 1) 20 else 12).toInt()
            val stsd = child(b, trak, "mdia")?.let { child(b, it, "minf") }?.let { child(b, it, "stbl") }?.let { child(b, it, "stsd") } ?: continue
            // stsd: version and flags, entry count, then the sample entries
            for (entry in boxes(b, stsd.body + 8, stsd.end)) {
                if (entry.type != "encv" && entry.type != "enca") continue
                // Sample entry fields before its child boxes: 78 bytes for video, 28 for audio
                val sinf = child(b, entry, "sinf", if (entry.type == "encv") 78 else 28) ?: continue
                val frma = child(b, sinf, "frma") ?: continue
                val scheme = child(b, sinf, "schm")?.let { String(b, it.body + 4, 4, Charsets.ISO_8859_1) } ?: "cenc"
                val tenc = child(b, sinf, "schi")?.let { child(b, it, "tenc") } ?: continue
                val version = b[tenc.body].toInt()
                val pattern = if (version > 0) b[tenc.body + 5].toInt() and 0xff else 0
                val ivSize = b[tenc.body + 7].toInt() and 0xff
                val kid = b.copyOfRange(tenc.body + 8, tenc.body + 24).toHex()
                val constantIv = if (ivSize == 0) {
                    val n = b[tenc.body + 24].toInt() and 0xff
                    b.copyOfRange(tenc.body + 25, tenc.body + 25 + n)
                } else null
                known[trackId] = Track(kid, scheme, ivSize, constantIv, pattern shr 4, pattern and 0xf)
                // Back to the original format, protection hidden from the player
                b.copyInto(b, entry.start + 4, frma.body, frma.body + 4)
                rename(b, sinf, "free")
            }
        }
    }

    /** Decrypts the samples of one moof's track fragments; returns how many. */
    private fun decryptFragment(b: ByteArray, moof: Box, known: Map<Int, Track>, keys: Map<String, ByteArray>): Int {
        var done = 0
        children(b, moof, "pssh").forEach { rename(b, it, "free") }
        for (traf in children(b, moof, "traf")) {
            val tfhd = child(b, traf, "tfhd") ?: continue
            val tfFlags = u24(b, tfhd.body + 1)
            val trackId = u32(b, tfhd.body + 4).toInt()
            val track = known[trackId] ?: continue
            val key = keys[track.kid] ?: keys.values.singleOrNull() ?: continue
            var p = tfhd.body + 8
            val base = if (tfFlags and 0x1 != 0) u64(b, p).toInt().also { p += 8 } else moof.start
            if (tfFlags and 0x2 != 0) p += 4
            if (tfFlags and 0x8 != 0) p += 4
            val defaultSize = if (tfFlags and 0x10 != 0) u32(b, p).toInt() else 0

            // Where each sample is, from the track runs
            val samples = mutableListOf<Pair<Int, Int>>()
            for (trun in children(b, traf, "trun")) {
                val flags = u24(b, trun.body + 1)
                val count = u32(b, trun.body + 4).toInt()
                var q = trun.body + 8
                var offset = base + if (flags and 0x1 != 0) u32(b, q).toInt().also { q += 4 } else 0
                if (flags and 0x4 != 0) q += 4
                repeat(count) {
                    if (flags and 0x100 != 0) q += 4
                    val size = if (flags and 0x200 != 0) u32(b, q).toInt().also { q += 4 } else defaultSize
                    if (flags and 0x400 != 0) q += 4
                    if (flags and 0x800 != 0) q += 4
                    samples += offset to size
                    offset += size
                }
            }

            val senc = child(b, traf, "senc")
                ?: children(b, traf, "uuid").firstOrNull { b.copyOfRange(it.body, it.body + 16).contentEquals(piffSenc) }
            val sencBody = senc?.let { if (it.type == "uuid") it.body + 16 else it.body }
            if (sencBody == null) {
                // No per-sample data: whole samples with the constant IV (cbcs audio does this)
                val iv = track.constantIv ?: continue
                samples.forEach { (at, size) -> decryptSample(b, at, size, null, iv, track, key) }
                done += samples.size
                continue
            }
            val sencFlags = u24(b, sencBody + 1)
            val count = u32(b, sencBody + 4).toInt()
            var q = sencBody + 8
            for (i in 0 until minOf(count, samples.size)) {
                val iv = if (track.ivSize > 0) b.copyOfRange(q, q + track.ivSize).also { q += track.ivSize } else track.constantIv ?: break
                var ranges: List<Pair<Int, Int>>? = null
                if (sencFlags and 0x2 != 0) {
                    val n = u16(b, q); q += 2
                    ranges = List(n) { (u16(b, q) to u32(b, q + 2).toInt()).also { q += 6 } }
                }
                val (at, size) = samples[i]
                if (at + size <= b.size) { decryptSample(b, at, size, ranges, iv, track, key); done++ }
            }
        }
        return done
    }

    /** [ranges]: (clear, encrypted) byte counts; null means the whole sample is encrypted. */
    private fun decryptSample(b: ByteArray, at: Int, size: Int, ranges: List<Pair<Int, Int>>?, iv: ByteArray, track: Track, key: ByteArray) {
        val parts = ranges ?: listOf(0 to size)
        val keySpec = SecretKeySpec(key, "AES")
        if (track.scheme == "cbcs" || track.scheme == "cbc1") {
            var p = at
            for ((clear, encrypted) in parts) {
                p += clear
                // cbcs starts each subsample again from the IV
                decryptCbcPattern(b, p, encrypted, keySpec, iv.copyOf(16), track.crypt, track.skip)
                p += encrypted
            }
        } else {
            // cenc: one AES-CTR stream over the encrypted bytes of the sample
            val cipher = Cipher.getInstance("AES/CTR/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, keySpec, IvParameterSpec(iv.copyOf(16)))
            var p = at
            for ((clear, encrypted) in parts) {
                p += clear
                if (encrypted > 0) cipher.update(b, p, encrypted, b, p)
                p += encrypted
            }
        }
    }

    /** CBC over [length] bytes from [at] in blocks of 16: [crypt] encrypted, [skip] clear, repeating. */
    private fun decryptCbcPattern(b: ByteArray, at: Int, length: Int, key: SecretKeySpec, iv: ByteArray, crypt: Int, skip: Int) {
        val cipher = Cipher.getInstance("AES/CBC/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, IvParameterSpec(iv))
        val blocks = length / 16
        if (crypt == 0 && skip == 0) {
            if (blocks > 0) cipher.update(b, at, blocks * 16, b, at)
            return
        }
        var block = 0
        while (block < blocks) {
            val n = minOf(crypt, blocks - block)
            // The chain carries on across the clear blocks in between
            cipher.update(b, at + block * 16, n * 16, b, at + block * 16)
            block += n + skip
        }
    }

    private fun u16(b: ByteArray, p: Int) = ((b[p].toInt() and 0xff) shl 8) or (b[p + 1].toInt() and 0xff)
    private fun u24(b: ByteArray, p: Int) = ((b[p].toInt() and 0xff) shl 16) or ((b[p + 1].toInt() and 0xff) shl 8) or (b[p + 2].toInt() and 0xff)
    private fun u32(b: ByteArray, p: Int) = ByteBuffer.wrap(b, p, 4).int.toLong() and 0xffffffffL
    private fun u64(b: ByteArray, p: Int) = ByteBuffer.wrap(b, p, 8).long
    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }
}
