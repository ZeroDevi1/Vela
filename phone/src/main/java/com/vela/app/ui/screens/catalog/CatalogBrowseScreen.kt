package com.vela.app.ui.screens.catalog

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.AsyncImage
import com.vela.data.model.*
import com.vela.data.repository.*
import com.vela.shared.R
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.LocalDate

data class BrowseState(val filter: CatalogFilter = CatalogFilter(), val titles: List<CatalogTitle> = emptyList(),
    val page: Int = 0, val totalPages: Int = 1, val loading: Boolean = false, val error: String? = null)

class CatalogBrowseViewModel : ViewModel() {
    private val repository = CatalogRepository()
    private val mutable = MutableStateFlow(BrowseState())
    val state = mutable.asStateFlow()
    private var job: Job? = null
    fun load(shelf: CatalogShelf, filter: CatalogFilter = mutable.value.filter, reset: Boolean = false) {
        if (!reset && (mutable.value.loading || mutable.value.page >= mutable.value.totalPages)) return
        job?.cancel()
        val old = if (reset) BrowseState(filter) else mutable.value
        mutable.value = old.copy(loading = true, error = null)
        job = viewModelScope.launch {
            val result = catalogResult { repository.browse(shelf, old.page + 1, filter) }
            mutable.value = result.fold(onSuccess = { page ->
                old.copy(titles = (old.titles + page.results).distinctBy { it.key }, page = old.page + 1,
                    totalPages = if (page.results.isEmpty()) old.page + 1 else page.totalPages.coerceIn(1, 500), loading = false)
            }, onFailure = { old.copy(error = it.message, loading = false) })
        }
    }
}

@Composable
internal fun CatalogBrowseScreen(shelf: CatalogShelf, back: () -> Unit, open: (CatalogTitle) -> Unit, modifier: Modifier = Modifier, initialGenre: Int? = null) {
    val vm: CatalogBrowseViewModel = viewModel(key = "catalog-browse:${shelf.name}:$initialGenre")
    val state by vm.state.collectAsStateWithLifecycle()
    var filters by remember { mutableStateOf(false) }
    val grid = rememberLazyGridState()
    LaunchedEffect(shelf) { if (state.page == 0 && state.error == null) vm.load(shelf, state.filter.copy(genre = initialGenre), reset = true) }
    BackHandler(onBack = back)
    Column(modifier.fillMaxSize().statusBarsPadding().padding(horizontal = 20.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = back) { Text(stringResource(R.string.catalog_back)) }
            if (shelf.filterable) TextButton(onClick = { filters = true }) { Text(stringResource(R.string.feed_filters)) }
        }
        Text(stringResource(shelf.label()), style = MaterialTheme.typography.headlineSmall)
        if (shelf in setOf(CatalogShelf.TOP_MOVIES, CatalogShelf.TOP_TV, CatalogShelf.RECENT_TOP, CatalogShelf.CLASSICS, CatalogShelf.ANIMATION_TOP) || state.filter.sort == "vote_average.desc")
            Text(stringResource(R.string.feed_top_hint), style = MaterialTheme.typography.bodySmall)
        if (shelf in setOf(CatalogShelf.AIRING_TODAY, CatalogShelf.ON_AIR)) Text(stringResource(R.string.feed_schedule_hint), style = MaterialTheme.typography.bodySmall)
        LazyVerticalGrid(columns = GridCells.Adaptive(132.dp), state = grid, contentPadding = PaddingValues(vertical = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            items(state.titles, key = { it.key }) { title ->
                Card(onClick = { open(title) }) {
                    AsyncImage(title.posterUrl, title.displayTitle, Modifier.fillMaxWidth().aspectRatio(2f / 3), contentScale = ContentScale.Crop)
                    Column(Modifier.padding(10.dp)) {
                        Text(title.displayTitle, maxLines = 2, style = MaterialTheme.typography.titleSmall)
                        Text("${title.date.take(4)} · ${stringResource(if (title.mediaType == "movie") R.string.feed_movies else R.string.feed_series)}", style = MaterialTheme.typography.labelSmall)
                        title.voteAverage?.takeIf { title.voteCount > 0 }?.let { Text("TMDB %.1f".format(it), color = MaterialTheme.colorScheme.primary) }
                    }
                }
            }
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column(Modifier.fillMaxWidth()) {
                    if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                    state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    if (!state.loading && state.titles.isEmpty() && state.error == null) Text(stringResource(R.string.catalog_empty))
                    if (!state.loading && (state.page < state.totalPages || state.error != null))
                        OutlinedButton(onClick = { vm.load(shelf) }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(if (state.error != null) R.string.catalog_retry else R.string.feed_more)) }
                    else if (!state.loading && state.titles.isNotEmpty()) Text(stringResource(R.string.feed_end))
                }
            }
        }
    }
    if (filters) CatalogFilterDialog(shelf, state.filter, { filters = false }) { filter ->
        vm.load(shelf, filter, true); filters = false
    }
    var shownFilter by rememberSaveable { mutableStateOf(state.filter.toString()) }
    LaunchedEffect(state.filter) {
        if (shownFilter != state.filter.toString()) { grid.scrollToItem(0); shownFilter = state.filter.toString() }
    }
}

@Composable
private fun CatalogFilterDialog(shelf: CatalogShelf, initial: CatalogFilter, dismiss: () -> Unit, apply: (CatalogFilter) -> Unit) {
    var genre by remember { mutableStateOf(initial.genre) }
    var year by remember { mutableStateOf(initial.year?.toString().orEmpty()) }
    var country by remember { mutableStateOf(initial.country) }
    var sort by remember { mutableStateOf(initial.sort) }
    var genres by remember { mutableStateOf<List<CatalogGenre>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    var revision by remember { mutableIntStateOf(0) }
    val validYear = year.isBlank() || year.toIntOrNull()?.let { it in 1870..LocalDate.now().year + 1 } == true
    LaunchedEffect(shelf, revision) { catalogResult { CatalogRepository().genres(shelf.type) }.fold(onSuccess = { genres = it; error = null }, onFailure = { error = it.message }) }
    AlertDialog(onDismissRequest = dismiss, title = { Text(stringResource(R.string.feed_filters)) }, text = {
        LazyColumn(Modifier.heightIn(max = 440.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item { OutlinedTextField(year, { year = it }, label = { Text(stringResource(R.string.feed_year)) }, singleLine = true, isError = !validYear)
                if (!validYear) Text(stringResource(R.string.feed_year_error), color = MaterialTheme.colorScheme.error) }
            item { Text(stringResource(R.string.feed_sort), style = MaterialTheme.typography.titleSmall) }
            items(listOf("" to R.string.feed_default, "popularity.desc" to R.string.feed_popularity, "vote_average.desc" to R.string.feed_rating, "date" to R.string.feed_date)) { (value, label) ->
                FilterChip(sort == value, { sort = value }, label = { Text(stringResource(label)) })
            }
            item { Text(stringResource(R.string.feed_country), style = MaterialTheme.typography.titleSmall) }
            items(listOf("", "CN", "US", "JP", "KR", "GB", "FR")) { value ->
                FilterChip(country == value, { country = value }, label = { Text(if (value.isEmpty()) stringResource(R.string.feed_any) else java.util.Locale.Builder().setRegion(value).build().getDisplayCountry(java.util.Locale.getDefault())) })
            }
            item { Text(stringResource(R.string.feed_genre), style = MaterialTheme.typography.titleSmall)
                FilterChip(genre == null, { genre = null }, label = { Text(stringResource(R.string.feed_any)) }) }
            error?.let { message -> item { Text(message, color = MaterialTheme.colorScheme.error); TextButton(onClick = { revision++ }) { Text(stringResource(R.string.catalog_retry)) } } }
            items(genres) { option -> FilterChip(genre == option.id, { genre = option.id }, label = { Text(option.name) }) }
        }
    }, confirmButton = { TextButton(onClick = { apply(CatalogFilter(genre, year.toIntOrNull(), country, sort)) }, enabled = validYear) { Text(stringResource(R.string.feed_apply)) } },
        dismissButton = { TextButton(onClick = { apply(CatalogFilter()) }) { Text(stringResource(R.string.feed_reset)) } })
}

@Composable
internal fun BangumiSchedule(onCatalog: (CatalogTitle) -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var feed by remember { mutableStateOf<CalendarFeed?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var revision by remember { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<CalendarEntry?>(null) }
    LaunchedEffect(revision) { loading = true; catalogResult { SubscriptionRepository(context).publicCalendar() }.fold(onSuccess = { feed = it; error = null }, onFailure = { error = it.message }); loading = false }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.sub_public), style = MaterialTheme.typography.titleLarge)
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (!loading && error == null && feed?.entries?.isEmpty() == true) Text(stringResource(R.string.catalog_empty))
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        feed?.entries?.groupBy { it.date }?.forEach { (date, rows) ->
            Text(date.toString(), style = MaterialTheme.typography.titleSmall)
            androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(rows, key = { it.key }) { entry -> Card(onClick = { selected = entry }, modifier = Modifier.width(140.dp)) {
                    AsyncImage(entry.poster, entry.title, Modifier.fillMaxWidth().height(190.dp), contentScale = ContentScale.Crop)
                    Text(entry.title, Modifier.padding(8.dp), maxLines = 2)
                } }
            }
        }
        TextButton(onClick = { revision++ }, enabled = !loading) { Text(stringResource(R.string.feed_refresh)) }
    }
    selected?.let { entry -> UnmappedCalendarDialog(entry, { selected = null }, onCatalog) }
}
