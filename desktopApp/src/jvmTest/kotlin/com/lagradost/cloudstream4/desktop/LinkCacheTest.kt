package com.lagradost.cloudstream4.desktop

import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream4.desktop.data.LinkCache
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LinkCacheTest {
    @Test
    fun keepsSourcesAndTheLinkThatPlayed() = runBlocking {
        System.setProperty("cloudstream.dataDir", Files.createTempDirectory("cs-cache").toString())
        val a = newExtractorLink("Bollyflix", "GDFlix[Cloud] 1080p", "https://a.example/v.mkv", ExtractorLinkType.VIDEO) {
            quality = 1080; referer = "https://ref.example/"; headers = mapOf("X-Token" to "1")
        }
        val b = newExtractorLink("MovieBox", "Original", "https://b.example/v.m3u8", ExtractorLinkType.M3U8) { quality = 720 }
        val sub = newSubtitleFile("English", "https://s.example/en.srt") { headers = mapOf("Referer" to "https://s.example") }

        LinkCache.put("Api", "episode-1", listOf(a, b), listOf(sub), lastPlayedUrl = null)
        LinkCache.markPlayed("Api", "episode-1", b.url)

        val cached = LinkCache.get("Api", "episode-1")!!
        assertEquals(listOf(a.url, b.url), cached.links.map { it.url })
        assertEquals(b.url, cached.lastPlayedUrl)
        val restored = cached.links.first().toLink()
        assertEquals(1080, restored.quality)
        assertEquals("https://ref.example/", restored.referer)
        assertEquals(mapOf("X-Token" to "1"), restored.headers)
        assertEquals(ExtractorLinkType.M3U8, cached.links[1].toLink().type)
        assertEquals(mapOf("Referer" to "https://s.example"), cached.subtitles.single().toSubtitle().headers)

        // A new search keeps what played last unless told otherwise
        LinkCache.put("Api", "episode-1", listOf(a, b), emptyList(), lastPlayedUrl = null)
        assertEquals(b.url, LinkCache.get("Api", "episode-1")!!.lastPlayedUrl)

        assertNull(LinkCache.get("Api", "episode-2"))
        LinkCache.remove("Api", "episode-1")
        assertNull(LinkCache.get("Api", "episode-1"))
    }
}
