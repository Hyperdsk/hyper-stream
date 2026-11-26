package com.lagradost.cloudstream4.desktop.ui

import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.utils.Levenshtein
import com.lagradost.cloudstream4.desktop.data.SearchHistory
import com.lagradost.cloudstream4.desktop.data.SettingsStore
import com.lagradost.cloudstream4.desktop.plugins.ExtensionManager
import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toPersistentList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.launch

/** The quick filters under the search box, grouped as in CloudStream's Android app. */
enum class MediaFilter(val label: String, val types: Set<TvType>) {
    Movies("Movies", setOf(TvType.Movie)),
    Series("TV Series", setOf(TvType.TvSeries)),
    Anime("Anime", setOf(TvType.Anime, TvType.OVA, TvType.AnimeMovie)),
    Asian("Asian", setOf(TvType.AsianDrama)),
    Cartoons("Cartoons", setOf(TvType.Cartoon)),
    Documentaries("Documentaries", setOf(TvType.Documentary)),
    Live("Live", setOf(TvType.Live)),
    Torrents("Torrents", setOf(TvType.Torrent)),
    Nsfw("NSFW", setOf(TvType.NSFW)),
    Others(
        "Others",
        TvType.entries.toSet() - setOf(
            TvType.Movie, TvType.TvSeries, TvType.Anime, TvType.OVA, TvType.AnimeMovie, TvType.AsianDrama,
            TvType.Cartoon, TvType.Documentary, TvType.Live, TvType.Torrent, TvType.NSFW,
        ),
    ),
}

/** One installed extension, as the picker lists it. Same-named extensions share a row. */
data class SearchProvider(val name: String, val types: Set<TvType>)

data class ProviderResults(
    val provider: String,
    /** Position in the search: two extensions can have the same name, so the name can't tell them apart. */
    val id: Int = 0,
    val items: PersistentList<SearchResponse> = persistentListOf(),
    val loading: Boolean = true,
    val error: String? = null,
    /** [items] as posters, built once when they arrive rather than on every recomposition. */
    val posters: PersistentList<PosterItem> = persistentListOf(),
    /** How well the best of [items] matches the search, 0 to 100 (see [searchRelevance]). */
    val best: Int = 0,
    /** When it arrived among this search's answers, for adding rows at the end once the order is frozen. */
    val arrival: Int = 0,
)

data class SearchState(
    val query: String = "",
    val providers: PersistentList<SearchProvider> = persistentListOf(),
    val results: PersistentList<ProviderResults> = persistentListOf(),
    val searchedFor: String? = null,
    /**
     * Row ids in the order shown when the user moved into the results. Rows then stay put (on TV
     * the row under the remote moved as answers came in), and later answers go at the end.
     */
    val frozenOrder: PersistentList<Int>? = null,
)

/** Rows to show: close matches, other results, and rows that arrived after the order was frozen. */
internal data class OrderedRows(
    val relevant: List<ProviderResults>,
    val others: List<ProviderResults>,
    val later: List<ProviderResults> = emptyList(),
) {
    val all: List<ProviderResults> get() = relevant + others + later
}

/**
 * The rows that found something, best match first and rows with a close match before the rest.
 * Once [frozen], those rows keep their order and newer ones follow in the order they arrived.
 */
internal fun orderRows(results: List<ProviderResults>, frozen: List<Int>?): OrderedRows {
    val found = results.filter { !it.loading && it.items.isNotEmpty() }
    if (frozen == null) {
        val ranked = found.sortedWith(compareByDescending<ProviderResults> { it.best }.thenBy { it.id })
        return OrderedRows(ranked.filter { it.best >= RELEVANT }, ranked.filter { it.best < RELEVANT })
    }
    val byId = found.associateBy { it.id }
    val kept = frozen.mapNotNull { byId[it] }
    val keptIds = kept.map { it.id }.toSet()
    return OrderedRows(
        relevant = kept.takeWhile { it.best >= RELEVANT },
        others = kept.dropWhile { it.best >= RELEVANT },
        later = found.filter { it.id !in keptIds }.sortedBy { it.arrival },
    )
}

/** One extension's answer, scored and sorted best match first, with its posters. */
internal class ScoredItems(val items: PersistentList<SearchResponse>, val posters: PersistentList<PosterItem>, val best: Int)

internal fun scoreItems(query: String, items: List<SearchResponse>): ScoredItems {
    val sorted = items.map { it to searchRelevance(query, it.name) }.sortedByDescending { it.second }
    return ScoredItems(
        sorted.map { it.first }.toPersistentList(),
        sorted.map { it.first.toPoster() }.toPersistentList(),
        sorted.firstOrNull()?.second ?: 0,
    )
}

/**
 * The last search's answers per extension, for a title page's "Also on" list (see DetailsModel):
 * opened from Search, the other extensions that have the title are known without searching again.
 */
object LastSearch {
    @Volatile
    var query: String? = null
        private set
    private val found = java.util.concurrent.ConcurrentHashMap<String, List<SearchResponse>>()

    fun start(query: String) {
        found.clear()
        this.query = query
    }

    fun add(query: String, apiName: String, items: List<SearchResponse>) {
        if (query == this.query) found.merge(apiName, items) { a, b -> a + b }
    }

    fun results(): Map<String, List<SearchResponse>> = found.toMap()
}

/** A title name for comparing: no punctuation or case, and without a year at the end ("Dune (2021)"). */
private fun comparableTitle(name: String) = normalizeTitle(name).replace(Regex("\\s+(19|20)\\d\\d$"), "")

/**
 * Whether two extensions' titles are the same one: the same name (ignoring case, punctuation and
 * a year in it) and the same year when both give one. Not the type: many extensions leave it at
 * the library's default whatever the title is (see [toPoster]).
 */
internal fun sameTitle(nameA: String, yearA: Int?, nameB: String, yearB: Int?): Boolean {
    val a = comparableTitle(nameA)
    if (a.isEmpty() || a != comparableTitle(nameB)) return false
    return yearA == null || yearB == null || yearA == yearB
}

/** The year a search result gives, if its kind has one. */
internal val SearchResponse.yearOrNull: Int?
    get() = when (this) {
        is com.lagradost.cloudstream3.MovieSearchResponse -> year
        is com.lagradost.cloudstream3.TvSeriesSearchResponse -> year
        is com.lagradost.cloudstream3.AnimeSearchResponse -> year
        else -> null
    }

private fun normalizeTitle(text: String) = text.lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()

/**
 * How well [title] matches what was searched, 0 to 100: the exact title first, then titles that
 * start with it or contain every word ("dune" -> "Dune: Part Two"), then close spellings.
 */
internal fun searchRelevance(query: String, title: String): Int {
    val q = normalizeTitle(query)
    val t = normalizeTitle(title)
    if (q.isEmpty() || t.isEmpty()) return 0
    if (t == q) return 100
    val titleWords = t.split(' ')
    // A word counts when it's there whole, or as the start of a longer word ("spider" in "spiderman")
    val allWords = q.split(' ').all { w -> titleWords.any { it == w || (w.length >= 3 && it.startsWith(w)) } }
    // Extra words in the title (a sequel's subtitle) make a match a little weaker
    val extra = (titleWords.size - q.split(' ').size).coerceAtLeast(0)
    return when {
        t.startsWith("$q ") -> 95 - extra.coerceAtMost(10)
        allWords -> 85 - extra.coerceAtMost(10)
        // Close spellings, word by word ("jawaani" for "jawani"): the share of words found, up to 75
        else -> {
            val words = q.split(' ')
            val close = words.count { w -> titleWords.any { Levenshtein.ratio(w, it) >= 80 } }
            75 * close / words.size
        }
    }
}

/** Results this close to the search are shown first; the rest go under "Other results". */
internal const val RELEVANT = 60

/** The types the chosen quick filters cover, or null when none is chosen (everything). */
private fun Set<String>.wantedTypes(): Set<TvType>? =
    MediaFilter.entries.filter { it.name in this }.flatMap { it.types }.toSet().takeIf { it.isNotEmpty() }

/** Whether search uses [provider], given the extensions left out and the quick filters chosen. */
internal fun searches(provider: SearchProvider, excluded: Set<String>, types: Set<String>): Boolean {
    if (provider.name in excluded) return false
    val wanted = types.wantedTypes() ?: return true
    return provider.types.any { it in wanted }
}

class SearchModel : ScreenModel<SearchState>(SearchState()) {
    /** Whether the search box was focused on arrival already (see SearchScreen). */
    var autoFocused = false
    private var searchJob: Job? = null
    private var debounceJob: Job? = null

    init {
        scope.launch {
            ExtensionManager.state.distinctUntilChangedBy { it.providersVersion }.collect {
                val providers = ExtensionManager.providers()
                    .groupBy { it.name }
                    .map { (name, apis) -> SearchProvider(name, apis.flatMap { it.supportedTypes }.toSet()) }
                    .sortedBy { it.name.lowercase() }
                update { copy(providers = providers.toPersistentList()) }
            }
        }
    }

    fun onQueryChange(query: String) {
        update { copy(query = query) }
        debounceJob?.cancel()
        // A TV remote types slowly, pausing between letters: each pause searched every extension
        val tv = com.lagradost.cloudstream4.desktop.Platform.isTv
        debounceJob = scope.launch {
            delay(if (tv) 1_500 else 600)
            if (query.trim().length >= (if (tv) 3 else 2)) search()
        }
    }

    /** Search from the keyboard's Search key or a recent search: these are kept in the history. */
    fun submit(query: String = current.query) {
        if (query != current.query) update { copy(query = query) }
        SearchHistory.add(query)
        search()
    }

    /** Opening a result: what was typed found something, so it's worth keeping. */
    fun opened() = SearchHistory.add(current.query)

    fun search() {
        debounceJob?.cancel()
        val query = current.query.trim()
        if (query.isEmpty() || query == current.searchedFor && current.results.none { it.error != null }) return
        searchJob?.cancel()
        val settings = SettingsStore.state.value
        val wanted = settings.searchTypes.wantedTypes()
        val apis = ExtensionManager.providers().filter {
            searches(SearchProvider(it.name, it.supportedTypes.toSet()), settings.searchExcluded, settings.searchTypes)
        }
        update { copy(searchedFor = query, frozenOrder = null, results = apis.mapIndexed { i, api -> ProviderResults(api.name, i) }.toPersistentList()) }
        LastSearch.start(query)
        val arrivals = java.util.concurrent.atomic.AtomicInteger()
        searchJob = scope.launch {
            apis.forEachIndexed { i, api ->
                launch {
                    val result = providerCall { api.search(query, 1)?.items ?: emptyList() }
                        // With a media filter on, drop results of other types (results of unknown type stay)
                        .map { items -> if (wanted == null) items else items.filter { it.type == null || it.type in wanted } }
                    val items = result.getOrElse { e ->
                        setResult(i) { copy(loading = false, error = e.userMessage(), arrival = arrivals.incrementAndGet()) }
                        return@launch
                    }
                    LastSearch.add(query, api.name, items)
                    // Scored here, once, off the screen's thread: the screen scored every row again
                    // each time an extension answered, which made a TV slow while results came in
                    val scored = withContext(Dispatchers.Default) { scoreItems(query, items) }
                    setResult(i) {
                        copy(items = scored.items, posters = scored.posters, best = scored.best, loading = false, arrival = arrivals.incrementAndGet())
                    }
                }
            }
        }
    }

    /** Turns a quick filter on or off; [filter] null is "All". */
    fun toggleType(filter: MediaFilter?) {
        SettingsStore.update {
            it.copy(
                searchTypes = when {
                    filter == null -> emptySet()
                    filter.name in it.searchTypes -> it.searchTypes - filter.name
                    else -> it.searchTypes + filter.name
                },
            )
        }
        searchAgain()
    }

    fun setIncluded(names: Collection<String>, included: Boolean) {
        SettingsStore.update { it.copy(searchExcluded = if (included) it.searchExcluded - names.toSet() else it.searchExcluded + names) }
    }

    /** After the filters change: the same query with the new set of extensions. */
    fun searchAgain() {
        update { copy(searchedFor = null) }
        search()
    }

    fun clear() {
        searchJob?.cancel()
        update { copy(query = "", results = persistentListOf(), searchedFor = null, frozenOrder = null) }
    }

    /** The user moved into the results: keep the rows where they are now (see [SearchState.frozenOrder]). */
    fun freezeOrder(ids: List<Int>) {
        if (current.frozenOrder == null) update { copy(frozenOrder = ids.toPersistentList()) }
    }

    private fun setResult(id: Int, block: ProviderResults.() -> ProviderResults) = update {
        copy(results = results.map { if (it.id == id) it.block() else it }.toPersistentList())
    }
}

@Composable
fun SearchScreen(model: SearchModel) {
    val nav = LocalNavigator.current
    val state by model.state.collectAsState()
    val settings by SettingsStore.state.collectAsState()
    val history by SearchHistory.entries.collectAsState()
    val focus = remember { FocusRequester() }
    var picking by remember { mutableStateOf(false) }
    // Ready to type on arrival, but only once per visit to an empty Search: on phones focusing the
    // box opens the keyboard, which would cover the results every time you came back to them
    LaunchedEffect(Unit) {
        if (com.lagradost.cloudstream4.desktop.Platform.isTv || (state.query.isEmpty() && !model.autoFocused)) {
            model.autoFocused = true
            runCatching { focus.requestFocus() }
        }
    }

    val searched = state.providers.count { searches(it, settings.searchExcluded, settings.searchTypes) }
    // Only the filters some installed extension can use
    val filters = MediaFilter.entries.filter { f -> state.providers.any { p -> p.types.any { it in f.types } } }

    // TV: one compact line (field, media filters, extensions) and no large title, since the top
    // bar already says where you are; the title, a tall field and a chip row took a third of the screen
    val tv = com.lagradost.cloudstream4.desktop.Platform.isTv
    val chips: @Composable (Modifier, PaddingValues) -> Unit = { modifier, padding ->
        LazyRow(modifier, contentPadding = padding, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            item(key = "all") {
                FilterChip(selected = settings.searchTypes.isEmpty(), onClick = { model.toggleType(null) }, label = { Text("All") })
            }
            items(filters, key = { it.name }) { filter ->
                FilterChip(
                    selected = filter.name in settings.searchTypes,
                    onClick = { model.toggleType(filter) },
                    label = { Text(filter.label) },
                )
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        if (tv) Spacer(Modifier.height(10.dp)) else LargeTitle("Search")
        Row(
            Modifier.fillMaxWidth().padding(horizontal = appLayout.gutter),
            horizontalArrangement = Arrangement.spacedBy(if (tv) 10.dp else 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val tvChips = tv && filters.size > 1
            // On TV the screen opens with the box focused and no keyboard; OK opens it
            TvTextFieldBox(if (tvChips) Modifier.width(320.dp) else Modifier.weight(1f), focusRequester = focus) { fieldModifier ->
                if (tv) {
                    CompactSearchField(state.query, model::onQueryChange, model::clear, { model.submit() }, fieldModifier)
                } else {
                    // A filled, rounded field like Apple's search fields, rather than an outlined form input
                    TextField(
                        value = state.query,
                        onValueChange = model::onQueryChange,
                        placeholder = { Text("Movies, shows and more", style = MaterialTheme.typography.bodyLarge) },
                        leadingIcon = { Icon(Icons.Default.Search, null) },
                        trailingIcon = {
                            if (state.query.isNotEmpty()) IconButton(onClick = model::clear) { Icon(Icons.Default.Clear, "Clear") }
                        },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyLarge,
                        shape = RoundedCornerShape(12.dp),
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.1f),
                            unfocusedContainerColor = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.07f),
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent,
                        ),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { model.submit() }),
                        modifier = fieldModifier,
                    )
                }
            }
            if (tvChips) chips(Modifier.weight(1f), PaddingValues(0.dp))
            if (state.providers.size > 1) {
                // Which extensions to search; the badge shows how many are on when not all are
                BadgedBox(badge = { if (searched < state.providers.size) Badge { Text("$searched") } }) {
                    FilledTonalIconButton(
                        onClick = { picking = true },
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.size(if (tv) 44.dp else 56.dp),
                    ) { Icon(Icons.Default.Tune, "Choose extensions") }
                }
            }
        }
        if (tv) Spacer(Modifier.height(6.dp))
        else if (filters.size > 1) chips(Modifier, PaddingValues(horizontal = appLayout.gutter, vertical = 12.dp))

        when {
            state.providers.isEmpty() -> MessageBox(
                "No providers to search",
                "Install an extension first.",
                "Open Extensions",
            ) { nav.open(Route.Extensions) }

            searched == 0 -> MessageBox(
                "No extensions to search",
                "None of the chosen extensions has this kind of media.",
                "Search everything",
            ) {
                model.setIncluded(state.providers.map { it.name }, true)
                model.toggleType(null)
            }

            state.results.isEmpty() && state.query.isBlank() && history.isNotEmpty() -> RecentSearches(
                history.map { it.query },
                onSearch = { model.submit(it) },
                onRemove = SearchHistory::remove,
                onClear = SearchHistory::clear,
            )

            state.results.isEmpty() -> MessageBox(
                "Search for movies, shows and anime",
                if (searched == state.providers.size) "Results from all ${state.providers.size} extensions appear here."
                else "Results from $searched of ${state.providers.size} extensions appear here.",
            )

            else -> {
                // Only extensions that found something get a row; the rest are counted in the status line.
                // Each row's titles go best match first (scored as they arrived, see SearchModel), and
                // rows with a close match come before the rest until the user moves into them
                val rows = remember(state.results, state.frozenOrder) { orderRows(state.results, state.frozenOrder) }
                val relevant = rows.relevant
                val others = rows.others
                val found = rows.all
                val done = state.results.count { !it.loading }
                val total = state.results.size
                val searching = done < total
                if (!searching && found.isEmpty()) {
                    MessageBox(
                        "Nothing found for “${state.searchedFor}”",
                        "None of the $total extensions searched had it. Try another spelling, or more extensions with the filter button.",
                    )
                } else {
                    val tv = com.lagradost.cloudstream4.desktop.Platform.isTv
                    // TV: smaller posters, so about two rows fit on a 540 dp tall screen, and each
                    // row's extension name stays in view above the poster that has focus
                    val posterSize = if (tv) appLayout.poster * 0.7f else null
                    fun open(item: PosterItem) {
                        model.opened()
                        nav.open(Route.Details(item.apiName, item.url, item.name))
                    }
                    LazyColumn(
                        Modifier.fillMaxSize().onFocusChanged { if (it.hasFocus) model.freezeOrder(found.map { r -> r.id }) },
                        contentPadding = PaddingValues(bottom = 24.dp),
                    ) {
                        item(key = "status") {
                            Column(Modifier.fillMaxWidth().padding(horizontal = appLayout.gutter, vertical = 4.dp)) {
                                Text(
                                    when {
                                        searching -> "Searching… $done of $total extensions" + if (found.isNotEmpty()) ", found in ${found.size}" else ""
                                        else -> "Found in ${found.size} of $total extensions"
                                    },
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                if (searching) {
                                    LinearProgressIndicator(
                                        progress = { done.toFloat() / total.coerceAtLeast(1) },
                                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                                    )
                                }
                            }
                        }
                        items(relevant, key = { it.id }) { result ->
                            PosterRow(result.provider, result.posters, posterSize = posterSize, keepTitleVisible = tv, onClick = ::open)
                        }
                        if (others.isNotEmpty()) {
                            item(key = "others") {
                                Text(
                                    if (relevant.isEmpty()) "No close matches. Other results" else "Other results",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(start = appLayout.gutter, end = appLayout.gutter, top = 24.dp, bottom = 4.dp),
                                )
                            }
                            items(others, key = { it.id }) { result ->
                                PosterRow(result.provider, result.posters, posterSize = posterSize, keepTitleVisible = tv, onClick = ::open)
                            }
                        }
                        if (rows.later.isNotEmpty()) {
                            item(key = "later") {
                                Text(
                                    "More results",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(start = appLayout.gutter, end = appLayout.gutter, top = 24.dp, bottom = 4.dp),
                                )
                            }
                            items(rows.later, key = { it.id }) { result ->
                                PosterRow(result.provider, result.posters, posterSize = posterSize, keepTitleVisible = tv, onClick = ::open)
                            }
                        }
                    }
                }
            }
        }
    }

    if (picking) {
        ExtensionPicker(
            providers = state.providers,
            excluded = settings.searchExcluded,
            types = settings.searchTypes,
            onIncluded = model::setIncluded,
            onDone = {
                picking = false
                model.searchAgain()
            },
        )
    }
}

/** Searches made before, newest first, to run again with one click. */
@Composable
internal fun RecentSearches(queries: List<String>, onSearch: (String) -> Unit, onRemove: (String) -> Unit, onClear: () -> Unit) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item(key = "title") {
            Row(
                Modifier.fillMaxWidth().padding(start = appLayout.gutter, end = appLayout.gutter - 12.dp, top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Recent searches", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                TextButton(onClick = onClear) { Text("Clear") }
            }
        }
        // Prefixed, so a search for "title" can't clash with the header's key; and one row per
        // search, as entries merged from other devices may differ only in case or spaces
        items(queries.distinctBy { SearchHistory.key(it) }, key = { "q:" + SearchHistory.key(it) }) { query ->
            // The search and its remove button sit side by side, so a remote can reach both
            Row(Modifier.widthIn(max = 720.dp).padding(horizontal = appLayout.gutter - 16.dp), verticalAlignment = Alignment.CenterVertically) {
                ListItem(
                    headlineContent = { Text(query) },
                    leadingContent = { Icon(Icons.Default.History, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    modifier = Modifier.weight(1f)
                        .clickable(indication = tvIndication ?: LocalIndication.current, interactionSource = null) { onSearch(query) },
                )
                IconButton(onClick = { onRemove(query) }) { Icon(Icons.Default.Close, "Remove $query from recent searches") }
            }
        }
    }
}

/** Ticks for which extensions search uses, with a box to find one among many. */
@Composable
private fun ExtensionPicker(
    providers: List<SearchProvider>,
    excluded: Set<String>,
    types: Set<String>,
    onIncluded: (Collection<String>, Boolean) -> Unit,
    onDone: () -> Unit,
) {
    var filter by remember { mutableStateOf("") }
    val wanted = types.wantedTypes()
    val shown = providers.filter { filter.isBlank() || it.name.contains(filter.trim(), ignoreCase = true) }
    AlertDialog(
        onDismissRequest = onDone,
        title = { Text("Extensions to search") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (providers.size > 8) {
                    TvTextFieldBox(Modifier.fillMaxWidth()) { fieldModifier ->
                        OutlinedTextField(
                            value = filter,
                            onValueChange = { filter = it },
                            placeholder = { Text("Find an extension") },
                            leadingIcon = { Icon(Icons.Default.Search, null) },
                            singleLine = true,
                            modifier = fieldModifier,
                        )
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val on = providers.count { it.name !in excluded }
                    Text("$on of ${providers.size} on", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                    // Applies to the extensions the find box shows
                    TextButton(onClick = { onIncluded(shown.map { it.name }, true) }) { Text("All") }
                    TextButton(onClick = { onIncluded(shown.map { it.name }, false) }) { Text("None") }
                }
                LazyColumn(Modifier.heightIn(max = 420.dp)) {
                    items(shown, key = { it.name }) { provider ->
                        val included = provider.name !in excluded
                        val kinds = MediaFilter.entries.filter { f -> provider.types.any { it in f.types } }.joinToString(", ") { it.label }
                        // Media filters can leave out an extension that is ticked: say so
                        val matchesTypes = wanted == null || provider.types.any { it in wanted }
                        ListItem(
                            headlineContent = { Text(provider.name) },
                            supportingContent = {
                                if (kinds.isNotEmpty()) Text(if (matchesTypes) kinds else "$kinds · not in the chosen filters")
                            },
                            leadingContent = { Checkbox(checked = included, onCheckedChange = null) },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                            modifier = Modifier.clickable(indication = tvIndication ?: LocalIndication.current, interactionSource = null) {
                                onIncluded(listOf(provider.name), !included)
                            },
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDone) { Text("Done") } },
    )
}

/**
 * The TV's search field: 44 dp tall with smaller text, where Material's field is 56 dp with a
 * large font. Its focus ring comes from [TvTextFieldBox], so its corners match that ring's.
 */
@Composable
private fun CompactSearchField(query: String, onChange: (String) -> Unit, onClear: () -> Unit, onSearch: () -> Unit, modifier: Modifier) {
    val color = MaterialTheme.colorScheme.onBackground
    BasicTextField(
        value = query,
        onValueChange = onChange,
        singleLine = true,
        textStyle = MaterialTheme.typography.bodyMedium.copy(color = color),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { onSearch() }),
        modifier = modifier,
        decorationBox = { inner ->
            Row(
                Modifier.height(44.dp).background(color.copy(alpha = 0.08f), RoundedCornerShape(12.dp)).padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Default.Search, null, tint = color.copy(alpha = 0.7f), modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Box(Modifier.weight(1f)) {
                    if (query.isEmpty()) Text("Movies, shows and more", style = MaterialTheme.typography.bodyMedium, color = color.copy(alpha = 0.5f), maxLines = 1)
                    inner()
                }
                if (query.isNotEmpty()) {
                    Icon(Icons.Default.Clear, "Clear", tint = color.copy(alpha = 0.7f), modifier = Modifier.padding(start = 8.dp).size(18.dp).clickable(onClick = onClear))
                }
            }
        },
    )
}
