package com.lagradost.cloudstream4.desktop.plugins

import com.fasterxml.jackson.databind.JsonNode
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.plugins.Repository
import com.lagradost.cloudstream3.plugins.SitePlugin
import com.lagradost.cloudstream4.desktop.data.jsonMapper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.IOException
import java.net.URI
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/** Thrown when a site answers with a web page (an error or bot-check page) instead of repository data. */
class WebPageResponseException(url: String) : IOException(
    "${hostOf(url)} returned a web page instead of repository data. " +
        "The site may be down, or it may be showing a bot check."
)

private fun hostOf(url: String) = runCatching { URI(url).host }.getOrNull() ?: url

/** Fetches repositories and extension files. Port of the Android RepositoryManager networking. */
object RepositoryClient {
    /**
     * Repository files are plain data, so they are fetched as an app, not as a browser. Some
     * hosts (git.disroot.org, which runs the Anubis bot check) answer browser user agents with
     * a challenge page instead of the file. Extensions keep using the browser user agent.
     */
    const val USER_AGENT = "CloudStream-Desktop/1.0"

    private fun get(url: String): Response {
        val request = Request.Builder().url(url).header("User-Agent", USER_AGENT).build()
        val response = app.baseClient.newCall(request).execute()
        if (!response.isSuccessful) {
            response.close()
            throw IOException("${hostOf(url)} answered with HTTP ${response.code}.")
        }
        return response
    }

    /** Text of a json file, refusing html pages so the user gets a readable reason. */
    private fun getJsonText(url: String): String {
        val text = get(url).use { it.body.string() }
        if (looksLikeWebPage(text)) throw WebPageResponseException(url)
        return text
    }

    /** Json never starts with "<", while html and xml pages always do. */
    internal fun looksLikeWebPage(text: String): Boolean = text.trimStart().startsWith("<")
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
        jsonMapper.readValue(getJsonText(url), Repository::class.java)
    }

    suspend fun fetchJson(url: String): JsonNode = withContext(Dispatchers.IO) {
        jsonMapper.readTree(getJsonText(url))
    }

    /**
     * Reads a list of repositories, like CloudStream's community list (cs-repos repos-db.json).
     * Entries are either a url string or an object with a "url" field. Returns null when the
     * json is not such a list.
     */
    fun repositoryList(json: JsonNode): List<String>? {
        if (!json.isArray) return null
        return json.mapNotNull { entry ->
            when {
                entry.isTextual -> entry.asText()
                entry.isObject -> entry.get("url")?.takeIf { it.isTextual }?.asText()
                else -> null
            }
        }.map { it.trim() }.filter { it.startsWith("http") }.distinct()
    }

    suspend fun fetchPluginList(url: String): List<SitePlugin> = withContext(Dispatchers.IO) {
        jsonMapper.readValue(getJsonText(url), Array<SitePlugin>::class.java).toList()
    }

    /**
     * All plugins listed by a repository, across all of its plugin lists. A list that fails is
     * skipped, but if every list fails the first error is thrown, so the user sees why.
     */
    suspend fun fetchRepoPlugins(repo: Repository): List<SitePlugin> = coroutineScope {
        val results = repo.pluginLists.map { url -> async { runCatching { fetchPluginList(url) } } }.awaitAll()
        if (results.isNotEmpty() && results.all { it.isFailure }) throw results.first().exceptionOrNull()!!
        results.flatMap { it.getOrDefault(emptyList()) }
    }

    /** Downloads to a temp file, checks the hash when the repository provides one, then moves into place. */
    suspend fun download(url: String, target: File, expectedHash: String?): File = withContext(Dispatchers.IO) {
        target.parentFile?.mkdirs()
        val tmp = File.createTempFile(target.name, ".tmp", target.parentFile)
        try {
            get(url).use { response ->
                response.body.byteStream().use { input -> tmp.outputStream().use { input.copyTo(it) } }
            }
            // A .cs3 is a zip, which always starts with "PK"
            val head = tmp.inputStream().use { it.readNBytes(64) }
            if (head.size < 2 || head[0] != 'P'.code.toByte() || head[1] != 'K'.code.toByte()) {
                if (looksLikeWebPage(String(head))) throw WebPageResponseException(url)
                throw IOException("${hostOf(url)} did not return an extension file.")
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
