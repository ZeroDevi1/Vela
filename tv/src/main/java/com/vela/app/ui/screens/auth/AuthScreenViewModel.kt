package com.vela.app.ui.screens.auth

import android.app.Application
import androidx.annotation.StringRes
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.vela.shared.R
import com.vela.app.ui.screens.dashboard.home.CachedData
import com.vela.data.network.ServerType
import com.vela.data.network.ServerCertificateInfo
import com.vela.data.network.ServerTrust
import com.vela.data.network.UntrustedServerCertificateException
import com.vela.data.repository.AuthRepositoryProvider
import com.vela.data.repository.MediaRepositoryProvider
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class AuthScreenViewModel(application: Application) : AndroidViewModel(application) {
    
    private val authRepository = AuthRepositoryProvider.getInstance(application)
    private val mediaRepository = MediaRepositoryProvider.getInstance(application)
    private var quickConnectPollingJob: Job? = null
    private var connectionJob: Job? = null
    private var capabilitiesJob: Job? = null
    private var connectedServerUrl: String? = null

    private fun invalidateConnection() {
        connectionJob?.cancel()
        capabilitiesJob?.cancel()
        quickConnectPollingJob?.cancel()
        connectedServerUrl = null
        _uiState.value = _uiState.value.copy(
            isServerLoading = false, isServerConnected = false, serverInfo = null,
            pendingCertificate = null, isQuickConnectLoading = false, quickConnectCode = null,
            showQuickConnect = false, loginErrorMessage = null
        )
    }

    fun updateServerType(serverType: ServerType) {
        if (serverType == ServerType.UNKNOWN || serverType == _uiState.value.serverType) return
        invalidateConnection()
        _uiState.value = _uiState.value.copy(
            serverType = serverType, isPeach = serverType == ServerType.PEACH,
            username = "", password = "", serverErrorMessage = null
        )
    }
    
    private val _uiState = MutableStateFlow(AuthScreenUiState())
    val uiState: StateFlow<AuthScreenUiState> = _uiState.asStateFlow()

    // Authentication state flow
    val isAuthenticated: Flow<Boolean> = authRepository.isAuthenticated
    
    fun updateServerUrl(url: String) {
        if (url == _uiState.value.serverUrl) return
        invalidateConnection()
        _uiState.value = _uiState.value.copy(
            serverUrl = url,
            serverErrorMessage = null
        )
    }
    
    fun updateUsername(username: String) {
        _uiState.value = _uiState.value.copy(
            username = username,
            loginErrorMessage = null
        )
    }
    
    fun updatePassword(password: String) {
        _uiState.value = _uiState.value.copy(
            password = password,
            loginErrorMessage = null
        )
    }

    fun setLoginError(error: Throwable) {
        _uiState.value = _uiState.value.copy(loginErrorMessage = mapLoginError(error))
    }
    
    fun connectToServer(onSuccess: (serverUrl: String, serverName: String?) -> Unit) {
        val currentState = _uiState.value
        if (currentState.isServerLoading) return
        
        if (currentState.serverUrl.isBlank()) {
            _uiState.value = currentState.copy(
                serverErrorMessage = string(R.string.auth_error_enter_server_url)
            )
            return
        }
        
        _uiState.value = currentState.copy(
            isServerLoading = true,
            serverErrorMessage = null
        )
        
        connectionJob?.cancel()
        connectionJob = viewModelScope.launch {
            val result = authRepository.testServerConnection(currentState.serverUrl, currentState.serverType)
            coroutineContext.ensureActive()
            
            result.fold(
                onSuccess = { serverInfo ->
                    connectedServerUrl = currentState.serverUrl.trim()
                    _uiState.value = _uiState.value.copy(
                        isServerLoading = false,
                        serverInfo = serverInfo,
                        isServerConnected = true,
                        isPeach = currentState.serverType == ServerType.PEACH,
                        showQuickConnect = currentState.serverType == ServerType.JELLYFIN
                    )
                    onSuccess(currentState.serverUrl, serverInfo.serverName)
                },
                onFailure = { error ->
                    _uiState.value = _uiState.value.copy(
                        isServerLoading = false,
                        serverErrorMessage = if (error is UntrustedServerCertificateException) null else mapServerError(error),
                        pendingCertificate = (error as? UntrustedServerCertificateException)?.certificate
                    )
                }
            )
        }
    }

    /** 用户核对指纹后信任该证书，并重新连接。 */
    fun trustPendingCertificate(onSuccess: (serverUrl: String, serverName: String?) -> Unit) {
        val certificate = _uiState.value.pendingCertificate ?: return
        ServerTrust.pin(certificate)
        _uiState.value = _uiState.value.copy(pendingCertificate = null)
        connectToServer(onSuccess)
    }

    /** 用户导入了签发该证书的 CA：不再固定这张叶子证书，直接重连。 */
    fun retryAfterAuthorityImport(onSuccess: (serverUrl: String, serverName: String?) -> Unit) {
        _uiState.value = _uiState.value.copy(pendingCertificate = null)
        connectToServer(onSuccess)
    }

    fun dismissPendingCertificate() {
        _uiState.value = _uiState.value.copy(
            pendingCertificate = null,
            serverErrorMessage = string(R.string.auth_certificate_not_trusted)
        )
    }
    
    fun login(serverUrl: String, onSuccess: () -> Unit) {
        val currentState = _uiState.value
        if (currentState.isLoginLoading || currentState.isQuickConnectLoading) return
        
        if (currentState.username.isBlank() && !currentState.isPeach) {
            _uiState.value = currentState.copy(
                loginErrorMessage = string(R.string.auth_error_enter_username)
            )
            return
        }
        
        _uiState.value = currentState.copy(
            isLoginLoading = true,
            loginErrorMessage = null
        )
        
        viewModelScope.launch {
            val result = authRepository.authenticateUser(
                serverUrl = serverUrl,
                username = if (currentState.isPeach) "" else currentState.username,
                password = currentState.password,
                serverType = currentState.serverType.takeIf { connectedServerUrl == serverUrl.trim() }
            )
            
            coroutineContext.ensureActive()
            result.fold(
                onSuccess = {
                    _uiState.value = _uiState.value.copy(
                        isLoginLoading = false
                    )
                    onSuccess()
                },
                onFailure = { error ->
                    _uiState.value = _uiState.value.copy(
                        isLoginLoading = false,
                        // Peach 的失败原因由仓库层给出可读文案，直接显示。
                        loginErrorMessage = if (_uiState.value.isPeach && !error.message.isNullOrBlank()) {
                            error.message
                        } else {
                            mapLoginError(error)
                        }
                    )
                }
            )
        }
    }

    fun refreshQuickConnectVisibility(serverUrl: String) {
        capabilitiesJob?.cancel()
        val normalizedUrl = serverUrl.trim()
        if (connectedServerUrl == normalizedUrl) {
            _uiState.value = _uiState.value.copy(
                isPeach = _uiState.value.serverType == ServerType.PEACH,
                showQuickConnect = _uiState.value.serverType == ServerType.JELLYFIN
            )
            return
        }
        if (normalizedUrl.isBlank()) {
            _uiState.value = _uiState.value.copy(showQuickConnect = false)
            return
        }

        capabilitiesJob = viewModelScope.launch {
            val supported = authRepository.isQuickConnectSupported(normalizedUrl)
            val isPeach = authRepository.isPeachServer(normalizedUrl)
            coroutineContext.ensureActive()
            _uiState.value = _uiState.value.copy(
                isPeach = isPeach,
                showQuickConnect = supported,
                quickConnectCode = if (supported) _uiState.value.quickConnectCode else null,
                isQuickConnectLoading = if (supported) _uiState.value.isQuickConnectLoading else false
            )
        }
    }

    fun loginWithQuickConnect(serverUrl: String, onSuccess: () -> Unit) {
        if (!_uiState.value.showQuickConnect || _uiState.value.isLoginLoading) return
        if (_uiState.value.isQuickConnectLoading) return

        val normalizedUrl = serverUrl.trim()
        if (normalizedUrl.isBlank()) {
            _uiState.value = _uiState.value.copy(
                loginErrorMessage = string(R.string.auth_error_missing_server_url_quick_connect)
            )
            return
        }

        quickConnectPollingJob?.cancel()
        quickConnectPollingJob = viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                isQuickConnectLoading = true,
                loginErrorMessage = null,
                quickConnectCode = null
            )

            val initiation = authRepository.initiateQuickConnect(normalizedUrl)
            coroutineContext.ensureActive()
            val initiated = initiation.getOrElse { error ->
                _uiState.value = _uiState.value.copy(
                    isQuickConnectLoading = false,
                    quickConnectCode = null,
                    loginErrorMessage = mapLoginError(error)
                )
                return@launch
            }

            var currentSecret = initiated.secret.orEmpty()
            val initialCode = initiated.code.orEmpty()
            if (currentSecret.isBlank() || initialCode.isBlank()) {
                _uiState.value = _uiState.value.copy(
                    isQuickConnectLoading = false,
                    quickConnectCode = null,
                    loginErrorMessage = string(R.string.auth_error_quick_connect_start_failed)
                )
                return@launch
            }

            _uiState.value = _uiState.value.copy(quickConnectCode = initialCode)

            var pollCount = 0
            while (pollCount < 40) {
                delay(3000L)
                val quickConnectLogin = authRepository.authenticateWithQuickConnect(
                    serverUrl = normalizedUrl,
                    secret = currentSecret
                )
                coroutineContext.ensureActive()
                if (quickConnectLogin.isSuccess) {
                    _uiState.value = _uiState.value.copy(
                        isQuickConnectLoading = false,
                        quickConnectCode = null
                    )
                    onSuccess()
                    return@launch
                }
                pollCount += 1
            }

            _uiState.value = _uiState.value.copy(
                isQuickConnectLoading = false,
                quickConnectCode = null,
                loginErrorMessage = string(R.string.auth_error_quick_connect_timed_out)
            )
        }
    }

    fun clearServerError() {
        _uiState.value = _uiState.value.copy(serverErrorMessage = null)
    }
    
    fun clearLoginError() {
        capabilitiesJob?.cancel()
        quickConnectPollingJob?.cancel()
        _uiState.value = _uiState.value.copy(
            loginErrorMessage = null, isQuickConnectLoading = false, quickConnectCode = null
        )
    }

    fun cancelPendingConnection() {
        invalidateConnection()
    }

    fun logout() {
        invalidateConnection()
        viewModelScope.launch {
            quickConnectPollingJob?.cancel()
            authRepository.logout()
            mediaRepository.clearPersistedHomeSnapshot()
            CachedData.clearAllCache()
            _uiState.value = AuthScreenUiState()
        }
    }

    private fun mapLoginError(error: Throwable): String {
        val message = error.message?.trim().orEmpty()
        val code = extractHttpCode(message)

        return when (code) {
            400 -> string(R.string.auth_error_login_invalid_request)
            401 -> string(R.string.auth_error_login_invalid_credentials)
            403 -> string(R.string.auth_error_login_access_denied)
            404 -> string(R.string.auth_error_login_service_not_found)
            429 -> string(R.string.auth_error_login_too_many_attempts)
            500, 502, 503, 504 -> string(R.string.auth_error_login_server_error)
            else -> when {
                message.equals("401") -> string(R.string.auth_error_login_invalid_credentials)
                message.contains("authentication failed", ignoreCase = true) -> string(R.string.auth_error_login_invalid_credentials)
                message.contains("timeout", ignoreCase = true) -> string(R.string.auth_error_login_timeout)
                message.contains("unable to resolve host", ignoreCase = true) -> string(R.string.auth_error_login_cannot_reach_server)
                message.contains("failed to connect", ignoreCase = true) -> string(R.string.auth_error_login_cannot_connect)
                else -> string(R.string.auth_error_login_generic)
            }
        }
    }

    private fun mapServerError(error: Throwable): String {
        val message = error.message?.trim().orEmpty()
        val code = extractHttpCode(message)

        return when (code) {
            400 -> string(R.string.auth_error_server_invalid_request)
            401, 403 -> string(R.string.auth_error_server_access_rejected)
            404 -> string(R.string.auth_error_server_endpoint_not_found)
            500, 502, 503, 504 -> string(R.string.auth_error_server_unavailable)
            else -> when {
                message.equals("401") -> string(R.string.auth_error_server_access_rejected)
                message.contains("timeout", ignoreCase = true) -> string(R.string.auth_error_server_timeout)
                message.contains("unable to resolve host", ignoreCase = true) -> string(R.string.auth_error_server_cannot_find)
                message.contains("failed to connect", ignoreCase = true) -> string(R.string.auth_error_server_cannot_connect)
                message.isBlank() -> string(R.string.auth_error_server_generic)
                else -> message
            }
        }
    }

    private fun string(@StringRes resId: Int): String {
        return getApplication<Application>().getString(resId)
    }

    private fun extractHttpCode(message: String): Int? {
        val match = """\b([1-5]\d{2})\b""".toRegex().find(message) ?: return null
        return match.groupValues.getOrNull(1)?.toIntOrNull()
    }
}

data class AuthScreenUiState(
    // Server connection state
    val serverUrl: String = "",
    val serverType: ServerType = ServerType.EMBY,
    val isServerLoading: Boolean = false,
    val isServerConnected: Boolean = false,
    val serverInfo: com.vela.data.model.ServerInfo? = null,
    val serverErrorMessage: String? = null,
    
    // Login state
    val username: String = "",
    val password: String = "",
    val isLoginLoading: Boolean = false,
    val loginErrorMessage: String? = null,
    val showQuickConnect: Boolean = false,
    val isQuickConnectLoading: Boolean = false,
    val quickConnectCode: String? = null,
    val isPeach: Boolean = false,
    val pendingCertificate: ServerCertificateInfo? = null
)
