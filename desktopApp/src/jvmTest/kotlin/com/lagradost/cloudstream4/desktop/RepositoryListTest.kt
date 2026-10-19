package com.lagradost.cloudstream4.desktop

import com.lagradost.cloudstream4.desktop.data.jsonMapper
import com.lagradost.cloudstream4.desktop.plugins.RepositoryClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RepositoryListTest {
    @Test
    fun readsCommunityListWithStringAndObjectEntries() {
        // Same shape as recloudstream/cs-repos repos-db.json
        val json = jsonMapper.readTree(
            """
            [
              {"url": "https://example.com/a/repo.json", "verified": true},
              "https://example.com/b/repo.json",
              "https://example.com/b/repo.json",
              {"name": "no url"},
              42
            ]
            """
        )
        assertEquals(
            listOf("https://example.com/a/repo.json", "https://example.com/b/repo.json"),
            RepositoryClient.repositoryList(json),
        )
    }

    @Test
    fun aSingleRepositoryIsNotAList() {
        val json = jsonMapper.readTree("""{"name":"Repo","manifestVersion":1,"pluginLists":[]}""")
        assertNull(RepositoryClient.repositoryList(json))
    }
}

class WebPageDetectionTest {
    @Test
    fun recognisesBotCheckPagesButNotJson() {
        kotlin.test.assertTrue(RepositoryClient.looksLikeWebPage("<!doctype html><html><head><title>Making sure you're not a bot!</title>"))
        kotlin.test.assertTrue(RepositoryClient.looksLikeWebPage("\n  <html>"))
        kotlin.test.assertFalse(RepositoryClient.looksLikeWebPage("""{"name": "FStream"}"""))
        kotlin.test.assertFalse(RepositoryClient.looksLikeWebPage("""[{"url": "x"}]"""))
    }
}
