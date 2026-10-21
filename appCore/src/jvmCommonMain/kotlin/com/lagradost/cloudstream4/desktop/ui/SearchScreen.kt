package com.lagradost.cloudstream4.desktop.ui

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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream4.desktop.data.SearchHistory
import com.lagradost.cloudstream4.desktop.data.SettingsStore
import com.lagradost.cloudstream4.desktop.plugins.ExtensionManager
import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toPersistentList
import kotlinx.coroutines.Job
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
)

data class SearchState(
    val query: String = "",
    val providers: PersistentList<SearchProvider> = persistentListOf(),
    val results: PersistentList<ProviderResults> = persistentListOf(),
    val searchedFor: String? = null,
)

/** The types the chosen quick filters cover, or null when none is chosen (everything). */
private fun Set<String>.wantedTypes(): Set<TvType>? =
    MediaFilter.entries.filter { it.name in this }.flatMap { it.types }.toSet().takeIf { it.isNotEmpty() }

/** Whether search uses [provider], given the extensions left out and the quick filters chosen. */
private fun searches(provider: SearchProvider, excluded: Set<String>, types: Set<String>): Boolean {
    if (provider.name in excluded) return false
    val wanted = types.wantedTypes() ?: return true
    return provider.types.any { it in wanted }
}

class SearchModel : ScreenModel<SearchState>(SearchState()) {
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
        debounceJob = scope.launch {
            delay(600)
            if (query.trim().length >= 2) search()
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
        update { copy(searchedFor = query, results = apis.mapIndexed { i, api -> ProviderResults(api.name, i) }.toPersistentList()) }
        searchJob = scope.launch {
            apis.forEachIndexed { i, api ->
                launch {
                    providerCall { api.search(query, 1)?.items ?: emptyList() }
                        // With a media filter on, drop results of other types (results of unknown type stay)
                        .map { items -> if (wanted == null) items else items.filter { it.type == null || it.type in wanted } }
                        .onSuccess { items -> setResult(i) { copy(items = items.toPersistentList(), loading = false) } }
                        .onFailure { e -> setResult(i) { copy(loading = false, error = e.userMessage()) } }
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
        update { copy(query = "", results = persistentListOf(), searchedFor = null) }
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
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    val searched = state.providers.count { searches(it, settings.searchExcluded, settings.searchTypes) }
    // Only the filters some installed extension can use
    val filters = MediaFilter.entries.filter { f -> state.providers.any { p -> p.types.any { it in f.types } } }

    Column(Modifier.fillMaxSize()) {
        LargeTitle("Search")
        Row(
            Modifier.fillMaxWidth().padding(horizontal = appLayout.gutter),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // A filled, rounded field like Apple's search fields, rather than an outlined form input
            // On TV the screen opens with the box focused and no keyboard; OK opens it
            TvTextFieldBox(Modifier.weight(1f), focusRequester = focus) { fieldModifier ->
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
                    // This field has no outline, so on TV it gets a ring when focused
                    modifier = fieldModifier,
                )
            }
            if (state.providers.size > 1) {
                // Which extensions to search; the badge shows how many are on when not all are
                BadgedBox(badge = { if (searched < state.providers.size) Badge { Text("$searched") } }) {
                    FilledTonalIconButton(
                        onClick = { picking = true },
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.size(56.dp),
                    ) { Icon(Icons.Default.Tune, "Choose extensions") }
                }
            }
        }
        if (filters.size > 1) {
            LazyRow(
                contentPadding = PaddingValues(horizontal = appLayout.gutter, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
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

            else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
                items(state.results, key = { it.id }) { result ->
                    when {
                        result.loading -> PosterRowPlaceholder(result.provider, "Searching…")
                        result.error != null -> PosterRowPlaceholder(result.provider, result.error)
                        result.items.isEmpty() -> PosterRowPlaceholder(result.provider, "No results")
                        else -> PosterRow(result.provider, result.items.map { it.toPoster() }) {
                            model.opened()
                            nav.open(Route.Details(it.apiName, it.url, it.name))
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
private fun RecentSearches(queries: List<String>, onSearch: (String) -> Unit, onRemove: (String) -> Unit, onClear: () -> Unit) {
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
        items(queries, key = { SearchHistory.key(it) }) { query ->
            ListItem(
                headlineContent = { Text(query) },
                leadingContent = { Icon(Icons.Default.History, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                trailingContent = {
                    IconButton(onClick = { onRemove(query) }) { Icon(Icons.Default.Close, "Remove $query from recent searches") }
                },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                modifier = Modifier.widthIn(max = 720.dp).padding(horizontal = appLayout.gutter - 16.dp)
                    .clickable(indication = tvIndication ?: LocalIndication.current, interactionSource = null) { onSearch(query) },
            )
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

@Composable
private fun PosterRowPlaceholder(title: String, message: String) {
    Column(Modifier.fillMaxWidth().padding(horizontal = appLayout.gutter, vertical = 12.dp)) {
        Text(title, style = MaterialTheme.typography.titleLarge)
        Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
