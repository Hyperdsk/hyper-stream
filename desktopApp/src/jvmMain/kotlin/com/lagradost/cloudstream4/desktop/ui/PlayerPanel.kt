package com.lagradost.cloudstream4.desktop.ui

import androidx.compose.material3.TextButton
import com.lagradost.cloudstream4.desktop.player.SubtitleFiles
import androidx.compose.runtime.collectAsState
import com.lagradost.cloudstream4.desktop.data.SettingsStore
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.Star
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.TooltipPlacement
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import com.lagradost.cloudstream4.desktop.player.PlaybackState

enum class PanelTab(val label: String) { Sources("Sources"), Subtitles("Subtitles"), Audio("Audio") }

private val panelColor = Color(0xF0141414)
private val rowColor = Color(0xFF1E1E1E)
private val dim = Color.White.copy(alpha = 0.6f)
private val warning = Color(0xFFFFB74D)

/** Quality buckets for grouping and filtering, best first. */
private fun bucket(quality: Int): String = when {
    quality >= 2160 -> "4K"
    quality >= 1440 -> "1440p"
    quality >= 1080 -> "1080p"
    quality >= 720 -> "720p"
    quality >= 480 -> "480p"
    quality > 0 && quality != 400 -> "Low"
    else -> "Other"
}

private val bucketOrder = listOf("4K", "1440p", "1080p", "720p", "480p", "Low", "Other")

/**
 * Side panel for choosing a source, subtitle or audio track. Takes the right side of the player,
 * closes on Escape (handled by the player) or a click on the video.
 */
@Composable
fun BoxScope.PlayerPanel(
    tab: PanelTab,
    onTab: (PanelTab) -> Unit,
    ui: PlayerUiState,
    playback: PlaybackState,
    model: PlayerModel,
    onClose: () -> Unit,
) {
    // Clicking the video area outside the panel closes it
    Box(
        Modifier.fillMaxSize().clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClose)
    )
    Column(
        Modifier
            .align(Alignment.CenterEnd)
            .fillMaxHeight()
            .widthIn(min = 360.dp, max = 460.dp)
            .fillMaxWidth(0.4f)
            .background(panelColor)
            // Swallow clicks so they don't reach the close area behind
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
            .padding(top = 12.dp),
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            PanelTab.entries.forEach { t ->
                val count = when (t) {
                    PanelTab.Sources -> ui.links.size
                    PanelTab.Subtitles -> playback.subtitleTracks.count { it.id >= 0 }
                    PanelTab.Audio -> playback.audioTracks.count { it.id >= 0 }
                }
                val selected = t == tab
                Column(
                    Modifier.clickable { onTab(t) }.padding(horizontal = 10.dp, vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        if (count > 0) "${t.label} $count" else t.label,
                        color = if (selected) Color.White else dim,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                    )
                    Spacer(Modifier.height(4.dp))
                    Box(Modifier.height(2.dp).width(28.dp).background(if (selected) MaterialTheme.colorScheme.primary else Color.Transparent))
                }
            }
            Spacer(Modifier.weight(1f))
            IconButton(onClick = onClose) { Icon(Icons.Default.Close, "Close", tint = Color.White) }
        }
        when (tab) {
            PanelTab.Sources -> SourcesTab(ui, model, onClose)
            PanelTab.Subtitles -> SubtitlesTab(ui, playback, model)
            PanelTab.Audio -> AudioTab(playback, model)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SourcesTab(ui: PlayerUiState, model: PlayerModel, onClose: () -> Unit) {
    var query by remember { mutableStateOf("") }
    var quality by remember { mutableStateOf<String?>(null) }

    val all = ui.links.mapIndexed { i, link -> Triple(i, link.info(), bucket(link.quality)) }
    val counts = all.groupingBy { it.third }.eachCount()
    val q = query.trim().lowercase()
    val shown = all.filter { (_, info, b) ->
        (quality == null || b == quality) &&
            (q.isEmpty() || q.split(' ').all { word ->
                listOfNotNull(info.title, info.fileName, info.size, info.format, info.quality, info.tags.joinToString(" "))
                    .any { it.lowercase().contains(word) }
            })
    }
    val settings by SettingsStore.state.collectAsState()
    // Best first by the user's preferences; starred servers also get their own group at the top
    val rank = SourcePreferences.order(ui.links, shown.map { it.first }, settings).withIndex().associate { it.value to it.index }
    val ordered = shown.sortedBy { rank[it.first] ?: Int.MAX_VALUE }
    val starred = ordered.filter { SourcePreferences.preferredRank(it.second, settings) != null }
    val groups = ordered.filter { it !in starred }.groupBy { it.third }.toSortedMap(compareBy { bucketOrder.indexOf(it) })

    Column(Modifier.fillMaxSize()) {
        if (ui.loadingLinks) {
            LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 16.dp).height(2.dp))
            Text(
                "Still finding sources, ${ui.links.size} so far",
                color = dim,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }
        if (ui.links.size > 6) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("Filter by server, size, codec…") },
                leadingIcon = { Icon(Icons.Default.Search, null) },
                trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Default.Clear, "Clear") } },
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            )
            if (counts.size > 1) {
                FlowRow(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    QualityChip("All ${all.size}", quality == null) { quality = null }
                    bucketOrder.filter { it in counts }.forEach { b ->
                        QualityChip("$b ${counts.getValue(b)}", quality == b) { quality = if (quality == b) null else b }
                    }
                }
            }
        }

        if (ui.links.isEmpty()) {
            Text(
                if (ui.loadingLinks) "Looking for sources…" else "No sources were found.",
                color = dim,
                modifier = Modifier.padding(16.dp),
            )
            return
        }

        LazyColumn(contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 24.dp)) {
            val sections = (if (starred.isNotEmpty()) listOf("Your servers" to starred) else emptyList()) + groups.toList()
            sections.forEach { (b, rows) ->
                item(key = "header-$b") {
                    Text(
                        "$b · ${rows.size}",
                        color = dim,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(start = 4.dp, top = 12.dp, bottom = 6.dp),
                    )
                }
                items(rows, key = { "link-${it.first}" }) { (index, info, _) ->
                    SourceRow(
                        info = info,
                        rawName = ui.links[index].name,
                        playing = index == ui.current,
                        failed = index in ui.failed,
                        preferred = SourcePreferences.preferredRank(info, settings) != null,
                        avoided = SourcePreferences.isAvoided(info, settings),
                    ) {
                        model.playLink(index)
                        onClose()
                    }
                }
            }
            if (shown.isEmpty()) {
                item { Text("No sources match.", color = dim, modifier = Modifier.padding(8.dp)) }
            }
        }
    }
}

@Composable
private fun QualityChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label, style = MaterialTheme.typography.labelMedium) },
        colors = FilterChipDefaults.filterChipColors(labelColor = Color.White),
    )
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
private fun SourceRow(
    info: SourceInfo,
    rawName: String,
    playing: Boolean,
    failed: Boolean,
    preferred: Boolean = false,
    avoided: Boolean = false,
    onClick: () -> Unit,
) {
    val primary = MaterialTheme.colorScheme.primary
    TooltipArea(
        tooltip = {
            // The full original name, for anyone who wants every detail
            Surface(shape = RoundedCornerShape(6.dp), color = Color(0xFF2A2A2A)) {
                Text(rawName, color = Color.White, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(8.dp).widthIn(max = 420.dp))
            }
        },
        delayMillis = 700,
        tooltipPlacement = TooltipPlacement.CursorPoint(offset = DpOffset(0.dp, 16.dp)),
    ) {
        Surface(
            shape = RoundedCornerShape(10.dp),
            color = if (playing) primary.copy(alpha = 0.22f) else rowColor,
            modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp).alpha(if ((failed || avoided) && !playing) 0.55f else 1f),
            onClick = onClick,
        ) {
            Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    Text(
                        info.title,
                        color = Color.White,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    val details = listOfNotNull(info.size, info.format) + info.tags
                    if (details.isNotEmpty() || playing || failed) {
                        Spacer(Modifier.height(6.dp))
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            // Status first, so it is seen before the details
                            if (playing) StatusTag(Icons.Default.Check, "Playing", primary)
                            else if (failed) StatusTag(Icons.Default.ErrorOutline, "Didn't play", MaterialTheme.colorScheme.error)
                            details.forEach { Tag(it, if (it in lowQualityTags) warning else dim) }
                        }
                    }
                    info.fileName?.let {
                        Spacer(Modifier.height(4.dp))
                        Text(it, color = dim, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                Column(horizontalAlignment = Alignment.End) {
                    info.quality?.let {
                        Text(
                            it,
                            color = Color.Black,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(start = 10.dp).background(Color.White.copy(alpha = 0.9f), RoundedCornerShape(4.dp)).padding(horizontal = 6.dp, vertical = 2.dp),
                        )
                    }
                    // Prefer or avoid this server for every title: by provider name ("MovieBox")
                    val name = info.provider
                    Row {
                        IconButton(onClick = { SourcePreferences.togglePreferred(name) }, modifier = Modifier.size(32.dp)) {
                            Icon(
                                if (preferred) Icons.Default.Star else Icons.Default.StarBorder,
                                if (preferred) "Stop preferring $name" else "Prefer $name",
                                tint = if (preferred) Color(0xFFFFC107) else dim,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                        IconButton(onClick = { SourcePreferences.toggleAvoided(name) }, modifier = Modifier.size(32.dp)) {
                            Icon(
                                Icons.Default.Block,
                                if (avoided) "Stop avoiding $name" else "Avoid $name",
                                tint = if (avoided) MaterialTheme.colorScheme.error else dim,
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusTag(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String, color: Color) {
    Row(
        Modifier.background(color.copy(alpha = 0.18f), RoundedCornerShape(4.dp)).padding(horizontal = 6.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = color, modifier = Modifier.size(12.dp))
        Spacer(Modifier.width(3.dp))
        Text(text, color = color, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun Tag(text: String, color: Color) {
    Text(
        text,
        color = color,
        style = MaterialTheme.typography.labelSmall,
        modifier = Modifier.background(color.copy(alpha = 0.14f), RoundedCornerShape(4.dp)).padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

@Composable
private fun TrackRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.22f) else rowColor,
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        onClick = onClick,
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(label, color = Color.White, modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (selected) Icon(Icons.Default.Check, "Selected", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
private fun SubtitlesTab(ui: PlayerUiState, playback: PlaybackState, model: PlayerModel) {
    val player = model.player ?: return
    LazyColumn(contentPadding = PaddingValues(12.dp)) {
        // VLC lists "Disable" with id -1, shown here as Off
        val tracks = playback.subtitleTracks
        item { TrackRow("Off", playback.subtitleTrack < 0) { player.setSubtitleTrack(-1) } }
        items(tracks.filter { it.id >= 0 }, key = { "sub-${it.id}" }) { t ->
            TrackRow(t.name, t.id == playback.subtitleTrack) { player.setSubtitleTrack(t.id) }
        }
        if (ui.subtitles.isNotEmpty()) {
            // Working ones first by language; ones whose site failed are tucked away at the end
            val failed = ui.subtitles.filter { ui.loadedSubtitles[it.url] is SubtitleLoad.Failed }
            val usable = ui.subtitles.filter { it !in failed }.sortedBy { it.lang.lowercase() }
            val checking = usable.count { ui.loadedSubtitles[it.url] == null || ui.loadedSubtitles[it.url] == SubtitleLoad.Loading }
            item {
                Text(
                    if (checking > 0) "From the provider · checking $checking…" else "From the provider",
                    color = dim,
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(top = 16.dp, start = 4.dp, bottom = 4.dp),
                )
            }
            val counts = mutableMapOf<String, Int>()
            val labelled = usable.map { sub ->
                val n = (counts[sub.lang] ?: 0) + 1
                counts[sub.lang] = n
                sub to if (n > 1) "${sub.lang} $n" else sub.lang
            }
            items(labelled, key = { "provider-${it.first.url}" }) { (sub, label) ->
                val status = when (ui.loadedSubtitles[sub.url]) {
                    SubtitleLoad.Loading -> " · loading…"
                    SubtitleLoad.Loaded -> " · added"
                    is SubtitleLoad.Ready -> ""
                    else -> " · checking…"
                }
                SubtitleRow(label + status, SubtitleFiles.host(sub)) { model.loadSubtitle(sub) }
            }
            if (failed.isNotEmpty()) {
                item {
                    var open by remember { mutableStateOf(false) }
                    Column {
                        TextButton(onClick = { open = !open }) {
                            Text("Unavailable (${failed.size}) ${if (open) "▾" else "▸"}", color = dim)
                        }
                        if (open) failed.forEach { sub ->
                            val reason = (ui.loadedSubtitles[sub.url] as? SubtitleLoad.Failed)?.reason ?: ""
                            Text(
                                "${sub.lang} · ${SubtitleFiles.host(sub)} · $reason",
                                color = dim,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                            )
                        }
                    }
                }
            }
        } else if (tracks.count { it.id >= 0 } == 0) {
            item { Text("This source has no subtitles.", color = dim, modifier = Modifier.padding(8.dp)) }
        }
    }
}

@Composable
private fun AudioTab(playback: PlaybackState, model: PlayerModel) {
    val player = model.player ?: return
    val tracks = playback.audioTracks.filter { it.id >= 0 }
    LazyColumn(contentPadding = PaddingValues(12.dp)) {
        if (tracks.isEmpty()) {
            item { Text("This source has a single audio track.", color = dim, modifier = Modifier.padding(8.dp)) }
        }
        items(tracks, key = { "audio-${it.id}" }) { t ->
            TrackRow(t.name, t.id == playback.audioTrack) { player.setAudioTrack(t.id) }
        }
    }
}

@Composable
private fun SubtitleRow(label: String, host: String, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = rowColor,
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        onClick = onClick,
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            Text(label, color = Color.White)
            if (host.isNotEmpty()) Text(host, color = dim, style = MaterialTheme.typography.labelSmall)
        }
    }
}
