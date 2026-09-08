package com.vela.app.ui.screens.catalog

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.vela.data.repository.*
import com.vela.shared.playback.UserDataRefreshSignals
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

class SubscriptionViewModel(application: Application) : AndroidViewModel(application) {
    val repository = SubscriptionRepository(application)
    private val federated = FederatedMediaRepository(application)
    private val mutable = MutableStateFlow(SubscriptionState())
    val state = mutable.asStateFlow()
    private var job: Job? = null
    private var connectionKey: String? = null
    init {
        viewModelScope.launch { UserDataRefreshSignals.refreshEvent.drop(1).collect { refresh() } }
        viewModelScope.launch { AuthRepositoryProvider.getInstance(application).observeActiveSession().drop(1).collect { refresh() } }
    }
    fun refresh() {
        job?.cancel()
        job = viewModelScope.launch {
            val connected = repository.enabled && repository.loggedIn
            val key = "${repository.url}|${repository.username}"
            if (connectionKey != key) { mutable.value = SubscriptionState(); connectionKey = key }
            mutable.update { it.copy(loading = true, error = null, connected = connected) }
            if (!connected) {
                mutable.value = SubscriptionState(connected = false)
                return@launch
            }
            val list = catalogResult { repository.subscriptions() }
            if (list.isFailure) {
                mutable.update { it.copy(loading = false, calendarLoading = false, error = list.exceptionOrNull()?.message ?: "MoviePilot unavailable") }
                return@launch
            }
            mutable.update { it.copy(records = list.getOrThrow(), libraries = emptyMap(), loading = false, updated = System.currentTimeMillis(), feed = null, calendarError = null, calendarLoading = true) }
            launch {
                val feed = catalogResult { repository.subscriptionCalendar(list.getOrThrow()) }
                mutable.update { it.copy(feed = feed.getOrNull(), calendarLoading = false, calendarError = feed.exceptionOrNull()?.message) }
            }
            val gate = Semaphore(2)
            list.getOrThrow().groupBy { it.catalog?.key ?: "unmapped:${it.id}" }.values.map { records -> launch {
                gate.withPermit {
                    val title = records.first().catalog
                    if (title == null) {
                        records.forEach { record -> mutable.update { it.copy(libraries = it.libraries + (record.id to SubscriptionLibrary())) } }
                    } else {
                        val result = catalogResult { federated.matchCatalog(title.mediaType, title.id) }
                        val resources = result.getOrNull()?.items.orEmpty()
                        val errors = result.getOrNull()?.failures.orEmpty().map { "${it.serverName}: ${it.message}" } + listOfNotNull(result.exceptionOrNull()?.message)
                        records.forEach { record ->
                            val episodes = mutableListOf<FederatedMediaItem>()
                            val failures = errors.toMutableList()
                            if (record.type == "tv" && record.season != null && record.episodeGroup == null) resources.forEach { match ->
                                catalogResult { federated.subscriptionEpisodes(match, requireNotNull(record.season)) }.fold(
                                    onSuccess = { episodes.addAll(it) }, onFailure = { failures.add("${match.serverName}: ${it.message}") })
                            }
                            val library = SubscriptionLibrary(resources, episodes, failures,
                                failures.isEmpty() && federated.availableServers().isNotEmpty() && (record.type == "movie" || (record.season != null && record.episodeGroup == null)))
                            mutable.update { it.copy(libraries = it.libraries + (record.id to library)) }
                        }
                    }
                }
            } }.joinAll()
        }
    }
}

data class SubscriptionState(
    val connected: Boolean = false, val records: List<SubscriptionRecord> = emptyList(),
    val libraries: Map<Int, SubscriptionLibrary> = emptyMap(), val feed: CalendarFeed? = null,
    val loading: Boolean = false, val calendarLoading: Boolean = false, val error: String? = null, val calendarError: String? = null, val updated: Long? = null
)
