@file:OptIn(org.jetbrains.compose.resources.ExperimentalResourceApi::class)

package com.lagradost.cloudstream4.desktop.ui

import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.material3.IconButton
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.DropdownMenu
import androidx.compose.material.icons.filled.MoreVert
import com.lagradost.cloudstream4.desktop.Platform
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lagradost.cloudstream4.desktop.data.HistoryEntry
import com.lagradost.cloudstream4.desktop.data.LibraryStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.decodeToImageBitmap
import java.io.File

/** "Continue watching", Netflix style: where you stopped, with the poster on hover. */
@Composable
fun ContinueWatchingRow(entries: List<HistoryEntry>) {
    if (entries.isEmpty()) return
    val nav = LocalNavigator.current
    Column(Modifier.fillMaxWidth().padding(top = 18.dp, bottom = 6.dp)) {
        ShelfTitle("Up Next")
        Spacer(Modifier.height(12.dp))
        LazyRow(
            contentPadding = PaddingValues(start = appLayout.gutter, end = appLayout.gutter, top = 10.dp, bottom = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(appLayout.shelfSpacing),
        ) {
            items(entries, key = { "${it.apiName}|${it.parentUrl}" }) { entry ->
                ContinueWatchingCard(
                    entry = entry,
                    onPlay = { nav.open(Route.Player(entry.resumeRequest())) },
                    onDetails = { nav.open(Route.Details(entry.apiName, entry.parentUrl, entry.name)) },
                    onRemove = { LibraryStore.removeFromContinueWatching(entry.apiName, entry.parentUrl) },
                )
            }
        }
    }
}

/** Resume right where the entry left off. The player loads the rest of the show's episodes itself. */
fun HistoryEntry.resumeRequest() = PlayRequest(
    apiName = apiName,
    parentUrl = parentUrl,
    title = name,
    posterUrl = posterUrl,
    posterHeaders = posterHeaders,
    episodes = listOf(PlayableEpisode(episodeData, episodeName, season, episode)),
    index = 0,
    startMs = positionMs,
)

@Composable
fun ContinueWatchingCard(entry: HistoryEntry, onPlay: () -> Unit, onDetails: () -> Unit, onRemove: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHighlightedAsState()
    val snapshot = rememberSnapshot(entry.snapshotPath, entry.updatedAt)

    Column(Modifier.width(appLayout.card)) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .tvLift(interaction, hoverScale = 1.05f)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .clickable(interactionSource = interaction, indication = null, onClick = onPlay),
        ) {
            // Where you stopped; the original poster fades in on hover, or when there is no picture yet
            Crossfade(targetState = hovered || snapshot == null, animationSpec = tween(250)) { showPoster ->
                if (showPoster || snapshot == null) {
                    WideImage(entry.posterUrl, entry.posterHeaders, Modifier.fillMaxSize())
                } else {
                    Image(snapshot, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                }
            }
            if (hovered) {
                Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f)))
                Box(
                    Modifier.align(Alignment.Center).size(52.dp)
                        .background(Color.Black.copy(alpha = 0.55f), CircleShape)
                        .border(2.dp, Color.White, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Default.PlayArrow, "Resume", tint = Color.White, modifier = Modifier.size(30.dp))
                }
                if (!Platform.isTv && !Platform.isTouch) Row(Modifier.align(Alignment.TopEnd).padding(6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    CardAction(Icons.Outlined.Info, "Details", onDetails)
                    CardAction(Icons.Default.Close, "Remove from Up Next", onRemove)
                }
            }
            // Progress, red like the seek bar's played part
            Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(4.dp).background(Color.White.copy(alpha = 0.3f))) {
                Box(Modifier.fillMaxHeight().fillMaxWidth(entry.progress.coerceIn(0.02f, 1f)).background(MaterialTheme.colorScheme.primary))
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    plainText(entry.name),
                    color = MaterialTheme.colorScheme.onBackground,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    listOfNotNull(entry.episodeLabel(), timeLeft(entry)).joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall,
                    color = secondaryText,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            // Touch screens and remotes can't hover, so the card's actions are in a menu here
            if (Platform.isTouch || Platform.isTv) {
                var menu by remember { mutableStateOf(false) }
                Box {
                    IconButton(onClick = { menu = true }, modifier = Modifier.size(36.dp)) {
                        Icon(Icons.Default.MoreVert, "More", tint = secondaryText)
                    }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("Details") }, onClick = { menu = false; onDetails() })
                        DropdownMenuItem(text = { Text("Remove from Up Next") }, onClick = { menu = false; onRemove() })
                    }
                }
            }
        }
    }
}

@Composable
private fun CardAction(icon: ImageVector, description: String, onClick: () -> Unit) {
    Box(
        Modifier.size(30.dp).background(Color.Black.copy(alpha = 0.6f), CircleShape).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, description, tint = Color.White, modifier = Modifier.size(18.dp))
    }
}

private fun HistoryEntry.episodeLabel(): String? = when {
    season != null && episode != null -> "S$season:E$episode"
    episode != null -> "E$episode"
    else -> null
}

private fun timeLeft(entry: HistoryEntry): String? {
    val minutes = ((entry.durationMs - entry.positionMs) / 60_000).toInt()
    return when {
        entry.durationMs <= 0 -> null
        minutes >= 60 -> "${minutes / 60}h ${minutes % 60}m left"
        minutes >= 1 -> "$minutes min left"
        else -> "Almost done"
    }
}

/** Loads a saved snapshot from disk. [version] changes when the picture is replaced. */
@Composable
private fun rememberSnapshot(path: String?, version: Long): ImageBitmap? {
    val image by produceState<ImageBitmap?>(null, path, version) {
        value = path?.let {
            withContext(Dispatchers.IO) {
                runCatching { File(it).readBytes().decodeToImageBitmap() }.getOrNull()
            }
        }
    }
    return image
}
