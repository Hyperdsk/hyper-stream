package com.lagradost.cloudstream4.desktop.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Update
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lagradost.cloudstream4.desktop.data.AppDirs
import com.lagradost.cloudstream4.desktop.plugins.ExtensionManager
import com.lagradost.cloudstream4.desktop.plugins.InstalledPlugin
import com.lagradost.cloudstream4.desktop.plugins.PluginStatus
import com.lagradost.cloudstream4.desktop.plugins.RepositoryState
import com.lagradost.cloudstream3.plugins.SitePlugin
import com.lagradost.cloudstream4.desktop.Platform

@Composable
fun ExtensionsScreen() {
    val state by ExtensionManager.state.collectAsState()
    var openRepo by rememberSaveable { mutableStateOf<String?>(null) }
    val repo = state.repositories.firstOrNull { it.data.url == openRepo }

    if (state.message != null) {
        AlertDialog(
            onDismissRequest = ExtensionManager::dismissMessage,
            confirmButton = { TextButton(onClick = ExtensionManager::dismissMessage) { Text("OK") } },
            text = { Text(state.message!!) },
        )
    }

    // Back (Android, the TV remote) closes an open repository before leaving Extensions
    PlatformBackHandler(enabled = repo != null) { openRepo = null }
    if (repo != null) {
        RepositoryScreen(repo, onBack = { openRepo = null })
    } else {
        ExtensionsOverview(onOpenRepo = { openRepo = it })
    }
}

@Composable
private fun ExtensionsOverview(onOpenRepo: (String) -> Unit) {
    val state by ExtensionManager.state.collectAsState()
    var input by remember { mutableStateOf("") }
    // A repository over plain http: anyone on the network (public Wi-Fi, the ISP) could swap its
    // extensions for harmful code, which then runs inside the app. Ask before adding one
    var insecure by remember { mutableStateOf<String?>(null) }
    fun addChecked(text: String) {
        if (Regex("(?i)\\bhttp://").containsMatchIn(text)) insecure = text else ExtensionManager.addRepository(text)
    }
    insecure?.let { text ->
        ConfirmDialog(
            "Add a repository that isn't secure?",
            "It uses http, not https: someone on your network could change its extensions into harmful code that runs in Hyper Stream. Add it only if you trust the network.",
            "Add anyway",
            onConfirm = { ExtensionManager.addRepository(text) },
            onDismiss = { insecure = null },
        )
    }
    var query by rememberSaveable { mutableStateOf("") }
    val q = query.trim()

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = appLayout.gutter, end = appLayout.gutter, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            LargeTitle("Extensions", inset = false, trailing = { Row(verticalAlignment = Alignment.CenterVertically) {
                if (Platform.canOpenFolders) IconButton(onClick = { Platform.openFolder(AppDirs.extensions) }) {
                    Icon(Icons.Default.FolderOpen, "Open extensions folder")
                }
                IconButton(onClick = { ExtensionManager.reloadLocal() }) { Icon(Icons.Default.Refresh, "Load local extensions") }
                if (appLayout.compact) {
                    IconButton(onClick = { ExtensionManager.updateAll() }) { Icon(Icons.Default.Update, "Update all") }
                } else {
                    Button(onClick = { ExtensionManager.updateAll() }) {
                        Icon(Icons.Default.Update, null)
                        Spacer(Modifier.width(6.dp))
                        Text("Update all")
                    }
                }
            } })
            Text(
                "Extensions are the same files the Android app uses. Add a repository by link or short code, " +
                    "paste a list of repositories to add them all" + if (Platform.canOpenFolders) ", or drop .cs3 files into the extensions folder." else ".",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 8.dp)) {
                TvTextFieldBox(Modifier.weight(1f)) { fieldModifier ->
                    OutlinedTextField(
                        value = input,
                        onValueChange = { input = it },
                        label = { Text("Repository link or code") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { if (input.isNotBlank()) { addChecked(input); input = "" } }),
                        modifier = fieldModifier,
                    )
                }
                Spacer(Modifier.width(12.dp))
                Button(enabled = input.isNotBlank(), onClick = { addChecked(input); input = "" }) { Text("Add") }
            }
        }

        item {
            TvTextFieldBox(Modifier.fillMaxWidth().padding(top = 8.dp)) { fieldModifier ->
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("Search repositories and extensions") },
                leadingIcon = { Icon(Icons.Default.Search, null) },
                trailingIcon = {
                    if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Default.Clear, "Clear search") }
                },
                singleLine = true,
                modifier = fieldModifier,
            )
            }
        }

        val repos = state.repositories.filter {
            matches(q, it.repository?.name ?: it.data.name, it.repository?.description, it.data.url)
        }
        item { SectionTitle(if (q.isEmpty()) "Repositories" else "Repositories (${repos.size})") }
        if (repos.isEmpty()) {
            item { Hint(if (q.isEmpty()) "No repositories added." else "No repositories match.") }
        }
        items(repos, key = { it.data.url }) { repo ->
            RepoCard(repo, onClick = { onOpenRepo(repo.data.url) })
        }

        // While searching, also list matching extensions from every repository, ready to install
        if (q.isNotEmpty()) {
            val found = state.repositories.flatMap { repo ->
                repo.plugins
                    .filter { matches(q, it.name, it.internalName, it.description, it.language, it.tvTypes?.joinToString(), it.authors.joinToString()) }
                    .map { repo to it }
            }
            item { SectionTitle("Extensions (${found.size})") }
            if (found.isEmpty()) item { Hint("No extensions match.") }
            items(found.take(MAX_RESULTS), key = { (repo, plugin) -> "found|${repo.data.url}|${plugin.internalName}" }) { (repo, plugin) ->
                val installed = state.installed.firstOrNull { it.repositoryUrl == repo.data.url && it.internalName == plugin.internalName }
                PluginRow(
                    plugin = plugin,
                    installed = installed,
                    status = installed?.let { state.status[it.filePath] },
                    busy = plugin.internalName in state.busy,
                    repoName = repo.repository?.name ?: repo.data.name,
                    onInstall = { ExtensionManager.install(repo.data.url, plugin) },
                    onUninstall = { installed?.let { ExtensionManager.uninstall(it) } },
                )
            }
            if (found.size > MAX_RESULTS) {
                item { Hint("Showing the first $MAX_RESULTS. Type more to narrow it down.") }
            }
        }

        val installed = state.installed.filter { matches(q, it.name, it.internalName, it.language) }
        val localFiles = state.status.keys
            .filter { path -> state.installed.none { it.filePath == path } }
            .filter { matches(q, java.io.File(it).nameWithoutExtension) }
        item { SectionTitle("Installed") }
        if (installed.isEmpty() && localFiles.isEmpty()) {
            item { Hint(if (q.isEmpty()) "No extensions installed." else "No installed extensions match.") }
        }
        items(installed, key = { it.filePath }) { plugin ->
            InstalledRow(plugin, state.status[plugin.filePath])
        }
        items(localFiles, key = { it }) { path ->
            InstalledRow(null, state.status[path], localName = java.io.File(path).nameWithoutExtension)
        }
    }
}

private const val MAX_RESULTS = 150

/** True when every word of the query appears in at least one of the fields. Blank matches all. */
private fun matches(query: String, vararg fields: String?): Boolean {
    if (query.isBlank()) return true
    val haystack = fields.filterNotNull().joinToString(" ").lowercase()
    return query.lowercase().split(' ').filter { it.isNotBlank() }.all { it in haystack }
}

@Composable
private fun SectionTitle(text: String) =
    Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 16.dp))

@Composable
private fun Hint(text: String) = Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant)

@Composable
private fun RepoCard(repo: RepositoryState, onClick: () -> Unit) {
    // The open area and the buttons are side by side, not nested: a remote can't move focus into
    // a clickable's children, so Refresh and Remove were out of reach on TV
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(end = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(
                Modifier.weight(1f).clip(RoundedCornerShape(10.dp))
                    .clickable(indication = tvIndication ?: androidx.compose.foundation.LocalIndication.current, interactionSource = null, onClick = onClick)
                    .padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RemoteImage(repo.data.iconUrl ?: repo.repository?.iconUrl, null, Modifier.size(40.dp).clip(RoundedCornerShape(8.dp)))
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(repo.repository?.name ?: repo.data.name, style = MaterialTheme.typography.titleSmall)
                    Text(
                        when {
                            repo.loading -> "Loading…"
                            repo.error != null -> repo.error
                            repo.data.url.startsWith("http://", ignoreCase = true) -> "Not secure (http) · ${repo.plugins.size} extensions"
                            else -> "${repo.plugins.size} extensions" + (repo.repository?.description?.let { " · $it" } ?: "")
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (repo.error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            IconButton(onClick = { ExtensionManager.refreshRepository(repo.data.url) }) { Icon(Icons.Default.Refresh, "Refresh") }
            var confirm by remember { mutableStateOf(false) }
            IconButton(onClick = { confirm = true }) { Icon(Icons.Default.Delete, "Remove repository") }
            if (confirm) {
                ConfirmDialog(
                    "Remove ${repo.repository?.name ?: repo.data.name}?",
                    "Its extensions stay installed until you uninstall them.",
                    "Remove",
                    onConfirm = { ExtensionManager.removeRepository(repo.data.url) },
                    onDismiss = { confirm = false },
                )
            }
        }
    }
}

@Composable
private fun InstalledRow(plugin: InstalledPlugin?, status: PluginStatus?, localName: String? = null) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        RemoteImage(plugin?.iconUrl, null, Modifier.size(36.dp).clip(RoundedCornerShape(8.dp)))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                (plugin?.name ?: localName ?: "?") + (plugin?.let { " · v${it.version}" } ?: " · local"),
                style = MaterialTheme.typography.bodyLarge,
            )
            StatusText(status)
        }
        if (plugin != null) {
            var confirm by remember { mutableStateOf(false) }
            IconButton(onClick = { confirm = true }) { Icon(Icons.Default.Delete, "Uninstall") }
            if (confirm) {
                ConfirmDialog(
                    "Uninstall ${plugin.name}?",
                    "Its titles stay in your library, but won't open until it's installed again.",
                    "Uninstall",
                    onConfirm = { ExtensionManager.uninstall(plugin) },
                    onDismiss = { confirm = false },
                )
            }
        }
    }
    HorizontalDivider()
}

@Composable
private fun StatusText(status: PluginStatus?) {
    val (text, error) = when (status) {
        is PluginStatus.Loaded -> listOfNotNull(
            if (status.providers.isEmpty()) "Loaded" else "Providers: " + status.providers.joinToString(),
            androidOnlyNote(status.androidOnly),
        ).joinToString(" · ") to false
        is PluginStatus.Failed -> status.reason to true
        PluginStatus.Loading -> "Loading…" to false
        else -> "Not loaded" to false
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (error) {
            Icon(Icons.Default.Error, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(4.dp))
        }
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun RepositoryScreen(repo: RepositoryState, onBack: () -> Unit) {
    val state by ExtensionManager.state.collectAsState()
    var filter by remember { mutableStateOf("") }
    val plugins = repo.plugins.filter {
        filter.isBlank() || it.name.contains(filter, true) || it.language?.contains(filter, true) == true ||
            it.tvTypes.orEmpty().any { t -> t.contains(filter, true) }
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(start = 12.dp, end = 24.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
            Text(
                repo.repository?.name ?: repo.data.name,
                style = if (appLayout.compact) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.headlineLarge,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (!appLayout.compact) TvTextFieldBox(Modifier.width(260.dp)) { fieldModifier ->
                OutlinedTextField(filter, { filter = it }, placeholder = { Text("Filter") }, singleLine = true, modifier = fieldModifier)
            }
        }
        if (appLayout.compact) {
            TvTextFieldBox(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) { fieldModifier ->
                OutlinedTextField(filter, { filter = it }, placeholder = { Text("Filter") }, singleLine = true, modifier = fieldModifier)
            }
        }
        when {
            repo.loading && repo.plugins.isEmpty() -> LoadingBox()
            repo.error != null && repo.plugins.isEmpty() -> MessageBox("Couldn't load repository", repo.error, "Try again") {
                ExtensionManager.refreshRepository(repo.data.url)
            }

            else -> LazyColumn(contentPadding = PaddingValues(horizontal = if (appLayout.compact) 12.dp else 24.dp, vertical = 12.dp)) {
                items(plugins, key = { it.internalName }) { plugin ->
                    val installed = state.installed.firstOrNull { it.repositoryUrl == repo.data.url && it.internalName == plugin.internalName }
                    PluginRow(
                        plugin = plugin,
                        installed = installed,
                        status = installed?.let { state.status[it.filePath] },
                        busy = plugin.internalName in state.busy,
                        onInstall = { ExtensionManager.install(repo.data.url, plugin) },
                        onUninstall = { installed?.let { ExtensionManager.uninstall(it) } },
                    )
                }
            }
        }
    }
}

@Composable
private fun PluginRow(
    plugin: SitePlugin,
    installed: InstalledPlugin?,
    status: PluginStatus?,
    busy: Boolean,
    repoName: String? = null,
    onInstall: () -> Unit,
    onUninstall: () -> Unit,
) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        RemoteImage(plugin.iconUrl, null, Modifier.size(40.dp).clip(RoundedCornerShape(8.dp)))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(plugin.name, style = MaterialTheme.typography.bodyLarge)
            Text(
                listOfNotNull(
                    "v${plugin.version}",
                    plugin.language?.uppercase(),
                    plugin.tvTypes?.joinToString(),
                    when (plugin.status) { 0 -> "Down"; 2 -> "Slow"; 3 -> "Beta"; else -> null },
                    plugin.authors.takeIf { it.isNotEmpty() }?.joinToString(prefix = "by "),
                    repoName?.let { "from $it" },
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            plugin.description?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            if (installed != null) StatusText(status)
        }
        Spacer(Modifier.width(12.dp))
        when {
            busy -> CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
            installed == null -> IconButton(onClick = onInstall) { Icon(Icons.Default.Download, "Install") }
            plugin.version > installed.version -> IconButton(onClick = onInstall) { Icon(Icons.Default.Update, "Update") }
            else -> IconButton(onClick = onUninstall) { Icon(Icons.Default.Delete, "Uninstall") }
        }
    }
    HorizontalDivider()
}

/** A short note when Android-only parts (usually the extension's settings screen) are turned off. */
private fun androidOnlyNote(classes: List<String>): String? {
    if (classes.isEmpty()) return null
    val ui = classes.any { c ->
        listOf("Fragment", "View", "Dialog", "Layout", "Activity", "Adapter", "Toast", "Preference", "Widget", "R$")
            .any { c.substringAfterLast('.').contains(it) || c.contains(".widget.") }
    }
    return if (ui) "Android settings screen not available" else "Some Android-only features are off"
}
