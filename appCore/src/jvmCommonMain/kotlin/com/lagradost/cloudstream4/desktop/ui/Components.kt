package com.lagradost.cloudstream4.desktop.ui

import coil3.size.Precision
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.isSpecified
import coil3.compose.AsyncImagePainter
import coil3.compose.rememberAsyncImagePainter
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.network.NetworkHeaders
import coil3.network.httpHeaders
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream4.desktop.data.Bookmark
import com.lagradost.cloudstream4.desktop.data.HistoryEntry
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter

/** Everything a poster card needs, whether it comes from a provider, a bookmark or history. */
data class PosterItem(
    val name: String,
    val url: String,
    val apiName: String,
    val posterUrl: String?,
    val posterHeaders: Map<String, String>? = null,
    val badge: String? = null,
    val subtitle: String? = null,
    val progress: Float? = null,
)

fun SearchResponse.toPoster() = PosterItem(
    name = name,
    url = url,
    apiName = apiName,
    posterUrl = posterUrl,
    posterHeaders = posterHeaders,
    badge = quality?.name?.replace("HD", " HD")?.trim()?.takeIf { it.isNotBlank() },
    // No type label: like the Android app, since many extensions leave the type at the
    // library's default (Anime for newAnimeSearchResponse) whatever the title really is
    subtitle = null,
)

fun Bookmark.toPoster() = PosterItem(name, url, apiName, posterUrl, posterHeaders, subtitle = apiName)

fun HistoryEntry.toPoster() = PosterItem(
    name = name,
    url = parentUrl,
    apiName = apiName,
    posterUrl = posterUrl,
    posterHeaders = posterHeaders,
    subtitle = listOfNotNull(
        season?.let { "S$it" }, episode?.let { "E$it" },
    ).joinToString(" ").ifBlank { episodeName ?: apiName },
    progress = progress,
)

@Composable
fun RemoteImage(
    url: String?,
    headers: Map<String, String>?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    /** A filled box behind the image while it loads; off for logos over other imagery. */
    placeholder: Boolean = true,
) {
    if (url.isNullOrBlank()) {
        if (placeholder) Box(modifier.background(MaterialTheme.colorScheme.surfaceVariant))
        return
    }
    val context = LocalPlatformContext.current
    val request = remember(url, headers) {
        ImageRequest.Builder(context)
            .data(url)
            .apply {
                if (!headers.isNullOrEmpty()) {
                    httpHeaders(NetworkHeaders.Builder().apply { headers.forEach { (k, v) -> set(k, v) } }.build())
                }
            }
            .crossfade(true)
            .build()
    }
    AsyncImage(
        model = request,
        contentDescription = null,
        contentScale = contentScale,
        modifier = if (placeholder) modifier.background(MaterialTheme.colorScheme.surfaceVariant) else modifier,
    )
}

/**
 * An image for a wide frame that may get a portrait poster. A poster much taller than the frame
 * is shown whole, at [fitAlignment], over a blurred, dimmed fill of itself, instead of being
 * cropped to a strip across its middle. Other images fill the frame, cropped from near the top
 * so faces stay in view.
 */
@Composable
fun WideImage(
    url: String?,
    headers: Map<String, String>?,
    modifier: Modifier = Modifier,
    fitAlignment: Alignment = Alignment.Center,
) {
    if (url.isNullOrBlank()) {
        Box(modifier.background(MaterialTheme.colorScheme.surfaceVariant))
        return
    }
    val context = LocalPlatformContext.current
    val request = remember(url, headers) {
        ImageRequest.Builder(context)
            .data(url)
            .apply {
                if (!headers.isNullOrEmpty()) {
                    httpHeaders(NetworkHeaders.Builder().apply { headers.forEach { (k, v) -> set(k, v) } }.build())
                }
            }
            // Without a size this painter decodes the original: a 4K backdrop is about 33 MB, and
            // Home keeps several. No larger than the screen it fills (TVs are 1920 wide)
            .size(if (com.lagradost.cloudstream4.desktop.Platform.isTv) 1920 else 2560, if (com.lagradost.cloudstream4.desktop.Platform.isTv) 1080 else 1440)
            .precision(Precision.INEXACT)
            .build()
    }
    val painter = rememberAsyncImagePainter(request)
    val state by painter.state.collectAsState()
    BoxWithConstraints(modifier.background(MaterialTheme.colorScheme.surfaceVariant).clipToBounds()) {
        val size = (state as? AsyncImagePainter.State.Success)?.painter?.intrinsicSize
        val imageRatio = size?.takeIf { it.isSpecified && it.height > 0f }?.let { it.width / it.height }
        val frameRatio = if (maxHeight > 0.dp) maxWidth / maxHeight else 16f / 9f
        val portrait = imageRatio != null && imageRatio < frameRatio * 0.7f
        AnimatedVisibility(state is AsyncImagePainter.State.Success, enter = fadeIn(tween(300)), exit = fadeOut()) {
            Box(Modifier.fillMaxSize()) {
                if (portrait) {
                    // A large blur is heavy for a TV's graphics chip, so TVs dim the fill instead
                    val tv = com.lagradost.cloudstream4.desktop.Platform.isTv
                    Image(painter, null, contentScale = ContentScale.Crop, modifier = if (tv) Modifier.fillMaxSize() else Modifier.fillMaxSize().blur(28.dp))
                    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = if (tv) 0.7f else 0.35f)))
                    Image(painter, null, contentScale = ContentScale.Fit, alignment = fitAlignment, modifier = Modifier.fillMaxSize())
                } else {
                    Image(painter, null, contentScale = ContentScale.Crop, alignment = BiasAlignment(0f, -0.6f), modifier = Modifier.fillMaxSize())
                }
            }
        }
    }
}

@Composable
fun PosterCard(
    item: PosterItem,
    width: Dp,
    horizontal: Boolean = false,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    Column(Modifier.width(width)) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(if (horizontal) 16f / 9f else 2f / 3f)
                // The tvOS focus lift: grow with a deep shadow on hover, dip on press
                .tvLift(interaction, hoverScale = 1.06f)
                .clip(RoundedCornerShape(12.dp))
                .clickable(interactionSource = interaction, indication = null, onClick = onClick)
        ) {
            RemoteImage(item.posterUrl, item.posterHeaders, Modifier.fillMaxSize())
            if (item.badge != null) {
                Text(
                    item.badge,
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(8.dp)
                        .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(5.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
            if (item.progress != null) {
                LinearProgressIndicator(
                    progress = { item.progress },
                    modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(4.dp),
                    trackColor = Color.Black.copy(alpha = 0.5f),
                )
            }
        }
        Text(
            item.name,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 10.dp, start = 2.dp, end = 2.dp),
        )
        if (item.subtitle != null) {
            Text(
                item.subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = secondaryText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 2.dp, vertical = 2.dp),
            )
        }
    }
}

/** A shelf title, Apple style: large and bold, with a chevron when the shelf leads somewhere. */
@Composable
fun ShelfTitle(title: String, modifier: Modifier = Modifier, trailing: @Composable (() -> Unit)? = null) {
    Row(modifier.padding(horizontal = appLayout.gutter), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onBackground, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
        Spacer(Modifier.weight(1f))
        trailing?.invoke()
    }
}

/** A titled, horizontally scrolling shelf of posters. Calls [onReachEnd] near the end, for paging. */
@Composable
fun PosterRow(
    title: String,
    items: List<PosterItem>,
    horizontal: Boolean = false,
    loadingMore: Boolean = false,
    onReachEnd: (() -> Unit)? = null,
    trailing: @Composable (() -> Unit)? = null,
    onClick: (PosterItem) -> Unit,
) {
    val listState = rememberLazyListState()
    if (onReachEnd != null) {
        val nearEnd by remember {
            derivedStateOf {
                val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
                last >= listState.layoutInfo.totalItemsCount - 4
            }
        }
        LaunchedEffect(listState, items.size) {
            snapshotFlow { nearEnd }.distinctUntilChanged().filter { it }.collect { onReachEnd() }
        }
    }
    Column(Modifier.fillMaxWidth().padding(top = 18.dp, bottom = 6.dp)) {
        ShelfTitle(title, trailing = trailing)
        Spacer(Modifier.height(12.dp))
        LazyRow(
            state = listState,
            // A remote coming back into the row lands on the poster it left, not the first
            modifier = Modifier.focusRestorer(),
            // Vertical room so the hover lift and its shadow aren't clipped
            contentPadding = PaddingValues(start = appLayout.gutter, end = appLayout.gutter, top = 10.dp, bottom = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(appLayout.shelfSpacing),
        ) {
            itemsIndexed(items, key = { i, it -> "$i|${it.apiName}|${it.url}" }) { _, item ->
                PosterCard(item, if (horizontal) appLayout.widePoster else appLayout.poster, horizontal) { onClick(item) }
            }
            if (loadingMore) {
                item {
                    // As tall as the row's posters, so the row doesn't jump while the next page loads
                    val posterHeight = if (horizontal) appLayout.widePoster * 9f / 16f else appLayout.poster * 1.5f
                    Box(Modifier.size(80.dp, posterHeight), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                    }
                }
            }
        }
    }
}

@Composable
fun LoadingBox(modifier: Modifier = Modifier.fillMaxSize(), text: String? = null) {
    Box(modifier, contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator()
            if (text != null) {
                Spacer(Modifier.height(12.dp))
                Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
fun MessageBox(
    title: String,
    body: String? = null,
    actionLabel: String? = null,
    modifier: Modifier = Modifier.fillMaxSize(),
    onAction: (() -> Unit)? = null,
) {
    Box(modifier.padding(appLayout.gutter), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.widthIn(max = 420.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
            if (body != null) {
                Spacer(Modifier.height(8.dp))
                Text(body, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
            }
            if (actionLabel != null && onAction != null) {
                Spacer(Modifier.height(20.dp))
                Button(onClick = onAction) { Text(actionLabel) }
            }
        }
    }
}

@Composable
fun Pill(text: String, modifier: Modifier = Modifier) {
    Surface(
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = modifier,
    ) {
        Text(text, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp))
    }
}

/** Darkens a backdrop so text on top stays readable. Solid, as upstream's design rules avoid gradients. */
val scrimColor = Color.Black.copy(alpha = 0.62f)

@Composable
fun SmallOutlinedButton(text: String, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)) {
        Text(text, style = MaterialTheme.typography.labelMedium)
    }
}

fun formatTime(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

/** Asks before something that can't simply be undone, such as removing or uninstalling. */
@Composable
fun ConfirmDialog(title: String, body: String, action: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(body) },
        confirmButton = { androidx.compose.material3.TextButton(onClick = { onDismiss(); onConfirm() }) { Text(action) } },
        dismissButton = { androidx.compose.material3.TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/**
 * Text from a site's page as plain text: some extensions pass descriptions with HTML in them
 * ("<br>", "&amp;"), which showed as written. Line breaks become new lines, other tags go.
 */
fun plainText(html: String): String = html
    .replace(Regex("(?i)<br\\s*/?>|</p>\\s*<p[^>]*>"), "\n")
    .replace(Regex("<[^>]+>"), "")
    .replace("&nbsp;", " ").replace("&amp;", "&").replace("&quot;", "\"").replace("&#39;", "'").replace("&apos;", "'")
    .replace("&lt;", "<").replace("&gt;", ">")
    .replace(Regex("[ \\t]+\\n"), "\n").replace(Regex("\\n{3,}"), "\n\n")
    .trim()
