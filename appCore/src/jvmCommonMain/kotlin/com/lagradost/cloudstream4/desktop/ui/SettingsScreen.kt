package com.lagradost.cloudstream4.desktop.ui

import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.lagradost.cloudstream4.desktop.DesktopToasts
import com.lagradost.cloudstream4.desktop.data.AppDirs
import com.lagradost.cloudstream4.desktop.data.LibraryStore
import com.lagradost.cloudstream4.desktop.data.SettingsStore
import com.lagradost.cloudstream4.desktop.download.DownloadManager
import com.lagradost.cloudstream4.desktop.plugins.ExtensionManager
import com.lagradost.cloudstream4.theme.CloudStreamPrimaryColor
import com.lagradost.cloudstream4.theme.CloudStreamThemeMode
import com.lagradost.cloudstream4.desktop.Platform
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(vlcAvailable: Boolean) {
    val settings by SettingsStore.state.collectAsState()
    var confirmClear by remember { mutableStateOf(false) }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Clear watch history?") },
            text = { Text("Progress and Continue watching are removed. Bookmarks stay.") },
            confirmButton = { TextButton(onClick = { LibraryStore.clearHistory(); confirmClear = false }) { Text("Clear") } },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } },
        )
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = if (appLayout.compact) 16.dp else appLayout.gutter, vertical = if (appLayout.compact) 16.dp else 24.dp).widthIn(max = 760.dp)) {
        Text("Settings", style = if (appLayout.compact) MaterialTheme.typography.headlineMedium else MaterialTheme.typography.headlineLarge)

        run {
            // Phones and TVs have no Extensions tab, so they live here, as in the Android app (and
            // as a shortcut everywhere else)
            val nav = LocalNavigator.current
            val extensions by com.lagradost.cloudstream4.desktop.plugins.ExtensionManager.state.collectAsState()
            androidx.compose.material3.ListItem(
                headlineContent = { Text("Extensions") },
                supportingContent = { Text("${extensions.repositories.size} repositories · ${extensions.installed.size} installed") },
                leadingContent = { androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.Default.Extension, null) },
                trailingContent = { androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.AutoMirrored.Filled.KeyboardArrowRight, null) },
                modifier = Modifier.padding(top = 12.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(12.dp)).clickable { nav.stack.add(Route.Extensions) },
            )
        }

        Section("Sync")
        SyncSettings()

        Section("Appearance")
        Label("Theme")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            CloudStreamThemeMode.entries.filter { it != CloudStreamThemeMode.Dynamic }.forEach { mode ->
                FilterChip(
                    selected = settings.themeMode == mode,
                    onClick = { SettingsStore.update { it.copy(themeMode = mode) } },
                    label = { Text(if (mode == CloudStreamThemeMode.FollowSystem) "System" else mode.name) },
                )
            }
        }
        Label("Accent color")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            CloudStreamPrimaryColor.entries.forEach { color ->
                val selected = settings.primaryColor == color
                Box(
                    // 40 points: big enough to tap, and named for screen readers
                    Modifier.size(40.dp)
                        .background(color.swatch, CircleShape)
                        .border(if (selected) 3.dp else 0.dp, MaterialTheme.colorScheme.onBackground, CircleShape)
                        .clip(CircleShape)
                        .clickable(indication = tvIndication ?: androidx.compose.foundation.LocalIndication.current, interactionSource = null) {
                            SettingsStore.update { it.copy(primaryColor = color) }
                        }
                        .semantics {
                            contentDescription = color.name.lowercase().replaceFirstChar { it.uppercase() } + " accent"
                            this.selected = selected
                        }
                )
            }
        }

        Section("Player")
        if (!vlcAvailable) {
            Text(
                "VLC wasn't found. Install VLC from videolan.org into Applications, then restart Hyper Stream to play videos.",
                color = MaterialTheme.colorScheme.error,
            )
        }
        Toggle("Play next episode automatically", settings.autoPlayNext) { v -> SettingsStore.update { it.copy(autoPlayNext = v) } }
        if (Platform.isTouch) {
            Toggle("Swipe for brightness (left) and volume (right)", settings.swipeControls) { v -> SettingsStore.update { it.copy(swipeControls = v) } }
        }
        Toggle("Start playing as soon as a source is found", settings.playFirstSource) { v -> SettingsStore.update { it.copy(playFirstSource = v) } }
        Label("Streaming connections")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(1, 4, 8, 16).forEach { n ->
                FilterChip(
                    selected = settings.streamConnections == n,
                    onClick = { SettingsStore.update { it.copy(streamConnections = n) } },
                    label = { Text(if (n == 1) "1 (off)" else "$n") },
                )
            }
        }
        Text(
            "Fetches the video ahead over several connections at once, like downloads, so playback isn't held back " +
                "by one slow connection. Lower this if a host refuses or playback stalls.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Toggle("Show skip intro buttons", settings.skipButtons) { v -> SettingsStore.update { it.copy(skipButtons = v) } }
        Toggle("Skip intros and recaps automatically", settings.autoSkipIntros && settings.skipButtons) { v ->
            SettingsStore.update { it.copy(autoSkipIntros = v, skipButtons = it.skipButtons || v) }
        }
        Text(
            "Intro, recap and credit times come from AniSkip, TheIntroDB and IntroDB, the same sources as the Android app. " +
                "Not every episode has them. " + when {
                    com.lagradost.cloudstream4.desktop.Platform.isTv -> "Press OK while the Skip button shows."
                    com.lagradost.cloudstream4.desktop.Platform.isTouch -> "Tap the Skip button when it shows."
                    else -> "Press S in the player to skip."
                },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            "Other sources keep loading in the background and appear under Sources. When off, the player waits for all sources, or up to 8 seconds.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Label("Play first")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(null to "Highest (4K first)", 1080 to "1080p", 720 to "720p", 480 to "480p", 360 to "360p").forEach { (q, label) ->
                FilterChip(
                    selected = settings.preferredQuality == q,
                    onClick = { SettingsStore.update { it.copy(preferredQuality = q) } },
                    label = { Text(label) },
                )
            }
        }
        Text(
            "A chosen quality plays first, then the nearest lower one, then higher ones.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Label("Order sources by")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("quality" to "Quality, then my servers", "servers" to "My servers, then quality").forEach { (v, label) ->
                FilterChip(
                    selected = settings.sourceOrder == v,
                    onClick = { SettingsStore.update { it.copy(sourceOrder = v) } },
                    label = { Text(label) },
                )
            }
        }
        Toggle("Wait for a source that matches these", settings.waitForPreferred) { v -> SettingsStore.update { it.copy(waitForPreferred = v) } }
        Text(
            "Instead of starting with the first source found, wait up to 30 seconds for one of your servers or the quality above.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Label("My servers")
        if (settings.preferredSources.isEmpty() && settings.avoidedSources.isEmpty()) {
            Text(
                "Star a server in the player's Sources panel to try it first, or block one to try it last.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        settings.preferredSources.forEachIndexed { i, name ->
            ServerRow("${i + 1}. $name", "Preferred", canMoveUp = i > 0, onMoveUp = { SourcePreferences.moveUp(name) }) {
                SourcePreferences.togglePreferred(name)
            }
        }
        settings.avoidedSources.forEach { name ->
            ServerRow(name, "Avoided", canMoveUp = false, onMoveUp = {}) { SourcePreferences.toggleAvoided(name) }
        }
        Label("Seek step")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(5, 10, 15, 30).forEach { s ->
                FilterChip(
                    selected = settings.seekSeconds == s,
                    onClick = { SettingsStore.update { it.copy(seekSeconds = s) } },
                    label = { Text("${s}s") },
                )
            }
        }

        Section("Downloads")
        // Re-read the folder when Android's access to Downloads is granted or taken away
        val storageRevision by Platform.storageRevision.collectAsState()
        val folder = remember(storageRevision, settings.downloadFolder) { DownloadManager.folder }
        Text(folder.path, style = MaterialTheme.typography.bodyMedium)
        if (Platform.downloadFolderNeedsAccess) {
            Text(
                "Allow access to files to save downloads in the Downloads folder. Until then they stay in Hyper Stream's own storage.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(top = 8.dp)) {
            val folderScope = rememberCoroutineScope()
            if (Platform.downloadFolderNeedsAccess) {
                OutlinedButton(onClick = {
                    if (Platform.requestDownloadFolderAccess()) DesktopToasts.show("Allow access to files, then come back to Settings.")
                }) { Text("Allow access") }
            }
            OutlinedButton(onClick = {
                folderScope.launch {
                    Platform.chooseFolder("Choose a download folder")?.let { dir ->
                        SettingsStore.update { it.copy(downloadFolder = dir.path) }
                        DesktopToasts.show("New downloads go to ${dir.path}.")
                    }
                }
            }) { Text("Change folder") }
            if (settings.downloadFolder != null) {
                OutlinedButton(onClick = { SettingsStore.update { it.copy(downloadFolder = null) } }) { Text("Use the default folder") }
            }
        }
        Label("Downloads at the same time")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(1, 2, 3, 4).forEach { n ->
                FilterChip(
                    selected = settings.parallelDownloads == n,
                    onClick = {
                        SettingsStore.update { it.copy(parallelDownloads = n) }
                        DownloadManager.limitChanged()
                    },
                    label = { Text("$n") },
                )
            }
        }
        Text(
            "Other downloads wait and start one by one as these finish.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp),
        )
        Label("Connections per download")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(1, 4, 8, 16).forEach { n ->
                FilterChip(
                    selected = settings.downloadConnections == n,
                    onClick = { SettingsStore.update { it.copy(downloadConnections = n) } },
                    label = { Text(if (n == 1) "1 (off)" else "$n") },
                )
            }
        }
        Text(
            "Downloads a file in parts over several connections at once, when the host allows it. " +
                "Some hosts limit connections, so lower this if downloads fail.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp),
        )
        Text(
            "Finished downloads stay where they are when you change the folder. Move them from the Downloads screen.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp),
        )

        Section("Extensions")
        Toggle("Show demo provider with open movies", settings.demoProvider) { v -> ExtensionManager.setDemoProvider(v) }
        Text(
            "Adds Big Buck Bunny, Tears of Steel and Apple's test stream, so you can try the app without third-party extensions.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Section("Data")
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (Platform.canOpenFolders) OutlinedButton(onClick = { Platform.openFolder(AppDirs.root) }) { Text("Open data folder") }
            OutlinedButton(onClick = { confirmClear = true }) { Text("Clear watch history") }
        }
        Text(AppDirs.root.absolutePath, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))

        Section("Updates")
        UpdateSettings()

        Section("About")
        Text(
            "Hyper Stream for Mac, Android and Android TV, built on CloudStream's open-source library. " +
                "Extensions are third-party and Hyper Stream does not host any content. Licensed under GPL-3.0.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            "${Platform.description} · ${if (vlcAvailable) "Player ready" else "No video player"}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

@Composable
private fun Section(title: String) {
    Spacer(Modifier.size(24.dp))
    // Secondary: a lighter tint of the accent, readable as text on dark backgrounds
    Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.secondary)
    HorizontalDivider(Modifier.padding(vertical = 8.dp))
}

@Composable
private fun Label(text: String) =
    Text(text, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 12.dp, bottom = 6.dp))

@Composable
private fun Toggle(text: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun ServerRow(name: String, kind: String, canMoveUp: Boolean, onMoveUp: () -> Unit, onRemove: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(name, Modifier.weight(1f))
        Text(kind, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        androidx.compose.material3.IconButton(onClick = onMoveUp, enabled = canMoveUp) {
            androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.Default.KeyboardArrowUp, "Move up")
        }
        androidx.compose.material3.IconButton(onClick = onRemove) {
            androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.Default.Close, "Remove")
        }
    }
}
