package com.lagradost.cloudstream3.plugins

import android.content.Context
import com.lagradost.cloudstream3.ui.settings.extensions.RepositoryData
import com.lagradost.cloudstream4.desktop.plugins.ExtensionManager
import com.lagradost.cloudstream4.desktop.plugins.RepositoryClient
import kotlinx.coroutines.CancellationException

/**
 * Desktop version of the Android app's RepositoryManager, with the same JVM signatures.
 *
 * Some extensions manage repositories themselves. MegaRepo's MegaProvider, for example, reads
 * the community repository list and calls [addRepository] for each entry. Here those calls go
 * to [ExtensionManager], so the repositories show up on the Extensions screen.
 */
object RepositoryManager {
    const val ONLINE_PLUGINS_FOLDER = "Extensions"

    /** The Android app can route GitHub raw urls through a CDN. Desktop uses them as they are. */
    fun convertRawGitUrl(url: String): String = url

    suspend fun parseRepoUrl(url: String): String? = RepositoryClient.parseRepoUrl(url)

    suspend fun parseRepository(url: String): Repository? = try {
        RepositoryClient.fetchRepository(url)
    } catch (e: CancellationException) {
        throw e
    } catch (_: Throwable) {
        null
    }

    suspend fun getRepoPlugins(repositoryData: RepositoryData): List<PluginWrapper>? {
        val repo = parseRepository(repositoryData.url) ?: return null
        return RepositoryClient.fetchRepoPlugins(repo).map { PluginWrapper(repo, repositoryData, it) }
    }

    fun getRepositories(): Array<RepositoryData> = ExtensionManager.repositories().toTypedArray()

    suspend fun addRepository(repository: RepositoryData) {
        ExtensionManager.addRepositoryData(repository)
    }

    /** Also deletes the extensions installed from that repository, like on Android. */
    suspend fun removeRepository(context: Context, repository: RepositoryData) {
        ExtensionManager.removeRepository(repository.url).join()
    }
}
