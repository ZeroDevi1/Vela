package com.vela.app.ui.screens.catalog

import android.app.Application
import android.text.format.DateUtils
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.AsyncImage
import com.vela.data.model.*
import com.vela.data.repository.*
import com.vela.app.ui.activity.PlayerActivity
import com.vela.app.ui.screens.dashboard.search.FederatedSessionNavigator
import com.vela.shared.R
import kotlinx.coroutines.launch
import java.time.LocalDate

@Composable
fun CalendarScreen(onLibrary: (BaseItemDto) -> Unit, onCatalog: (CatalogTitle) -> Unit, onConnections: () -> Unit, modifier: Modifier = Modifier) {
    val vm: SubscriptionViewModel = viewModel()
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val navigator = remember { FederatedSessionNavigator(context.applicationContext as Application) }
    val scope = rememberCoroutineScope()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var query by rememberSaveable { mutableStateOf("") }
    var type by rememberSaveable { mutableIntStateOf(0) }
    var status by rememberSaveable { mutableStateOf<String?>(null) }
    var availability by rememberSaveable { mutableIntStateOf(0) }
    var sort by rememberSaveable { mutableIntStateOf(0) }
    var range by rememberSaveable { mutableIntStateOf(0) }
    var selectedDay by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedId by rememberSaveable { mutableStateOf<Int?>(null) }
    var remove by remember { mutableStateOf<SubscriptionRecord?>(null) }
    var sources by remember { mutableStateOf<List<FederatedMediaItem>>(emptyList()) }
    var playSource by remember { mutableStateOf(false) }
    var search by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var actionError by remember { mutableStateOf<String?>(null) }
    val today = LocalDate.now()
    // Re-entering after details/settings refreshes service-owned state; playback emits its own signal.
    LaunchedEffect(Unit) { vm.refresh() }
    fun choose(rows: List<FederatedMediaItem>, play: Boolean) { sources = rows; playSource = play }
    val nextDates = state.records.associate { record -> record.id to state.feed?.entries.orEmpty()
        .filter { it.catalog?.key == record.catalog?.key && it.season == record.season && it.date >= today }.minOfOrNull { it.date } }
    val filtered = state.records.filter { record ->
        (status == null || record.state == status) && record.name.contains(query.trim(), true) && (type == 0 || record.type == if (type == 1) "movie" else "tv") &&
            when (availability) {
                1 -> state.libraries[record.id]?.let { if (record.type == "movie") it.resources.isNotEmpty() else it.episodes.isNotEmpty() } == true
                2 -> state.libraries[record.id]?.let { it.complete && (if (record.type == "movie") it.resources.isEmpty() else it.episodes.isEmpty()) } == true
                else -> true
            }
    }.let { rows -> if (sort == 1) rows.sortedWith(compareBy<SubscriptionRecord> { nextDates[it.id] == null }.thenBy { nextDates[it.id] })
        else rows.sortedByDescending { (if (sort == 2) it.lastUpdated else it.added).orEmpty() } }
    val pendingRecords = filtered.filter { record -> state.libraries[record.id]?.let { lib ->
        lib.pending.isNotEmpty() || (record.type == "movie" && lib.resources.any { it.item.userData?.played == false } && lib.resources.none { it.item.userData?.played == true })
    } == true }
    val rows = if (tab == 2) pendingRecords else filtered
    LazyColumn(modifier.fillMaxSize().statusBarsPadding(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text(stringResource(R.string.sub_heading), style = MaterialTheme.typography.headlineLarge)
            Row {
                TextButton(onClick = onConnections) { Text(stringResource(R.string.sub_connections)) }
                TextButton(onClick = vm::refresh, enabled = !state.loading && !busy) { Text(stringResource(R.string.feed_refresh)) }
                TextButton(onClick = { search = true }, enabled = state.connected && !busy) { Text(stringResource(R.string.sub_add)) }
            }
        }
        item { LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(listOf(R.string.sub_mine, R.string.sub_calendar, R.string.sub_unwatched).withIndex().toList()) { (i, label) ->
                FilterChip(tab == i, { tab = i }, label = { Text(stringResource(label)) })
            }
        } }
        if (state.loading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        state.error?.let { message -> item {
            Text(message, color = MaterialTheme.colorScheme.error)
            if (state.updated != null) Text(stringResource(R.string.sub_refresh_failed))
        } }
        actionError?.let { item { Text(it, color = MaterialTheme.colorScheme.error) } }
        state.updated?.let { item { Text(stringResource(R.string.feed_updated, DateUtils.getRelativeTimeSpanString(it).toString()), style = MaterialTheme.typography.labelSmall) } }
        if (!state.connected) {
            item { Text(stringResource(R.string.sub_connect_hint)) }
            item { BangumiSchedule(onCatalog) }
        } else {
            if (state.updated != null) item {
                ElevatedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(stringResource(R.string.sub_count, state.records.size), style = MaterialTheme.typography.titleMedium)
                        if (state.feed != null) Text(stringResource(R.string.sub_today_count, state.feed!!.entries.count { it.date == today }))
                        val libraries = state.records.filter { it.type == "tv" }.distinctBy { "${it.catalog?.key}:${it.season}" }.mapNotNull { state.libraries[it.id] }
                        val pending = libraries.sumOf { it.pending.size }
                        val complete = state.records.filter { it.type == "tv" }.all { state.libraries[it.id]?.progressKnown == true }
                        if (complete) Text(stringResource(R.string.sub_pending_count, pending))
                        else if (pending > 0) Text(stringResource(R.string.sub_pending_partial, pending))
                        else Text(stringResource(R.string.sub_pending_unknown))
                        if (state.records.any { state.libraries[it.id]?.progressKnown != true }) Text(stringResource(R.string.sub_partial), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            if (tab != 1) {
                item { OutlinedTextField(query, { query = it }, modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text(stringResource(R.string.sub_title_filter)) }) }
                item { LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(listOf(R.string.feed_any, R.string.feed_movies, R.string.feed_series).withIndex().toList()) { (i, label) -> FilterChip(type == i, { type = i }, label = { Text(stringResource(label)) }) }
                    items(listOf(R.string.feed_any, R.string.sub_available, R.string.sub_no_match).withIndex().toList()) { (i, label) -> if (i > 0) FilterChip(availability == i, { availability = if (availability == i) 0 else i }, label = { Text(stringResource(label)) }) }
                } }
                item { LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    item { FilterChip(status == null, { status = null }, label = { Text(stringResource(R.string.sub_status_filter)) }) }
                    items(state.records.mapNotNull { it.state }.distinct()) { value ->
                        FilterChip(status == value, { status = value }, label = { Text(subscriptionStateLabel(value)) })
                    }
                } }
                item { LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { item {
                    FilterChip(sort == 0, { sort = 0 }, label = { Text(stringResource(R.string.sub_newest)) })
                }; item {
                    FilterChip(sort == 1, { sort = 1 }, label = { Text(stringResource(R.string.sub_next_sort)) })
                }; item { FilterChip(sort == 2, { sort = 2 }, label = { Text(stringResource(R.string.sub_last_updated)) }) } } }
                if (!state.loading && state.error == null && rows.isEmpty()) item { Text(stringResource(when {
                    state.records.isEmpty() -> R.string.sub_no_subscriptions; tab == 2 -> R.string.sub_empty_pending; else -> R.string.sub_no_results
                })) }
                items(rows, key = { it.id }) { record ->
                    val library = state.libraries[record.id]
                    Card(onClick = { selectedId = record.id }, modifier = Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            AsyncImage(record.catalog?.posterUrl ?: record.poster, record.name, Modifier.width(72.dp).height(108.dp), contentScale = ContentScale.Crop)
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(record.name, style = MaterialTheme.typography.titleMedium)
                                record.season?.let { Text(stringResource(R.string.sub_season, it)) }
                                Text(stringResource(R.string.sub_state, subscriptionStateLabel(record.state)), style = MaterialTheme.typography.labelMedium)
                                nextDates[record.id]?.let { Text(stringResource(R.string.sub_next, it.toString())) }
                                    ?: Text(stringResource(if (state.calendarLoading || state.calendarError != null || state.feed?.warning != null) R.string.sub_schedule_unknown else R.string.sub_no_schedule))
                                SubscriptionProgress(record, library)
                            }
                        }
                    }
                }
            } else {
                item { LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(listOf(R.string.catalog_day, R.string.sub_week, R.string.sub_month, R.string.catalog_past).withIndex().toList()) { (i, label) ->
                        FilterChip(range == i && selectedDay == null, { range = i; selectedDay = null }, label = { Text(stringResource(label)) })
                    }
                } }
                item { LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items((0L..6L).map { today.plusDays(it) }) { date -> FilterChip(selectedDay == date.toString(), { selectedDay = date.toString() }, label = { Text("${date.monthValue}/${date.dayOfMonth} ${date.dayOfWeek.getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.getDefault())}") }) }
                } }
                if (state.calendarLoading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                state.calendarError?.let { item { Text(it, color = MaterialTheme.colorScheme.error) } }
                state.feed?.warning?.let { item { Text(stringResource(R.string.sub_calendar_partial), color = MaterialTheme.colorScheme.error); Text(it, style = MaterialTheme.typography.bodySmall) } }
                val entries = state.feed?.entries.orEmpty().filter { entry -> if (selectedDay != null) entry.date.toString() == selectedDay else when(range) {
                    0 -> entry.date == today; 1 -> entry.date > today && entry.date <= today.plusDays(7)
                    2 -> entry.date > today && entry.date <= today.plusDays(30); else -> entry.date < today && entry.date >= today.minusDays(7)
                } }
                if (!state.calendarLoading && state.calendarError == null && state.feed != null && entries.isEmpty()) item { Text(stringResource(R.string.catalog_no_updates)) }
                entries.groupBy { it.date }.forEach { (date, dayEntries) ->
                    item { Text(date.toString(), style = MaterialTheme.typography.titleLarge) }
                    dayEntries.groupBy { "${it.catalog?.key}:${it.season}" }.forEach { (identity, episodes) ->
                        item(key = "calendar:$date:$identity") {
                            val entry = episodes.first()
                            var expanded by rememberSaveable { mutableStateOf(false) }
                            val record = state.records.firstOrNull { it.catalog?.key == entry.catalog?.key && it.season == entry.season }
                            Card(onClick = { if (record != null) selectedId = record.id else entry.catalog?.let(onCatalog) }, modifier = Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text(entry.title, style = MaterialTheme.typography.titleMedium)
                                    val uniqueEpisodes = episodes.distinctBy { it.episode }
                                    if (uniqueEpisodes.size > 1) Text(stringResource(R.string.sub_group_count, uniqueEpisodes.size))
                                    (if (expanded) uniqueEpisodes else uniqueEpisodes.take(1)).forEach { episode ->
                                        Text(episode.episode?.let { stringResource(R.string.catalog_airing_episode, episode.season ?: 1, it) } ?: episode.date.toString())
                                    }
                                    if (uniqueEpisodes.size > 1) TextButton(onClick = { expanded = !expanded }) { Text(stringResource(if (expanded) R.string.catalog_collapse else R.string.catalog_expand)) }
                                    Text(stringResource(R.string.feed_schedule_hint), style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                }
            }
            if (state.error != null || state.calendarError != null) item { BangumiSchedule(onCatalog) }
        }
    }
    if (remove == null && sources.isEmpty()) state.records.firstOrNull { it.id == selectedId }?.let { record ->
        val library = state.libraries[record.id]
        AlertDialog(onDismissRequest = { selectedId = null }, title = { Text(record.name) }, text = {
            LazyColumn(Modifier.heightIn(max = 420.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                item {
                    record.season?.let { Text(stringResource(R.string.sub_season, it)) }
                    record.added?.let { Text(stringResource(R.string.sub_added, it)) }
                    record.totalEpisodes?.let { Text(stringResource(R.string.sub_episode_total, it)) }
                    if (record.episodeGroup != null) Text(stringResource(R.string.sub_episode_group_hint))
                    SubscriptionProgress(record, library)
                    library?.errors?.forEach { Text(it, color = MaterialTheme.colorScheme.error) }
                    if (record.catalog == null) Text(stringResource(R.string.sub_mapping_missing))
                }
                item { TextButton(onClick = { selectedId = null; record.catalog?.let(onCatalog) }, enabled = record.catalog != null) { Text(stringResource(R.string.sub_details)) } }
                if (!library?.resources.isNullOrEmpty()) item {
                    TextButton(onClick = { choose(library.resources, false) }) { Text(stringResource(R.string.catalog_resources)) }
                    val next = if (record.type == "movie") library.resources else {
                        val resumed = library.pending.values.flatten().filter { (it.item.userData?.playbackPositionTicks ?: 0L) > 0 }
                        if (resumed.isNotEmpty()) resumed else library.pending.entries.minByOrNull { it.key }?.value.orEmpty()
                    }
                    if (next.isNotEmpty()) TextButton(onClick = { choose(next.sortedByDescending { it.item.userData?.playbackPositionTicks ?: 0 }, true) }) { Text(stringResource(R.string.sub_play_next)) }
                }
                val scheduled = state.feed?.entries.orEmpty().filter { it.catalog?.key == record.catalog?.key && it.season == record.season }
                val episodeNumbers = (scheduled.mapNotNull { it.episode } + library?.episodeGroups.orEmpty().keys).distinct().sorted()
                items(episodeNumbers) { number ->
                    val options = library?.episodeGroups?.get(number).orEmpty()
                    val date = scheduled.firstOrNull { it.episode == number }
                    val label = when { options.any { it.item.userData?.played == true } -> R.string.sub_played
                        options.any { it.item.userData?.played == false } -> R.string.sub_ready
                        options.isNotEmpty() -> R.string.sub_unknown; else -> R.string.sub_scheduled }
                    Column {
                        Text(stringResource(R.string.sub_episode, number, stringResource(label)))
                        date?.let { Text("${it.date} · ${it.episodeName.orEmpty()}", style = MaterialTheme.typography.bodySmall) }
                        if (options.isNotEmpty()) TextButton(onClick = { choose(options, true) }) { Text(stringResource(R.string.catalog_play)) }
                    }
                }
            }
        }, confirmButton = { TextButton(onClick = { remove = record }, enabled = vm.repository.syncRemoval && !busy && state.error == null) { Text(stringResource(R.string.catalog_remove_subscription)) } },
            dismissButton = { TextButton(onClick = { selectedId = null }) { Text(stringResource(R.string.catalog_back)) } })
    }
    remove?.let { record -> AlertDialog(onDismissRequest = { if (!busy) remove = null }, title = { Text(stringResource(R.string.sub_remove_title)) }, text = {
        Column { Text(record.name); record.season?.let { Text(stringResource(R.string.sub_season, it)) }; Text(stringResource(R.string.sub_remove_hint)); actionError?.let { Text(it, color = MaterialTheme.colorScheme.error) } }
    }, confirmButton = { TextButton(onClick = {
        busy = true; actionError = null
        scope.launch { catalogResult { vm.repository.removeSubscription(record) }.fold(onSuccess = { remove = null; selectedId = null; vm.refresh() }, onFailure = { actionError = it.message }); busy = false }
    }, enabled = !busy) { Text(stringResource(R.string.catalog_remove_subscription)) } }, dismissButton = { TextButton(onClick = { remove = null }, enabled = !busy) { Text(stringResource(R.string.catalog_back)) } }) }
    if (sources.isNotEmpty()) AlertDialog(onDismissRequest = { if (!busy) sources = emptyList() }, title = { Text(stringResource(R.string.sub_source)) }, text = {
        LazyColumn(Modifier.heightIn(max = 400.dp)) {
            actionError?.let { message -> item { Text(message, color = MaterialTheme.colorScheme.error) } }
            items(sources.distinctBy { "${it.serverId}:${it.item.id}" }, key = { "${it.serverId}:${it.item.id}" }) { source ->
            TextButton(onClick = {
                busy = true; actionError = null
                scope.launch {
                    catalogResult { navigator.activate(source.serverId).getOrThrow()
                        if (playSource) PlayerActivity.start(context, requireNotNull(source.item.id)) else onLibrary(source.item)
                    }.fold(onSuccess = { sources = emptyList(); selectedId = null }, onFailure = { actionError = it.message }); busy = false
                }
            }, enabled = !busy) { Text("${source.serverName} · ${source.item.name.orEmpty()}") }
        } }
    }, confirmButton = { TextButton(onClick = { sources = emptyList() }, enabled = !busy) { Text(stringResource(R.string.catalog_back)) } })
    if (search) SubscriptionSearchDialog({ search = false }) { search = false; onCatalog(it) }
}

@Composable
private fun SubscriptionProgress(record: SubscriptionRecord, library: SubscriptionLibrary?) {
    when {
        library == null -> Text(stringResource(R.string.sub_lookup), style = MaterialTheme.typography.bodySmall)
        record.catalog == null -> Text(stringResource(R.string.sub_mapping_missing), style = MaterialTheme.typography.bodySmall)
        !library.complete -> Text(stringResource(R.string.sub_partial), style = MaterialTheme.typography.bodySmall)
        library.resources.isEmpty() -> Text(stringResource(R.string.sub_no_library), style = MaterialTheme.typography.bodySmall)
        record.type == "tv" && library.episodes.isEmpty() -> Text(stringResource(R.string.sub_season_missing), style = MaterialTheme.typography.bodySmall)
        record.type == "movie" -> Text(stringResource(R.string.sub_movie_ready), style = MaterialTheme.typography.bodySmall)
    }
    if (record.type == "tv" && library != null && library.episodeGroups.isNotEmpty()) {
        Text(stringResource(if (library.progressKnown) R.string.sub_progress else R.string.sub_progress_partial, library.episodeGroups.size, library.watched, library.pending.size), style = MaterialTheme.typography.labelMedium)
        if (!library.progressKnown) Text(stringResource(R.string.sub_partial), style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun SubscriptionSearchDialog(dismiss: () -> Unit, open: (CatalogTitle) -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    var results by remember { mutableStateOf<List<CatalogTitle>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    AlertDialog(onDismissRequest = dismiss, title = { Text(stringResource(R.string.sub_add)) }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.sub_search_hint))
            OutlinedTextField(query, { query = it }, singleLine = true, label = { Text(stringResource(R.string.sub_search)) }, enabled = !loading)
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            LazyColumn(Modifier.heightIn(max = 280.dp)) { items(results, key = { it.key }) { title ->
                TextButton(onClick = { open(title) }) { Text("${title.displayTitle} · ${title.date.take(4)} · ${stringResource(if (title.mediaType == "movie") R.string.feed_movies else R.string.feed_series)}") }
            } }
        }
    }, confirmButton = { TextButton(onClick = {
        loading = true; error = null
        scope.launch { catalogResult { CatalogRepository().search(query.trim()) }.fold(onSuccess = { results = it }, onFailure = { error = it.message }); loading = false }
    }, enabled = !loading && query.isNotBlank()) { Text(stringResource(R.string.sub_search)) } }, dismissButton = { TextButton(onClick = dismiss) { Text(stringResource(R.string.catalog_back)) } })
}

@Composable
internal fun UnmappedCalendarDialog(entry: CalendarEntry, dismiss: () -> Unit, open: (CatalogTitle) -> Unit) {
    val uri = LocalUriHandler.current
    val scope = rememberCoroutineScope()
    var candidates by remember { mutableStateOf<List<CatalogTitle>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    AlertDialog(onDismissRequest = dismiss, title = { Text(entry.title) }, text = {
        Column {
            Text(stringResource(R.string.catalog_unmapped)); error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            LazyColumn(Modifier.heightIn(max = 300.dp)) { items(candidates.take(8), key = { it.key }) { title -> TextButton(onClick = { dismiss(); open(title) }) { Text("${title.displayTitle} · ${title.date.take(4)}") } } }
        }
    }, confirmButton = { TextButton(onClick = { loading = true; scope.launch {
        catalogResult { CatalogRepository().search(entry.originalTitle ?: entry.title) }.fold(onSuccess = { candidates = it; error = null }, onFailure = { error = it.message }); loading = false
    } }, enabled = !loading) { Text(stringResource(R.string.catalog_find_title)) } }, dismissButton = {
        TextButton(onClick = { entry.sourceUrl?.let(uri::openUri) }) { Text(stringResource(R.string.catalog_source_detail)) }
    })
}

@Composable
private fun subscriptionStateLabel(state: String?): String = when (state) {
    "R" -> stringResource(R.string.sub_state_running)
    "P" -> stringResource(R.string.sub_state_pending)
    "S" -> stringResource(R.string.sub_state_paused)
    else -> state ?: stringResource(R.string.sub_unknown)
}
