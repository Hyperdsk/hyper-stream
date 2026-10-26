package com.lagradost.cloudstream4.desktop.ui

import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.background
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Add
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.border
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
import androidx.compose.material.icons.filled.Download
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
import com.lagradost.cloudstream3.ActorData
import com.lagradost.cloudstream3.Actor
import com.lagradost.cloudstream4.desktop.meta.compactCount
import com.lagradost.cloudstream4.desktop.meta.ImdbInfo
import com.lagradost.cloudstream4.desktop.meta.ImdbReviewPage
import com.lagradost.cloudstream4.desktop.meta.Imdb
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
import com.lagradost.cloudstream4.desktop.Platform
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
    /** IMDb rating, credits and facts, loaded after the provider's details. */
    val imdb: ImdbInfo? = null,
    /** The most helpful IMDb reviews, for the Ratings & Reviews shelf. */
    val reviews: ImdbReviewPage? = null,
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
        scope.launch {
            providerCall { Imdb.forTitle(response) }.getOrNull()?.let { info ->
                update { copy(imdb = info) }
                providerCall { Imdb.reviews(info.id, count = 10) }.getOrNull()?.let { page -> update { copy(reviews = page) } }
            }
        }
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
                    // Extensions often copy these from the page with HTML in them
                    PlayableEpisode(it.data, it.name?.let(::plainText), it.season, it.episode),
                    it.posterUrl,
                    it.description?.let(::plainText)?.takeIf { it.isNotBlank() },
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
            loadResponse = response,
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

    /**
     * Queues every episode of the shown season that isn't downloaded or queued yet. Each picks
     * its own best source up to [maxQuality] when its turn comes, so links don't expire waiting.
     */
    fun downloadSeason(maxQuality: Int?) {
        val response = current.response ?: return
        val existing = DownloadManager.entries.value.map { it.id }.toSet()
        var added = 0
        for (ep in current.episodes) {
            val id = DownloadManager.idFor(route.apiName, ep.playable.data)
            if (id in existing) {
                // Failed or paused ones get another go
                DownloadManager.resume(id)
                continue
            }
            DownloadManager.enqueue(
                DownloadRequest(
                    apiName = route.apiName,
                    parentUrl = response.url,
                    title = response.name,
                    posterUrl = response.posterUrl,
                    posterHeaders = response.posterHeaders,
                    episode = ep.playable,
                    maxQuality = maxQuality,
                )
            )
            added++
        }
        DesktopToasts.show(if (added == 0) "All episodes are already in Downloads." else "Added $added episodes to Downloads.")
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

private fun isWebLink(url: String?) = url != null && (url.startsWith("http://", true) || url.startsWith("https://", true))

/**
 * The title's page on the provider's site. Some providers keep an id or JSON in the title's
 * url instead of a link; then it's the provider's home page, or null when there is none.
 */
private fun webPage(apiName: String, url: String): String? =
    url.trim().takeIf(::isWebLink) ?: providerByName(apiName)?.mainUrl?.trim()?.takeIf(::isWebLink)

private fun openInBrowser(url: String) {
    val opened = isWebLink(url) && Platform.openUrl(url)
    if (!opened) com.lagradost.cloudstream4.desktop.DesktopToasts.show("Couldn't open this link in a browser.")
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DetailsScreen(route: Route.Details, model: DetailsModel) {
    val nav = LocalNavigator.current
    val state by model.state.collectAsState()
    val library by LibraryStore.state.collectAsState()
    val downloads by DownloadManager.entries.collectAsState()
    val response = state.response
    var showSeasonDialog by remember { mutableStateOf(false) }
    var showReviews by remember { mutableStateOf(false) }

    if (showReviews) state.imdb?.let { ReviewsDialog(it) { showReviews = false } }

    if (showSeasonDialog) {
        val seasonName = state.seasons.firstOrNull { it.first == state.season }?.second
        val already = state.episodes.count { ep -> downloads.any { it.id == DownloadManager.idFor(route.apiName, ep.playable.data) } }
        SeasonDownloadDialog(
            title = listOfNotNull(response?.name ?: route.name, seasonName).joinToString(" · "),
            episodes = state.episodes.size,
            alreadyAdded = already,
            onConfirm = { quality -> showSeasonDialog = false; model.downloadSeason(quality) },
            onDismiss = { showSeasonDialog = false },
        )
    }

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

            else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 40.dp)) {
                item { Header(route, response, state, model) }

                if (state.unsupported != null) {
                    item { Text(state.unsupported!!, Modifier.padding(horizontal = appLayout.pageMargin, vertical = 12.dp), color = MaterialTheme.colorScheme.error) }
                }

                if (!state.isMovieLike && state.allEpisodes.isNotEmpty()) {
                    item {
                        // Season and dub tabs, Apple style: plain text, the chosen one bright
                        val tabs: @Composable (Modifier) -> Unit = { modifier ->
                            LazyRow(modifier, horizontalArrangement = Arrangement.spacedBy(22.dp)) {
                                if (state.dubOptions.size > 1) {
                                    items(state.dubOptions) { dub ->
                                        TabText(dub.name, dub == state.dub) { model.selectDub(dub) }
                                    }
                                }
                                if (state.seasons.size > 1) {
                                    items(state.seasons) { (number, name) ->
                                        TabText(name, number == state.season) { model.selectSeason(number) }
                                    }
                                } else {
                                    item { Text("Episodes", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onBackground) }
                                }
                            }
                        }
                        val downloadButton: @Composable () -> Unit = {
                            val remaining = state.episodes.count { ep ->
                                downloads.none { it.id == DownloadManager.idFor(route.apiName, ep.playable.data) }
                            }
                            SubtleCapsule(
                                Icons.Default.Download,
                                (if (state.seasons.size > 1) "Download season" else "Download all") +
                                    if (remaining in 1 until state.episodes.size) " ($remaining left)" else "",
                            ) { showSeasonDialog = true }
                        }
                        if (appLayout.compact) {
                            // Phones: the tabs get the full width, the button goes under them
                            Column(Modifier.fillMaxWidth().padding(start = appLayout.pageMargin, end = appLayout.gutter, top = 8.dp)) {
                                tabs(Modifier.fillMaxWidth())
                                Spacer(Modifier.height(12.dp))
                                downloadButton()
                            }
                        } else {
                            Row(Modifier.fillMaxWidth().padding(start = appLayout.pageMargin, end = appLayout.gutter, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                tabs(Modifier.weight(1f))
                                Spacer(Modifier.width(24.dp))
                                downloadButton()
                            }
                        }
                    }
                    item {
                        LazyRow(
                            contentPadding = PaddingValues(start = appLayout.pageMargin, end = appLayout.gutter, top = 14.dp, bottom = 14.dp),
                            horizontalArrangement = Arrangement.spacedBy(20.dp),
                        ) {
                            items(state.episodes, key = { it.playable.data }) { ep ->
                                val progress = library.progress(route.apiName, ep.playable.data)
                                val download = downloads.firstOrNull { it.id == DownloadManager.idFor(route.apiName, ep.playable.data) }
                                EpisodeCard(
                                    ep = ep,
                                    fallbackImage = response.backgroundPosterUrl ?: response.posterUrl,
                                    headers = response.posterHeaders,
                                    progress = progress?.progress,
                                    finished = progress?.finished == true,
                                    download = download,
                                    onDownload = { model.download(ep.playable) },
                                ) {
                                    val start = progress?.takeIf { !it.finished }?.positionMs ?: 0
                                    model.playRequest(ep.playable, start)?.let { nav.open(Route.Player(it)) }
                                }
                            }
                        }
                    }
                }

                // The provider's cast, or IMDb's stars when the provider has none
                val cast = response.actors.orEmpty().ifEmpty {
                    state.imdb?.stars.orEmpty().map { ActorData(Actor(it.name, it.image)) }
                }
                if (cast.isNotEmpty()) {
                    item {
                        ShelfTitle("Cast & Crew", Modifier.padding(start = appLayout.pageMargin - appLayout.gutter, top = 18.dp))
                        LazyRow(
                            contentPadding = PaddingValues(start = appLayout.pageMargin, end = appLayout.gutter, top = 14.dp, bottom = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(22.dp),
                        ) {
                            items(cast) { actor ->
                                Column(Modifier.width(104.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                    RemoteImage(actor.actor.image, null, Modifier.size(96.dp).clip(CircleShape))
                                    Spacer(Modifier.height(8.dp))
                                    Text(
                                        actor.actor.name, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onBackground,
                                        maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
                                    )
                                    (actor.roleString ?: actor.role?.name)?.let {
                                        Text(it, style = MaterialTheme.typography.labelSmall, color = secondaryText, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
                                    }
                                }
                            }
                        }
                    }
                }

                val recommendations = response.recommendations.orEmpty()
                if (recommendations.isNotEmpty()) {
                    item {
                        Box(Modifier.padding(start = appLayout.pageMargin - appLayout.gutter)) {
                            PosterRow("More Like This", recommendations.map { it.toPoster() }) {
                                nav.open(Route.Details(it.apiName, it.url, it.name))
                            }
                        }
                    }
                }

                state.imdb?.let { imdb ->
                    item { RatingsAndReviews(imdb, state.reviews) { showReviews = true } }
                    item { InformationSection(imdb) }
                }
            }
        }
        GlassButton(
            Icons.AutoMirrored.Filled.ArrowBack, "Back",
            modifier = Modifier.padding(start = if (appLayout.compact) 12.dp else 20.dp, top = if (appLayout.compact) 12.dp else 20.dp),
        ) { nav.back() }
    }
}

/**
 * The top of the page: a full-bleed backdrop that fades into the page, with the logo or title,
 * key facts, the main actions and the summary over its lower left.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Header(route: Route.Details, response: LoadResponse, state: DetailsState, model: DetailsModel) {
    val library by LibraryStore.state.collectAsState()
    val downloads by DownloadManager.entries.collectAsState()
    val bookmark = library.bookmark(route.apiName, route.url)
    val background = MaterialTheme.colorScheme.background
    var plotExpanded by remember { mutableStateOf(false) }

    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val height = (maxWidth * 0.52f).coerceIn(440.dp, 660.dp)
        Box(Modifier.fillMaxWidth().heightIn(min = height)) {
            RemoteImage(
                response.backgroundPosterUrl ?: response.posterUrl,
                response.posterHeaders,
                Modifier.matchParentSize(),
            )
            Box(Modifier.matchParentSize().background(leftScrim(background)))
            Box(Modifier.matchParentSize().background(bottomScrim(background)))

            Column(
                Modifier.align(Alignment.BottomStart).padding(start = appLayout.pageMargin, end = appLayout.pageMargin, top = 120.dp, bottom = 28.dp).widthIn(max = 680.dp),
            ) {
                val logo = response.logoUrl
                if (logo != null) {
                    RemoteImage(logo, response.posterHeaders, Modifier.heightIn(max = if (appLayout.compact) 96.dp else 150.dp).widthIn(max = 440.dp), contentScale = ContentScale.Fit, placeholder = false)
                } else {
                    Text(plainText(response.name), style = if (appLayout.compact) MaterialTheme.typography.headlineLarge else MaterialTheme.typography.displayMedium, color = Color.White, maxLines = 3, overflow = TextOverflow.Ellipsis)
                }
                Spacer(Modifier.height(14.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp), itemVerticalAlignment = Alignment.CenterVertically) {
                    val facts = listOfNotNull(
                        response.year?.toString(),
                        response.type.label,
                        response.duration?.takeIf { it > 0 }?.let { if (it >= 60) "${it / 60}h ${it % 60}m" else "${it}m" },
                        (response as? EpisodeResponse)?.showStatus?.name,
                        response.score?.toStringNull(0.1, 10, 1)?.let { "★ $it" },
                    )
                    facts.forEachIndexed { i, fact ->
                        if (i > 0) Text("·", style = MaterialTheme.typography.labelLarge, color = Color.White.copy(alpha = 0.6f))
                        Text(fact, style = MaterialTheme.typography.labelLarge, color = Color.White.copy(alpha = 0.85f))
                    }
                    state.imdb?.let { imdb -> ImdbBadges(imdb) }
                    (response.contentRating ?: state.imdb?.certificate)?.let { rating ->
                        Text(
                            rating, style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.85f),
                            modifier = Modifier.border(1.dp, Color.White.copy(alpha = 0.6f), RoundedCornerShape(3.dp)).padding(horizontal = 5.dp, vertical = 1.dp),
                        )
                    }
                }
                response.tags?.map(::plainText)?.filter { it.isNotBlank() }?.takeIf { it.isNotEmpty() }?.let { tags ->
                    Spacer(Modifier.height(6.dp))
                    Text(tags.take(5).joinToString(" · "), style = MaterialTheme.typography.labelMedium, color = Color.White.copy(alpha = 0.65f))
                }
                Spacer(Modifier.height(20.dp))
                // Wraps onto a second line on phones
                FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp), itemVerticalAlignment = Alignment.CenterVertically) {
                    PlayButton(route, response, state, model)
                    BookmarkButton(bookmark?.watchType, model::setBookmark)
                    model.movieItem?.let { movie ->
                        val download = downloads.firstOrNull { it.id == DownloadManager.idFor(route.apiName, movie.data) }
                        Box(Modifier.background(Color.White.copy(alpha = 0.16f), CircleShape)) {
                            DownloadButton(download, tint = Color.White) { model.download(movie) }
                        }
                    }
                    // TVs usually have no web browser, so these would only show an error there
                    response.trailers.firstOrNull()?.takeIf { !com.lagradost.cloudstream4.desktop.Platform.isTv }?.let { trailer ->
                        GlassButton(Icons.Default.Theaters, "Trailer", text = "Trailer") { openInBrowser(trailer.extractorUrl) }
                    }
                    webPage(route.apiName, response.url)?.takeIf { !com.lagradost.cloudstream4.desktop.Platform.isTv }?.let { page ->
                        GlassButton(Icons.Default.OpenInBrowser, "Open in browser") { openInBrowser(page) }
                    }
                }
                (response.plot?.let(::plainText)?.takeIf { it.isNotBlank() } ?: state.imdb?.plot)?.let {
                    Spacer(Modifier.height(18.dp))
                    Text(
                        it,
                        style = if (appLayout.compact) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.bodyLarge,
                        color = Color.White.copy(alpha = 0.85f),
                        maxLines = if (plotExpanded) Int.MAX_VALUE else 3,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.clickable(indication = tvIndication, interactionSource = remember { MutableInteractionSource() }) { plotExpanded = !plotExpanded },
                    )
                }
                Spacer(Modifier.height(10.dp))
                Text(route.apiName, style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.5f))
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
            if (p != null && !p.finished && p.positionMs > 0) Triple("Resume", null, p.positionMs)
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
    // TV: a title opens with Play focused, so OK plays and the arrows start from there
    val playFocus = remember { androidx.compose.ui.focus.FocusRequester() }
    if (com.lagradost.cloudstream4.desktop.Platform.isTv) {
        androidx.compose.runtime.LaunchedEffect(Unit) { runCatching { playFocus.requestFocus() } }
    }
    PrimaryCapsuleButton(label, Icons.Default.PlayArrow, Modifier.focusRequester(playFocus)) {
        model.playRequest(target, start)?.let { nav.open(Route.Player(it)) }
    }
}

@Composable
private fun BookmarkButton(current: WatchType?, onSelect: (WatchType?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        GlassButton(
            if (current != null) Icons.Default.Check else Icons.Default.Add,
            current?.label ?: "Add to Library",
            text = current?.label,
        ) { open = true }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            WatchType.entries.forEach { type ->
                DropdownMenuItem(
                    text = { Text(type.label, fontWeight = if (type == current) FontWeight.Bold else null) },
                    onClick = { open = false; onSelect(type) },
                )
            }
            if (current != null) {
                DropdownMenuItem(text = { Text("Remove from Library") }, onClick = { open = false; onSelect(null) })
            }
        }
    }
}

/** Season or dub choice as plain text tabs: the selected one bright and bold, others dimmed. */
@Composable
private fun TabText(text: String, selected: Boolean, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHighlightedAsState()
    Text(
        text,
        style = MaterialTheme.typography.titleLarge,
        color = MaterialTheme.colorScheme.onBackground.copy(alpha = if (selected) 1f else if (hovered) 0.8f else 0.45f),
        modifier = Modifier.hoverable(interaction).clickable(interactionSource = interaction, indication = tvIndication, onClick = onClick),
    )
}

/** A quiet capsule button for actions on the plain page, next to more important content. */
@Composable
private fun SubtleCapsule(icon: ImageVector, text: String, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHighlightedAsState()
    Row(
        Modifier
            .pressScale(interaction)
            .hoverable(interaction)
            .background(MaterialTheme.colorScheme.onBackground.copy(alpha = if (hovered) 0.16f else 0.09f), RoundedCornerShape(50))
            .clickable(interactionSource = interaction, indication = tvIndication, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.onBackground, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onBackground, maxLines = 1)
    }
}

/** An episode as an Apple TV card: a still with progress, the episode number, title and summary. */
@Composable
private fun EpisodeCard(
    ep: EpisodeUi,
    fallbackImage: String?,
    headers: Map<String, String>?,
    progress: Float?,
    finished: Boolean,
    download: DownloadEntry?,
    onDownload: () -> Unit,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHighlightedAsState()
    Column(Modifier.width(if (appLayout.compact) 240.dp else 300.dp)) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .tvLift(interaction, hoverScale = 1.05f)
                .clip(RoundedCornerShape(12.dp)),
        ) {
            RemoteImage(ep.posterUrl ?: fallbackImage, if (ep.posterUrl != null) null else headers, Modifier.fillMaxSize())
            if (hovered) {
                Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.3f)))
                Box(
                    Modifier.align(Alignment.Center).size(48.dp).background(Color.Black.copy(alpha = 0.55f), CircleShape).border(2.dp, Color.White, CircleShape),
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Default.PlayArrow, "Play", tint = Color.White, modifier = Modifier.size(28.dp)) }
            }
            // The card's click area is a layer of its own, beside the download button rather than
            // around it: a remote can't move focus into a clickable's children, so on TV the button
            // was out of reach
            Box(Modifier.matchParentSize().clickable(interactionSource = interaction, indication = null, onClick = onClick))
            // Download stays visible once started, otherwise it appears on hover. Touch screens and remotes can't hover, so there it always shows.
            if (hovered || download != null || com.lagradost.cloudstream4.desktop.Platform.isTouch || com.lagradost.cloudstream4.desktop.Platform.isTv) {
                Box(Modifier.align(Alignment.TopEnd).padding(6.dp).background(Color.Black.copy(alpha = 0.6f), CircleShape)) {
                    DownloadButton(download, tint = Color.White, onStart = onDownload)
                }
            }
            if (finished) {
                Icon(
                    Icons.Default.CheckCircle, "Watched", tint = Color.White,
                    modifier = Modifier.align(Alignment.TopStart).padding(8.dp).size(22.dp),
                )
            }
            if (progress != null && progress > 0f && !finished) {
                Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(4.dp).background(Color.White.copy(alpha = 0.3f))) {
                    Box(Modifier.fillMaxHeight().fillMaxWidth(progress.coerceIn(0.02f, 1f)).background(MaterialTheme.colorScheme.primary))
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        val number = listOfNotNull(
            ep.playable.season?.takeIf { it > 0 }?.let { "SEASON $it" },
            ep.playable.episode?.let { "EPISODE $it" },
        ).joinToString(", ")
        if (number.isNotEmpty()) Text(number, style = MaterialTheme.typography.labelSmall, color = secondaryText)
        Text(
            ep.playable.name ?: ep.playable.label,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        ep.description?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = secondaryText, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        ep.runTimeMinutes?.let { Text("$it min", style = MaterialTheme.typography.labelSmall, color = secondaryText, modifier = Modifier.padding(top = 2.dp)) }
    }
}


/** IMDb's yellow badge with the rating and vote count, and the Metascore in its colour. */
@Composable
internal fun ImdbBadges(imdb: ImdbInfo) {
    imdb.rating?.let { rating ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "IMDb", color = Color.Black, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.ExtraBold,
                modifier = Modifier.background(Color(0xFFF5C518), RoundedCornerShape(3.dp)).padding(horizontal = 5.dp, vertical = 1.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text("%.1f".format(rating), style = MaterialTheme.typography.labelLarge, color = Color.White)
            imdb.votes?.let { Text("  ${compactCount(it)}", style = MaterialTheme.typography.labelMedium, color = Color.White.copy(alpha = 0.6f)) }
        }
    }
    imdb.metascore?.let { score ->
        val color = when {
            score >= 61 -> Color(0xFF54A72A)
            score >= 40 -> Color(0xFFFFCC33)
            else -> Color(0xFFFF0000)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "$score", color = if (score in 40..60) Color.Black else Color.White, style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.background(color, RoundedCornerShape(3.dp)).padding(horizontal = 5.dp, vertical = 1.dp),
            )
            Spacer(Modifier.width(5.dp))
            Text("Metascore", style = MaterialTheme.typography.labelMedium, color = Color.White.copy(alpha = 0.6f))
        }
    }
}

/**
 * The Apple TV "Information" block: facts and credits in columns, from IMDb, with a link to
 * the title's IMDb page.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun InformationSection(imdb: ImdbInfo) {
    val facts = buildList<Pair<String, String>> {
        imdb.released?.let { add("Released" to it) }
        if (imdb.endYear != null && imdb.year != null) add("Years" to "${imdb.year}–${imdb.endYear}")
        imdb.runtimeMinutes?.takeIf { it > 0 }?.let { add("Run Time" to if (it >= 60) "${it / 60} hr ${it % 60} min" else "$it min") }
        imdb.certificate?.let { add("Rated" to it) }
        if (imdb.genres.isNotEmpty()) add("Genres" to imdb.genres.joinToString(", "))
        if (imdb.seasons != null || imdb.episodes != null) {
            add("Episodes" to listOfNotNull(imdb.seasons?.let { "$it season${if (it > 1) "s" else ""}" }, imdb.episodes?.let { "$it episodes" }).joinToString(", "))
        }
        if (imdb.creators.isNotEmpty()) add("Created By" to imdb.creators.joinToString(", ") { it.name })
        if (imdb.directors.isNotEmpty()) add((if (imdb.directors.size > 1) "Directors" else "Director") to imdb.directors.joinToString(", ") { it.name })
        if (imdb.writers.isNotEmpty()) add("Writers" to imdb.writers.take(4).joinToString(", ") { it.name })
        if (imdb.stars.isNotEmpty()) add("Starring" to imdb.stars.take(5).joinToString(", ") { it.name })
        if (imdb.countries.isNotEmpty()) add("Region of Origin" to imdb.countries.joinToString(", "))
        if (imdb.languages.isNotEmpty()) add("Languages" to imdb.languages.take(4).joinToString(", "))
        imdb.awards?.let { add("Awards" to it) }
        imdb.budget?.let { add("Budget" to it) }
        imdb.grossWorldwide?.let { add("Box Office" to "$it worldwide") }
        imdb.rating?.let { r -> add("IMDb Rating" to "%.1f/10".format(r) + (imdb.votes?.let { " from ${compactCount(it)} ratings" } ?: "")) }
        imdb.popularity?.let { add("Popularity" to "#$it on IMDb") }
    }
    Column(Modifier.fillMaxWidth().padding(start = appLayout.pageMargin, end = appLayout.gutter, top = 28.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Information", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onBackground, modifier = Modifier.weight(1f))
            if (!com.lagradost.cloudstream4.desktop.Platform.isTv) SubtleCapsule(Icons.Default.OpenInBrowser, "View on IMDb") { openInBrowser(imdb.url) }
        }
        Spacer(Modifier.height(16.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(if (appLayout.compact) 16.dp else 32.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            facts.forEach { (label, value) ->
                Column(Modifier.width(if (appLayout.compact) 150.dp else 240.dp)) {
                    Text(label, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onBackground)
                    Spacer(Modifier.height(3.dp))
                    Text(value, style = MaterialTheme.typography.bodySmall, color = secondaryText)
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Text("Information from IMDb", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.35f))
    }
}
