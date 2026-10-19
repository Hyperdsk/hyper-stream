package com.lagradost.cloudstream4.desktop.ui

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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.lagradost.cloudstream4.desktop.data.AppDirs
import com.lagradost.cloudstream4.desktop.data.LibraryStore
import com.lagradost.cloudstream4.desktop.data.SettingsStore
import com.lagradost.cloudstream4.desktop.download.DownloadManager
import com.lagradost.cloudstream4.desktop.plugins.ExtensionManager
import com.lagradost.cloudstream4.theme.CloudStreamPrimaryColor
import com.lagradost.cloudstream4.theme.CloudStreamThemeMode
import java.awt.Desktop

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

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp).widthIn(max = 760.dp)) {
        Text("Settings", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)

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
                    Modifier.size(32.dp)
                        .background(color.color, CircleShape)
                        .border(if (selected) 3.dp else 0.dp, MaterialTheme.colorScheme.onBackground, CircleShape)
                        .clickable { SettingsStore.update { it.copy(primaryColor = color) } }
                )
            }
        }

        Section("Player")
        if (!vlcAvailable) {
            Text(
                "VLC wasn't found. Install VLC from videolan.org into Applications, then restart CloudStream to play videos.",
                color = MaterialTheme.colorScheme.error,
            )
        }
        Toggle("Play next episode automatically", settings.autoPlayNext) { v -> SettingsStore.update { it.copy(autoPlayNext = v) } }
        Toggle("Start playing as soon as a source is found", settings.playFirstSource) { v -> SettingsStore.update { it.copy(playFirstSource = v) } }
        Text(
            "Other sources keep loading in the background and appear under Sources. When off, the player waits for all sources, or up to 8 seconds.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Label("Preferred quality")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(null to "Best", 2160 to "4K", 1080 to "1080p", 720 to "720p", 480 to "480p", 360 to "360p").forEach { (q, label) ->
                FilterChip(
                    selected = settings.preferredQuality == q,
                    onClick = { SettingsStore.update { it.copy(preferredQuality = q) } },
                    label = { Text(label) },
                )
            }
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
        Text(DownloadManager.folder.path, style = MaterialTheme.typography.bodyMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(top = 8.dp)) {
            OutlinedButton(onClick = {
                chooseFolder()?.let { path -> SettingsStore.update { it.copy(downloadFolder = path) } }
            }) { Text("Change folder") }
            if (settings.downloadFolder != null) {
                OutlinedButton(onClick = { SettingsStore.update { it.copy(downloadFolder = null) } }) { Text("Use Movies folder") }
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
            "Finished downloads stay where they are when you change the folder.",
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
            OutlinedButton(onClick = { runCatching { Desktop.getDesktop().open(AppDirs.root) } }) { Text("Open data folder") }
            OutlinedButton(onClick = { confirmClear = true }) { Text("Clear watch history") }
        }
        Text(AppDirs.root.absolutePath, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))

        Section("About")
        Text(
            "CloudStream for Mac, built on CloudStream's cross-platform library. " +
                "Extensions are third-party and CloudStream does not host any content. Licensed under GPL-3.0.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            "Java ${System.getProperty("java.version")} · ${System.getProperty("os.name")} ${System.getProperty("os.arch")} · VLC ${if (vlcAvailable) "found" else "missing"}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

@Composable
private fun Section(title: String) {
    Spacer(Modifier.size(24.dp))
    Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
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

/** The native macOS folder picker. Returns null when cancelled. */
private fun chooseFolder(): String? {
    System.setProperty("apple.awt.fileDialogForDirectories", "true")
    return try {
        val dialog = java.awt.FileDialog(null as java.awt.Frame?, "Choose a download folder", java.awt.FileDialog.LOAD)
        dialog.directory = DownloadManager.folder.path
        dialog.isVisible = true
        dialog.file?.let { java.io.File(dialog.directory, it).path }
    } finally {
        System.setProperty("apple.awt.fileDialogForDirectories", "false")
    }
}
