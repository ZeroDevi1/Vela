package com.vela.app.ui.screens.catalog

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.vela.data.model.BaseItemDto
import com.vela.data.repository.*
import com.vela.shared.R
import kotlinx.coroutines.launch

@Composable
fun MoviePilotConnectionCard() {
    val context = LocalContext.current
    val repo = remember { SubscriptionRepository(context) }
    var url by remember { mutableStateOf(repo.url) }
    var username by remember { mutableStateOf(repo.username) }
    var password by remember { mutableStateOf("") }
    var otp by remember { mutableStateOf("") }
    var connectionChecked by remember { mutableStateOf(false) }
    var enabled by remember { mutableStateOf(repo.enabled) }
    var syncRemoval by remember { mutableStateOf(repo.syncRemoval) }
    var loggedIn by remember { mutableStateOf(repo.loggedIn) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    ElevatedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(stringResource(R.string.catalog_subscriptions), style = MaterialTheme.typography.titleLarge)
        Row { Text(stringResource(R.string.catalog_mp_enabled), Modifier.weight(1f)); Switch(enabled, { enabled = it; repo.enabled = it }, enabled = !busy) }
        Text(if (loggedIn) stringResource(R.string.catalog_logged_in, repo.username) else stringResource(R.string.catalog_disconnected))
        Text(stringResource(if (connectionChecked) R.string.catalog_connection_ok else R.string.catalog_connection_unchecked))
        OutlinedTextField(url, { url = it }, Modifier.fillMaxWidth(), enabled = !busy, singleLine = true, label = { Text(stringResource(R.string.catalog_mp_url)) })
        OutlinedTextField(username, { username = it }, Modifier.fillMaxWidth(), enabled = !busy, singleLine = true, label = { Text(stringResource(R.string.catalog_username)) })
        OutlinedTextField(password, { password = it }, Modifier.fillMaxWidth(), enabled = !busy, singleLine = true, label = { Text(stringResource(R.string.catalog_password)) }, visualTransformation = PasswordVisualTransformation())
        OutlinedTextField(otp, { otp = it }, Modifier.fillMaxWidth(), enabled = !busy, singleLine = true, label = { Text(stringResource(R.string.catalog_otp)) }, visualTransformation = PasswordVisualTransformation())
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        Button(onClick = { busy = true; error = null; scope.launch {
            catalogResult { repo.connect(url, username, password, otp) }.fold(onSuccess = { loggedIn = true; enabled = true; connectionChecked = true; password = ""; otp = "" }, onFailure = { error = it.message })
            busy = false
        } }, enabled = !busy && url.isNotBlank() && username.isNotBlank() && password.isNotBlank()) { Text(stringResource(R.string.catalog_connect)) }
        if (loggedIn && enabled) TextButton(onClick = { busy = true; error = null; scope.launch {
            catalogResult { repo.testConnection() }.fold(onSuccess = { connectionChecked = true }, onFailure = { connectionChecked = false; error = it.message })
            busy = false
        } }, enabled = !busy) { Text(stringResource(R.string.catalog_test_connection)) }
        if (loggedIn) TextButton(onClick = { repo.logout(); loggedIn = false; enabled = false; connectionChecked = false }, enabled = !busy) { Text(stringResource(R.string.catalog_logout)) }
        Row { Text(stringResource(R.string.catalog_sync_removal), Modifier.weight(1f)); Switch(syncRemoval, { syncRemoval = it; repo.syncRemoval = it }, enabled = enabled && !busy) }
        Text(stringResource(R.string.catalog_mp_sync), style = MaterialTheme.typography.bodySmall)
    } }
}

@Composable
fun TraktConnectionCard(onLibrary: (BaseItemDto) -> Unit) {
    val context = LocalContext.current
    val uri = LocalUriHandler.current
    val vm: TraktConnectionViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
    val state by vm.state.collectAsState()
    val reportingError by vm.reportingError.collectAsState()
    var showHistory by remember { mutableStateOf(false) }
    ElevatedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(stringResource(R.string.catalog_trakt), style = MaterialTheme.typography.titleLarge)
        Text(if (state.loggedIn) stringResource(R.string.catalog_connected, state.username) else stringResource(R.string.catalog_disconnected))
        if (!state.loggedIn) {
            Text(stringResource(R.string.catalog_trakt_setup), style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(state.clientId, vm::setClientId, Modifier.fillMaxWidth(), enabled = !state.busy, singleLine = true, label = { Text(stringResource(R.string.catalog_client_id)) })
            OutlinedTextField(state.secret, vm::setSecret, Modifier.fillMaxWidth(), enabled = !state.busy, singleLine = true, label = { Text(stringResource(R.string.catalog_client_secret)) }, visualTransformation = PasswordVisualTransformation())
            Button(onClick = vm::authorize, enabled = !state.busy && state.clientId.isNotBlank() && state.secret.isNotBlank()) { Text(stringResource(R.string.catalog_authorize)) }
        }
        state.code?.let { device ->
            Text(stringResource(R.string.catalog_device_code, device.verificationUrl, device.userCode))
            Text(stringResource(R.string.catalog_waiting_auth))
            TextButton(onClick = { uri.openUri(device.verificationUrl) }) { Text(stringResource(R.string.catalog_open_auth)) }
        }
        if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        (state.error ?: reportingError)?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (state.loggedIn) {
            Button(onClick = vm::sync, enabled = !state.busy) { Text(stringResource(R.string.catalog_trakt_sync)) }
            if (state.history.isNotEmpty()) TextButton(onClick = { showHistory = true }) { Text(stringResource(R.string.catalog_trakt_history)) }
        }
        if (state.loggedIn || state.code != null) TextButton(onClick = vm::logout) { Text(stringResource(R.string.catalog_logout)) }
    } }
    if (showHistory) {
        androidx.compose.ui.window.Dialog(onDismissRequest = { showHistory = false }, properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                CatalogNavigationHost(onLibrary) { openCatalog ->
                    LazyColumn(Modifier.fillMaxSize().statusBarsPadding().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        item { TextButton(onClick = { showHistory = false }) { Text(stringResource(R.string.catalog_back)) }; Text(stringResource(R.string.catalog_trakt_readonly)) }
                        items(state.history) { entry -> Card(onClick = { openCatalog(entry.title) }, modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(16.dp)) { Text(entry.title.displayTitle); Text(stringResource(when (entry.state) { "watched" -> R.string.catalog_status_watched; "watching" -> R.string.catalog_trakt_watching; else -> R.string.catalog_status_paused }) + (entry.progress?.let { " · %.1f%%".format(it) } ?: "")); entry.date?.let { Text(it) } }
                        } }
                        if (state.history.isEmpty()) item { Text(stringResource(R.string.catalog_empty)) }
                    }
                }
            }
        }
    }
}
