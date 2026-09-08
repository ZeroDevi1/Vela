package com.vela.app.ui.screens.dashboard.search

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.AsyncImage
import com.vela.data.model.BaseItemDto
import com.vela.data.model.CatalogTitle
import com.vela.data.model.SearchMediaType
import com.vela.shared.R
import com.vela.data.repository.*
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FederatedSearchScreen(
    onNavigateToDetail: (BaseItemDto) -> Unit,
    onBack: () -> Unit = {},
    modifier: Modifier = Modifier,
    onCatalog: (CatalogTitle) -> Unit
) {
    val vm: FederatedSearchViewModel = viewModel()
    val state by vm.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val subscriptions = remember { SubscriptionRepository(context) }
    val uri = androidx.compose.ui.platform.LocalUriHandler.current
    var external by remember { mutableStateOf<DoubanSearchTitle?>(null) }
    var resolving by remember { mutableStateOf(false) }
    var resolutionError by remember { mutableStateOf<String?>(null) }
    var candidates by remember { mutableStateOf<List<CatalogTitle>>(emptyList()) }
    var showSources by remember { mutableStateOf(false) }
    val sources = state.servers.map { it.id to it.name } + listOf("tmdb" to "TMDB", "douban" to stringResource(R.string.catalog_douban))
    BackHandler(onBack = onBack)
    Column(modifier.fillMaxSize().statusBarsPadding().padding(horizontal = 16.dp)) {
        Row(Modifier.fillMaxWidth()) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.catalog_back)) }
            OutlinedTextField(state.query, vm::updateQuery, Modifier.weight(1f), singleLine = true, label = { Text(stringResource(R.string.catalog_search)) })
        }
        TextButton(onClick = { showSources = true }) { Text(stringResource(R.string.catalog_sources, state.selectedSources.size)) }
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            item { FilterChip(state.selectedServerId == null, { vm.selectServer(null) }, label = { Text(stringResource(R.string.catalog_filter_all)) }) }
            items(sources.filter { it.first in state.selectedSources }, key = { it.first }) { (id, label) ->
                FilterChip(state.selectedServerId == id, { vm.selectServer(id) }, label = { Text(label) })
            }
        }
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(SearchMediaType.entries) { type -> FilterChip(type in state.selectedTypes, { vm.toggleType(type) }, label = { Text(stringResource(when(type) { SearchMediaType.MOVIE -> R.string.catalog_movies; SearchMediaType.SERIES -> R.string.catalog_series; SearchMediaType.EPISODE -> R.string.catalog_episodes })) }) }
        }
        if (state.isSearching) LinearProgressIndicator(Modifier.fillMaxWidth())
        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(vertical = 12.dp)) {
            state.actionError?.let { item { Text(it, color = MaterialTheme.colorScheme.error) } }
            items(state.failures) { failure -> Row { Text("${failure.serverName}: ${failure.message}", Modifier.weight(1f), color = MaterialTheme.colorScheme.error); TextButton(onClick = vm::submitSearch) { Text(stringResource(R.string.catalog_retry)) } } }
            if (state.selectedSources.isEmpty()) item { Text(stringResource(R.string.catalog_select_source)) }
            items(state.items.filter { state.selectedServerId == null || state.selectedServerId == it.serverId }, key = { "${it.serverId}:${it.item.id}" }) { result ->
                SearchResultRow(result.imageUrl, result.item.name.orEmpty(), result.serverName, result.item.overview.orEmpty(), state.openingServerId == null) { vm.openResult(result, onNavigateToDetail) }
            }
            items(state.catalogItems.filter { state.selectedServerId == null || state.selectedServerId == it.first }, key = { "${it.first}:${it.second.key}" }) { (source, title) ->
                SearchResultRow(title.posterUrl, title.displayTitle, sources.first { it.first == source }.second + " · " + title.date.take(4), title.overview, true) { onCatalog(title) }
            }
            if (state.selectedServerId == null || state.selectedServerId == "douban") items(state.doubanItems, key = { "douban:${it.id}" }) { title ->
                SearchResultRow(title.poster, title.title, stringResource(R.string.catalog_douban), title.overview, !resolving) {
                    resolving = true; resolutionError = null; candidates = emptyList()
                    scope.launch {
                        catalogResult { subscriptions.resolveDouban(title) }.fold(onSuccess = { mapped ->
                            if (mapped != null) onCatalog(mapped) else external = title
                        }, onFailure = { resolutionError = it.message; external = title })
                        resolving = false
                    }
                }
            }
            if (resolving) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            if (!state.isSearching && state.query.isNotBlank() && state.items.isEmpty() && state.catalogItems.isEmpty() && state.doubanItems.isEmpty() && state.failures.isEmpty() && state.selectedSources.isNotEmpty()) item { Text(stringResource(R.string.catalog_empty)) }
        }
    }
    external?.let { title -> AlertDialog(onDismissRequest = { external = null }, title = { Text(title.title) }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.catalog_unmapped))
            resolutionError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            candidates.take(6).forEach { candidate -> TextButton(onClick = { external = null; onCatalog(candidate.copy(doubanId = title.id, doubanRating = title.rating)) }) {
                Text("${candidate.displayTitle} · ${candidate.date.take(4)}")
            } }
        }
    }, confirmButton = { TextButton(onClick = { scope.launch {
        catalogResult { CatalogRepository().search(title.title) }.fold(onSuccess = { candidates = it }, onFailure = { resolutionError = it.message })
    } }) { Text(stringResource(R.string.catalog_find_title)) } }, dismissButton = { TextButton(onClick = { uri.openUri("https://movie.douban.com/subject/${title.id}/") }) { Text(stringResource(R.string.catalog_source_detail)) } }) }
    if (showSources) ModalBottomSheet(onDismissRequest = { showSources = false }) {
        Column(Modifier.padding(20.dp)) {
            Text(stringResource(R.string.catalog_source_title), style = MaterialTheme.typography.titleLarge)
            Row {
                TextButton(onClick = { vm.selectSources(sources.map { it.first }.toSet()) }) { Text(stringResource(R.string.catalog_all)) }
                TextButton(onClick = { vm.selectSources(emptySet()) }) { Text(stringResource(R.string.catalog_none)) }
            }
            LazyColumn(Modifier.heightIn(max = 380.dp)) { items(sources, key = { it.first }) { (id, label) ->
                Row { Checkbox(id in state.selectedSources, { checked -> vm.selectSources(if (checked) state.selectedSources + id else state.selectedSources - id) }); Text(label, Modifier.padding(top = 14.dp)) }
            } }
            Text(stringResource(R.string.catalog_douban_connection), style = MaterialTheme.typography.bodySmall)
            Button(onClick = { showSources = false }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.catalog_done)) }
        }
    }
}

@Composable
private fun SearchResultRow(poster: String?, title: String, source: String, overview: String, enabled: Boolean, onClick: () -> Unit) {
    Card(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            AsyncImage(poster, title, Modifier.width(72.dp).height(108.dp), contentScale = ContentScale.Crop)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 2)
                Text(source, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                Text(overview, maxLines = 2, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
