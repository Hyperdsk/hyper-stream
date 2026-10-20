package com.lagradost.cloudstream4.desktop.ui

import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities

/**
 * A source link split into the parts people scan for. Extensions usually pack everything into
 * the link name, like "Hub-Cloud[FSLv2 Server] Street-Fighter.2026.1080p.HDTS.x264.mkv[2.51 GB]".
 */
data class SourceInfo(
    /** "Hub-Cloud" */
    val provider: String,
    /** "FSLv2 Server" */
    val server: String?,
    /** "1080p", or null when unknown */
    val quality: String?,
    /** "2.51 GB" */
    val size: String?,
    /** Size in bytes, for sorting */
    val sizeBytes: Long?,
    /** Codec, dynamic range, release type: "HEVC", "HDR", "HDTS"... */
    val tags: List<String>,
    /** Container or stream type: "MKV", "MP4", "HLS", "DASH" */
    val format: String?,
    /** The release file name, when the link carries one */
    val fileName: String?,
) {
    val title: String get() = listOfNotNull(provider, server).joinToString(" · ")

    /** Short one-line description for the player's top bar. */
    val summary: String get() = listOfNotNull(title, quality, size).joinToString(" · ")
}

private val sizeRegex = Regex("""(\d+(?:[.,]\d+)?)\s*(TB|GB|MB|KB|GiB|MiB)\b""", RegexOption.IGNORE_CASE)
private val leadingBracket = Regex("""^\s*([^\[]*?)\s*\[([^\]]+)]\s*(.*)$""")
private val trailingBrackets = Regex("""(\s*\[[^\]]*])+\s*$""")

/** Release tags in a file name, in the order they are shown. Low-quality release types first. */
private val tagRules: List<Pair<Regex, String>> = listOf(
    Regex("""\b(HD)?CAM(RIP)?\b""") to "CAM",
    Regex("""\b(HD)?TS(RIP)?\b|\bTELESYNC\b""") to "HDTS",
    Regex("""\bHDTC\b|\bTELECINE\b""") to "HDTC",
    Regex("""\bREMUX\b""") to "Remux",
    Regex("""\bBLU-?RAY\b|\bBDRIP\b|\bBRRIP\b""") to "BluRay",
    Regex("""\bWEB-?DL\b""") to "WEB-DL",
    Regex("""\bWEB-?RIP\b""") to "WEBRip",
    Regex("""\bHEVC\b|\bX\.?265\b|\bH\.?265\b""") to "HEVC",
    Regex("""\bAV1\b""") to "AV1",
    Regex("""\bX\.?264\b|\bH\.?264\b|\bAVC\b""") to "H.264",
    Regex("""\b10[- ]?BIT\b""") to "10-bit",
    Regex("""\bDV\b|\bDOVI\b|\bDOLBY[ .]?VISION\b""") to "Dolby Vision",
    Regex("""\bHDR10\+?|\bHDR\b""") to "HDR",
    Regex("""\bATMOS\b""") to "Atmos",
    Regex("""\b(DDP|EAC3|DD\+)[ .]?\d\.\d\b|\b5\.1\b|\b7\.1\b""") to "Surround",
)

/** Tags that mean a poor, recorded-in-cinema release, shown in a warning color. */
val lowQualityTags = setOf("CAM", "HDTS", "HDTC")

fun parseSourceInfo(name: String, source: String, quality: Int, type: ExtractorLinkType): SourceInfo {
    val raw = name.trim().replace(Regex("""\s+"""), " ")
    val match = leadingBracket.find(raw)
    val (provider, server, rest) = if (match != null) {
        Triple(match.groupValues[1].ifBlank { source }, match.groupValues[2].trim(), match.groupValues[3])
    } else {
        Triple(raw, null, "")
    }

    val sizeMatch = sizeRegex.findAll(raw).lastOrNull()
    val size = sizeMatch?.let { "${it.groupValues[1].replace(',', '.')} ${it.groupValues[2].uppercase().replace("IB", "B")}" }
    val sizeBytes = sizeMatch?.let {
        val n = it.groupValues[1].replace(',', '.').toDoubleOrNull() ?: return@let null
        val unit = when (it.groupValues[2].uppercase().first()) { 'T' -> 1L shl 40; 'G' -> 1L shl 30; 'M' -> 1L shl 20; else -> 1L shl 10 }
        (n * unit).toLong()
    }

    // Whatever follows the server is usually the release file name, minus trailing [size | views] blocks
    val fileName = rest.replace(trailingBrackets, "").trim().takeIf { it.length > 3 }
        // A bare provider name with no brackets can itself be a long file name
        ?: provider.takeIf { match == null && it.length > 40 }

    val ext = fileName?.substringAfterLast('.', "")?.lowercase()?.takeIf { it in setOf("mkv", "mp4", "avi", "webm", "mov", "ts", "m4v") }
    // Without the extension, so a ".ts" file isn't mistaken for a telesync release
    val haystack = (fileName?.let { if (ext != null) it.substringBeforeLast('.') else it } ?: raw).uppercase().replace('_', ' ')
    val tags = tagRules.filter { (regex, _) -> regex.containsMatchIn(haystack) }.map { it.second }.distinct()
    val format = when (type) {
        ExtractorLinkType.M3U8 -> "HLS"
        ExtractorLinkType.DASH -> "DASH"
        else -> ext?.uppercase()
    }

    val qualityText = Qualities.getStringByInt(quality).ifBlank { null }
    val title = if (match == null && fileName != null) source.ifBlank { "Source" } else provider
    return SourceInfo(title, server, qualityText, size, sizeBytes, tags, format, fileName)
}

fun ExtractorLink.info(): SourceInfo = parseSourceInfo(name, source, quality, type)
