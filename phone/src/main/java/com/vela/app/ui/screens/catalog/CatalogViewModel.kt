package com.vela.app.ui.screens.catalog

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.vela.app.ui.screens.dashboard.search.FederatedSessionNavigator
import com.vela.data.model.BaseItemDto
import com.vela.data.model.CatalogTitle
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
        refresh()
        viewModelScope.launch {
            AuthRepositoryProvider.getInstance(application).observeActiveSession().drop(1).collect { refreshResume() }
        }
        viewModelScope.launch {
            UserDataRefreshSignals.refreshEvent.drop(1).collect { refreshResume() }
        }
    }

    fun refresh() {
        if (_state.value.loading) return
        _state.update { it.copy(loading = true, errors = emptyList()) }
        viewModelScope.launch {
            val result = catalogResult { repository.trending("day") }
            _state.update { it.copy(day = result.getOrDefault(emptyList()), errors = it.errors + listOfNotNull(result.exceptionOrNull()?.message)) }
            val week = catalogResult { repository.trending("week") }
            _state.update { it.copy(week = week.getOrDefault(emptyList()), errors = it.errors + listOfNotNull(week.exceptionOrNull()?.message), loading = false) }
        }
        refreshResume()
    }

    private fun refreshResume() {
        resumeJob?.cancel()
        resumeJob = viewModelScope.launch {
            val result = catalogResult { federated.loadContent(FederatedContentSection.CONTINUE_WATCHING) }
            _state.update { it.copy(resume = result.getOrNull()?.items.orEmpty(), errors = it.errors + result.getOrNull()?.failures.orEmpty().map { "${it.serverName}: ${it.message}" } + listOfNotNull(result.exceptionOrNull()?.message)) }
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

data class CatalogState(
    val day: List<CatalogTitle> = emptyList(), val week: List<CatalogTitle> = emptyList(),
    val resume: List<FederatedMediaItem> = emptyList(), val errors: List<String> = emptyList(),
    val loading: Boolean = false, val opening: Boolean = false
)
