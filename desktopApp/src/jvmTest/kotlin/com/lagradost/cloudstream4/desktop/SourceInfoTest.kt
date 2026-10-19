package com.lagradost.cloudstream4.desktop

import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream4.desktop.ui.parseSourceInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SourceInfoTest {
    @Test
    fun splitsHubCloudStyleNames() {
        val info = parseSourceInfo(
            "Hub-Cloud[FSLv2 Server] Street-Fighter.2026.1080p.HDTS.Hindi-Telugu.CLEAN-English.ORG.x264-MoviesDrives.BID.mkv[2.51 GB]",
            "Hub-Cloud", 1080, ExtractorLinkType.VIDEO,
        )
        assertEquals("Hub-Cloud · FSLv2 Server", info.title)
        assertEquals("1080p", info.quality)
        assertEquals("2.51 GB", info.size)
        assertEquals(listOf("HDTS", "H.264"), info.tags)
        assertEquals("MKV", info.format)
        assertEquals("Street-Fighter.2026.1080p.HDTS.Hindi-Telugu.CLEAN-English.ORG.x264-MoviesDrives.BID.mkv", info.fileName)
    }

    @Test
    fun handlesSizeWithViewsAndHevc() {
        val info = parseSourceInfo(
            "GDFlix[Direct] Street-Fighter.2026.720p.HEVC.HDTS.Hindi-Telugu.x265-MoviesDrives.BID.mkv[765.74MB | Views : 0]",
            "GDFlix", 720, ExtractorLinkType.VIDEO,
        )
        assertEquals("GDFlix · Direct", info.title)
        assertEquals("765.74 MB", info.size)
        assertEquals(listOf("HDTS", "HEVC"), info.tags)
    }

    @Test
    fun keepsSimpleNamesAsTheyAre() {
        val info = parseSourceInfo("Vidplay", "Vidplay", 1080, ExtractorLinkType.M3U8)
        assertEquals("Vidplay", info.title)
        assertEquals("HLS", info.format)
        assertNull(info.fileName)
        assertNull(info.size)
    }
}
