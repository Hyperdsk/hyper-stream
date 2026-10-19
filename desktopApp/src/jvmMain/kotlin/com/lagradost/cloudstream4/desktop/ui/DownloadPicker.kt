package com.lagradost.cloudstream4.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream4.desktop.download.SavedLink
import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.persistentListOf

/** The source picker shown before a download starts. */
data class DownloadPickerState(
    val episode: PlayableEpisode,
    val links: PersistentList<SavedLink> = persistentListOf(),
    val subtitles: List<SubtitleFile> = emptyList(),
    val loading: Boolean = true,
    val error: String? = null,
)

fun SavedLink.info(): SourceInfo = parseSourceInfo(name, source, quality, type)

/**
 * Lists the downloadable sources, best first, as they arrive. The caller starts the download
 * by itself when loading ends with a single source, so this is only seen when there is a choice.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DownloadPickerDialog(
    state: DownloadPickerState,
    title: String,
    onPick: (SavedLink) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text("Download from")
                val label = state.episode.label.takeIf { state.episode.season != null || state.episode.episode != null }
                Text(
                    listOfNotNull(title, label).joinToString(" · "),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        text = {
            Column(Modifier.width(520.dp)) {
                if (state.loading) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text(
                        if (state.links.isEmpty()) "Finding sources…" else "Finding sources, ${state.links.size} so far",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
                    )
                }
                when {
                    state.error != null -> Text(state.error, color = MaterialTheme.colorScheme.error)
                    state.links.isEmpty() && !state.loading -> Text(
                        "No downloadable sources were found. Streams in DASH or torrent form can't be downloaded.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    else -> LazyColumn(Modifier.heightIn(max = 440.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        itemsIndexed(state.links, key = { i, it -> "$i|${it.url}" }) { i, link ->
                            PickerRow(link.info(), recommended = i == 0) { onPick(link) }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PickerRow(info: SourceInfo, recommended: Boolean, onClick: () -> Unit) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Text(info.title, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                val details = listOfNotNull(info.size, info.format) + info.tags
                if (details.isNotEmpty() || recommended) {
                    Spacer(Modifier.height(6.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (recommended) Chip("Best match", MaterialTheme.colorScheme.primary)
                        details.forEach { Chip(it, if (it in lowQualityTags) Color(0xFFE09A3E) else muted) }
                    }
                }
                info.fileName?.let {
                    Spacer(Modifier.height(4.dp))
                    Text(it, color = muted, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            info.quality?.let {
                Spacer(Modifier.width(10.dp))
                Text(
                    it,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.background(MaterialTheme.colorScheme.primary, RoundedCornerShape(4.dp)).padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
        }
    }
}

@Composable
private fun Chip(text: String, color: Color) {
    Text(
        text,
        color = color,
        style = MaterialTheme.typography.labelSmall,
        modifier = Modifier.background(color.copy(alpha = 0.14f), RoundedCornerShape(4.dp)).padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

/** Confirms downloading a whole season and asks for the quality to aim for. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SeasonDownloadDialog(
    title: String,
    episodes: Int,
    alreadyAdded: Int,
    onConfirm: (maxQuality: Int?) -> Unit,
    onDismiss: () -> Unit,
) {
    val default = com.lagradost.cloudstream4.desktop.data.SettingsStore.state.value.preferredQuality ?: -1
    var quality by androidx.compose.runtime.remember { androidx.compose.runtime.mutableIntStateOf(default) }
    val toAdd = episodes - alreadyAdded
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text("Download season")
                Text(title, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        text = {
            Column(Modifier.width(440.dp)) {
                Text(
                    when {
                        toAdd <= 0 -> "All $episodes episodes are already in Downloads. Unfinished ones will be resumed."
                        alreadyAdded > 0 -> "$toAdd of $episodes episodes will be added. The other $alreadyAdded are already in Downloads."
                        else -> "$episodes episodes will be added to Downloads."
                    },
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(16.dp))
                Text("Quality", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface)
                Spacer(Modifier.height(6.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(-1 to "Best", 2160 to "4K", 1080 to "1080p", 720 to "720p", 480 to "480p").forEach { (q, label) ->
                        androidx.compose.material3.FilterChip(
                            selected = quality == q,
                            onClick = { quality = q },
                            label = { Text(label) },
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                Text(
                    "Each episode picks its best source up to this quality when its turn comes, and tries the next one if a source fails.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            androidx.compose.material3.Button(onClick = { onConfirm(quality) }) {
                Text(if (toAdd > 0) "Download $toAdd episodes" else "Resume")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
