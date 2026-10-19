package com.lagradost.cloudstream4.desktop.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.lagradost.cloudstream4.desktop.download.DownloadEntry
import com.lagradost.cloudstream4.desktop.download.DownloadManager
import com.lagradost.cloudstream4.desktop.download.DownloadStatus

/**
 * One button that reflects a download's state: start, pause while running (shows progress),
 * resume when paused, retry when failed, and a check mark when done.
 */
@Composable
fun DownloadButton(entry: DownloadEntry?, tint: Color = MaterialTheme.colorScheme.onSurface, onStart: () -> Unit) {
    val status = entry?.status
    val description = when (status) {
        null -> "Download"
        DownloadStatus.Queued -> "Waiting to download, click to pause"
        DownloadStatus.Downloading -> "Downloading ${entry.progress?.let { "${(it * 100).toInt()}%" } ?: ""}, click to pause"
        DownloadStatus.Paused -> "Download paused, click to resume"
        DownloadStatus.Failed -> "Download failed: ${entry.error ?: ""}. Click to retry"
        DownloadStatus.Completed -> "Downloaded"
    }
    IconButton(
        onClick = {
            when (status) {
                null -> onStart()
                DownloadStatus.Queued, DownloadStatus.Downloading -> DownloadManager.pause(entry.id)
                DownloadStatus.Paused, DownloadStatus.Failed -> DownloadManager.resume(entry.id)
                DownloadStatus.Completed -> Unit
            }
        },
    ) {
        Box(contentAlignment = Alignment.Center) {
            when (status) {
                DownloadStatus.Queued -> CircularProgressIndicator(Modifier.size(26.dp), strokeWidth = 2.dp)
                DownloadStatus.Downloading, DownloadStatus.Paused -> {
                    val progress = entry.progress
                    if (progress != null) {
                        CircularProgressIndicator(progress = { progress }, modifier = Modifier.size(26.dp), strokeWidth = 2.dp)
                    } else if (status == DownloadStatus.Downloading) {
                        CircularProgressIndicator(Modifier.size(26.dp), strokeWidth = 2.dp)
                    }
                }
                else -> Unit
            }
            Icon(
                when (status) {
                    null -> Icons.Default.Download
                    DownloadStatus.Queued, DownloadStatus.Downloading -> Icons.Default.Pause
                    DownloadStatus.Paused -> Icons.Default.Download
                    DownloadStatus.Failed -> Icons.Default.ErrorOutline
                    DownloadStatus.Completed -> Icons.Default.DownloadDone
                },
                contentDescription = description,
                tint = when (status) {
                    DownloadStatus.Failed -> MaterialTheme.colorScheme.error
                    DownloadStatus.Completed -> MaterialTheme.colorScheme.primary
                    else -> tint
                },
                modifier = Modifier.size(if (status == DownloadStatus.Downloading || status == DownloadStatus.Queued || status == DownloadStatus.Paused) 14.dp else 22.dp),
            )
        }
    }
}

fun formatBytes(bytes: Long): String = when {
    bytes >= 1L shl 30 -> "%.2f GB".format(bytes / (1L shl 30).toDouble())
    bytes >= 1L shl 20 -> "%.1f MB".format(bytes / (1L shl 20).toDouble())
    bytes >= 1L shl 10 -> "%.0f KB".format(bytes / (1L shl 10).toDouble())
    else -> "$bytes B"
}
