package com.vela.app.ui.screens.catalog

import android.app.Application
import android.text.format.DateUtils
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.AsyncImage
import com.vela.data.model.*
import com.vela.data.repository.*
import com.vela.app.ui.screens.dashboard.search.FederatedSessionNavigator
import com.vela.shared.R
import kotlinx.coroutines.launch
import java.time.Instant

@Composable
fun CatalogScreen(onLibrary: (BaseItemDto) -> Unit, onCatalog: (CatalogTitle) -> Unit, modifier: Modifier = Modifier) {
    val vm: CatalogViewModel = viewModel()
    val state by vm.state.collectAsStateWithLifecycle()
    LazyColumn(modifier.fillMaxSize().statusBarsPadding(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(stringResource(R.string.catalog_discover), style = MaterialTheme.typography.headlineLarge)
            TextButton(onClick = vm::refresh, enabled = !state.loading) { Text(stringResource(R.string.catalog_retry)) }
        } }
        if (state.loading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        items(state.errors) { Text(it, color = MaterialTheme.colorScheme.error) }
        if (state.resume.isNotEmpty()) {
            item { Text(stringResource(R.string.catalog_continue), style = MaterialTheme.typography.titleLarge) }
            item { LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                items(state.resume, key = { "${it.serverId}:${it.item.id}" }) { result ->
                    Card(onClick = { vm.openLibrary(result, onLibrary) }, enabled = !state.opening, modifier = Modifier.width(272.dp)) {
                        AsyncImage(result.imageUrl, result.item.name, Modifier.fillMaxWidth().height(150.dp), contentScale = ContentScale.Crop)
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(result.item.seriesName ?: result.item.name.orEmpty(), maxLines = 1)
                            Text(result.serverName, style = MaterialTheme.typography.labelSmall)
                            val ticks = result.item.userData?.playbackPositionTicks ?: 0L
                            val seconds = ticks / 10_000_000
                            LinearProgressIndicator(progress = { (ticks.toFloat() / (result.item.runTimeTicks ?: 1L).coerceAtLeast(1)).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                            Text(stringResource(R.string.catalog_position, "%02d:%02d".format(seconds / 60, seconds % 60)), style = MaterialTheme.typography.labelMedium)
                            result.item.userData?.lastPlayedDate?.let { date ->
                                val millis = runCatching { Instant.parse(date).toEpochMilli() }.getOrNull()
                                if (millis != null) Text(DateUtils.getRelativeTimeSpanString(millis).toString(), style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
            } }
        }
        item { Text(stringResource(R.string.catalog_today), style = MaterialTheme.typography.titleLarge) }
        item { if (!state.loading && state.day.isEmpty()) Text(stringResource(R.string.catalog_empty)) else CatalogPosters(state.day, onCatalog) }
        item { Text(stringResource(R.string.catalog_week), style = MaterialTheme.typography.titleLarge) }
        item { if (!state.loading && state.week.isEmpty()) Text(stringResource(R.string.catalog_empty)) else CatalogPosters(state.week, onCatalog) }
        item { Text(stringResource(R.string.catalog_tmdb_attribution), style = MaterialTheme.typography.labelSmall) }
    }
}

@Composable
fun CatalogPosters(titles: List<CatalogTitle>, onClick: (CatalogTitle) -> Unit) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        items(titles, key = { it.key }) { title ->
            Card(onClick = { onClick(title) }, modifier = Modifier.width(140.dp)) {
                AsyncImage(title.posterUrl, title.displayTitle, Modifier.fillMaxWidth().height(210.dp), contentScale = ContentScale.Crop)
                Column(Modifier.padding(10.dp)) {
                    Text(title.displayTitle, maxLines = 2, style = MaterialTheme.typography.titleSmall)
                    Text(title.date.take(4), style = MaterialTheme.typography.labelMedium)
                    if (title.genres.isNotEmpty()) Text(title.genres.joinToString(" · ") { it.name }, maxLines = 1, style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CatalogDetailScreen(initial: CatalogTitle, onBack: () -> Unit, onCatalog: (CatalogTitle) -> Unit, onLibrary: (BaseItemDto) -> Unit, onPlay: (BaseItemDto) -> Unit) {
    val context = LocalContext.current
    val repo = remember { CatalogRepository() }
    val federated = remember { FederatedMediaRepository(context) }
    val navigator = remember { FederatedSessionNavigator(context.applicationContext as Application) }
    val media = remember { MediaRepositoryProvider.getInstance(context) }
    val subscriptions = remember { SubscriptionRepository(context) }
    var subscribedSeasons by remember(initial.key) { mutableStateOf<Set<Int>>(emptySet()) }
    var subscriptionReady by remember(initial.key) { mutableStateOf(false) }
    var showSubscribe by remember { mutableStateOf(false) }
    var selectedSeason by remember(initial.key) { mutableIntStateOf(1) }
    var title by remember(initial.key) { mutableStateOf(initial) }
    var matches by remember(initial.key) { mutableStateOf<List<FederatedMediaItem>>(emptyList()) }
    var collection by remember(initial.key) { mutableStateOf<List<CatalogTitle>>(emptyList()) }
    var errors by remember(initial.key) { mutableStateOf<List<String>>(emptyList()) }
    var traktRating by remember(initial.key) { mutableStateOf<Double?>(null) }
    var expanded by rememberSaveable(initial.key) { mutableStateOf(false) }
    var loading by remember(initial.key) { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    var revision by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    BackHandler(onBack = onBack)
    LaunchedEffect(initial.key, revision) {
        loading = true; errors = emptyList()
        val detail = catalogResult { repo.detail(initial) }
        title = detail.getOrDefault(initial).copy(doubanId = initial.doubanId, doubanRating = initial.doubanRating)
        errors = listOfNotNull(detail.exceptionOrNull()?.message)
        val result = catalogResult { federated.matchCatalog(title.mediaType, title.id, title.externalIds?.imdbId) }
        matches = result.getOrNull()?.items.orEmpty()
        errors = errors + result.getOrNull()?.failures.orEmpty().map { "${it.serverName}: ${it.message}" } + listOfNotNull(result.exceptionOrNull()?.message)
        loading = false
        title.collection?.let { group ->
            val parts = catalogResult { repo.collection(group.id) }
            collection = parts.getOrDefault(emptyList())
            errors = errors + listOfNotNull(parts.exceptionOrNull()?.message)
        }
    }
    LaunchedEffect(initial.key, revision) {
        if (subscriptions.enabled && subscriptions.loggedIn) {
            catalogResult { subscriptions.subscribedSeasons(initial) }.fold(onSuccess = { subscribedSeasons = it; subscriptionReady = true }, onFailure = { errors = errors + (it.message ?: "MoviePilot unavailable"); subscriptionReady = false })
        }
    }
    LaunchedEffect(initial.key) {
        traktRating = catalogResult { TraktRepository.getInstance(context).rating(initial) }.getOrNull()
    }
    fun open(result: FederatedMediaItem, play: Boolean) {
        if (busy) return
        busy = true
        scope.launch {
            val activated = catalogResult {
                navigator.activate(result.serverId).getOrThrow()
                if (play && result.item.type == "Series") {
                    val id = requireNotNull(result.item.id)
                    val next = media.getNextUpItems(seriesId = id, limit = 1).getOrThrow().firstOrNull()
                        ?: media.getEpisodes(id, limit = 1).getOrThrow().firstOrNull()
                    if (next == null) onLibrary(result.item) else onPlay(next)
                } else if (play) onPlay(result.item) else onLibrary(result.item)
            }
            busy = false
            activated.onFailure { errors = errors + (it.message ?: "Server unavailable") }
        }
    }
    Scaffold(topBar = { TopAppBar(title = { Text(title.displayTitle, maxLines = 1) }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.catalog_back)) } }) }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item { AsyncImage(title.backdropPath?.let { "https://image.tmdb.org/t/p/w780$it" } ?: title.posterUrl, title.displayTitle, Modifier.fillMaxWidth().height(220.dp), contentScale = ContentScale.Crop) }
            item { Text(title.displayTitle, style = MaterialTheme.typography.headlineMedium); Text(listOf(title.date, title.genres.joinToString(" · ") { it.name }).filter { it.isNotBlank() }.joinToString(" · ")) }
            if (loading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            items(errors) { Text(it, color = MaterialTheme.colorScheme.error) }
            item { Button(onClick = { matches.firstOrNull()?.let { open(it, true) } }, enabled = matches.isNotEmpty() && !loading && !busy, modifier = Modifier.fillMaxWidth()) { Text(stringResource(if (matches.isEmpty()) R.string.catalog_no_resources else R.string.catalog_play)) } }
            item { Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val match = matches.firstOrNull()
                fun mutate(favorite: Boolean) {
                    if (match == null || busy) return
                    busy = true
                    scope.launch {
                        val result = catalogResult {
                            navigator.activate(match.serverId).getOrThrow()
                            val id = requireNotNull(match.item.id)
                            if (favorite) media.setFavoriteStatus(id, match.item.userData?.isFavorite != true).getOrThrow()
                            else if (match.item.type == "Series") media.setSeriesPlayedStatus(id, match.item.userData?.played != true).getOrThrow()
                            else media.setPlayedStatus(id, match.item.userData?.played != true).getOrThrow()
                        }
                        busy = false
                        if (result.isSuccess) revision++ else errors = errors + (result.exceptionOrNull()?.message ?: "Update failed")
                    }
                }
                OutlinedButton(onClick = { mutate(true) }, enabled = match != null && !busy) { Text(stringResource(if (match?.item?.userData?.isFavorite == true) R.string.catalog_unfavorite else R.string.catalog_favorite)) }
                OutlinedButton(onClick = { mutate(false) }, enabled = match != null && !busy) { Text(stringResource(if (match?.item?.userData?.played == true) R.string.catalog_unwatched else R.string.catalog_watched)) }
                TextButton(onClick = { revision++ }, enabled = !loading) { Text(stringResource(R.string.catalog_retry)) }
            } }
            if (subscriptions.enabled && subscriptions.loggedIn) item {
                OutlinedButton(onClick = { showSubscribe = true }, enabled = subscriptionReady && !busy) { Text(stringResource(R.string.catalog_manage_subscription)) }
            }
            item { Text(title.overview.ifBlank { context.getString(R.string.catalog_no_overview) }) }
            item { Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                title.voteAverage?.takeIf { title.voteCount > 0 }?.let { SuggestionChip(onClick = {}, label = { Text("TMDB %.1f".format(it)) }) }
                traktRating?.let { SuggestionChip(onClick = {}, label = { Text(stringResource(R.string.catalog_trakt_rating, it)) }) }
                title.doubanRating?.let { SuggestionChip(onClick = {}, label = { Text(stringResource(R.string.catalog_douban_rating, it)) }) }
            } }
            if (collection.isNotEmpty()) { item { Text(title.collection?.name.orEmpty(), style = MaterialTheme.typography.titleLarge) }; item { CatalogPosters(collection, onCatalog) } }
            items(title.seasons) { Text("${it.name} · ${it.episodeCount}", style = MaterialTheme.typography.titleSmall) }
            item { Text(stringResource(R.string.catalog_resources), style = MaterialTheme.typography.titleLarge) }
            items(matches, key = { "${it.serverId}:${it.item.id}" }) { result ->
                Card(onClick = { open(result, false) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("✓ ${result.serverName}", color = MaterialTheme.colorScheme.primary)
                        result.lineName?.let { Text(it, style = MaterialTheme.typography.labelMedium) }
                        Text(result.item.name.orEmpty())
                        result.item.mediaSources.orEmpty().forEach { source ->
                            Text(listOfNotNull(source.name, source.size?.let { "%.2f GB".format(it / 1e9) }, source.bitrate?.let { "%.1f Mbps".format(it / 1e6) }).joinToString(" · "), style = MaterialTheme.typography.labelMedium)
                            source.mediaStreams.orEmpty().filter { it.type == "Video" }.forEach { Text(it.videoRangeType ?: it.videoRange ?: it.displayTitle.orEmpty()) }
                        }
                    }
                }
            }
            matches.firstOrNull()?.item?.chapters.orEmpty().let { chapters -> if (chapters.isNotEmpty()) {
                item { Text(stringResource(R.string.catalog_chapters), style = MaterialTheme.typography.titleLarge) }
                items(chapters) { Text(it.name.orEmpty()) }
            } }
            item { OutlinedButton(onClick = { expanded = !expanded }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(if (expanded) R.string.catalog_collapse else R.string.catalog_expand)) } }
            if (expanded) {
                items(matches) { result -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    val streams = result.item.mediaStreams ?: result.item.mediaSources?.firstOrNull()?.mediaStreams.orEmpty()
                    streams.filter { it.type == "Video" || it.type == "Audio" }.forEach { stream ->
                        ElevatedCard(Modifier.fillMaxWidth()) { Text(listOfNotNull(stream.type, stream.displayTitle, stream.codec, stream.width?.let { "$it × ${stream.height}" }, stream.channels?.let { "$it ch" }, stream.sampleRate?.let { "$it Hz" }).joinToString(" · "), Modifier.padding(16.dp)) }
                    }
                } }
                item { LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items((title.credits?.cast.orEmpty() + title.credits?.crew.orEmpty()).distinctBy { it.id }.take(30)) { person -> Column(Modifier.width(88.dp)) {
                        AsyncImage(person.profilePath?.let { "https://image.tmdb.org/t/p/w185$it" }, person.name, Modifier.size(80.dp).clip(CircleShape), contentScale = ContentScale.Crop)
                        Text(person.name, maxLines = 2, style = MaterialTheme.typography.labelMedium)
                        Text(person.character ?: person.job.orEmpty(), maxLines = 2, style = MaterialTheme.typography.labelSmall)
                    } }
                } }
                item { LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { items(title.genres) { SuggestionChip(onClick = {}, label = { Text(it.name) }) } } }
                item { Text(stringResource(R.string.catalog_similar), style = MaterialTheme.typography.titleLarge) }
                item { CatalogPosters(title.similar?.results.orEmpty(), onCatalog) }
            }
        }
    }
    if (showSubscribe) {
        val season = if (title.mediaType == "movie") 0 else selectedSeason
        val subscribed = season in subscribedSeasons
        AlertDialog(onDismissRequest = { if (!busy) showSubscribe = false }, title = { Text(stringResource(R.string.catalog_manage_subscription)) }, text = {
            Column {
                if (title.mediaType == "tv") LazyColumn(Modifier.heightIn(max = 280.dp)) {
                    items(title.seasons.filter { it.number > 0 }) { option ->
                        Row { RadioButton(selectedSeason == option.number, onClick = { selectedSeason = option.number }, enabled = !busy); Text(option.name, Modifier.padding(top = 12.dp)) }
                    }
                }
                Text(stringResource(if (subscribed) R.string.catalog_subscribed else R.string.catalog_subscribe_hint))
                if (subscribed && !subscriptions.syncRemoval) Text(stringResource(R.string.catalog_removal_disabled))
            }
        }, confirmButton = { TextButton(onClick = {
            busy = true
            scope.launch {
                val result = catalogResult { subscriptions.setSubscription(title, season.takeIf { title.mediaType == "tv" }, subscribed) }
                busy = false
                if (result.isSuccess) { showSubscribe = false; revision++ } else errors = errors + (result.exceptionOrNull()?.message ?: "MoviePilot update failed")
            }
        }, enabled = !busy && (title.mediaType == "movie" || title.seasons.any { it.number == selectedSeason }) && (!subscribed || subscriptions.syncRemoval)) { Text(stringResource(if (subscribed) R.string.catalog_remove_subscription else R.string.catalog_add_subscription)) } },
        dismissButton = { TextButton(onClick = { showSubscribe = false }, enabled = !busy) { Text(stringResource(R.string.catalog_back)) } })
    }

}
