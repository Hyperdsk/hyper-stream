package com.lagradost.cloudstream4.desktop.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lagradost.cloudstream3.LiveStreamLoadResponse
import com.lagradost.cloudstream3.MovieLoadResponse
import com.lagradost.cloudstream4.desktop.data.Bookmark
import com.lagradost.cloudstream4.desktop.data.LibraryStore
import com.lagradost.cloudstream4.desktop.data.WatchType
import kotlinx.coroutines.delay

/**
 * The Apple TV hero: a full-width backdrop of a featured title with its logo or name, a short
 * summary and the main actions. Slides advance on their own every few seconds and wait while
 * the pointer is over them. Text sits on a scrim towards the page colour, so it stays readable.
 */
@Composable
fun HeroCarousel(items: List<HeroItem>) {
    if (items.isEmpty()) return
    val nav = LocalNavigator.current
    var index by remember { mutableIntStateOf(0) }
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val background = MaterialTheme.colorScheme.background
    val safeIndex = index.coerceIn(0, items.lastIndex)

    LaunchedEffect(safeIndex, hovered, items.size) {
        if (!hovered && items.size > 1) {
            delay(8_000)
            index = (safeIndex + 1) % items.size
        }
    }

    BoxWithConstraints(Modifier.fillMaxWidth().hoverable(interaction)) {
        val height = (maxWidth * 0.48f).coerceIn(380.dp, 620.dp)
        Box(Modifier.fillMaxWidth().height(height)) {
            Crossfade(targetState = safeIndex, animationSpec = tween(700)) { i ->
                val item = items[i]
                val details = item.details
                // A portrait poster sits whole on the right, clear of the title text on the left
                WideImage(
                    details?.backgroundPosterUrl ?: details?.posterUrl ?: item.search.posterUrl,
                    details?.posterHeaders ?: item.search.posterHeaders,
                    Modifier.fillMaxSize(),
                    fitAlignment = BiasAlignment(0.75f, 0f),
                )
            }
            Box(Modifier.fillMaxSize().background(leftScrim(background)))
            Box(Modifier.fillMaxSize().background(bottomScrim(background)))

            Crossfade(
                targetState = safeIndex,
                animationSpec = tween(450),
                modifier = Modifier.align(Alignment.BottomStart).padding(start = 72.dp, bottom = 44.dp, end = 72.dp),
            ) { i -> HeroText(items[i]) { nav.open(it) } }

            if (items.size > 1) {
                Row(
                    Modifier.align(Alignment.BottomEnd).padding(end = 48.dp, bottom = 52.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    items.indices.forEach { i -> Dot(i == safeIndex) { index = i } }
                }
                AnimatedVisibility(hovered, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.CenterStart).padding(start = 12.dp)) {
                    GlassButton(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Previous") {
                        index = (safeIndex - 1 + items.size) % items.size
                    }
                }
                AnimatedVisibility(hovered, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.CenterEnd).padding(end = 12.dp)) {
                    GlassButton(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Next") {
                        index = (safeIndex + 1) % items.size
                    }
                }
            }
        }
    }
}

@Composable
private fun HeroText(item: HeroItem, open: (Route) -> Unit) {
    val details = item.details
    val library by LibraryStore.state.collectAsState()
    val bookmark = library.bookmark(item.search.apiName, item.search.url)

    Column(Modifier.widthIn(max = 620.dp)) {
        val logo = details?.logoUrl
        if (logo != null) {
            RemoteImage(
                logo, details.posterHeaders,
                Modifier.heightIn(max = 140.dp).widthIn(max = 420.dp),
                contentScale = ContentScale.Fit,
                placeholder = false,
            )
        } else {
            Text(
                details?.name ?: item.search.name,
                style = MaterialTheme.typography.displayMedium,
                color = Color.White,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.height(12.dp))
        val meta = listOfNotNull(
            details?.year?.toString(),
            (details?.type ?: item.search.type)?.name,
            details?.duration?.takeIf { it > 0 }?.let { if (it >= 60) "${it / 60}h ${it % 60}m" else "${it}m" },
            details?.score?.toStringNull(0.1, 10, 1)?.let { "★ $it" },
            details?.tags?.filter { it.isNotBlank() }?.take(3)?.takeIf { it.isNotEmpty() }?.joinToString(", "),
        )
        if (meta.isNotEmpty()) {
            Text(meta.joinToString("  ·  "), style = MaterialTheme.typography.labelLarge, color = Color.White.copy(alpha = 0.8f))
            Spacer(Modifier.height(10.dp))
        }
        details?.plot?.let {
            Text(it, style = MaterialTheme.typography.bodyLarge, color = Color.White.copy(alpha = 0.85f), maxLines = 3, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(20.dp))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            val detailsRoute = Route.Details(item.search.apiName, item.search.url, item.search.name)
            PrimaryCapsuleButton("Play", Icons.Default.PlayArrow) {
                // Movies play straight away (resuming where you were); shows open their page to choose an episode
                val data = (details as? MovieLoadResponse)?.dataUrl ?: (details as? LiveStreamLoadResponse)?.dataUrl
                if (details != null && data != null) {
                    val resume = library.progress(item.search.apiName, data)?.takeIf { !it.finished }?.positionMs ?: 0
                    open(
                        Route.Player(
                            PlayRequest(
                                item.search.apiName, details.url, details.name, details.posterUrl, details.posterHeaders,
                                listOf(PlayableEpisode(data, null, null, null)), 0, resume,
                            )
                        )
                    )
                } else {
                    open(detailsRoute)
                }
            }
            GlassButton(Icons.Outlined.Info, "Details", text = "Details") { open(detailsRoute) }
            GlassButton(if (bookmark != null) Icons.Default.Check else Icons.Default.Add, if (bookmark != null) "In Library" else "Add to Library") {
                if (bookmark != null) {
                    LibraryStore.setBookmark(null, item.search.apiName, item.search.url)
                } else {
                    LibraryStore.setBookmark(
                        Bookmark(
                            url = item.search.url,
                            apiName = item.search.apiName,
                            name = details?.name ?: item.search.name,
                            posterUrl = details?.posterUrl ?: item.search.posterUrl,
                            posterHeaders = details?.posterHeaders ?: item.search.posterHeaders,
                            type = (details?.type ?: item.search.type)?.name,
                            watchType = WatchType.PLANTOWATCH,
                        ),
                        item.search.apiName,
                        item.search.url,
                    )
                }
            }
        }
    }
}
