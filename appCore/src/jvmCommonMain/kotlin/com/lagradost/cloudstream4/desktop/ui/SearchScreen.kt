package com.lagradost.cloudstream4.desktop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream4.desktop.data.SettingsStore
import com.lagradost.cloudstream4.desktop.plugins.ExtensionManager
import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toPersistentList
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.launch

data class ProviderResults(
    val provider: String,
    val items: PersistentList<SearchResponse> = persistentListOf(),
    val loading: Boolean = true,
    val error: String? = null,
)

data class SearchState(
    val query: String = "",
    val providers: PersistentList<String> = persistentListOf(),
    val results: PersistentList<ProviderResults> = persistentListOf(),
    val searchedFor: String? = null,
)

class SearchModel : ScreenModel<SearchState>(SearchState()) {
    private var searchJob: Job? = null
    private var debounceJob: Job? = null

    init {
        scope.launch {
            ExtensionManager.state.distinctUntilChangedBy { it.providersVersion }.collect {
                update { copy(providers = ExtensionManager.providers().map { it.name }.toPersistentList()) }
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

    fun search() {
        debounceJob?.cancel()
        val query = current.query.trim()
        if (query.isEmpty() || query == current.searchedFor && current.results.none { it.error != null }) return
        searchJob?.cancel()
        val excluded = SettingsStore.state.value.searchExcluded
        val apis = ExtensionManager.providers().filter { it.name !in excluded }
        update { copy(searchedFor = query, results = apis.map { ProviderResults(it.name) }.toPersistentList()) }
        searchJob = scope.launch {
            apis.forEach { api ->
                launch {
                    providerCall { api.search(query, 1)?.items ?: emptyList() }
                        .onSuccess { items -> setResult(api.name) { copy(items = items.toPersistentList(), loading = false) } }
                        .onFailure { e -> setResult(api.name) { copy(loading = false, error = e.userMessage()) } }
                }
            }
        }
    }

    fun toggleProvider(name: String) {
        SettingsStore.update {
            it.copy(searchExcluded = if (name in it.searchExcluded) it.searchExcluded - name else it.searchExcluded + name)
        }
        update { copy(searchedFor = null) }
        search()
    }

    fun clear() {
        searchJob?.cancel()
        update { copy(query = "", results = persistentListOf(), searchedFor = null) }
    }

    private fun setResult(provider: String, block: ProviderResults.() -> ProviderResults) = update {
        copy(results = results.map { if (it.provider == provider) it.block() else it }.toPersistentList())
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SearchScreen(model: SearchModel) {
    val nav = LocalNavigator.current
    val state by model.state.collectAsState()
    val settings by SettingsStore.state.collectAsState()
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    Column(Modifier.fillMaxSize()) {
        LargeTitle("Search")
        // A filled, rounded field like Apple's search fields, rather than an outlined form input
        // On TV the screen opens with the box focused and no keyboard; OK opens it
        TvTextFieldBox(Modifier.fillMaxWidth().padding(horizontal = appLayout.gutter), focusRequester = focus) { fieldModifier ->
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
                shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.1f),
                    unfocusedContainerColor = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.07f),
                    focusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                    unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                ),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { model.search() }),
                // This field has no outline, so on TV it gets a ring when focused
                modifier = fieldModifier,
            )
        }
        if (state.providers.size > 1) {
            FlowRow(
                Modifier.padding(horizontal = appLayout.gutter, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                state.providers.forEach { name ->
                    FilterChip(
                        selected = name !in settings.searchExcluded,
                        onClick = { model.toggleProvider(name) },
                        label = { Text(name) },
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

            state.results.isEmpty() -> MessageBox("Search for movies, shows and anime", "Results from every installed provider appear here.")

            else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
                items(state.results, key = { it.provider }) { result ->
                    when {
                        result.loading -> PosterRowPlaceholder(result.provider, "Searching…")
                        result.error != null -> PosterRowPlaceholder(result.provider, result.error)
                        result.items.isEmpty() -> PosterRowPlaceholder(result.provider, "No results")
                        else -> PosterRow(result.provider, result.items.map { it.toPoster() }) {
                            nav.open(Route.Details(it.apiName, it.url, it.name))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PosterRowPlaceholder(title: String, message: String) {
    Column(Modifier.fillMaxWidth().padding(horizontal = appLayout.gutter, vertical = 12.dp)) {
        Text(title, style = MaterialTheme.typography.titleLarge)
        Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
