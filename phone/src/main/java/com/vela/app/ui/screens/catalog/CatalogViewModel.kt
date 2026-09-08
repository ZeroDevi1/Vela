package com.vela.app.ui.screens.catalog

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.vela.app.ui.screens.dashboard.search.FederatedSessionNavigator
import com.vela.data.model.*
import com.vela.data.repository.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.drop
import com.vela.shared.playback.UserDataRefreshSignals

class CatalogViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = CatalogRepository()
    private val federated = FederatedMediaRepository(application)
    private val navigator = FederatedSessionNavigator(application)
    private val _state = MutableStateFlow(CatalogState())
    val state = _state.asStateFlow()
    private var resumeJob: Job? = null
    init {
        refreshLibrary()
        viewModelScope.launch {
            AuthRepositoryProvider.getInstance(application).observeActiveSession().drop(1).collect { refreshLibrary() }
        }
        viewModelScope.launch { UserDataRefreshSignals.refreshEvent.drop(1).collect { refreshLibrary() } }
    }

    fun load(shelf: CatalogShelf, force: Boolean = false) {
        val previous = _state.value.shelves[shelf]
        if (previous?.loading == true || (!force && previous != null)) return
        _state.update { it.copy(shelves = it.shelves + (shelf to (previous ?: CatalogShelfState()).copy(loading = true, error = null))) }
        viewModelScope.launch {
            val result = catalogResult { repository.browse(shelf) }
            _state.update { state -> state.copy(shelves = state.shelves + (shelf to
                (state.shelves[shelf] ?: CatalogShelfState()).copy(
                    titles = result.getOrNull()?.results ?: previous?.titles.orEmpty(), loading = false,
                    updated = if (result.isSuccess) System.currentTimeMillis() else previous?.updated,
                    error = result.exceptionOrNull()?.message
                ))) }
        }
    }

    fun refreshLibrary() {
        resumeJob?.cancel()
        resumeJob = viewModelScope.launch {
            val resume = catalogResult { federated.loadContent(FederatedContentSection.CONTINUE_WATCHING) }
            val recent = catalogResult { federated.loadContent(FederatedContentSection.RECENT) }
            _state.update { it.copy(resume = resume.getOrNull()?.items.orEmpty(),
                recent = recent.getOrNull()?.items.orEmpty().sortedByDescending { row -> row.item.dateCreated.orEmpty() },
                errors = listOf(resume, recent).flatMap { result ->
                    result.getOrNull()?.failures.orEmpty().map { "${it.serverName}: ${it.message}" } + listOfNotNull(result.exceptionOrNull()?.message)
                }.distinct()) }
        }
    }

    fun openLibrary(result: FederatedMediaItem, onReady: (BaseItemDto) -> Unit) {
        if (_state.value.opening) return
        _state.update { it.copy(opening = true) }
        viewModelScope.launch {
            val activated = navigator.activate(result.serverId)
            _state.update { it.copy(opening = false, errors = it.errors + listOfNotNull(activated.exceptionOrNull()?.message)) }
            if (activated.isSuccess) onReady(result.item)
        }
    }
}

data class CatalogShelfState(val titles: List<CatalogTitle> = emptyList(), val loading: Boolean = false, val error: String? = null, val updated: Long? = null)
data class CatalogState(
    val shelves: Map<CatalogShelf, CatalogShelfState> = emptyMap(),
    val resume: List<FederatedMediaItem> = emptyList(), val recent: List<FederatedMediaItem> = emptyList(),
    val errors: List<String> = emptyList(), val opening: Boolean = false
)
