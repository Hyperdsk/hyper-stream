package com.lagradost.cloudstream4.desktop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lagradost.cloudstream4.desktop.download.DownloadEntry
import com.lagradost.cloudstream4.desktop.download.DownloadManager
import com.lagradost.cloudstream4.desktop.download.DownloadStatus
import com.lagradost.cloudstream4.desktop.Platform
import java.io.File

@Composable
fun DownloadsScreen() {
    val nav = LocalNavigator.current
    val entries by DownloadManager.entries.collectAsState()
    var confirmDelete by remember { mutableStateOf<DownloadEntry?>(null) }

    confirmDelete?.let { entry ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Delete download?") },
            text = { Text("${entry.title}${entry.playable.label.takeIf { entry.season != null || entry.episode != null }?.let { " · $it" } ?: ""} will be removed from your Mac.") },
            confirmButton = { TextButton(onClick = { DownloadManager.delete(entry.id); confirmDelete = null }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Cancel") } },
        )
    }

    // Newest titles first, episodes in order within a title
    val groups = entries.groupBy { it.apiName to it.parentUrl }
        .values
        .sortedByDescending { group -> group.maxOf { it.addedAt } }
        .map { group -> group.sortedWith(compareBy({ it.season ?: 0 }, { it.episode ?: 0 })) }
    val used = entries.filter { it.status == DownloadStatus.Completed }.sumOf { it.sizeOnDisk }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 16.dp, top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Downloads", style = MaterialTheme.typography.headlineLarge)
                val downloading = entries.count { it.status == DownloadStatus.Downloading }
                val waiting = entries.count { it.status == DownloadStatus.Queued }
                Text(
                    listOfNotNull(
                        downloading.takeIf { it > 0 }?.let { "$it downloading" },
                        waiting.takeIf { it > 0 }?.let { "$it waiting" },
                        "${formatBytes(used)} in ${DownloadManager.folder.path.replace(System.getProperty("user.home").orEmpty().ifEmpty { "\u0000" }, "~")}",
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (Platform.canOpenFolders) OutlinedButton(onClick = { Platform.openFolder(DownloadManager.folder) }) {
                Icon(Icons.Default.FolderOpen, null)
                Spacer(Modifier.width(6.dp))
                Text("Open folder")
            }
        }

        if (entries.isEmpty()) {
            MessageBox(
                "No downloads yet",
                "Use the download button on a movie or episode to watch it offline.",
            )
            return
        }

        LazyColumn(contentPadding = PaddingValues(if (appLayout.compact) 12.dp else 24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            items(groups, key = { it.first().apiName + "|" + it.first().parentUrl }) { group ->
                val first = group.first()
                Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)) {
                    Row(Modifier.padding(14.dp)) {
                        RemoteImage(
                            first.posterUrl,
                            first.posterHeaders,
                            Modifier.width(84.dp).aspectRatio(2f / 3f).clip(RoundedCornerShape(8.dp)),
                        )
                        Spacer(Modifier.width(16.dp))
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(first.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                    val done = group.count { it.status == DownloadStatus.Completed }
                                    Text(
                                        listOfNotNull(first.apiName, if (group.size > 1) "$done of ${group.size} downloaded" else null).joinToString(" · "),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                // Whole-season controls, once there is more than one episode
                                if (group.size > 1) {
                                    val running = group.any { it.status == DownloadStatus.Downloading || it.status == DownloadStatus.Queued }
                                    val stopped = group.any { it.status == DownloadStatus.Paused || it.status == DownloadStatus.Failed }
                                    if (running) TextButton(onClick = { DownloadManager.pauseAll(first.apiName, first.parentUrl) }) { Text("Pause all") }
                                    if (stopped) TextButton(onClick = { DownloadManager.resumeAll(first.apiName, first.parentUrl) }) { Text("Resume all") }
                                }
                            }
                            Spacer(Modifier.height(6.dp))
                            group.forEach { entry ->
                                DownloadRow(
                                    entry = entry,
                                    showLabel = group.size > 1 || entry.season != null || entry.episode != null,
                                    onPlay = { request -> nav.open(Route.Player(request)) },
                                    onDelete = { confirmDelete = entry },
                                    playable = group,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DownloadRow(
    entry: DownloadEntry,
    showLabel: Boolean,
    playable: List<DownloadEntry>,
    onPlay: (PlayRequest) -> Unit,
    onDelete: () -> Unit,
) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            if (showLabel) {
                Text(entry.playable.label, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text(
                statusText(entry),
                style = MaterialTheme.typography.bodySmall,
                color = if (entry.status == DownloadStatus.Failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (entry.status != DownloadStatus.Completed) {
                Spacer(Modifier.height(4.dp))
                val progress = entry.progress
                if (progress != null) {
                    LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth().height(4.dp))
                } else if (entry.status == DownloadStatus.Downloading) {
                    LinearProgressIndicator(Modifier.fillMaxWidth().height(4.dp))
                }
            }
        }
        Spacer(Modifier.width(8.dp))
        when (entry.status) {
            DownloadStatus.Completed -> {
                IconButton(onClick = {
                    // Play this item, with the title's other downloaded items as next and previous
                    val done = playable.filter { it.status == DownloadStatus.Completed }
                    onPlay(
                        PlayRequest(
                            apiName = entry.apiName,
                            parentUrl = entry.parentUrl,
                            title = entry.title,
                            posterUrl = entry.posterUrl,
                            posterHeaders = entry.posterHeaders,
                            episodes = done.map { it.playable },
                            index = done.indexOfFirst { it.id == entry.id }.coerceAtLeast(0),
                        )
                    )
                }) { Icon(Icons.Default.PlayArrow, "Play") }
                if (Platform.canOpenFolders) IconButton(onClick = { entry.file?.let(::reveal) }) { Icon(Icons.Default.FolderOpen, "Show in Finder") }
            }
            DownloadStatus.Downloading, DownloadStatus.Queued ->
                IconButton(onClick = { DownloadManager.pause(entry.id) }) { Icon(Icons.Default.Pause, "Pause") }
            DownloadStatus.Paused ->
                IconButton(onClick = { DownloadManager.resume(entry.id) }) { Icon(Icons.Default.Download, "Resume") }
            DownloadStatus.Failed ->
                IconButton(onClick = { DownloadManager.resume(entry.id) }) { Icon(Icons.Default.Refresh, "Retry") }
        }
        IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, "Delete") }
    }
}

private fun statusText(entry: DownloadEntry): String {
    val size = when {
        entry.bytesTotal != null && entry.status != DownloadStatus.Completed -> "${formatBytes(entry.bytesDone)} of ${formatBytes(entry.bytesTotal)}"
        entry.segmentsTotal != null && entry.status != DownloadStatus.Completed -> "${formatBytes(entry.bytesDone)}, part ${entry.segmentsDone} of ${entry.segmentsTotal}"
        else -> null
    }
    val percent = entry.progress?.let { "${(it * 100).toInt()}%" }
    return when (entry.status) {
        DownloadStatus.Queued -> DownloadManager.queuePosition(entry.id)?.let { "Waiting · ${ordinal(it)} in line" } ?: "Waiting"
        DownloadStatus.Downloading -> listOfNotNull(
            percent,
            size,
            entry.speed.takeIf { it > 0 }?.let { "${formatBytes(it)}/s" },
            entry.connections.takeIf { it > 1 }?.let { "$it connections" },
            entry.link?.info()?.title,
        ).joinToString(" · ").ifBlank { "Finding a source…" }
        DownloadStatus.Paused -> listOfNotNull("Paused", percent, size).joinToString(" · ")
        DownloadStatus.Failed -> entry.error ?: "Failed"
        DownloadStatus.Completed -> listOfNotNull(
            entry.sizeOnDisk.takeIf { it > 0 }?.let(::formatBytes),
            if (entry.isPackage) "HLS" else entry.file?.extension?.uppercase(),
            entry.subtitles.size.takeIf { it > 0 }?.let { "$it subtitles" },
            if (entry.file?.exists() == false) "file missing" else null,
        ).joinToString(" · ")
    }
}

private fun reveal(file: File) = Platform.revealFile(file)

private fun ordinal(n: Int): String {
    val suffix = if (n % 100 in 11..13) "th" else when (n % 10) { 1 -> "st"; 2 -> "nd"; 3 -> "rd"; else -> "th" }
    return "$n$suffix"
}
