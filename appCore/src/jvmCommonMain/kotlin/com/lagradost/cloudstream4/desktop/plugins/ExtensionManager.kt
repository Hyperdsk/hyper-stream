package com.lagradost.cloudstream4.desktop.plugins

import kotlinx.coroutines.delay
import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.plugins.Repository
import com.lagradost.cloudstream3.plugins.SitePlugin
import com.lagradost.cloudstream3.ui.settings.extensions.RepositoryData
import com.lagradost.cloudstream4.desktop.data.AppDirs
import com.lagradost.cloudstream4.desktop.data.SettingsStore
import com.lagradost.cloudstream4.desktop.data.jsonFile
import com.lagradost.cloudstream4.desktop.data.jsonMapper
import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.PersistentMap
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.toPersistentList
import kotlinx.collections.immutable.toPersistentMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
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
    /** [androidOnly] lists Android classes replaced by placeholders, usually a settings screen. */
    data class Loaded(val providers: List<String>, val androidOnly: List<String> = emptyList()) : PluginStatus
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
        // All off the UI thread: the first JSON read sets up the parser's Kotlin support, which
        // takes most of a second on slower TVs and held up the first screen
        scope.launch {
            // Before any extension loads, so ids they add are stored under the right keys
            com.lagradost.cloudstream4.desktop.LibrarySetup.init()
            val savedRepos = reposFile.read()
            val savedInstalled = installedFile.read()
            // Merged, so anything added while the files were being read stays
            _state.update {
                it.copy(
                    repositories = (savedRepos.map { r -> RepositoryState(r) } +
                        it.repositories.filter { r -> savedRepos.none { saved -> saved.url == r.data.url } }).toPersistentList(),
                    installed = (savedInstalled + it.installed.filter { p -> savedInstalled.none { saved -> saved.filePath == p.filePath } }).toPersistentList(),
                )
            }
            DemoProvider.setEnabled(SettingsStore.state.value.demoProvider)
            loadAllInstalled()
            // Checking repositories for updates can wait until the first screen has loaded
            delay(REFRESH_DELAY_MS)
            _state.value.repositories.forEach { refreshRepository(it.data.url) }
        }
    }

    private const val REFRESH_DELAY_MS = 15_000L

    fun dismissMessage() = _state.update { it.copy(message = null) }

    fun setDemoProvider(enabled: Boolean) {
        SettingsStore.update { it.copy(demoProvider = enabled) }
        if (DemoProvider.setEnabled(enabled)) providersChanged()
    }

    // ---------- Repositories ----------

    fun repositories(): List<RepositoryData> = _state.value.repositories.map { it.data }

    /**
     * Adds what the user typed: a repository, or a list of repositories such as CloudStream's
     * community list, in which case every repository in it is added.
     */
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
        val json = runCatching { RepositoryClient.fetchJson(url) }.getOrElse { e ->
            _state.update { it.copy(message = "Could not read repository: ${e.message}") }
            return@launch
        }

        val list = RepositoryClient.repositoryList(json)
        if (list != null) {
            val added = addRepositoryList(list)
            _state.update {
                it.copy(message = "Added $added of ${list.size} repositories from this list. Open one to install extensions.")
            }
            return@launch
        }

        val repo = runCatching { jsonMapper.treeToValue(json, Repository::class.java) }
            .getOrElse { e ->
                _state.update { it.copy(message = "That link isn't a CloudStream repository: ${e.message}") }
                return@launch
            }
        if (addRepositoryData(RepositoryData(repo.iconUrl, repo.name, url), repo)) {
            _state.update { it.copy(message = "Added ${repo.name}.") }
        }
    }

    /** Adds each repository in a list, reading its name first. Returns how many were added. */
    private suspend fun addRepositoryList(urls: List<String>): Int {
        val known = repositories().map { it.url }.toSet()
        val limit = Semaphore(6)
        return urls.filter { it !in known }.map { repoUrl ->
            scope.async {
                limit.withPermit {
                    val repo = try {
                        RepositoryClient.fetchRepository(repoUrl)
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (_: Throwable) {
                        null
                    } ?: return@withPermit false
                    addRepositoryData(RepositoryData(repo.iconUrl, repo.name, repoUrl), repo)
                }
            }
        }.awaitAll().count { it }
    }

    /**
     * Adds a repository without asking the user, the way the Android RepositoryManager does.
     * Extensions call this through RepositoryManager.addRepository. Returns false if it was already added.
     */
    suspend fun addRepositoryData(data: RepositoryData, known: Repository? = null): Boolean {
        val added = lock.withLock {
            val repos = reposFile.read()
            if (repos.any { it.url == data.url }) return@withLock false
            reposFile.write(repos + data)
            _state.update { s ->
                if (s.repositories.any { it.data.url == data.url }) s
                else s.copy(repositories = (s.repositories + RepositoryState(data, known)).toPersistentList())
            }
            true
        }
        if (added) refreshRepository(data.url)
        return added
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
            fixStoredName(url, repo)
        }.onFailure { e ->
            updateRepo(url) { it.copy(loading = false, error = repositoryError(url, e)) }
        }
    }

    private fun repositoryError(url: String, e: Throwable): String = when (e) {
        is com.fasterxml.jackson.core.JsonProcessingException ->
            "${runCatching { java.net.URI(url).host }.getOrNull() ?: url} doesn't contain a valid CloudStream repository file."
        else -> e.message ?: "Could not load repository"
    }

    /**
     * Repositories added while their site was unreachable are saved as "No name" (the Android
     * app and extensions such as MegaProvider do this). Once the repository loads, keep its real
     * name and icon.
     */
    private suspend fun fixStoredName(url: String, repo: Repository) {
        val stored = _state.value.repositories.firstOrNull { it.data.url == url }?.data ?: return
        if (stored.name.isNotBlank() && stored.name != "No name" && (stored.iconUrl != null || repo.iconUrl == null)) return
        val updated = stored.copy(
            name = stored.name.takeUnless { it.isBlank() || it == "No name" } ?: repo.name,
            iconUrl = stored.iconUrl ?: repo.iconUrl,
        )
        lock.withLock { reposFile.write(reposFile.read().map { if (it.url == url) updated else it }) }
        updateRepo(url) { it.copy(data = updated) }
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
            PluginStatus.Loaded(plugin.providerNames, plugin.stubbed)
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
