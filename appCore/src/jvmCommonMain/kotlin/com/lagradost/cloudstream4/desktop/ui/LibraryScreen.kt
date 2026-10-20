package com.lagradost.cloudstream4.desktop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.lagradost.cloudstream4.desktop.data.LibraryStore
import com.lagradost.cloudstream4.desktop.data.WatchType

@Composable
fun LibraryScreen() {
    val nav = LocalNavigator.current
    val library by LibraryStore.state.collectAsState()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val types = WatchType.entries
    val historyTab = types.size

    Column(Modifier.fillMaxSize()) {
        Text(
            "Library",
            style = MaterialTheme.typography.headlineLarge,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(start = 32.dp, top = 28.dp, bottom = 12.dp),
        )
        PrimaryScrollableTabRow(selectedTabIndex = tab, edgePadding = 16.dp) {
            types.forEachIndexed { i, type ->
                val count = library.bookmarks.count { it.watchType == type }
                Tab(selected = tab == i, onClick = { tab = i }, text = { Text("${type.label} ($count)") })
            }
            Tab(selected = tab == historyTab, onClick = { tab = historyTab }, text = { Text("History") })
        }

        val items: List<PosterItem> = if (tab == historyTab) {
            library.history.sortedByDescending { it.updatedAt }.distinctBy { it.apiName to it.parentUrl }.map { it.toPoster() }
        } else {
            library.bookmarks.filter { it.watchType == types[tab] }.sortedByDescending { it.updatedAt }.map { it.toPoster() }
        }

        if (items.isEmpty()) {
            MessageBox(
                if (tab == historyTab) "Nothing watched yet" else "Nothing in ${types[tab].label}",
                if (tab == historyTab) "What you play shows up here." else "Use the bookmark button on any title to add it here.",
            )
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(if (appLayout.compact) 104.dp else 150.dp),
                contentPadding = PaddingValues(if (appLayout.compact) 12.dp else 24.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(items, key = { "${it.apiName}|${it.url}" }) { item ->
                    PosterCard(item, if (appLayout.compact) 104.dp else 150.dp) { nav.open(Route.Details(item.apiName, item.url, item.name)) }
                }
            }
        }
    }
}
