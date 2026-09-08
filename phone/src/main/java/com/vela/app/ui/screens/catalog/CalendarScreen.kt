package com.vela.app.ui.screens.catalog

import android.app.Application
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.vela.data.model.BaseItemDto
import com.vela.data.model.CatalogTitle
import com.vela.data.repository.*
import com.vela.app.ui.screens.dashboard.search.FederatedSessionNavigator
import com.vela.shared.R
import kotlinx.coroutines.launch
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.time.LocalDate

@Composable
fun CalendarScreen(onLibrary: (BaseItemDto) -> Unit, onCatalog: (CatalogTitle) -> Unit, onConnections: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val uri = LocalUriHandler.current
    val repo = remember { SubscriptionRepository(context) }
    val federated = remember { FederatedMediaRepository(context) }
    val navigator = remember { FederatedSessionNavigator(context.applicationContext as Application) }
    val scope = rememberCoroutineScope()
    var libraryMatches by remember { mutableStateOf<Map<String, FederatedMediaItem>>(emptyMap()) }
    var feed by remember { mutableStateOf<CalendarFeed?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    var opening by remember { mutableStateOf(false) }
    var refresh by remember { mutableIntStateOf(0) }
    var range by remember { mutableIntStateOf(0) }
    var selected by remember { mutableStateOf<CalendarEntry?>(null) }
    var candidates by remember { mutableStateOf<List<CatalogTitle>>(emptyList()) }
    LaunchedEffect(refresh) {
        loading = true; error = null
        catalogResult { repo.calendar() }.fold(onSuccess = { feed = it }, onFailure = { error = it.message })
        loading = false
        libraryMatches = emptyMap()
        val titles = feed?.entries.orEmpty().mapNotNull { it.catalog }.distinctBy { it.key }
        val gate = Semaphore(2)
        coroutineScope {
            titles.map { title -> async { gate.withPermit {
                val result = catalogResult { federated.matchCatalog(title.mediaType, title.id) }
                result.getOrNull()?.items?.firstOrNull()?.let { match -> libraryMatches = libraryMatches + (title.key to match) }
            } } }.awaitAll()
        }
    }
    fun open(entry: CalendarEntry) {
        val title = entry.catalog
        if (title == null) { selected = entry; candidates = emptyList(); return }
        if (opening) return
        opening = true
        scope.launch {
            val result = catalogResult { federated.matchCatalog(title.mediaType, title.id) }
            val match = result.getOrNull()?.items?.firstOrNull()
            if (match != null) {
                navigator.activate(match.serverId).fold(onSuccess = { onLibrary(match.item) }, onFailure = { error = it.message })
            } else onCatalog(title)
            opening = false
        }
    }
    val today = LocalDate.now()
    val rows = feed?.entries.orEmpty().filter { when(range) { 0 -> it.date == today; 1 -> it.date > today; else -> it.date < today && it.date >= today.minusDays(7) } }
    LazyColumn(modifier.fillMaxSize().statusBarsPadding(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text(stringResource(R.string.catalog_calendar), style = MaterialTheme.typography.headlineLarge) }
        item { Row {
            TextButton(onClick = onConnections) { Text(stringResource(R.string.catalog_subscriptions)) }
            TextButton(onClick = { refresh++ }, enabled = !loading) { Text(stringResource(R.string.catalog_retry)) }
        } }
        item { LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { items(listOf(R.string.catalog_day, R.string.catalog_future, R.string.catalog_past).withIndex().toList()) { (index, label) -> FilterChip(range == index, { range = index }, label = { Text(stringResource(label)) }) } } }
        feed?.let { value -> item { Text(value.source, color = MaterialTheme.colorScheme.primary) }; if (value.source == "Bangumi") item { Text(stringResource(R.string.catalog_bangumi_schedule), style = MaterialTheme.typography.bodySmall) } }
        if (loading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        error?.let { item { Text(it, color = MaterialTheme.colorScheme.error) } }
        feed?.warning?.let { item { Text(it, color = MaterialTheme.colorScheme.error) } }
        if (!loading && error == null && rows.isEmpty()) item { Text(stringResource(if(range == 0) R.string.catalog_no_updates else R.string.catalog_empty)) }
        rows.groupBy { it.date }.forEach { (date, entries) ->
            item { Text(date.toString(), style = MaterialTheme.typography.titleMedium) }
            items(entries, key = { it.key }) { entry -> Card(onClick = { open(entry) }, enabled = !opening, modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    AsyncImage(entry.poster, entry.title, Modifier.width(68.dp).height(102.dp), contentScale = ContentScale.Crop)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(entry.title, style = MaterialTheme.typography.titleMedium)
                        entry.episode?.let { Text(stringResource(R.string.catalog_airing_episode, entry.season ?: 1, it)) }
                        entry.completed?.let { Text(stringResource(if (it) R.string.catalog_finale else R.string.catalog_airing)) }
                        libraryMatches[entry.catalog?.key]?.let { match ->
                            Text("✓ ${match.serverName}", color = MaterialTheme.colorScheme.primary)
                            val total = match.item.episodeCount
                            val unplayed = match.item.userData?.unplayedItemCount
                            if (total != null && unplayed != null && total > 0 && unplayed in 0..total) {
                                Text(stringResource(R.string.catalog_library_progress, total - unplayed, total))
                            }
                        }
                    }
                }
            } }
        }
    }
    selected?.let { entry -> AlertDialog(onDismissRequest = { selected = null }, title = { Text(entry.title) }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.catalog_unmapped))
            candidates.take(8).forEach { title -> TextButton(onClick = { selected = null; onCatalog(title) }) { Text("${title.displayTitle} · ${title.date.take(4)}") } }
        }
    }, confirmButton = { TextButton(onClick = { scope.launch {
        catalogResult { CatalogRepository().search(entry.originalTitle ?: entry.title) }.fold(onSuccess = { candidates = it }, onFailure = { error = it.message; selected = null })
    } }) { Text(stringResource(R.string.catalog_find_title)) } }, dismissButton = { TextButton(onClick = { entry.sourceUrl?.let(uri::openUri) }) { Text(stringResource(R.string.catalog_source_detail)) } }) }
}
