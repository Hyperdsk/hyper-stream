package com.lagradost.cloudstream4.desktop

import com.lagradost.cloudstream4.desktop.plugins.RepositoryClient
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

/** "all" or "mega" in the repository field means CloudStream's community list of repositories. */
class RepoKeywordTest {
    @Test
    fun keywordsMeanTheCommunityList() = runBlocking {
        listOf("all", " ALL ", "Mega").forEach { assertEquals(RepositoryClient.COMMUNITY_REPOS, RepositoryClient.parseRepoUrl(it), it) }
        assertEquals("https://example.com/repo.json", RepositoryClient.parseRepoUrl("https://example.com/repo.json"))
    }
}
