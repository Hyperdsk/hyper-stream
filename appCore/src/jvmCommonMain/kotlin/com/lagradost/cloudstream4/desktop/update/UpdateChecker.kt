package com.lagradost.cloudstream4.desktop.update

import com.fasterxml.jackson.databind.JsonNode
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream4.desktop.Platform
import com.lagradost.cloudstream4.desktop.UpdateTarget
import com.lagradost.cloudstream4.desktop.data.SettingsStore
import com.lagradost.cloudstream4.desktop.data.jsonMapper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

data class UpdateAsset(
    val name: String,
    val url: String,
    val size: Long,
    val digest: String?,
)

data class UpdateRelease(
    val version: String,
    val tag: String,
    val name: String,
    val notes: String,
    val pageUrl: String,
    val asset: UpdateAsset,
)

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data class UpToDate(val currentVersion: String) : UpdateState
    data class Available(val release: UpdateRelease, val currentVersion: String) : UpdateState
    data class Failed(val message: String) : UpdateState
}

/**
 * Checks GitHub's releases for the newest build this platform can install. Releases are matched
 * by their asset names, so an Android-only release is never offered to the Mac, Windows or Linux
 * app, even though all of them share the same repository.
 */
object UpdateChecker {
    private const val REPO = "KamranAllahYar/hyper-stream"
    private const val RELEASES_URL = "https://api.github.com/repos/$REPO/releases?per_page=30"
    private const val DOWNLOAD_PREFIX = "https://github.com/$REPO/releases/download/"
    private const val CHECK_INTERVAL_MS = 6L * 60 * 60 * 1000

    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    private val checkLock = Mutex()

    /** The installed version, read from the APK on Android and from the app jar's manifest on desktop. */
    val currentVersion: String get() = Platform.appVersion

    /** Time until the six-hour automatic check is due, for the background check loop. */
    fun millisUntilAutomaticCheck(): Long {
        val last = SettingsStore.state.value.lastUpdateCheckAt
        return (CHECK_INTERVAL_MS - (System.currentTimeMillis() - last))
            .coerceIn(60_000, CHECK_INTERVAL_MS)
    }

    /**
     * Looks for a newer release. Automatic checks are skipped while [SettingsStore] says checking
     * is off, and run at most once every six hours. Manual checks always hit GitHub.
     */
    suspend fun check(automatic: Boolean = false): UpdateState {
        if (automatic) {
            val settings = SettingsStore.state.value
            if (!settings.checkForUpdates) return _state.value
            if (System.currentTimeMillis() - settings.lastUpdateCheckAt < CHECK_INTERVAL_MS) return _state.value
        }

        return checkLock.withLock {
            _state.value = UpdateState.Checking
            val result = try {
                val release = fetchLatest()
                SettingsStore.update { it.copy(lastUpdateCheckAt = System.currentTimeMillis()) }
                if (release != null && isNewer(release.version, currentVersion)) {
                    UpdateState.Available(release, currentVersion)
                } else {
                    UpdateState.UpToDate(currentVersion)
                }
            } catch (t: kotlinx.coroutines.CancellationException) {
                throw t
            } catch (t: Throwable) {
                UpdateState.Failed(friendlyError(t))
            }
            _state.value = result
            result
        }
    }

    private suspend fun fetchLatest(): UpdateRelease? = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(RELEASES_URL)
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .header("User-Agent", "HyperStream/${currentVersion}")
            .build()
        val client = app.baseClient.newBuilder().callTimeout(20, TimeUnit.SECONDS).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException(
                    when (response.code) {
                        403, 429 -> "GitHub's update check limit was reached. Try again later."
                        else -> "GitHub answered with HTTP ${response.code}."
                    }
                )
            }
            val json = jsonMapper.readTree(response.body.string())
            newestRelease(compatibleReleases(json, Platform.updateTarget))
        }
    }

    /**
     * The newest release with an asset this platform can install. Drafts, prereleases and releases
     * without a matching asset are skipped.
     */
    internal fun compatibleReleases(json: JsonNode, target: UpdateTarget): List<UpdateRelease> {
        if (!json.isArray) return emptyList()
        return json.mapNotNull { node ->
            if (node.path("draft").asBoolean(false) || node.path("prerelease").asBoolean(false)) {
                return@mapNotNull null
            }
            val tag = node.path("tag_name").asText("").trim()
            val version = versionFromTag(tag) ?: return@mapNotNull null
            val asset = assetFor(node.path("assets"), target) ?: return@mapNotNull null
            UpdateRelease(
                version = version,
                tag = tag,
                name = node.path("name").asText("").ifBlank { "Hyper Stream $version" },
                notes = node.path("body").asText("").trim(),
                pageUrl = node.path("html_url").asText(""),
                asset = asset,
            )
        }
    }

    internal fun newestRelease(releases: List<UpdateRelease>): UpdateRelease? =
        releases.maxWithOrNull(compareBy { versionValue(it.version) })

    internal fun assetFor(assets: JsonNode, target: UpdateTarget): UpdateAsset? {
        val patterns = when (target) {
            UpdateTarget.Android -> listOf(Regex("""-android-and-tv\.apk$""", RegexOption.IGNORE_CASE))
            UpdateTarget.MacAppleSilicon -> listOf(
                Regex("""-macos-apple-silicon\.dmg$""", RegexOption.IGNORE_CASE),
                Regex("""-macos-apple-silicon\.zip$""", RegexOption.IGNORE_CASE),
            )
            UpdateTarget.MacIntel -> listOf(
                Regex("""-macos-intel\.dmg$""", RegexOption.IGNORE_CASE),
                Regex("""-macos-intel\.zip$""", RegexOption.IGNORE_CASE),
            )
            UpdateTarget.Windows -> listOf(
                Regex("""-windows-x64\.msi$""", RegexOption.IGNORE_CASE),
                Regex("""-windows-x64-portable\.zip$""", RegexOption.IGNORE_CASE),
            )
            UpdateTarget.Linux -> listOf(
                Regex("""-linux-x64\.deb$""", RegexOption.IGNORE_CASE),
                Regex("""-linux-x64\.tar\.gz$""", RegexOption.IGNORE_CASE),
            )
        }
        for (pattern in patterns) {
            val match = assets.firstOrNull { pattern.containsMatchIn(it.path("name").asText("")) }
                ?: continue
            return UpdateAsset(
                name = match.path("name").asText(""),
                url = match.path("browser_download_url").asText(""),
                size = match.path("size").asLong(0),
                digest = match.path("digest").asText("").takeIf { it.isNotBlank() },
            )
        }
        return null
    }

    internal fun versionFromTag(tag: String): String? {
        val match = Regex("""^v?(\d+)\.(\d+)\.(\d+)(?:[.\-+].*)?$""").find(tag.trim()) ?: return null
        return "${match.groupValues[1]}.${match.groupValues[2]}.${match.groupValues[3]}"
    }

    internal fun isNewer(remote: String, current: String): Boolean {
        val remoteValue = versionValue(remote) ?: return false
        val currentValue = versionValue(current) ?: return true
        return remoteValue > currentValue
    }

    private fun versionValue(version: String): Long? {
        val match = Regex("""(\d+)\.(\d+)\.(\d+)""").find(version) ?: return null
        val (major, minor, patch) = match.destructured
        return major.toLongOrNull()?.times(1_000_000)
            ?.plus((minor.toLongOrNull() ?: return null) * 1_000)
            ?.plus(patch.toLongOrNull() ?: return null)
    }

    /**
     * Downloads [release]'s installer into the app's updates folder, verifies its size and SHA-256
     * when GitHub provides one, then hands it to the platform installer. Android opens the package
     * installer; desktop opens the downloaded .dmg/.msi/.deb with the system.
     */
    suspend fun downloadAndInstall(
        release: UpdateRelease,
        onProgress: (Float?) -> Unit,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        if (!release.asset.url.startsWith(DOWNLOAD_PREFIX)) {
            return@withContext Result.failure(IOException("The update link doesn't point at this project's GitHub release."))
        }
        val safeName = release.asset.name.substringAfterLast('/')
            .replace(Regex("""[^A-Za-z0-9._-]"""), "_")
            .ifBlank { "HyperStream-${release.version}" }
        val updates = File(Platform.dataFolder(), "updates")
        updates.listFiles()?.filter { it.name != release.version }?.forEach { it.deleteRecursively() }
        val folder = File(updates, release.version)
        folder.mkdirs()
        val target = File(folder, safeName)

        try {
            // If Android sent the user to grant install permission, the APK is still here; reuse it
            // instead of downloading the whole release again when they tap Update.
            val alreadyDownloaded = target.isFile && target.length() > 0 &&
                (release.asset.size <= 0 || target.length() == release.asset.size) &&
                matchesDigest(target, release.asset.digest)
            if (!alreadyDownloaded) {
                target.delete()
                val request = Request.Builder()
                    .url(release.asset.url)
                    .header("User-Agent", "HyperStream/${currentVersion}")
                    .build()
                val client = app.baseClient.newBuilder().callTimeout(10, TimeUnit.MINUTES).build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) throw IOException("The update download answered with HTTP ${response.code}.")
                    val body = response.body
                    val total = body.contentLength().takeIf { it > 0 } ?: release.asset.size
                    var copied = 0L
                    var lastReported = 0L
                    body.byteStream().use { input ->
                        target.outputStream().use { output ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                val read = input.read(buffer)
                                if (read < 0) break
                                output.write(buffer, 0, read)
                                copied += read
                                val report = total > 0 && copied - lastReported >= 256 * 1024
                                if (report || copied == total) {
                                    lastReported = copied
                                    onProgress(if (total > 0) (copied.toFloat() / total).coerceIn(0f, 1f) else null)
                                }
                            }
                        }
                    }
                }
                if (release.asset.size > 0 && target.length() != release.asset.size) {
                    throw IOException("The update download was incomplete.")
                }
                if (!matchesDigest(target, release.asset.digest)) {
                    throw IOException("The update file didn't match its checksum.")
                }
            }
            onProgress(1f)
        } catch (t: kotlinx.coroutines.CancellationException) {
            target.delete()
            throw t
        } catch (t: Throwable) {
            target.delete()
            return@withContext Result.failure(t)
        }

        // Keep the verified file if the system couldn't open it: the user can open it from the
        // path in the message, and tapping Update again retries without another large download.
        val opened = try {
            Platform.installUpdate(target)
        } catch (t: kotlinx.coroutines.CancellationException) {
            throw t
        } catch (t: Throwable) {
            return@withContext Result.failure(t)
        }
        if (!opened) {
            return@withContext Result.failure(
                IOException("The update downloaded to ${target.absolutePath}, but its installer couldn't be opened.")
            )
        }
        Result.success(Unit)
    }

    private fun matchesDigest(file: File, digest: String?): Boolean {
        val expected = digest
            ?.takeIf { it.startsWith("sha256:", ignoreCase = true) }
            ?.substringAfter(':')
            ?: return true
        return sha256(file).equals(expected, ignoreCase = true)
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun friendlyError(t: Throwable): String {
        val message = t.message?.takeIf { it.isNotBlank() }
        return when (t) {
            is java.net.UnknownHostException -> "Couldn't reach GitHub. Check the internet connection."
            is java.net.SocketTimeoutException -> "GitHub took too long to answer. Try again."
            else -> message ?: "Couldn't check for updates."
        }
    }
}
