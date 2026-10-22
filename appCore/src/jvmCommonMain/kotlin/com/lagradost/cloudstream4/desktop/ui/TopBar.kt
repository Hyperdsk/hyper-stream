package com.lagradost.cloudstream4.desktop.ui

import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.lagradost.cloudstream4.appcore.resources.Res as AppRes
import com.lagradost.cloudstream4.appcore.resources.app_icon
import com.lagradost.cloudstream4.desktop.download.DownloadManager
import com.lagradost.cloudstream4.desktop.download.DownloadStatus
import org.jetbrains.compose.resources.painterResource

/** Height of the top bar; screens under it start this far down. */
val TopBarHeight = 68.dp

/** Whether the top bar needs its solid background: Home sets it once its banner scrolls away. */
class TopBarState {
    var solid by mutableStateOf(false)
}

/** Present on the Mac, tablets and TVs, which have the top bar; null on phones. */
val LocalTopBarState = staticCompositionLocalOf<TopBarState?> { null }

/**
 * The top navigation of streaming apps on TVs and the web: the app mark and the sections as
 * tabs on the left, and Settings on the right. It floats over Home's banner and turns solid
 * once the page scrolls. [trailing] adds controls before Settings, such as Home's provider.
 */
@Composable
fun TopBar(
    nav: Navigator,
    solid: Boolean,
    modifier: Modifier = Modifier,
    sections: List<HomeSection> = rememberAvailableSections(),
    trailing: @Composable RowScope.() -> Unit = {},
) {
    val colors = MaterialTheme.colorScheme
    val background by animateColorAsState(if (solid) colors.background.copy(alpha = 0.97f) else Color.Transparent)
    val downloads by DownloadManager.entries.collectAsState()
    val active = downloads.count { it.status == DownloadStatus.Downloading || it.status == DownloadStatus.Queued }
    val tab = nav.tab

    BoxWithConstraints(
        modifier.fillMaxWidth().height(TopBarHeight)
            .background(background)
            // Over the banner: a soft shade so the tabs read over any picture
            .background(Brush.verticalGradient(listOf(colors.background.copy(alpha = if (solid) 0f else 0.7f), Color.Transparent)))
            .padding(horizontal = appLayout.gutter),
    ) {
        // Narrower windows and TVs (960 wide) drop the name and close up the tabs; TVs also move
        // Extensions into Settings, as phones do, to leave room for Home's provider controls
        val roomy = maxWidth > 1250.dp
        val narrow = maxWidth < 1000.dp
        Row(Modifier.fillMaxWidth().align(Alignment.Center), verticalAlignment = Alignment.CenterVertically) {
            Image(painterResource(AppRes.drawable.app_icon), "Hyper Stream", Modifier.size(32.dp).clip(RoundedCornerShape(8.dp)))
            if (roomy) {
                Spacer(Modifier.width(10.dp))
                Text("Hyper Stream", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = colors.onBackground)
            }
            // Right after the mark, so the tabs stay in the same place on every screen, whatever
            // controls the screen adds on the right
            Spacer(Modifier.width(28.dp))
            // In a window too narrow for every tab, the tabs scroll rather than run under the controls
            Row(
                // Takes the space up to the controls on the right, which stay put
                Modifier.weight(1f).padding(end = 12.dp).horizontalScroll(rememberScrollState()),
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TopTab(null, Icons.Outlined.Search, "Search", tab == Route.Search, tight = !roomy) { nav.open(Route.Search) }
                TopTab("Home", null, "Home", tab == Route.Home, tight = !roomy) { nav.open(Route.Home) }
                // Only the media pages some installed extension can fill
                sections.forEach { section ->
                    TopTab(section.label, null, section.label, tab == Route.Section(section), tight = !roomy) { nav.open(Route.Section(section)) }
                }
                TopTab("Library", null, "Library", tab == Route.Library, tight = !roomy) { nav.open(Route.Library) }
                TopTab("Downloads", null, "Downloads", tab == Route.Downloads, tight = !roomy, badge = active.takeIf { it > 0 }) { nav.open(Route.Downloads) }
                if (!narrow) TopTab("Extensions", null, "Extensions", tab == Route.Extensions, tight = !roomy) { nav.open(Route.Extensions) }
            }
            Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                trailing()
                TopTab(null, Icons.Outlined.Settings, "Settings", tab == Route.Settings) { nav.open(Route.Settings) }
            }
        }
    }
}

/**
 * A tab: a white capsule when selected, plain text otherwise, lit on hover or remote focus. The
 * selected tab turns the accent colour while focused, since a ring on white wouldn't show.
 */
@Composable
private fun TopTab(
    label: String?,
    icon: ImageVector?,
    description: String,
    selected: Boolean,
    tight: Boolean = false,
    badge: Int? = null,
    onClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val lit by interaction.collectIsHighlightedAsState()
    val background = when {
        selected && lit -> colors.primary
        selected -> colors.onBackground
        lit -> colors.onBackground.copy(alpha = 0.14f)
        else -> Color.Transparent
    }
    val content = when {
        selected && lit -> colors.onPrimary
        selected -> colors.background
        else -> colors.onBackground.copy(alpha = if (lit) 1f else 0.78f)
    }
    Row(
        Modifier
            .pressScale(interaction)
            .clip(CircleShape)
            .background(background)
            .hoverable(interaction)
            .clickable(interactionSource = interaction, indication = if (selected) null else tvIndication, onClick = onClick)
            // Taller on touch screens (tablets), where a tab must be about 48 points to tap reliably
            .padding(horizontal = if (label == null) 10.dp else if (tight) 12.dp else 16.dp, vertical = if (com.lagradost.cloudstream4.desktop.Platform.isTouch) 13.dp else 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) Icon(icon, description, tint = content, modifier = Modifier.size(22.dp))
        if (label != null) {
            Text(label, style = MaterialTheme.typography.titleSmall, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium, color = content)
        }
        if (badge != null) {
            Spacer(Modifier.width(6.dp))
            Box(Modifier.background(colors.primary, CircleShape).padding(horizontal = 6.dp, vertical = 1.dp)) {
                Text("$badge", style = MaterialTheme.typography.labelSmall, color = colors.onPrimary)
            }
        }
    }
}
