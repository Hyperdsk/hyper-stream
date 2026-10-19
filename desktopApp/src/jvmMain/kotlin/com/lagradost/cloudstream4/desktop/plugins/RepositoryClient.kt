package com.lagradost.cloudstream4.desktop.plugins

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream4.desktop.data.jsonMapper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/** Fetches repositories and extension files. Port of the Android RepositoryManager networking. */
object RepositoryClient {
    private val schemeRegex = "^(cloudstreamrepo://)|(https://cs\\.repo/\\??)".toRegex()

    /**
     * Turns what the user typed into a repository url. Accepts full urls, cloudstreamrepo:// links
     * and the short codes the Android app accepts (cutt.ly codes, or py.md codes prefixed with !).
     */
    suspend fun parseRepoUrl(input: String): String? = withContext(Dispatchers.IO) {
        val fixed = input.trim()
        when {
            fixed.contains("^https?://".toRegex()) -> fixed
            fixed.contains(schemeRegex) -> fixed.replace(schemeRegex, "").let {
                if (!it.contains("^https?://".toRegex())) "https://$it" else it
            }

            fixed.matches("^[a-zA-Z0-9!_-]+$".toRegex()) -> runCatching {
                val host = if (fixed.startsWith("!")) "py.md" else "cutt.ly"
                val response = app.get("https://$host/${fixed.removePrefix("!")}", allowRedirects = false)
                val location = response.headers["Location"] ?: return@runCatching null
                if (location.startsWith("https://$host/404") || location.removeSuffix("/") == "https://$host") null
                else location
            }.getOrNull()

            else -> null
        }
    }

    suspend fun fetchRepository(url: String): Repository = withContext(Dispatchers.IO) {
        jsonMapper.readValue(app.get(url).text, Repository::class.java)
    }

    suspend fun fetchPluginList(url: String): List<SitePlugin> = withContext(Dispatchers.IO) {
        jsonMapper.readValue(app.get(url).text, Array<SitePlugin>::class.java).toList()
    }

    /** All plugins listed by a repository, across all of its plugin lists. */
    suspend fun fetchRepoPlugins(repo: Repository): List<SitePlugin> = coroutineScope {
        repo.pluginLists.map { url ->
            async { runCatching { fetchPluginList(url) }.getOrElse { emptyList() } }
        }.awaitAll().flatten()
    }

    /** Downloads to a temp file, checks the hash when the repository provides one, then moves into place. */
    suspend fun download(url: String, target: File, expectedHash: String?): File = withContext(Dispatchers.IO) {
        target.parentFile?.mkdirs()
        val tmp = File.createTempFile(target.name, ".tmp", target.parentFile)
        try {
            app.get(url).okhttpResponse.body.byteStream().use { input ->
                tmp.outputStream().use { input.copyTo(it) }
            }
            if (expectedHash != null) {
                val actual = sha256(tmp)
                check(actual == expectedHash) {
                    "Extension hash mismatch for ${target.name}. Expected $expectedHash, got $actual."
                }
            }
            try {
                Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
            target
        } finally {
            tmp.delete()
        }
    }

    /** Same format as the Android app: "sha256-<hex>". */
    fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return "sha256-" + digest.digest().joinToString("") { "%02x".format(it) }
    }
}
