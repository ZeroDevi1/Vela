package com.vela.app.ui.screens.catalog

import android.text.format.DateUtils
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.AsyncImage
import com.vela.data.model.*
import com.vela.data.repository.*
import com.vela.shared.R
import java.time.Instant

internal fun CatalogShelf.label(): Int = when (this) {
    CatalogShelf.DAY -> R.string.feed_day; CatalogShelf.WEEK -> R.string.feed_week
    CatalogShelf.POPULAR_MOVIES -> R.string.feed_popular_movies; CatalogShelf.TOP_MOVIES -> R.string.feed_top_movies
    CatalogShelf.NOW_PLAYING -> R.string.feed_now_playing; CatalogShelf.UPCOMING -> R.string.feed_upcoming
    CatalogShelf.RECENT_TOP -> R.string.feed_recent_top; CatalogShelf.CLASSICS -> R.string.feed_classics
    CatalogShelf.POPULAR_TV -> R.string.feed_popular_tv; CatalogShelf.TOP_TV -> R.string.feed_top_tv
    CatalogShelf.AIRING_TODAY -> R.string.feed_airing_today; CatalogShelf.ON_AIR -> R.string.feed_on_air
    CatalogShelf.NEW_TV -> R.string.feed_new_tv; CatalogShelf.ANIMATION_MOVIES -> R.string.feed_animation_movies
    CatalogShelf.ANIMATION_TV -> R.string.feed_animation_tv; CatalogShelf.ANIMATION_TOP -> R.string.feed_animation_top
}

@Composable
fun CatalogScreen(onLibrary: (BaseItemDto) -> Unit, onCatalog: (CatalogTitle) -> Unit, modifier: Modifier = Modifier) {
    val vm: CatalogViewModel = viewModel()
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("discovery_layout", 0) }
    var category by rememberSaveable { mutableIntStateOf(0) }
    var browse by rememberSaveable { mutableStateOf<String?>(null) }
    var browseGenre by rememberSaveable { mutableStateOf<Int?>(null) }
    var customize by remember { mutableStateOf(false) }
    var hidden by remember { mutableStateOf(prefs.getStringSet("hidden", emptySet()).orEmpty().toSet()) }
    var order by remember { mutableStateOf((prefs.getString("order", "").orEmpty().split(',').mapNotNull { name -> CatalogShelf.entries.find { it.name == name } } + CatalogShelf.entries).distinct()) }
    val holder = rememberSaveableStateHolder()
    val current = CatalogCategory.entries[category]
    val shelves = order.filter { current == CatalogCategory.RECOMMENDED || it.category == current }
    browse?.let { name ->
        holder.SaveableStateProvider("browse:$name:$browseGenre") { CatalogBrowseScreen(CatalogShelf.valueOf(name), { browse = null }, onCatalog, modifier, browseGenre) }
        return
    }
    holder.SaveableStateProvider("category:$category") {
        LazyColumn(modifier.fillMaxSize().statusBarsPadding(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(stringResource(R.string.catalog_discover), style = MaterialTheme.typography.headlineLarge)
                TextButton(onClick = { customize = true }) { Text(stringResource(R.string.feed_customize)) }
            } }
            item { LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(listOf(R.string.feed_recommended, R.string.feed_movies, R.string.feed_series, R.string.feed_animation).withIndex().toList()) { (i, label) ->
                    FilterChip(category == i, { category = i }, label = { Text(stringResource(label)) })
                }
            } }
            if (current == CatalogCategory.RECOMMENDED) {
                item { TextButton(onClick = vm::refreshLibrary) { Text(stringResource(R.string.feed_refresh)) } }
                items(state.errors) { Text(it, color = MaterialTheme.colorScheme.error) }
                if (state.resume.isNotEmpty()) {
                    item { Text(stringResource(R.string.catalog_continue), style = MaterialTheme.typography.titleLarge) }
                    item { LibraryRow(state.resume, true, !state.opening) { vm.openLibrary(it, onLibrary) } }
                }
                if (state.recent.isNotEmpty()) {
                    item { Text(stringResource(R.string.feed_recent), style = MaterialTheme.typography.titleLarge) }
                    item { LibraryRow(state.recent, false, !state.opening) { vm.openLibrary(it, onLibrary) } }
                }
            }
            if (current == CatalogCategory.MOVIES || current == CatalogCategory.SERIES) item {
                GenreExplore(if (current == CatalogCategory.MOVIES) "movie" else "tv") { genre ->
                    browseGenre = genre
                    browse = (if (current == CatalogCategory.MOVIES) CatalogShelf.POPULAR_MOVIES else CatalogShelf.POPULAR_TV).name
                }
            }
            items(shelves.filter { it.name !in hidden }, key = { it.name }) { shelf ->
                LaunchedEffect(shelf) { vm.load(shelf) }
                val section = state.shelves[shelf] ?: CatalogShelfState(loading = true)
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(stringResource(shelf.label()), modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                        TextButton(onClick = { browseGenre = null; browse = shelf.name }) { Text(stringResource(R.string.feed_all)) }
                    }
                    if (section.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                    section.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    val earlier = shelves.takeWhile { it != shelf }.filter { it.name !in hidden }
                        .flatMap { state.shelves[it]?.titles.orEmpty().take(4) }.map { it.key }.toSet()
                    val posters = if (current == CatalogCategory.RECOMMENDED) section.titles.filter { it.key !in earlier } else section.titles
                    if (posters.isNotEmpty()) CatalogPosters(posters, onCatalog)
                    else if (!section.loading && section.error == null) Text(stringResource(R.string.catalog_empty))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        section.updated?.let { Text(stringResource(R.string.feed_updated, DateUtils.getRelativeTimeSpanString(it).toString()), style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f)) }
                        TextButton(onClick = { vm.load(shelf, true) }, enabled = !section.loading) { Text(stringResource(R.string.feed_refresh)) }
                    }
                }
            }
            if (current == CatalogCategory.ANIMATION) item { BangumiSchedule(onCatalog) }
            item { Text(stringResource(R.string.catalog_tmdb_attribution), style = MaterialTheme.typography.labelSmall) }
        }
    }
    if (customize) AlertDialog(onDismissRequest = { customize = false }, title = { Text(stringResource(R.string.feed_customize)) }, text = {
        LazyColumn(Modifier.heightIn(max = 440.dp)) { items(order, key = { it.name }) { shelf ->
            Column {
                Row { Checkbox(shelf.name !in hidden, { show ->
                    hidden = if (show) hidden - shelf.name else hidden + shelf.name
                    prefs.edit().putStringSet("hidden", hidden).apply()
                }); Text(stringResource(shelf.label()), Modifier.padding(top = 12.dp)) }
                Row {
                    fun move(delta: Int) {
                        val i = order.indexOf(shelf); val target = i + delta
                        if (target in order.indices) {
                            order = order.toMutableList().apply { removeAt(i); add(target, shelf) }
                            prefs.edit().putString("order", order.joinToString(",") { it.name }).apply()
                        }
                    }
                    TextButton(onClick = { move(-1) }, enabled = order.first() != shelf) { Text(stringResource(R.string.feed_up)) }
                    TextButton(onClick = { move(1) }, enabled = order.last() != shelf) { Text(stringResource(R.string.feed_down)) }
                }
            }
        } }
    }, confirmButton = { TextButton(onClick = { customize = false }) { Text(stringResource(R.string.feed_apply)) } }, dismissButton = {
        TextButton(onClick = { hidden = emptySet(); order = CatalogShelf.entries.toList(); prefs.edit().clear().apply() }) { Text(stringResource(R.string.feed_reset)) }
    })
}

@Composable
private fun LibraryRow(rows: List<FederatedMediaItem>, resume: Boolean, enabled: Boolean, open: (FederatedMediaItem) -> Unit) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        items(rows, key = { "${it.serverId}:${it.item.id}" }) { row ->
            Card(onClick = { open(row) }, enabled = enabled, modifier = Modifier.width(260.dp)) {
                AsyncImage(row.imageUrl, row.item.name, Modifier.fillMaxWidth().height(146.dp), contentScale = ContentScale.Crop)
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(row.item.seriesName ?: row.item.name.orEmpty(), maxLines = 2)
                    if (!row.item.seriesName.isNullOrBlank()) Text(row.item.name.orEmpty(), maxLines = 1, style = MaterialTheme.typography.labelMedium)
                    Text(row.serverName, style = MaterialTheme.typography.labelSmall)
                    if (resume) {
                        val ticks = row.item.userData?.playbackPositionTicks ?: 0L
                        LinearProgressIndicator(progress = { (ticks.toFloat() / (row.item.runTimeTicks ?: 1L).coerceAtLeast(1)).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                        val seconds = ticks / 10_000_000
                        Text(stringResource(R.string.catalog_position, "%02d:%02d".format(seconds / 60, seconds % 60)), style = MaterialTheme.typography.labelMedium)
                    }
                    (if (resume) row.item.userData?.lastPlayedDate else row.item.dateCreated)?.let { date ->
                        runCatching { Instant.parse(date).toEpochMilli() }.getOrNull()?.let {
                            Text(DateUtils.getRelativeTimeSpanString(it).toString(), style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun GenreExplore(type: String, open: (Int) -> Unit) {
    var genres by remember(type) { mutableStateOf<List<CatalogGenre>>(emptyList()) }
    var error by remember(type) { mutableStateOf<String?>(null) }
    var revision by remember { mutableIntStateOf(0) }
    LaunchedEffect(type, revision) {
        catalogResult { CatalogRepository().genres(type) }.fold(onSuccess = { genres = it; error = null }, onFailure = { error = it.message })
    }
    Column {
        Text(stringResource(R.string.feed_explore), style = MaterialTheme.typography.titleMedium)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { items(genres, key = { it.id }) { genre ->
            SuggestionChip(onClick = { open(genre.id) }, label = { Text(genre.name) })
        } }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error); TextButton(onClick = { revision++ }) { Text(stringResource(R.string.catalog_retry)) } }
    }
}
