package com.vela.app.ui.screens.dashboard.settings

import com.vela.shared.ui.components.common.bottomContentPadding
import com.vela.shared.ui.components.common.excludeBottom
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.vela.data.model.BaseItemDto
import com.vela.data.model.SeerrItemIds
import com.vela.data.model.SeerrRequestedItem
import com.vela.shared.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectionsSettingsScreen(
    onBackPressed: () -> Unit = {},
    onNavigateToRequestedItem: (BaseItemDto) -> Unit = {}
) {
    val context = LocalContext.current
    val viewModel: SettingsViewModel = viewModel { SettingsViewModel(context) }
    val uiState by viewModel.uiState.collectAsState()

    var showSeerrDialog by remember { mutableStateOf(false) }

    LaunchedEffect(uiState.activeServerId) {
        viewModel.reloadSeerrConnection()
    }

    Scaffold(
        containerColor = Color.Black,
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(stringResource(R.string.settings_connections)) },
                navigationIcon = {
                    IconButton(onClick = onBackPressed) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = null
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent
                )
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding.excludeBottom())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            contentPadding = innerPadding.bottomContentPadding()
        ) {
            item { com.vela.app.ui.screens.catalog.MoviePilotConnectionCard() }
            item { com.vela.app.ui.screens.catalog.TraktConnectionCard(onNavigateToRequestedItem) }
            item { SectionLabel(stringResource(R.string.settings_server_lines)) }
            item {
                val activeServer = uiState.savedServers.firstOrNull { it.id == uiState.activeServerId }
                ServerLinesSection(
                    server = activeServer,
                    isBusy = uiState.isLineBusy,
                    onAddLine = { url, name -> viewModel.addServerLine(url, name) },
                    onSwitchLine = viewModel::switchServerLine,
                    onRemoveLine = viewModel::removeServerLine,
                    onAutoSelect = viewModel::autoSelectServerLine,
                    onSetAutoRoute = viewModel::setAutoRouteEnabled
                )
            }
            item { Spacer(modifier = Modifier.height(4.dp)) }

            item { SectionLabel(stringResource(R.string.settings_seerr)) }
            item {
                SeerrConnectionCard(
                    seerr = uiState.seerr,
                    onClick = { showSeerrDialog = true }
                )
            }
        }
    }

    if (showSeerrDialog) {
        SeerrConnectionDialog(
            connectionState = uiState.seerr,
            isBusy = uiState.seerr.status == SeerrConnectionStatus.CONNECTING ||
                uiState.seerr.status == SeerrConnectionStatus.CHECKING,
            onDismiss = {
                if (uiState.seerr.status != SeerrConnectionStatus.CONNECTING &&
                    uiState.seerr.status != SeerrConnectionStatus.CHECKING
                ) {
                    showSeerrDialog = false
                }
            },
            onConnect = { serverUrl, username, password ->
                viewModel.connectSeerr(
                    serverUrl = serverUrl,
                    username = username,
                    password = password
                ) { result ->
                    if (result.isSuccess) {
                        showSeerrDialog = false
                    }
                }
            },
            onDisconnect = {
                viewModel.disconnectSeerr()
                showSeerrDialog = false
            },
            onRefreshStatus = viewModel::refreshSeerrConnection
        )
    }

    if (uiState.seerrRequestedItems.mediaType != null) {
        SeerrRequestedItemsDialog(
            state = uiState.seerrRequestedItems,
            onDismiss = viewModel::clearSeerrRequestedItems,
            onItemClick = { item ->
                viewModel.clearSeerrRequestedItems()
                onNavigateToRequestedItem(item.toBaseItem())
            }
        )
    }

}

@Composable
private fun SeerrConnectionCard(
    seerr: SeerrUiState,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
        border = BorderStroke(
            width = 1.dp,
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)
        ),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .background(
                        color = seerrAccentColor(seerr.status).copy(alpha = 0.12f),
                        shape = RoundedCornerShape(12.dp)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Rounded.Link,
                    contentDescription = null,
                    tint = seerrAccentColor(seerr.status),
                    modifier = Modifier.size(20.dp)
                )
            }

            Spacer(modifier = Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.settings_seerr),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = seerrSubtitle(seerr),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                )
            }

            SeerrStatusChip(seerr.status)
        }
    }
}

@Composable
private fun SectionLabel(title: String) {
    Text(
        text = title.uppercase(),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 4.dp)
    )
}

private fun SeerrRequestedItem.toBaseItem(): BaseItemDto {
    return BaseItemDto(
        id = localItemId ?: SeerrItemIds.detailId(tmdbId, mediaType),
        name = title,
        type = if (mediaType.equals("tv", ignoreCase = true)) "Series" else "Movie",
        providerIds = mapOf("tmdb" to tmdbId),
        productionYear = productionYear,
        imageUrl = posterUrl
    )
}