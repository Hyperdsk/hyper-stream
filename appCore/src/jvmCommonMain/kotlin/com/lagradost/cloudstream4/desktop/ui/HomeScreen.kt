package com.lagradost.cloudstream4.desktop.ui

import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream4.desktop.data.LibraryStore
import com.lagradost.cloudstream4.desktop.data.SettingsStore
import com.lagradost.cloudstream4.desktop.plugins.ExtensionManager
import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toPersistentList
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.launch

data class HomeRow(
    val key: String,
    val order: Int,
    val name: String,
    val request: MainPageRequest,
    val items: PersistentList<SearchResponse>,
    val horizontal: Boolean,
    val page: Int,
    val hasNext: Boolean,
    val loadingMore: Boolean = false,
)

data class HomeState(
    val providers: PersistentList<String> = persistentListOf(),
    val provider: String? = null,
    val rows: PersistentList<HomeRow> = persistentListOf(),
    val loading: Boolean = false,
    val error: String? = null,
    val hasMainPage: Boolean = true,
    /** The featured carousel: the top row's first titles, with details loaded for backdrops. */
    val hero: PersistentList<HeroItem> = persistentListOf(),
)

data class HeroItem(val search: SearchResponse, val details: LoadResponse? = null)

private const val HERO_COUNT = 6

class HomeModel : ScreenModel<HomeState>(HomeState()) {
    private var job: Job? = null
    private var heroJob: Job? = null

    init {
        scope.launch {
            ExtensionManager.state.distinctUntilChangedBy { it.providersVersion }.collect { refreshProviders() }
        }
    }

    private fun refreshProviders() {
        val apis = ExtensionManager.providers()
        val names = apis.map { it.name }.toPersistentList()
        val saved = SettingsStore.state.value.homeProvider
        val selected = when {
            current.provider != null && current.provider in names -> current.provider
            saved != null && saved in names -> saved
            else -> apis.firstOrNull { it.hasMainPage }?.name ?: names.firstOrNull()
        }
        val changed = selected != current.provider
        update { copy(providers = names, provider = selected) }
        if (changed || current.rows.isEmpty()) load()
    }

    fun select(name: String) {
        SettingsStore.update { it.copy(homeProvider = name) }
        update { copy(provider = name) }
        load()
    }

    fun load() {
        job?.cancel()
        val api = current.provider?.let { providerByName(it) }
        if (api == null) {
            update { copy(rows = persistentListOf(), loading = false, error = null) }
            return
        }
        if (!api.hasMainPage) {
            update { copy(rows = persistentListOf(), loading = false, error = null, hasMainPage = false) }
            return
        }
        heroJob?.cancel()
        update { copy(rows = persistentListOf(), loading = true, error = null, hasMainPage = true, hero = persistentListOf()) }
        job = scope.launch {
            val requests = api.mainPage.mapIndexed { i, page -> i to MainPageRequest(page.name, page.data, page.horizontalImages) }
            val errors = mutableListOf<Throwable>()

            suspend fun fetch(order: Int, request: MainPageRequest) {
                providerCall { api.getMainPage(1, request) }
                    .onSuccess { response ->
                        val rows = response?.items.orEmpty().mapIndexed { j, list ->
                            HomeRow(
                                key = "$order:$j:${list.name}",
                                order = order * 1000 + j,
                                name = list.name,
                                request = request,
                                items = list.list.toPersistentList(),
                                horizontal = list.isHorizontalImages,
                                page = 1,
                                hasNext = response?.hasNext == true,
                            )
                        }.filter { it.items.isNotEmpty() }
                        update { copy(rows = (this.rows + rows).sortedBy { it.order }.toPersistentList()) }
                        if (current.hero.isEmpty()) startHero(api)
                    }
                    .onFailure { errors += it }
            }

            if (api.sequentialMainPage) {
                requests.forEach { (i, r) ->
                    fetch(i, r)
                    if (api.sequentialMainPageDelay > 0) delay(api.sequentialMainPageDelay)
                }
            } else {
                requests.map { (i, r) -> async { fetch(i, r) } }.awaitAll()
            }
            update {
                copy(
                    loading = false,
                    error = if (this.rows.isEmpty()) errors.firstOrNull()?.userMessage() ?: "This provider returned nothing." else null,
                )
            }
        }
    }

    /** Fills the carousel from the first row, then loads each title's details for its backdrop and logo. */
    private fun startHero(api: com.lagradost.cloudstream3.MainAPI) {
        val picks = current.rows.firstOrNull()?.items?.take(HERO_COUNT).orEmpty()
        if (picks.isEmpty()) return
        update { copy(hero = picks.map { HeroItem(it) }.toPersistentList()) }
        heroJob = scope.launch {
            val limit = kotlinx.coroutines.sync.Semaphore(3)
            picks.map { item ->
                async {
                    limit.acquire()
                    try {
                        providerCall { api.load(item.url) }.getOrNull()?.let { details ->
                            update { copy(hero = hero.map { if (it.search.url == item.url) it.copy(details = details) else it }.toPersistentList()) }
                        }
                    } finally {
                        limit.release()
                    }
                }
            }.awaitAll()
        }
    }

    fun loadMore(key: String) {
        val row = current.rows.firstOrNull { it.key == key } ?: return
        if (!row.hasNext || row.loadingMore) return
        val api = current.provider?.let { providerByName(it) } ?: return
        setRow(key) { copy(loadingMore = true) }
        scope.launch {
            providerCall { api.getMainPage(row.page + 1, row.request) }
                .onSuccess { response ->
                    val lists = response?.items.orEmpty()
                    val list = lists.firstOrNull { it.name == row.name } ?: lists.singleOrNull()
                    val known = row.items.map { it.url }.toSet()
                    val added = list?.list.orEmpty().filter { it.url !in known }
                    setRow(key) {
                        copy(
                            items = (items + added).toPersistentList(),
                            page = page + 1,
                            hasNext = response?.hasNext == true && added.isNotEmpty(),
                            loadingMore = false,
                        )
                    }
                }
                .onFailure { setRow(key) { copy(loadingMore = false, hasNext = false) } }
        }
    }

    private fun setRow(key: String, block: HomeRow.() -> HomeRow) = update {
        copy(rows = rows.map { if (it.key == key) it.block() else it }.toPersistentList())
    }
}

@Composable
fun HomeScreen(model: HomeModel) {
    val nav = LocalNavigator.current
    val state by model.state.collectAsState()
    val library by LibraryStore.state.collectAsState()
    val continueWatching = library.continueWatching

    if (state.providers.isEmpty() && continueWatching.isEmpty()) {
        Column(Modifier.fillMaxSize()) {
            LargeTitle("Home")
            MessageBox(
                title = "No extensions yet",
                body = "Hyper Stream doesn't include any content. Add a repository and install extensions to start browsing.",
                actionLabel = "Open Extensions",
            ) { nav.open(Route.Extensions) }
        }
        return
    }

    Box(Modifier.fillMaxSize()) {
        val listState = rememberLazyListState()
        val scrollScope = rememberCoroutineScope()
        LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(bottom = 40.dp)) {
            if (state.hero.isNotEmpty()) {
                item(key = "hero") {
                    HeroCarousel(state.hero, onFocused = { scrollScope.launch { listState.animateScrollToItem(0) } })
                }
            } else {
                // Room for the floating provider picker when there is no hero
                item(key = "title") { LargeTitle("Home") }
            }
            if (continueWatching.isNotEmpty()) {
                item(key = "continue") { ContinueWatchingRow(continueWatching) }
            }
            when {
                state.loading && state.rows.isEmpty() -> item { LoadingBox(Modifier.fillMaxWidth().padding(64.dp)) }
                !state.hasMainPage -> item {
                    MessageBox(
                        "${state.provider} has no home page",
                        "Use Search to find something on this provider.",
                        "Go to Search",
                        Modifier.fillMaxWidth(),
                    ) { nav.open(Route.Search) }
                }

                state.error != null -> item {
                    MessageBox("Couldn't load ${state.provider}", state.error, "Try again", Modifier.fillMaxWidth(), model::load)
                }
            }
            items(state.rows, key = { it.key }) { row ->
                PosterRow(
                    title = row.name,
                    items = row.items.map { it.toPoster() },
                    horizontal = row.horizontal,
                    loadingMore = row.loadingMore,
                    onReachEnd = if (row.hasNext) ({ model.loadMore(row.key) }) else null,
                ) { nav.open(Route.Details(it.apiName, it.url, it.name)) }
            }
        }

        // Provider choice floats over the top right, like the Apple TV app's account controls
        if (state.providers.isNotEmpty()) {
            Row(
                Modifier.align(Alignment.TopEnd).padding(top = if (appLayout.compact) 12.dp else 20.dp, end = if (appLayout.compact) 12.dp else 24.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ProviderPicker(state.providers, state.provider, onSelect = model::select)
                GlassButton(Icons.Default.Refresh, "Reload", onClick = model::load)
            }
        }
    }
}

@Composable
fun ProviderPicker(providers: List<String>, selected: String?, onSelect: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        GlassButton(Icons.Default.ArrowDropDown, "Choose provider", text = selected ?: "Choose provider") { open = true }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            providers.forEach { name ->
                DropdownMenuItem(
                    text = { Text(name, fontWeight = if (name == selected) FontWeight.Bold else null) },
                    onClick = { open = false; onSelect(name) },
                )
            }
        }
    }
}
