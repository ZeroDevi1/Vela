package com.vela.app.ui.screens.dashboard.settings

import com.vela.shared.ui.components.common.bottomContentPadding
import com.vela.shared.ui.components.common.excludeBottom
import android.os.Build
import android.content.Context
import android.content.Intent
import android.media.MediaCodecList
import android.net.Uri
import android.provider.Settings as AndroidSettings
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.*
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.vela.app.ui.screens.auth.ServerSwitchDialogsHost
import com.vela.app.ui.screens.auth.ServerSwitchViewModel
import com.vela.app.ui.screens.auth.rememberServerSwitchDialogsState
import com.vela.data.model.BaseItemDto
import com.vela.data.model.SeerrItemIds
import com.vela.data.model.SeerrRequestedItem
import com.vela.shared.R
import com.vela.data.network.sameServerUrl
import com.vela.data.preferences.NetworkPreferences
import com.vela.data.repository.AuthRepository
import android.hardware.display.DisplayManager
import android.media.MediaCodecInfo
import android.view.Display
import android.widget.Toast
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.vela.app.BuildConfig
import com.vela.app.download.DownloadRepositoryProvider
import com.vela.app.ui.components.privacy.PrivateServerSession
import com.vela.player.preferences.PlayerPreferences

/**
 * 设置首页。结构与 iOS 版统一（Vela 品牌）：分组卡片 + 单行条目，状态值显示在右侧。
 *
 * 分组：服务器 → 探索（仅应用首页，有对应页签时）→ 播放 → 连接 → 存储 → 通用 → 隐私 → 关于。
 * Android 独有的选项（语言、网络超时、仅 Wi-Fi 下载、Seerr）保留在对应分组中。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Settings(
    onLogout: () -> Unit = {},
    onNavigateToPlayerSettings: () -> Unit = {},
    onNavigateToSubtitleSettings: () -> Unit = {},
    onNavigateToInterfaceSettings: () -> Unit = {},
    onNavigateToConnections: () -> Unit = {},
    /** 未登录根页面传入以切到服务器 tab；为 null（已进入服务器）时原地弹出切换弹窗，不离开当前会话。 */
    onNavigateToServers: (() -> Unit)? = null,
    /** 应用首页传入：切换服务器成功后进入该服务器。为 null 时（已在服务器内）由会话切换流程刷新当前页面。 */
    onServerSwitched: (() -> Unit)? = null,
    /** 应用首页的「发现」「订阅」页签；为 null 时不显示「探索」分组。 */
    onNavigateToDiscover: (() -> Unit)? = null,
    onNavigateToCalendar: (() -> Unit)? = null,
    onNavigateToDownloads: () -> Unit = {},
    onNavigateToCacheSettings: () -> Unit = {},
    onNavigateToAbout: () -> Unit = {},
    onNavigateToServerInfo: () -> Unit = {},
    onNavigateToRequestedItem: (BaseItemDto) -> Unit = {},
    onAddServer: () -> Unit = {},
    onAddUser: (serverUrl: String, serverName: String?) -> Unit = { _, _ -> },
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val viewModel: SettingsViewModel = viewModel { SettingsViewModel(context) }
    val serverSwitchViewModel: ServerSwitchViewModel = viewModel {
        ServerSwitchViewModel(context.applicationContext as android.app.Application)
    }
    val serversViewModel: ServersViewModel = viewModel {
        ServersViewModel(context.applicationContext as android.app.Application)
    }
    val uiState by viewModel.uiState.collectAsState()
    val serverSwitchUiState by serverSwitchViewModel.uiState.collectAsState()
    val serversUiState by serversViewModel.uiState.collectAsState()
    val capabilities = remember(context) { detectVideoCapabilities(context) }
    val downloads by remember(context) {
        DownloadRepositoryProvider.getInstance(context).observeTrackedDownloads()
    }.collectAsState()
    val playerPreferences = remember(context) { PlayerPreferences(context) }
    var playerEngine by remember { mutableStateOf(playerPreferences.getPlayerEngine()) }
    var hasUnlockedPrivateServer by remember { mutableStateOf(PrivateServerSession.hasUnlocked()) }
    val listState = rememberLazyListState()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val serverSwitchDialogsState = rememberServerSwitchDialogsState()
    var configuringServerId by remember { mutableStateOf<String?>(null) }

    var showNetworkDialog by remember { mutableStateOf(false) }
    var editingNetworkTimeout by remember { mutableStateOf<NetworkTimeoutField?>(null) }

    // 从播放设置返回、或解锁私密服务器后回到本页时刷新这些非响应式的状态。
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                playerEngine = playerPreferences.getPlayerEngine()
                hasUnlockedPrivateServer = PrivateServerSession.hasUnlocked()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(uiState.activeServerId) {
        viewModel.reloadSeerrConnection()
    }

    /** 当前服务器打开配置；其他服务器切换过去（应用首页切换后进入该服务器）。 */
    fun onServerRowClick(server: AuthRepository.SavedServer) {
        when {
            server.id == uiState.activeServerId -> configuringServerId = server.id
            onServerSwitched != null -> serversViewModel.switchServer(server.id, onServerSwitched)
            else -> serverSwitchViewModel.switchServer(serverId = server.id, activeServerId = uiState.activeServerId)
        }
    }

    Scaffold(
        modifier = modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            LargeTopAppBar(
                title = { Text(stringResource(R.string.settings), fontWeight = FontWeight.Bold) },
                scrollBehavior = scrollBehavior,
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    scrolledContainerColor = MaterialTheme.colorScheme.background
                )
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding.excludeBottom()),
            state = listState,
            contentPadding = innerPadding.bottomContentPadding()
        ) {
            item {
                SettingsGroup(
                    title = stringResource(R.string.settings_server_label),
                    dividerInset = IconDividerInset,
                    rows = buildList {
                        uiState.savedServers.forEach { server ->
                            add {
                                ServerRow(
                                    server = server,
                                    isActive = server.id == uiState.activeServerId,
                                    onClick = { onServerRowClick(server) },
                                    onConfigure = { configuringServerId = server.id }
                                )
                            }
                        }
                        add {
                            SettingsRow(
                                title = stringResource(R.string.settings_add_server),
                                icon = Icons.Rounded.Add,
                                titleColor = MaterialTheme.colorScheme.primary,
                                showChevron = false,
                                onClick = onNavigateToServers ?: serverSwitchDialogsState::openServers
                            )
                        }
                        if (uiState.isAdministrator == true && onNavigateToServers == null) {
                            add {
                                SettingsRow(
                                    title = stringResource(R.string.admin_panel),
                                    icon = Icons.Rounded.AdminPanelSettings,
                                    onClick = onNavigateToServerInfo
                                )
                            }
                        }
                    }
                )
            }

            item {
                SettingsGroup(
                    title = stringResource(R.string.settings_section_explore),
                    dividerInset = IconDividerInset,
                    rows = buildList {
                        onNavigateToDiscover?.let { open ->
                            add { SettingsRow(title = stringResource(R.string.catalog_discover), icon = Icons.Rounded.Explore, onClick = open) }
                        }
                        onNavigateToCalendar?.let { open ->
                            add { SettingsRow(title = stringResource(R.string.settings_subscription_calendar), icon = Icons.Rounded.CalendarMonth, onClick = open) }
                        }
                    }
                )
            }

            item {
                SettingsGroup(
                    title = stringResource(R.string.settings_section_playback),
                    rows = listOf(
                        { SettingsRow(title = stringResource(R.string.player_settings_title), onClick = onNavigateToPlayerSettings) },
                        { SettingsRow(title = stringResource(R.string.subtitle_settings_title), onClick = onNavigateToSubtitleSettings) },
                        { SettingsRow(title = stringResource(R.string.settings_library_config), onClick = onNavigateToInterfaceSettings) },
                        { SettingsRow(title = stringResource(R.string.player_settings_player_engine), value = playerEngine) },
                        { SettingsRow(title = stringResource(R.string.settings_dolby_vision), value = supportText(capabilities.dolbyVision)) },
                        { SettingsRow(title = stringResource(R.string.settings_hdr10), value = supportText(capabilities.hdr10)) },
                        { SettingsRow(title = stringResource(R.string.settings_av1_hardware), value = supportText(capabilities.av1Hardware)) }
                    )
                )
            }

            item {
                // MoviePilot / Trakt / Seerr 的登录与状态都在「连接」页，与 iOS 的发现 / 订阅 / 同步分组对应。
                SettingsGroup(
                    title = stringResource(R.string.settings_connections),
                    rows = listOf(
                        { SettingsRow(title = "MoviePilot", onClick = onNavigateToConnections) },
                        { SettingsRow(title = "Trakt", onClick = onNavigateToConnections) },
                        { SettingsRow(title = stringResource(R.string.settings_seerr), onClick = onNavigateToConnections) }
                    )
                )
            }

            item {
                SettingsGroup(
                    title = stringResource(R.string.settings_section_storage),
                    rows = listOf(
                        {
                            SettingsRow(
                                title = stringResource(R.string.downloads),
                                value = stringResource(R.string.settings_downloads_count, downloads.count { it.isOfflineAvailable }),
                                onClick = onNavigateToDownloads
                            )
                        },
                        {
                            SettingsRow(
                                title = stringResource(R.string.settings_wifi_only_downloads),
                                trailing = {
                                    Switch(
                                        checked = uiState.wifiOnlyDownloads,
                                        onCheckedChange = { viewModel.setWifiOnlyDownloads(it) }
                                    )
                                }
                            )
                        },
                        { SettingsRow(title = stringResource(R.string.settings_cache), onClick = onNavigateToCacheSettings) }
                    )
                )
            }

            item {
                SettingsGroup(
                    title = stringResource(R.string.settings_general),
                    rows = listOf(
                        {
                            SettingsRow(
                                title = stringResource(R.string.settings_language),
                                value = stringResource(R.string.settings_auto),
                                onClick = { openAppLanguageSettings(context) }
                            )
                        },
                        { SettingsRow(title = stringResource(R.string.settings_network), onClick = { showNetworkDialog = true }) }
                    )
                )
            }

            item {
                SettingsGroup(
                    title = stringResource(R.string.settings_section_privacy),
                    dividerInset = IconDividerInset,
                    rows = listOf {
                        SettingsRow(
                            title = stringResource(R.string.settings_lock_private_servers),
                            icon = Icons.Rounded.Lock,
                            titleColor = MaterialTheme.colorScheme.primary,
                            showChevron = false,
                            enabled = hasUnlockedPrivateServer,
                            onClick = {
                                PrivateServerSession.lockAll()
                                hasUnlockedPrivateServer = false
                                Toast.makeText(context, R.string.settings_private_servers_locked, Toast.LENGTH_SHORT).show()
                            }
                        )
                    }
                )
            }

            item {
                SettingsGroup(
                    title = null,
                    footer = stringResource(R.string.settings_footer_license),
                    rows = listOf(
                        { SettingsRow(title = stringResource(R.string.settings_version), value = BuildConfig.VERSION_NAME) },
                        { SettingsRow(title = stringResource(R.string.about_title), onClick = onNavigateToAbout) },
                        {
                            SettingsRow(
                                title = stringResource(R.string.settings_project_home),
                                titleColor = MaterialTheme.colorScheme.primary,
                                showChevron = false,
                                onClick = { uriHandler.openUri(ProjectHomeUrl) }
                            )
                        },
                        {
                            SettingsRow(
                                title = stringResource(R.string.logout),
                                titleColor = MaterialTheme.colorScheme.error,
                                showChevron = false,
                                onClick = { viewModel.logout(onLogout) }
                            )
                        }
                    )
                )
            }
            item { Spacer(modifier = Modifier.height(24.dp)) }
        }
    }

    uiState.error?.let { error ->
        LaunchedEffect(error) {
            viewModel.clearError()
        }
    }

    if (showNetworkDialog) {
        NetworkSettingsDialog(
            requestTimeoutMs = uiState.requestTimeoutMs,
            connectionTimeoutMs = uiState.connectionTimeoutMs,
            socketTimeoutMs = uiState.socketTimeoutMs,
            onDismiss = { showNetworkDialog = false },
            onSelectField = { field ->
                showNetworkDialog = false
                editingNetworkTimeout = field
            }
        )
    }

    editingNetworkTimeout?.let { field ->
        val initialValue = when (field) {
            NetworkTimeoutField.REQUEST -> uiState.requestTimeoutMs
            NetworkTimeoutField.CONNECTION -> uiState.connectionTimeoutMs
            NetworkTimeoutField.SOCKET -> uiState.socketTimeoutMs
        }
        TimeoutValueDialog(
            field = field,
            initialValue = initialValue,
            onDismiss = { editingNetworkTimeout = null },
            onSave = { value ->
                when (field) {
                    NetworkTimeoutField.REQUEST -> viewModel.setRequestTimeoutMs(value)
                    NetworkTimeoutField.CONNECTION -> viewModel.setConnectionTimeoutMs(value)
                    NetworkTimeoutField.SOCKET -> viewModel.setSocketTimeoutMs(value)
                }
                editingNetworkTimeout = null
            }
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

    // 服务器配置（备注、私密、STRM、地址、线路），与 iOS 的服务器编辑页对应；与「服务器」页签共用同一实现。
    serversUiState.servers.firstOrNull { it.id == configuringServerId }?.let { server ->
        ServerConfigScreen(
            server = server,
            isBusy = serversUiState.isBusy,
            isSaving = serversUiState.isSavingConfig,
            onDismiss = { if (!serversUiState.isBusy) configuringServerId = null },
            onSave = { note, preferStrmOriginalPath, host, https, port, path, isPrivate ->
                serversViewModel.saveServerConfig(
                    serverId = server.id,
                    isPrivate = isPrivate,
                    note = note,
                    preferStrmOriginalPath = preferStrmOriginalPath,
                    host = host,
                    https = https,
                    port = port,
                    path = path,
                    onSuccess = { configuringServerId = null }
                )
            },
            onAddLine = { url, name -> serversViewModel.addServerLine(server.id, url, name) },
            onSwitchLine = { lineId -> serversViewModel.switchServerLine(server.id, lineId) },
            onRemoveLine = { lineId -> serversViewModel.removeServerLine(server.id, lineId) },
            onAutoSelect = { serversViewModel.autoSelectServerLine(server.id) },
            onSetAutoRoute = { enabled -> serversViewModel.setAutoRouteEnabled(server.id, enabled) }
        )
    }

    ServerSwitchDialogsHost(
        state = serverSwitchDialogsState,
        savedServers = uiState.savedServers,
        activeServerId = uiState.activeServerId,
        currentServerName = uiState.serverName,
        currentServerUrl = uiState.serverUrl,
        isSwitching = serverSwitchUiState.isBusy,
        onAddServer = onAddServer,
        onAddUser = onAddUser,
        onServerSelected = { server, dismissDialog ->
            serverSwitchViewModel.switchServer(
                serverId = server.id,
                activeServerId = uiState.activeServerId,
                onSwitchComplete = dismissDialog
            )
        },
        onRequestRemoveServer = serverSwitchDialogsState::requestRemoval,
        onRequestRemoveUser = serverSwitchDialogsState::requestRemoval,
        onRemoveServer = { serverId, onRemoveComplete ->
            serverSwitchViewModel.removeServer(
                serverId = serverId,
                onRemoveComplete = onRemoveComplete
            )
        }
    )
}

private const val ProjectHomeUrl = "https://github.com/ZeroDevi1/Vela"

/** 带图标的分组里，分隔线从文字起点开始（与 iOS 分组列表一致）。 */
private val IconDividerInset = 64.dp

private fun openAppLanguageSettings(context: Context) {
    val appLanguageIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        Intent(AndroidSettings.ACTION_APP_LOCALE_SETTINGS).apply {
            data = Uri.fromParts("package", context.packageName, null)
        }
    } else {
        Intent(AndroidSettings.ACTION_LOCALE_SETTINGS)
    }
    val fallbackIntent = Intent(AndroidSettings.ACTION_LOCALE_SETTINGS)
    val intentToLaunch = when {
        appLanguageIntent.resolveActivity(context.packageManager) != null -> appLanguageIntent
        fallbackIntent.resolveActivity(context.packageManager) != null -> fallbackIntent
        else -> return
    }
    context.startActivity(intentToLaunch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
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

// MARK: - 分组列表组件（与 iOS 设置页的分组样式一致）

/**
 * 一个分组：小号灰色标题 + 圆角卡片，条目之间插入分隔线。
 *
 * @param title 分组标题；null 时只留出间距。
 * @param footer 卡片下方的说明文字。
 * @param dividerInset 分隔线左缩进：无图标条目与文字对齐为 20dp，带图标的分组传 [IconDividerInset]。
 * @param rows 各条目；由分组统一插入分隔线，条目自身不关心位置。
 */
@Composable
private fun SettingsGroup(
    title: String?,
    rows: List<@Composable () -> Unit>,
    footer: String? = null,
    dividerInset: Dp = 20.dp
) {
    if (rows.isEmpty()) return
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
    ) {
        if (title != null) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 20.dp, top = 24.dp, bottom = 8.dp)
            )
        } else {
            Spacer(modifier = Modifier.height(28.dp))
        }
        Surface(
            shape = RoundedCornerShape(26.dp),
            color = MaterialTheme.colorScheme.surfaceContainer
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                rows.forEachIndexed { index, row ->
                    if (index > 0) {
                        HorizontalDivider(
                            modifier = Modifier.padding(start = dividerInset),
                            thickness = 0.5.dp,
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)
                        )
                    }
                    row()
                }
            }
        }
        if (footer != null) {
            Text(
                text = footer,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 8.dp)
            )
        }
    }
}

/**
 * 单行条目：可选图标 + 标题 + 右侧状态值 / 自定义控件 + 箭头。
 *
 * @param titleColor 操作类条目（添加服务器、项目主页、退出登录）用强调色或错误色，不显示箭头。
 * @param enabled 为 false 时变灰且不可点击（如没有已解锁的私密服务器时的「立即锁定」）。
 */
@Composable
private fun SettingsRow(
    title: String,
    icon: ImageVector? = null,
    subtitle: String? = null,
    value: String? = null,
    titleColor: Color = MaterialTheme.colorScheme.onSurface,
    enabled: Boolean = true,
    trailing: @Composable (() -> Unit)? = null,
    onClick: (() -> Unit)? = null,
    showChevron: Boolean = onClick != null
) {
    val alpha = if (enabled) 1f else 0.38f
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .then(if (onClick != null && enabled) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary.copy(alpha = alpha),
                modifier = Modifier.size(24.dp)
            )
            Spacer(modifier = Modifier.width(20.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = titleColor.copy(alpha = alpha),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        if (value != null) {
            Text(
                text = value,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                modifier = Modifier.padding(start = 12.dp)
            )
        }
        trailing?.invoke()
        if (showChevron && onClick != null) {
            Icon(
                imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                modifier = Modifier
                    .padding(start = 6.dp)
                    .size(20.dp)
            )
        }
    }
}

/** 服务器条目：私密服务器显示锁图标；当前服务器打勾，点按打开配置，其余点按切换。 */
@Composable
private fun ServerRow(
    server: AuthRepository.SavedServer,
    isActive: Boolean,
    onClick: () -> Unit,
    onConfigure: () -> Unit
) {
    val kind = when {
        server.serverTypeRaw.equals("EMBY", ignoreCase = true) -> "Emby"
        server.serverTypeRaw.equals("JELLYFIN", ignoreCase = true) -> "Jellyfin"
        else -> server.serverTypeRaw
    }
    val host = server.activeLine()?.url?.let { runCatching { Uri.parse(it).host }.getOrNull() } ?: server.serverUrl
    SettingsRow(
        title = server.displayName(),
        icon = if (server.isPrivate) Icons.Rounded.Lock else Icons.Rounded.Dns,
        subtitle = listOf(kind, server.username, host).filter { it.isNotBlank() }.joinToString(" · "),
        trailing = {
            if (isActive) {
                Icon(
                    imageVector = Icons.Rounded.Check,
                    contentDescription = stringResource(R.string.settings_switch_server),
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(22.dp)
                )
            }
            IconButton(onClick = onConfigure) {
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                )
            }
        },
        showChevron = false,
        onClick = onClick
    )
}

@Composable
private fun supportText(supported: Boolean): String =
    stringResource(if (supported) R.string.settings_supported else R.string.settings_unsupported)

/** 本机视频能力：杜比视界解码器、屏幕 HDR10、AV1 硬件解码。与 iOS 设置页的三项能力对应。 */
private data class DeviceVideoCapabilities(
    val dolbyVision: Boolean,
    val hdr10: Boolean,
    val av1Hardware: Boolean
)

private fun detectVideoCapabilities(context: Context): DeviceVideoCapabilities {
    val decoders = runCatching {
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter { !it.isEncoder }
    }.getOrDefault(emptyList())
    fun hasDecoder(mimeType: String, hardwareOnly: Boolean): Boolean = decoders.any { info ->
        info.supportedTypes.any { it.equals(mimeType, ignoreCase = true) } && (!hardwareOnly || info.isHardwareDecoder())
    }
    return DeviceVideoCapabilities(
        dolbyVision = hasDecoder("video/dolby-vision", hardwareOnly = false),
        hdr10 = Display.HdrCapabilities.HDR_TYPE_HDR10 in displayHdrTypes(context),
        av1Hardware = hasDecoder("video/av01", hardwareOnly = true)
    )
}

/** API 29 起系统直接给出；更早的版本按软件解码器的命名约定排除。 */
private fun MediaCodecInfo.isHardwareDecoder(): Boolean =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        isHardwareAccelerated
    } else {
        !name.startsWith("OMX.google.", ignoreCase = true) && !name.startsWith("c2.android.", ignoreCase = true)
    }

/** 主屏幕支持的 HDR 类型；`hdrCapabilities` 在 API 34 被标记废弃，但仍是 API 27 起通用的查询方式。 */
@Suppress("DEPRECATION")
private fun displayHdrTypes(context: Context): IntArray {
    val displayManager = context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager ?: return IntArray(0)
    val display = displayManager.getDisplay(Display.DEFAULT_DISPLAY) ?: return IntArray(0)
    return display.hdrCapabilities?.supportedHdrTypes ?: IntArray(0)
}

@Preview(showBackground = true)
@Composable
fun SettingsPreview() {
    Settings()
}

private enum class NetworkTimeoutField(val titleRes: Int) {
    REQUEST(R.string.settings_request_timeout),
    CONNECTION(R.string.settings_connection_timeout),
    SOCKET(R.string.settings_socket_timeout)
}

@Composable
private fun NetworkSettingsDialog(
    requestTimeoutMs: Int,
    connectionTimeoutMs: Int,
    socketTimeoutMs: Int,
    onDismiss: () -> Unit,
    onSelectField: (NetworkTimeoutField) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_network)) },
        text = {
            Column {
                NetworkDialogItem(
                    title = stringResource(R.string.settings_request_timeout),
                    value = "$requestTimeoutMs ms",
                    onClick = { onSelectField(NetworkTimeoutField.REQUEST) }
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                NetworkDialogItem(
                    title = stringResource(R.string.settings_connection_timeout),
                    value = "$connectionTimeoutMs ms",
                    onClick = { onSelectField(NetworkTimeoutField.CONNECTION) }
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                NetworkDialogItem(
                    title = stringResource(R.string.settings_socket_timeout),
                    value = "$socketTimeoutMs ms",
                    onClick = { onSelectField(NetworkTimeoutField.SOCKET) }
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.settings_close))
            }
        }
    )
}

@Composable
private fun NetworkDialogItem(
    title: String,
    value: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Icon(
            imageVector = Icons.Rounded.ChevronRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun TimeoutValueDialog(
    field: NetworkTimeoutField,
    initialValue: Int,
    onDismiss: () -> Unit,
    onSave: (Int) -> Unit
) {
    var textValue by remember(initialValue) { mutableStateOf(initialValue.toString()) }
    val parsedValue = textValue.toIntOrNull()
    val isValid = parsedValue != null &&
        parsedValue in NetworkPreferences.MIN_TIMEOUT_MS..NetworkPreferences.MAX_TIMEOUT_MS
    val hasValidationError = textValue.isNotBlank() && !isValid

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(field.titleRes)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = textValue,
                    onValueChange = { input ->
                        textValue = input.filter { it.isDigit() }.take(6)
                    },
                    label = { Text(stringResource(R.string.settings_milliseconds)) },
                    singleLine = true,
                    isError = hasValidationError,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )
                Text(
                    text = stringResource(
                        R.string.settings_allowed_range_ms,
                        NetworkPreferences.MIN_TIMEOUT_MS,
                        NetworkPreferences.MAX_TIMEOUT_MS
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (hasValidationError) {
                    Text(
                        text = stringResource(R.string.settings_enter_valid_milliseconds),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = isValid,
                onClick = { parsedValue?.let(onSave) }
            ) {
                Text(stringResource(R.string.settings_apply))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}
