package com.lagradost.cloudstream4.desktop.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.VideoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import com.lagradost.cloudstream4.appcore.resources.Res as AppRes
import com.lagradost.cloudstream4.appcore.resources.app_icon
import org.jetbrains.compose.resources.painterResource
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.lagradost.cloudstream4.desktop.download.DownloadManager
import com.lagradost.cloudstream4.desktop.download.DownloadStatus

/**
 * The Apple TV app's sidebar: the app mark, a search field, the sections with labels, and
 * Settings at the bottom. A slightly lighter, heavier material than the content, which separates
 * the structural region from what scrolls next to it.
 */
@Composable
fun Sidebar(nav: Navigator) {
    val colors = MaterialTheme.colorScheme
    val sidebarColor = colors.onBackground.copy(alpha = 0.05f).compositeOver(colors.background)
    val downloads by DownloadManager.entries.collectAsState()
    val active = downloads.count { it.status == DownloadStatus.Downloading || it.status == DownloadStatus.Queued }

    Column(
        Modifier.width(232.dp).fillMaxHeight().background(sidebarColor).padding(horizontal = 12.dp, vertical = 16.dp),
    ) {
        Row(Modifier.padding(start = 8.dp, bottom = 18.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Image(painterResource(AppRes.drawable.app_icon), null, Modifier.size(30.dp))
            Spacer(Modifier.width(10.dp))
            Text("CloudStream", style = MaterialTheme.typography.titleMedium, color = colors.onBackground)
        }

        SearchEntry(selected = nav.tab == Route.Search) { nav.open(Route.Search) }
        Spacer(Modifier.height(14.dp))

        SidebarItem(Icons.Outlined.Home, "Home", nav.tab == Route.Home) { nav.open(Route.Home) }
        SidebarItem(Icons.Outlined.VideoLibrary, "Library", nav.tab == Route.Library) { nav.open(Route.Library) }
        SidebarItem(Icons.Outlined.Download, "Downloads", nav.tab == Route.Downloads, badge = active.takeIf { it > 0 }) {
            nav.open(Route.Downloads)
        }
        SidebarItem(Icons.Outlined.Extension, "Extensions", nav.tab == Route.Extensions) { nav.open(Route.Extensions) }

        Spacer(Modifier.weight(1f))
        SidebarItem(Icons.Outlined.Settings, "Settings", nav.tab == Route.Settings) { nav.open(Route.Settings) }
    }
}

@Composable
private fun SearchEntry(selected: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHighlightedAsState()
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(colors.onBackground.copy(alpha = if (selected || hovered) 0.12f else 0.07f))
            .hoverable(interaction)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.Search, null, tint = secondaryText, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text("Search", style = MaterialTheme.typography.bodyMedium, color = secondaryText)
    }
}

@Composable
private fun SidebarItem(icon: ImageVector, label: String, selected: Boolean, badge: Int? = null, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHighlightedAsState()
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 1.dp)
            .pressScale(interaction)
            .clip(RoundedCornerShape(8.dp))
            .background(
                when {
                    selected -> colors.onBackground.copy(alpha = 0.14f)
                    hovered -> colors.onBackground.copy(alpha = 0.06f)
                    else -> Color.Transparent
                }
            )
            .hoverable(interaction)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            icon,
            null,
            tint = if (selected) colors.primary else colors.onBackground.copy(alpha = 0.85f),
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(12.dp))
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = colors.onBackground,
            modifier = Modifier.weight(1f),
        )
        if (badge != null) {
            Text(
                "$badge",
                style = MaterialTheme.typography.labelSmall,
                color = colors.onPrimary,
                modifier = Modifier.background(colors.primary, RoundedCornerShape(50)).padding(horizontal = 7.dp, vertical = 2.dp),
            )
        }
    }
}
