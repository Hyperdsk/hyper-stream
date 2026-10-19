package com.lagradost.cloudstream4.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Theaters
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lagradost.cloudstream3.AnimeLoadResponse
import com.lagradost.cloudstream3.DubStatus
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.EpisodeResponse
import com.lagradost.cloudstream3.LiveStreamLoadResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MovieLoadResponse
import com.lagradost.cloudstream3.TorrentLoadResponse
import com.lagradost.cloudstream3.TvSeriesLoadResponse
import com.lagradost.cloudstream4.desktop.data.Bookmark
import com.lagradost.cloudstream4.desktop.data.LibraryStore
import com.lagradost.cloudstream4.desktop.data.WatchType
import com.lagradost.cloudstream4.desktop.download.DownloadEntry
import com.lagradost.cloudstream4.desktop.download.DownloadManager
import com.lagradost.cloudstream4.desktop.download.DownloadRequest
import com.lagradost.cloudstream4.desktop.download.SavedLink
import com.lagradost.cloudstream4.desktop.DesktopToasts
import kotlinx.coroutines.Job
import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toPersistentList
import kotlinx.coroutines.launch
import java.awt.Desktop
import java.net.URI

data class EpisodeUi(
    val playable: PlayableEpisode,
    val posterUrl: String?,
    val description: String?,
    val runTimeMinutes: Int?,
)

data class DetailsState(
    val loading: Boolean = true,
    val error: String? = null,
    val response: LoadResponse? = null,
    val dubOptions: PersistentList<DubStatus> = persistentListOf(),
    val dub: DubStatus? = null,
    /** Season number to display name. */
    val seasons: PersistentList<Pair<Int?, String>> = persistentListOf(),
    val season: Int? = null,
    /** Episodes of the selected dub, all seasons, in watch order. */
    val allEpisodes: PersistentList<EpisodeUi> = persistentListOf(),
    val unsupported: String? = null,
    /** Open while the user chooses a download source. */
    val picker: DownloadPickerState? = null,
) {
    val episodes: List<EpisodeUi> get() = allEpisodes.filter { seasons.isEmpty() || it.playable.season == season }
    val isMovieLike: Boolean get() = response is MovieLoadResponse || response is LiveStreamLoadResponse
}

class DetailsModel(private val route: Route.Details) : ScreenModel<DetailsState>(DetailsState()) {
    init {
        load()
    }

    fun load() {
        update { DetailsState(loading = true) }
        scope.launch {
            val api = providerByName(route.apiName)
            if (api == null) {
                update { copy(loading = false, error = "The ${route.apiName} extension isn't installed or failed to load.") }
                return@launch
            }
            providerCall { api.load(route.url) }
                .onSuccess { response ->
                    if (response == null) {
                        update { copy(loading = false, error = "The provider returned nothing for this title.") }
                    } else {
                        apply(response)
                    }
                }
                .onFailure { e -> update { copy(loading = false, error = e.userMessage()) } }
        }
    }

    private fun apply(response: LoadResponse) {
        val dubs = (response as? AnimeLoadResponse)?.episodes?.filterValues { it.isNotEmpty() }?.keys?.toList().orEmpty()
        val dub = dubs.firstOrNull()
        update {
            copy(
                loading = false,
                response = response,
                dubOptions = dubs.toPersistentList(),
                unsupported = if (response is TorrentLoadResponse) "Torrent streams aren't supported in the Mac app yet." else null,
            )
        }
        selectDub(dub)
    }

    fun selectDub(dub: DubStatus?) {
        val response = current.response ?: return
        val raw: List<Episode> = when (response) {
            is TvSeriesLoadResponse -> response.episodes
            is AnimeLoadResponse -> dub?.let { response.episodes[it] }.orEmpty()
            else -> emptyList()
        }
        val episodes = raw
            .sortedWith(compareBy<Episode>({ it.season ?: 0 }, { it.episode ?: Int.MAX_VALUE }))
            .map {
                EpisodeUi(
                    PlayableEpisode(it.data, it.name, it.season, it.episode),
                    it.posterUrl,
                    it.description,
                    it.runTime,
                )
            }
        val names = (response as? EpisodeResponse)?.seasonNames.orEmpty()
        val seasonNumbers = episodes.map { it.playable.season }.distinct()
        val seasons = if (seasonNumbers.size <= 1 && seasonNumbers.firstOrNull() == null) emptyList() else seasonNumbers.map { s ->
            val named = names.firstOrNull { it.season == s }
            s to (named?.name ?: "Season ${named?.displaySeason ?: s ?: "?"}")
        }
        // Keep the season the user picked, or open the one with the most recent progress
        val lastWatched = LibraryStore.state.value.history
            .firstOrNull { it.apiName == route.apiName && it.parentUrl == response.url }?.season
        val season = current.season?.takeIf { s -> seasons.any { it.first == s } }
            ?: lastWatched?.takeIf { s -> seasons.any { it.first == s } }
            ?: seasons.firstOrNull()?.first
        update {
            copy(
                dub = dub,
                allEpisodes = episodes.toPersistentList(),
                seasons = seasons.toPersistentList(),
                season = season,
            )
        }
    }

    fun selectSeason(season: Int?) = update { copy(season = season) }

    /** Builds the player request for the given item, with the whole episode list for next/previous. */
    fun playRequest(episode: PlayableEpisode?, startMs: Long = 0): PlayRequest? {
        val response = current.response ?: return null
        val list = when (response) {
            is MovieLoadResponse -> listOf(PlayableEpisode(response.dataUrl, null, null, null))
            is LiveStreamLoadResponse -> listOf(PlayableEpisode(response.dataUrl, null, null, null))
            else -> current.allEpisodes.map { it.playable }
        }
        if (list.isEmpty()) return null
        val index = episode?.let { e -> list.indexOfFirst { it.data == e.data } }?.takeIf { it >= 0 } ?: 0
        return PlayRequest(
            apiName = route.apiName,
            parentUrl = response.url,
            title = response.name,
            posterUrl = response.posterUrl,
            posterHeaders = response.posterHeaders,
            episodes = list,
            index = index,
            startMs = startMs,
        )
    }

    private var pickerJob: Job? = null

    /**
     * Finds the downloadable sources and lets the user pick one. When loading ends with a single
     * source, the download starts right away without asking.
     */
    fun download(episode: PlayableEpisode) {
        pickerJob?.cancel()
        update { copy(picker = DownloadPickerState(episode)) }
        pickerJob = scope.launch {
            providerCall {
                DownloadManager.findLinks(route.apiName, episode.data) { links, subs ->
                    update { copy(picker = picker?.copy(links = links.toPersistentList(), subtitles = subs)) }
                }
            }.onSuccess { (links, subs) ->
                update { copy(picker = picker?.copy(links = links.toPersistentList(), subtitles = subs, loading = false)) }
                if (links.size == 1) pickDownload(links.single())
            }.onFailure { e ->
                update { copy(picker = picker?.copy(loading = false, error = e.userMessage())) }
            }
        }
    }

    fun pickDownload(link: SavedLink) {
        val response = current.response ?: return
        val picker = current.picker ?: return
        pickerJob?.cancel()
        update { copy(picker = null) }
        DownloadManager.enqueue(
            DownloadRequest(
                apiName = route.apiName,
                parentUrl = response.url,
                title = response.name,
                posterUrl = response.posterUrl,
                posterHeaders = response.posterHeaders,
                episode = picker.episode,
                link = link,
                subtitles = picker.subtitles,
            )
        )
        DesktopToasts.show("Downloading from ${link.info().title}")
    }

    fun dismissPicker() {
        pickerJob?.cancel()
        update { copy(picker = null) }
    }

    /** The movie itself as one item, for playing and downloading. Null for series. */
    val movieItem: PlayableEpisode?
        get() = (current.response as? MovieLoadResponse)?.let { PlayableEpisode(it.dataUrl, null, null, null) }

    fun setBookmark(type: WatchType?) {
        val response = current.response ?: return
        LibraryStore.setBookmark(
            type?.let {
                Bookmark(
                    url = route.url,
                    apiName = route.apiName,
                    name = response.name,
                    posterUrl = response.posterUrl,
                    posterHeaders = response.posterHeaders,
                    type = response.type.name,
                    watchType = it,
                )
            },
            route.apiName,
            route.url,
        )
    }
}

private fun openInBrowser(url: String) {
    runCatching { Desktop.getDesktop().browse(URI(url)) }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DetailsScreen(route: Route.Details, model: DetailsModel) {
    val nav = LocalNavigator.current
    val state by model.state.collectAsState()
    val library by LibraryStore.state.collectAsState()
    val downloads by DownloadManager.entries.collectAsState()
    val response = state.response

    state.picker?.let { picker ->
        DownloadPickerDialog(
            state = picker,
            title = response?.name ?: route.name,
            onPick = model::pickDownload,
            onDismiss = model::dismissPicker,
        )
    }

    Box(Modifier.fillMaxSize()) {
        when {
            state.loading -> LoadingBox(text = "Loading ${route.name}")
            state.error != null || response == null -> MessageBox(
                "Couldn't open ${route.name}", state.error, "Try again", onAction = model::load,
            )

            else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 32.dp)) {
                item { Header(route, response, state, model) }

                if (state.unsupported != null) {
                    item { Text(state.unsupported!!, Modifier.padding(24.dp), color = MaterialTheme.colorScheme.error) }
                }

                if (!state.isMovieLike && state.allEpisodes.isNotEmpty()) {
                    item {
                        FlowRow(
                            Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            state.dubOptions.takeIf { it.size > 1 }?.forEach { dub ->
                                FilterChip(selected = dub == state.dub, onClick = { model.selectDub(dub) }, label = { Text(dub.name) })
                            }
                            state.seasons.takeIf { it.size > 1 }?.forEach { (number, name) ->
                                FilterChip(selected = number == state.season, onClick = { model.selectSeason(number) }, label = { Text(name) })
                            }
                        }
                    }
                    items(state.episodes, key = { it.playable.data }) { ep ->
                        val progress = library.progress(route.apiName, ep.playable.data)
                        val download = downloads.firstOrNull { it.id == DownloadManager.idFor(route.apiName, ep.playable.data) }
                        EpisodeRow(ep, progress?.progress, progress?.finished == true, download, onDownload = { model.download(ep.playable) }) {
                            val start = progress?.takeIf { !it.finished }?.positionMs ?: 0
                            model.playRequest(ep.playable, start)?.let { nav.open(Route.Player(it)) }
                        }
                    }
                }

                val cast = response.actors.orEmpty()
                if (cast.isNotEmpty()) {
                    item {
                        Text("Cast", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 24.dp, top = 20.dp, bottom = 8.dp))
                        LazyRow(contentPadding = PaddingValues(horizontal = 24.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            items(cast) { actor ->
                                Column(Modifier.width(88.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                    RemoteImage(actor.actor.image, null, Modifier.size(72.dp).clip(CircleShape))
                                    Text(actor.actor.name, style = MaterialTheme.typography.labelMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    (actor.roleString ?: actor.role?.name)?.let {
                                        Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                                    }
                                }
                            }
                        }
                    }
                }

                val recommendations = response.recommendations.orEmpty()
                if (recommendations.isNotEmpty()) {
                    item {
                        Spacer(Modifier.height(12.dp))
                        PosterRow("Recommended", recommendations.map { it.toPoster() }) {
                            nav.open(Route.Details(it.apiName, it.url, it.name))
                        }
                    }
                }
            }
        }
        IconButton(
            onClick = { nav.back() },
            modifier = Modifier.padding(12.dp).background(Color.Black.copy(alpha = 0.45f), CircleShape),
        ) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Header(route: Route.Details, response: LoadResponse, state: DetailsState, model: DetailsModel) {
    val nav = LocalNavigator.current
    val library by LibraryStore.state.collectAsState()
    val bookmark = library.bookmark(route.apiName, route.url)

    Box(Modifier.fillMaxWidth().heightIn(min = 380.dp)) {
        RemoteImage(
            response.backgroundPosterUrl ?: response.posterUrl,
            response.posterHeaders,
            Modifier.matchParentSize(),
        )
        Box(Modifier.matchParentSize().background(scrimColor))
        Row(Modifier.padding(start = 24.dp, end = 24.dp, top = 72.dp, bottom = 24.dp)) {
            RemoteImage(
                response.posterUrl,
                response.posterHeaders,
                Modifier.width(190.dp).aspectRatio(2f / 3f).clip(RoundedCornerShape(12.dp)),
            )
            Spacer(Modifier.width(24.dp))
            Column(Modifier.weight(1f)) {
                Text(response.name, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = Color.White)
                Spacer(Modifier.height(8.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    response.year?.let { Pill(it.toString()) }
                    Pill(response.type.name)
                    response.score?.let { Pill("★ ${it.toStringNull(0.1, 10, 1) ?: it.toString()}") }
                    response.duration?.takeIf { it > 0 }?.let { Pill("${it / 60}h ${it % 60}m".removePrefix("0h ")) }
                    response.contentRating?.let { Pill(it) }
                    (response as? EpisodeResponse)?.showStatus?.let { Pill(it.name) }
                    Pill(route.apiName)
                }
                Spacer(Modifier.height(12.dp))
                response.plot?.let {
                    Text(it, color = Color.White.copy(alpha = 0.9f), maxLines = 6, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(12.dp))
                }
                response.tags?.takeIf { it.isNotEmpty() }?.let { tags ->
                    Text(tags.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.7f))
                    Spacer(Modifier.height(16.dp))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    PlayButton(route, response, state, model)
                    BookmarkButton(bookmark?.watchType, model::setBookmark)
                    model.movieItem?.let { movie ->
                        val downloads by DownloadManager.entries.collectAsState()
                        val download = downloads.firstOrNull { it.id == DownloadManager.idFor(route.apiName, movie.data) }
                        DownloadButton(download, tint = Color.White) { model.download(movie) }
                    }
                    response.trailers.firstOrNull()?.let { trailer ->
                        OutlinedButton(onClick = { openInBrowser(trailer.extractorUrl) }) {
                            Icon(Icons.Default.Theaters, null)
                            Spacer(Modifier.width(6.dp))
                            Text("Trailer")
                        }
                    }
                    IconButton(onClick = { openInBrowser(response.url) }) {
                        Icon(Icons.Default.OpenInBrowser, "Open in browser", tint = Color.White)
                    }
                }
            }
        }
    }
}

@Composable
private fun PlayButton(route: Route.Details, response: LoadResponse, state: DetailsState, model: DetailsModel) {
    val nav = LocalNavigator.current
    val library by LibraryStore.state.collectAsState()
    if (state.unsupported != null) return

    // Resume where the user left off, otherwise start at the first unwatched episode
    val last = library.history.firstOrNull { it.apiName == route.apiName && it.parentUrl == response.url }
    val (label, target, start) = when {
        state.isMovieLike -> {
            val data = (response as? MovieLoadResponse)?.dataUrl ?: (response as? LiveStreamLoadResponse)?.dataUrl
            val p = data?.let { library.progress(route.apiName, it) }
            if (p != null && !p.finished && p.positionMs > 0) Triple("Resume ${formatTime(p.positionMs)}", null, p.positionMs)
            else Triple("Play", null, 0L)
        }

        last != null -> {
            val ep = state.allEpisodes.firstOrNull { it.playable.data == last.episodeData }
            if (ep != null && !last.finished) {
                Triple("Resume ${ep.playable.label.substringBefore(" · ")}", ep.playable, last.positionMs)
            } else {
                val idx = state.allEpisodes.indexOfFirst { it.playable.data == last.episodeData }
                val next = state.allEpisodes.getOrNull(idx + 1) ?: state.allEpisodes.firstOrNull()
                Triple("Play ${next?.playable?.label?.substringBefore(" · ") ?: ""}".trim(), next?.playable, 0L)
            }
        }

        else -> Triple("Play", state.allEpisodes.firstOrNull()?.playable, 0L)
    }
    if (!state.isMovieLike && state.allEpisodes.isEmpty()) return
    Button(onClick = { model.playRequest(target, start)?.let { nav.open(Route.Player(it)) } }) {
        Icon(Icons.Default.PlayArrow, null)
        Spacer(Modifier.width(6.dp))
        Text(label)
    }
}

@Composable
private fun BookmarkButton(current: WatchType?, onSelect: (WatchType?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { open = true }) {
            Icon(if (current != null) Icons.Default.Bookmark else Icons.Default.BookmarkBorder, null)
            Spacer(Modifier.width(6.dp))
            Text(current?.label ?: "Bookmark")
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            WatchType.entries.forEach { type ->
                DropdownMenuItem(
                    text = { Text(type.label, fontWeight = if (type == current) FontWeight.Bold else null) },
                    onClick = { open = false; onSelect(type) },
                )
            }
            if (current != null) {
                DropdownMenuItem(text = { Text("Remove bookmark") }, onClick = { open = false; onSelect(null) })
            }
        }
    }
}

@Composable
private fun EpisodeRow(
    ep: EpisodeUi,
    progress: Float?,
    finished: Boolean,
    download: DownloadEntry?,
    onDownload: () -> Unit,
    onClick: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 4.dp).clip(RoundedCornerShape(10.dp)).clickable(onClick = onClick),
    ) {
        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(160.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(8.dp))) {
                if (ep.posterUrl != null) {
                    RemoteImage(ep.posterUrl, null, Modifier.fillMaxSize())
                } else {
                    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface), contentAlignment = Alignment.Center) {
                        Icon(Icons.Default.PlayArrow, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (progress != null && progress > 0f) {
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(3.dp),
                    )
                }
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(ep.playable.label, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                ep.runTimeMinutes?.let { Text("$it min", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                ep.description?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            if (finished) Icon(Icons.Default.CheckCircle, "Watched", tint = MaterialTheme.colorScheme.primary)
            DownloadButton(download, onStart = onDownload)
        }
    }
}
