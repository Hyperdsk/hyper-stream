package com.lagradost.cloudstream4.desktop.plugins

import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream4.desktop.data.AppDirs
import com.lagradost.cloudstream4.desktop.data.SettingsStore
import com.lagradost.cloudstream4.desktop.data.jsonFile
import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.PersistentMap
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.toPersistentList
import kotlinx.collections.immutable.toPersistentMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

sealed interface PluginStatus {
    data object NotLoaded : PluginStatus
    data object Loading : PluginStatus
    data class Loaded(val providers: List<String>) : PluginStatus
    data class Failed(val reason: String) : PluginStatus
}

data class RepositoryState(
    val data: RepositoryData,
    val repository: Repository? = null,
    val plugins: PersistentList<SitePlugin> = persistentListOf(),
    val loading: Boolean = false,
    val error: String? = null,
)

data class ExtensionsState(
    val repositories: PersistentList<RepositoryState> = persistentListOf(),
    val installed: PersistentList<InstalledPlugin> = persistentListOf(),
    /** Keyed by absolute .cs3 path. */
    val status: PersistentMap<String, PluginStatus> = persistentMapOf(),
    /** Bumped whenever the set of providers changes, so screens can refresh. */
    val providersVersion: Int = 0,
    val busy: PersistentList<String> = persistentListOf(),
    val message: String? = null,
)

/**
 * Owns repositories and installed extensions. Mirrors the Android app's PluginManager and
 * RepositoryManager, with data kept in JSON files instead of SharedPreferences.
 */
object ExtensionManager {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()

    private val reposFile = jsonFile<List<RepositoryData>>("repositories.json") { emptyList() }
    private val installedFile = jsonFile<List<InstalledPlugin>>("installed-extensions.json") { emptyList() }

    private val _state = MutableStateFlow(ExtensionsState())
    val state: StateFlow<ExtensionsState> = _state.asStateFlow()

    /** Providers that are usable right now, in a stable order. */
    fun providers(): List<MainAPI> =
        APIHolder.allProviders.filter { it.sourcePlugin != null }.sortedBy { it.name.lowercase() }

    fun start() {
        _state.update {
            it.copy(
                repositories = reposFile.read().map { r -> RepositoryState(r) }.toPersistentList(),
                installed = installedFile.read().toPersistentList(),
            )
        }
        DemoProvider.setEnabled(SettingsStore.state.value.demoProvider)
        scope.launch {
            loadAllInstalled()
            _state.value.repositories.forEach { refreshRepository(it.data.url) }
        }
    }

    fun dismissMessage() = _state.update { it.copy(message = null) }

    fun setDemoProvider(enabled: Boolean) {
        SettingsStore.update { it.copy(demoProvider = enabled) }
        if (DemoProvider.setEnabled(enabled)) providersChanged()
    }

    // ---------- Repositories ----------

    fun addRepository(input: String) = scope.launch {
        if (input.isBlank()) return@launch
        val url = RepositoryClient.parseRepoUrl(input)
        if (url == null) {
            _state.update { it.copy(message = "That doesn't look like a repository link or code.") }
            return@launch
        }
        if (_state.value.repositories.any { it.data.url == url }) {
            _state.update { it.copy(message = "That repository is already added.") }
            return@launch
        }
        val repo = runCatching { RepositoryClient.fetchRepository(url) }.getOrElse { e ->
            _state.update { it.copy(message = "Could not read repository: ${e.message}") }
            return@launch
        }
        val data = RepositoryData(repo.iconUrl, repo.name, url)
        lock.withLock {
            val repos = reposFile.read() + data
            reposFile.write(repos.distinctBy { it.url })
        }
        _state.update {
            it.copy(
                repositories = (it.repositories + RepositoryState(data, repo)).toPersistentList(),
                message = "Added ${repo.name}.",
            )
        }
        refreshRepository(url)
    }

    /** Removes the repository and every extension that was installed from it. */
    fun removeRepository(url: String) = scope.launch {
        val toRemove = _state.value.installed.filter { it.repositoryUrl == url }
        toRemove.forEach { uninstallNow(it) }
        lock.withLock { reposFile.write(reposFile.read().filterNot { it.url == url }) }
        _state.update { s -> s.copy(repositories = s.repositories.removingAll { it.data.url == url }) }
    }

    fun refreshRepository(url: String) = scope.launch {
        updateRepo(url) { it.copy(loading = true, error = null) }
        runCatching {
            val repo = RepositoryClient.fetchRepository(url)
            repo to RepositoryClient.fetchRepoPlugins(repo)
        }.onSuccess { (repo, plugins) ->
            updateRepo(url) {
                it.copy(
                    repository = repo,
                    plugins = plugins.sortedBy { p -> p.name.lowercase() }.toPersistentList(),
                    loading = false,
                )
            }
        }.onFailure { e ->
            updateRepo(url) { it.copy(loading = false, error = e.message ?: "Could not load repository") }
        }
    }

    private fun updateRepo(url: String, block: (RepositoryState) -> RepositoryState) = _state.update { s ->
        s.copy(repositories = s.repositories.map { if (it.data.url == url) block(it) else it }.toPersistentList())
    }

    // ---------- Installing ----------

    fun installedFor(repositoryUrl: String, plugin: SitePlugin): InstalledPlugin? =
        _state.value.installed.firstOrNull { it.repositoryUrl == repositoryUrl && it.internalName == plugin.internalName }

    fun install(repositoryUrl: String, plugin: SitePlugin) = scope.launch {
        busy(plugin.internalName, true)
        try {
            val folder = File(AppDirs.extensions, folderName(repositoryUrl))
            val file = File(folder, "${plugin.internalName}.cs3")
            // Unload any previous version before replacing the file
            withContext(Dispatchers.IO) { PluginLoader.unload(file) }
            RepositoryClient.download(plugin.url, file, plugin.fileHash)
            PluginLoader.clearCache(file)

            val installed = InstalledPlugin(
                internalName = plugin.internalName,
                name = plugin.name,
                version = plugin.version,
                url = plugin.url,
                repositoryUrl = repositoryUrl,
                iconUrl = plugin.iconUrl,
                language = plugin.language,
                tvTypes = plugin.tvTypes,
                filePath = file.absolutePath,
            )
            lock.withLock {
                val list = installedFile.read().filterNot { it.filePath == installed.filePath } + installed
                installedFile.write(list)
            }
            _state.update { s ->
                s.copy(installed = (s.installed.removingAll { it.filePath == installed.filePath } + installed).toPersistentList())
            }
            loadFile(file)
            APIHolder.initAll()
            providersChanged()
        } catch (e: Throwable) {
            _state.update { it.copy(message = "Could not install ${plugin.name}: ${e.message}") }
        } finally {
            busy(plugin.internalName, false)
        }
    }

    fun uninstall(plugin: InstalledPlugin) = scope.launch {
        uninstallNow(plugin)
        providersChanged()
    }

    private suspend fun uninstallNow(plugin: InstalledPlugin) {
        val file = File(plugin.filePath)
        PluginLoader.unload(file)
        PluginLoader.clearCache(file)
        file.delete()
        lock.withLock { installedFile.write(installedFile.read().filterNot { it.filePath == plugin.filePath }) }
        _state.update { s ->
            s.copy(
                installed = s.installed.removingAll { it.filePath == plugin.filePath },
                status = s.status.removing(plugin.filePath),
            )
        }
    }

    /** Installs newer versions of every installed extension that its repository has updated. */
    fun updateAll() = scope.launch {
        var count = 0
        for (repo in _state.value.repositories) {
            for (installed in _state.value.installed.filter { it.repositoryUrl == repo.data.url }) {
                val latest = repo.plugins.firstOrNull { it.internalName == installed.internalName } ?: continue
                if (latest.version > installed.version) {
                    install(repo.data.url, latest).join()
                    count++
                }
            }
        }
        _state.update { it.copy(message = if (count == 0) "All extensions are up to date." else "Updated $count extensions.") }
    }

    // ---------- Loading ----------

    private fun loadAllInstalled() {
        val files = _state.value.installed.map { File(it.filePath) } + localFiles()
        files.filter { it.exists() }.forEach { loadFile(it) }
        APIHolder.initAll()
        providersChanged()
    }

    /** .cs3 files dropped straight into the Extensions folder, like the Android app's local extensions. */
    private fun localFiles(): List<File> =
        AppDirs.extensions.listFiles { f -> f.isFile && f.extension == "cs3" }?.toList() ?: emptyList()

    fun reloadLocal() = scope.launch {
        localFiles().filterNot { PluginLoader.isLoaded(it) }.forEach { loadFile(it) }
        APIHolder.initAll()
        providersChanged()
    }

    private fun loadFile(file: File) {
        setStatus(file, PluginStatus.Loading)
        val status = try {
            val plugin = PluginLoader.load(file)
            PluginStatus.Loaded(plugin.providerNames)
        } catch (e: Throwable) {
            System.err.println("Failed to load ${file.name}: ${e.message}")
            e.cause?.printStackTrace()
            PluginStatus.Failed(e.message ?: e.javaClass.simpleName)
        }
        setStatus(file, status)
    }

    private fun setStatus(file: File, status: PluginStatus) = _state.update {
        it.copy(status = (it.status + (file.absolutePath to status)).toPersistentMap())
    }

    private fun providersChanged() = _state.update { it.copy(providersVersion = it.providersVersion + 1) }

    private fun busy(name: String, on: Boolean) = _state.update {
        it.copy(busy = if (on) it.busy.adding(name) else it.busy.removing(name))
    }

    private fun folderName(repositoryUrl: String): String =
        repositoryUrl.removePrefix("https://").replace(Regex("[^A-Za-z0-9._-]"), "_").take(80)
}
