package com.vela.app.ui.screens.catalog

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.vela.data.repository.TraktDeviceCode
import com.vela.data.repository.TraktHistoryEntry
import com.vela.data.repository.TraktRepository
import com.vela.data.repository.catalogResult
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 授权轮询属于连接页，不能因为卡片滚出 LazyColumn 或屏幕旋转而取消。 */
class TraktConnectionViewModel(application: Application) : AndroidViewModel(application) {
    private val repo = TraktRepository.getInstance(application)
    private val _state = MutableStateFlow(TraktConnectionState(clientId = repo.clientId, loggedIn = repo.loggedIn, username = repo.username, history = repo.cachedHistory()))
    val state = _state.asStateFlow()
    val reportingError = repo.error
    private var authJob: Job? = null
    fun setClientId(value: String) { _state.update { it.copy(clientId = value) } }
    fun setSecret(value: String) { _state.update { it.copy(secret = value) } }
    fun authorize() {
        val input = _state.value
        if (input.busy) return
        _state.update { it.copy(busy = true, error = null) }
        authJob = viewModelScope.launch {
            val result = catalogResult {
                val device = repo.beginAuthorization(input.clientId, input.secret)
                _state.update { it.copy(secret = "", code = device) }
                repo.awaitAuthorization(device)
            }
            _state.update { it.copy(busy = false, loggedIn = repo.loggedIn, username = repo.username, code = null, error = result.exceptionOrNull()?.message) }
        }
    }
    fun sync() {
        if (_state.value.busy) return
        _state.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            val result = catalogResult { repo.sync() }
            _state.update { it.copy(busy = false, history = result.getOrDefault(it.history), error = result.exceptionOrNull()?.message) }
        }
    }
    fun logout() {
        authJob?.cancel()
        repo.logout()
        _state.update { it.copy(loggedIn = false, username = "", code = null, history = emptyList(), busy = false, error = null) }
    }
}

data class TraktConnectionState(val clientId: String = "", val secret: String = "", val loggedIn: Boolean = false,
    val username: String = "", val busy: Boolean = false, val error: String? = null, val code: TraktDeviceCode? = null,
    val history: List<TraktHistoryEntry> = emptyList())
