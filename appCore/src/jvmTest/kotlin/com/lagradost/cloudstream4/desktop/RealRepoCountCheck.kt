package com.lagradost.cloudstream4.desktop

import com.lagradost.cloudstream4.desktop.plugins.RepositoryClient
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.Test

/** Loads every saved repository's extension list, like the Extensions screen. Opt in: HYPER_REPOS_FILE. */
class RealRepoCountCheck {
    @Test
    fun repositoriesHaveExtensions() = runBlocking {
        val file = System.getenv("HYPER_REPOS_FILE")?.let(::File) ?: return@runBlocking println("Skipping, set HYPER_REPOS_FILE")
        val urls = Regex("\"url\"\\s*:\\s*\"([^\"]+)\"").findAll(file.readText()).map { it.groupValues[1] }.distinct().toList()
        urls.forEach { url ->
            val result = runCatching { RepositoryClient.fetchRepoPlugins(RepositoryClient.fetchRepository(url), url).size }
            println("REPO ${result.getOrNull() ?: "error: ${result.exceptionOrNull()?.message?.take(80)}"} $url")
        }
    }
}
