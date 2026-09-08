package com.vela.app.ui.screens.dashboard.search

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.vela.data.model.CatalogTitle
import com.vela.data.repository.CatalogRepository
import com.vela.data.repository.SubscriptionRepository
import com.vela.data.repository.catalogResult
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import com.vela.data.model.BaseItemDto
import com.vela.data.model.SearchMediaType
import com.vela.data.repository.AuthRepositoryProvider
import com.vela.data.repository.FederatedServerFailure
import com.vela.data.repository.FederatedMediaItem
import com.vela.data.repository.FederatedMediaRepository
import com.vela.data.repository.FederatedServer
import com.vela.shared.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class FederatedSearchUiState(
    val query: String = "",
    val selectedSources: Set<String> = emptySet(),
    val doubanItems: List<com.vela.data.repository.DoubanSearchTitle> = emptyList(),
    val catalogItems: List<Pair<String, CatalogTitle>> = emptyList(),
    val servers: List<FederatedServer> = emptyList(),
    val selectedServerId: String? = null,
    val selectedTypes: Set<SearchMediaType> = DEFAULT_FEDERATED_SEARCH_TYPES,
    val items: List<FederatedMediaItem> = emptyList(),
    val failures: List<FederatedServerFailure> = emptyList(),
    val isSearching: Boolean = false,
    val openingServerId: String? = null,
    val actionError: String? = null
)

class FederatedSearchViewModel(application: Application) : AndroidViewModel(application) {
    private val catalogRepository = CatalogRepository()
    private val subscriptions = SubscriptionRepository(application)
    private val searchRepository = FederatedMediaRepository(application)
    private val authRepository = AuthRepositoryProvider.getInstance(application)
    private val sessionNavigator = FederatedSessionNavigator(application)
    private val _uiState = MutableStateFlow(
        FederatedSearchUiState(servers = searchRepository.availableServers(), selectedSources = searchRepository.availableServers().map { it.id }.toSet() + "tmdb")
    )
    val uiState: StateFlow<FederatedSearchUiState> = _uiState.asStateFlow()

    private var searchJob: Job? = null

    init {
        viewModelScope.launch {
            authRepository.observeActiveSession().collect {
                val servers = searchRepository.availableServers()
                _uiState.update { current ->
                    current.copy(
                        servers = servers,
                        selectedSources = current.selectedSources.intersect(servers.map { it.id }.toSet() + setOf("tmdb", "douban")) +
                            if (current.servers.all { it.id in current.selectedSources }) servers.map { it.id }.toSet() else emptySet(),
                        selectedServerId = current.selectedServerId
                            ?.takeIf { selectedId -> servers.any { it.id == selectedId } }
                    )
                }
            }
        }
    }

    fun selectSources(sources: Set<String>) {
        _uiState.update { it.copy(selectedSources = sources, selectedServerId = null, items = emptyList(), doubanItems = emptyList(), catalogItems = emptyList()) }
        scheduleSearch(immediate = true)
    }

    fun updateQuery(query: String) {
        _uiState.update { it.copy(query = query) }
        scheduleSearch()
    }

    fun submitSearch() {
        searchJob?.cancel()
        searchJob = viewModelScope.launch { searchCurrentQuery() }
    }

    fun selectServer(serverId: String?) {
        _uiState.update { it.copy(selectedServerId = serverId) }
    }

    fun toggleType(type: SearchMediaType) {
        val current = _uiState.value.selectedTypes
        val updated = if (type in current) current - type else current + type
        if (updated.isEmpty()) return
        _uiState.update { it.copy(selectedTypes = updated) }
        scheduleSearch(immediate = true)
    }

    fun openResult(result: FederatedMediaItem, onReady: (BaseItemDto) -> Unit) {
        if (_uiState.value.openingServerId != null) return
        viewModelScope.launch {
            _uiState.update { it.copy(openingServerId = result.serverId, actionError = null) }
            sessionNavigator.activate(result.serverId).fold(
                onSuccess = {
                    _uiState.update { it.copy(openingServerId = null) }
                    onReady(result.item)
                },
                onFailure = { error ->
                    _uiState.update {
                        it.copy(
                            openingServerId = null,
                            actionError = error.message
                                ?: getApplication<Application>().getString(
                                    R.string.federated_search_switch_failed
                                )
                        )
                    }
                }
            )
        }
    }

    fun clearActionError() {
        _uiState.update { it.copy(actionError = null) }
    }

    private fun scheduleSearch(immediate: Boolean = false) {
        searchJob?.cancel()
        val query = _uiState.value.query
        if (query.isBlank()) {
            _uiState.update {
                it.copy(items = emptyList(), doubanItems = emptyList(), catalogItems = emptyList(), failures = emptyList(), isSearching = false)
            }
            return
        }
        searchJob = viewModelScope.launch {
            if (!immediate) delay(SEARCH_DEBOUNCE_MS)
            searchCurrentQuery()
        }
    }

    private suspend fun searchCurrentQuery() {
        val query = _uiState.value.query.trim()
        if (query.isEmpty()) return
        val selectedTypes = _uiState.value.selectedTypes
        val sources = _uiState.value.selectedSources
        _uiState.update { it.copy(isSearching = true, failures = emptyList()) }

        val response = try {
            coroutineScope {
                val library = async { searchRepository.search(query, selectedTypes, selectedServerIds = sources - setOf("tmdb", "douban")) }
                val tmdb = async { if ("tmdb" in sources) catalogResult { catalogRepository.search(query) } else Result.success(emptyList()) }
                val douban = async { if ("douban" in sources) catalogResult { subscriptions.searchDouban(query) } else Result.success(emptyList()) }
                val tmdbResult = tmdb.await()
                val doubanResult = douban.await()
                val catalogItems = tmdbResult.getOrDefault(emptyList()).map { "tmdb" to it }
                if (_uiState.value.query.trim() != query || _uiState.value.selectedSources != sources || _uiState.value.selectedTypes != selectedTypes) {
                    throw CancellationException("Search inputs changed")
                }
                _uiState.update { it.copy(doubanItems = doubanResult.getOrDefault(emptyList()).filter { title -> if (title.mediaType == "movie") SearchMediaType.MOVIE in selectedTypes else SearchMediaType.SERIES in selectedTypes }, catalogItems = catalogItems.filter { (_, title) ->
                    if (title.mediaType == "movie") SearchMediaType.MOVIE in selectedTypes else SearchMediaType.SERIES in selectedTypes
                }) }
                val result = library.await()
                result.copy(failures = result.failures + listOfNotNull(
                    tmdbResult.exceptionOrNull()?.let { FederatedServerFailure("tmdb", "TMDB", it.message ?: "Search failed") },
                    doubanResult.exceptionOrNull()?.let { FederatedServerFailure("douban", getApplication<Application>().getString(R.string.catalog_douban), it.message ?: "Search failed") }
                ))
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            _uiState.update {
                it.copy(
                    isSearching = false,
                    actionError = error.message
                        ?: getApplication<Application>().getString(R.string.search_failed)
                )
            }
            return
        }

        // 只允许当前输入和类型对应的请求落地，避免慢服务器覆盖更新后的查询结果。
        if (
            _uiState.value.query.trim() != query ||
            _uiState.value.selectedTypes != selectedTypes || _uiState.value.selectedSources != sources
        ) return
        _uiState.update {
            it.copy(
                servers = searchRepository.availableServers(),
                items = response.items,
                failures = response.failures,
                isSearching = false
            )
        }
    }

    private companion object {
        const val SEARCH_DEBOUNCE_MS = 350L
    }
}

private val DEFAULT_FEDERATED_SEARCH_TYPES = setOf(
    SearchMediaType.MOVIE,
    SearchMediaType.SERIES,
    SearchMediaType.EPISODE
)
