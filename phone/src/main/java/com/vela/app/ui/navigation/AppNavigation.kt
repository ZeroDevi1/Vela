package com.vela.app.ui.navigation

import android.widget.Toast
import com.vela.app.ui.screens.dashboard.settings.PlayerSettingsPage
import androidx.activity.BackEventCompat
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.AnimatedContentScope
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation.NamedNavArgument
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.vela.app.ui.screens.dashboard.DashboardContainer
import com.vela.app.ui.screens.dashboard.home.CachedData
import com.vela.data.network.ServerLineSwitchReason
import com.vela.data.repository.AuthRepositoryProvider
import com.vela.data.repository.MediaRepositoryProvider
import com.vela.shared.R
import com.vela.app.ui.screens.auth.AuthScreen
import com.vela.app.ui.screens.detail.DetailScreenContainer
import com.vela.app.ui.screens.detail.PersonScreenContainer
import com.vela.app.ui.screens.dashboard.settings.DownloadsScreen
import com.vela.app.ui.screens.dashboard.settings.CacheSettingsScreen
import com.vela.app.ui.screens.dashboard.settings.ConnectionsSettingsScreen
import com.vela.app.ui.screens.home.AppHomeContainer
import com.vela.app.ui.screens.admin.ServerInfoScreen
import com.vela.app.ui.screens.dashboard.settings.AboutScreen
import com.vela.app.ui.screens.dashboard.settings.PlayerSettingsScreen
import com.vela.app.ui.screens.dashboard.settings.SubtitleSettingsScreen
import com.vela.app.ui.screens.dashboard.settings.InterfaceSettingsScreen
import com.vela.app.ui.activity.PlayerActivity
import com.vela.app.player.mpv.MpvWarmPool
import androidx.media3.common.util.UnstableApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException
import com.vela.data.model.BaseItemDto
import com.vela.data.model.isAudioItem
import com.vela.data.model.isBookItem
import com.vela.app.ui.screens.library.MediaLibraryKind
import com.vela.app.ui.screens.library.MediaLibraryScreen
import com.vela.app.ui.screens.music.MusicPlayback
import com.vela.app.ui.screens.music.MusicMiniPlayer

/**
 * 应用内预测性返回的手势状态。手势进行中，被返回的页面按 M3 规范跟手缩小、向手势侧平移、圆角渐显；
 * 松手提交后保持最后的形变，与 popExit 叠加继续退场，不会弹回原尺寸再缩小。
 *
 * HyperOS 有时只发开始/提交、不发进度：此时 [progress] 始终为 0，提交后照常完整播放 popExit。
 */
@Stable
private class NavBackPreview {
    /** 正在被返回的页面；null 表示没有进行中的返回手势。 */
    var entryId by mutableStateOf<String?>(null)

    /** 已缓动的手势进度，0..1。 */
    var progress by mutableFloatStateOf(0f)

    /** 手势起始边缘，取值见 [BackEventCompat.EDGE_LEFT] / [BackEventCompat.EDGE_RIGHT]。 */
    var swipeEdge by mutableIntStateOf(BackEventCompat.EDGE_LEFT)
}

/** M3 预测性返回：页面最多缩到 90%。 */
private const val BACK_PREVIEW_MIN_SCALE = 0.9f

/** 预测性返回的进度缓动，前段跟手更灵敏。 */
private val BackPreviewEasing = CubicBezierEasing(0.1f, 0.1f, 0f, 1f)

@Composable
private fun PredictiveBackScene(
    entry: NavBackStackEntry,
    backPreview: NavBackPreview,
    content: @Composable () -> Unit
) {
    // 页面离开组合（popExit 播完）时清掉手势状态，下一次返回从头开始。
    DisposableEffect(entry.id) {
        onDispose {
            if (backPreview.entryId == entry.id) {
                backPreview.entryId = null
                backPreview.progress = 0f
            }
        }
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer {
                // 只在绘制阶段读取手势状态，跟手过程不触发重组。
                val progress = if (backPreview.entryId == entry.id) backPreview.progress else 0f
                if (progress <= 0f) {
                    clip = false
                    return@graphicsLayer
                }
                val scale = 1f - (1f - BACK_PREVIEW_MIN_SCALE) * progress
                scaleX = scale
                scaleY = scale
                // 向手势侧平移，最大位移为宽度的 1/20 减 8dp（M3 规范）。
                val maxShift = (size.width / 20f - 8.dp.toPx()).coerceAtLeast(0f)
                val direction = if (backPreview.swipeEdge == BackEventCompat.EDGE_LEFT) 1f else -1f
                translationX = direction * maxShift * progress
                shape = RoundedCornerShape(32.dp * progress)
                clip = true
                shadowElevation = 12.dp.toPx() * progress
            }
    ) {
        content()
    }
}

private val LocalNavBackPreview = staticCompositionLocalOf { NavBackPreview() }

private fun NavGraphBuilder.scene(
    route: String,
    arguments: List<NamedNavArgument> = emptyList(),
    enterTransition: (
        AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition
    )? = null,
    exitTransition: (
        AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition
    )? = null,
    content: @Composable AnimatedContentScope.(NavBackStackEntry) -> Unit
) {
    composable(
        route = route,
        arguments = arguments,
        enterTransition = enterTransition,
        exitTransition = exitTransition,
        popEnterTransition = { NavTransitions.popEnter() },
        popExitTransition = { NavTransitions.popExit() }
    ) { entry ->
        val animatedScope = this
        PredictiveBackScene(entry = entry, backPreview = LocalNavBackPreview.current) {
            animatedScope.content(entry)
        }
    }
}

private fun NavController.enterDashboard() {
    navigate("dashboard") {
        launchSingleTop = true
        popUpTo("servers") { inclusive = false }
    }
}

private fun NavController.openServerPicker() {
    if (!popBackStack("servers", inclusive = false)) {
        navigate("servers") {
            launchSingleTop = true
        }
    }
}

private fun NavController.openViewAll(
    contentType: String,
    parentId: String?,
    title: String,
    searchTerm: String? = null,
    tag: String? = null,
    initialSort: String? = null
) {
    if (contentType in setOf("MUSIC", "BOOKS")) {
        navigate("media_library/$contentType?libraryId=${android.net.Uri.encode(parentId.orEmpty())}")
        return
    }
    val encodedTitle = java.net.URLEncoder.encode(title, "UTF-8")
    val params = buildList {
        when {
            contentType.contains("GENRE") && parentId != null -> add("genreId=$parentId")
            parentId != null -> add("parentId=$parentId")
        }
        add("title=$encodedTitle")
        searchTerm?.takeIf { it.isNotBlank() }?.let {
            add("searchTerm=${java.net.URLEncoder.encode(it, "UTF-8")}")
        }
        tag?.takeIf { it.isNotBlank() }?.let {
            add("tag=${java.net.URLEncoder.encode(it, "UTF-8")}")
        }
        initialSort?.let { add("initialSort=$it") }
    }
    navigate("viewall/$contentType?${params.joinToString("&")}")
}

private fun NavController.openMediaItem(item: BaseItemDto, mergeVersions: Boolean = false) {
    val id = item.id ?: return
    val kind = when {
        item.isBookItem() -> "BOOKS"
        item.isAudioItem() || item.type in setOf("MusicAlbum", "MusicArtist") -> "MUSIC"
        else -> null
    }
    if (kind != null) navigate("media_library/$kind?itemId=${android.net.Uri.encode(id)}")
    else navigate("detail/$id${if (mergeVersions) "?mergeVersions=true" else ""}")
}

@UnstableApi
@Composable
fun AppNavigation(openMusic: Boolean = false, onMusicOpened: () -> Unit = {}) {
    val navController = rememberNavController()
    val context = LocalContext.current
    val authRepository = remember(context) { AuthRepositoryProvider.getInstance(context) }
    val mediaRepository = remember(context) { MediaRepositoryProvider.getInstance(context) }

    LaunchedEffect(authRepository) {
        authRepository.lineSwitchEvents.collect { event ->
            mediaRepository.clearPersistedHomeSnapshot()
            CachedData.clearAllCache()
            val lineName = event.customName.ifBlank {
                context.getString(
                    if (event.isLan) R.string.settings_server_line_lan else R.string.settings_server_line_wan
                )
            }
            val message = context.getString(
                if (event.reason == ServerLineSwitchReason.FAILOVER) {
                    R.string.settings_server_line_switched_failover
                } else {
                    R.string.settings_server_line_switched_network
                },
                lineName
            )
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
    }

    LaunchedEffect(openMusic) {
        if (openMusic) {
            navController.navigate("media_library/MUSIC?nowPlaying=true") { launchSingleTop = true }
            onMusicOpened()
        }
    }

    val backPreview = remember { NavBackPreview() }
    CompositionLocalProvider(LocalNavBackPreview provides backPreview) {
    Column(modifier = Modifier.fillMaxSize()) {
        val musicState by MusicPlayback.state.collectAsState()
        val currentEntry by navController.currentBackStackEntryAsState()
        val canPopNav = currentEntry != null && navController.previousBackStackEntry != null
        SideEffect {
            // NavHost 自带的预测性返回在提交时按已拖动比例计算剩余时长，HyperOS 不发进度时会一帧结束；
            // 关掉它，由下面的 PredictiveBackHandler 自己做跟手预览，提交后 popBackStack 播完整 popExit。
            navController.enableOnBackPressed(false)
        }
        val backScope = rememberCoroutineScope()
        PredictiveBackHandler(enabled = canPopNav) { backEvents ->
            val entryId = navController.currentBackStackEntry?.id
            backPreview.entryId = entryId
            backPreview.progress = 0f
            try {
                backEvents.collect { event ->
                    backPreview.swipeEdge = event.swipeEdge
                    backPreview.progress = BackPreviewEasing.transform(event.progress)
                }
                navController.popBackStack()
            } catch (cancel: CancellationException) {
                // 手势取消：弹回原样。本协程已取消，回弹放到界面作用域里执行。
                val from = backPreview.progress
                backScope.launch {
                    animate(from, 0f, animationSpec = spring(stiffness = Spring.StiffnessMediumLow)) { value, _ ->
                        if (backPreview.entryId == entryId) backPreview.progress = value
                    }
                    if (backPreview.entryId == entryId) backPreview.entryId = null
                }
                throw cancel
            }
        }

    NavHost(
        navController = navController,
        startDestination = "servers",
        modifier = Modifier.weight(1f).fillMaxWidth(),
        enterTransition = { NavTransitions.enter() },
        exitTransition = { NavTransitions.exit() },
        popEnterTransition = { NavTransitions.popEnter() },
        popExitTransition = { NavTransitions.popExit() }
    ) {
            scene(
                "splash",
                enterTransition = { NavTransitions.enter() },
                exitTransition = {
                    if (targetState.destination.route == "server_connection") {
                        ExitTransition.None
                    } else {
                        NavTransitions.exit()
                    }
                }
            ) {
                AuthScreen(
                    preferSavedServers = true,
                    onAddServer = {
                        navController.navigate("server_connection") {
                            popUpTo("splash") { inclusive = true }
                            launchSingleTop = true
                        }
                    },
                    onAuthSuccess = {
                        navController.enterDashboard()
                    }
                )
            }

            scene(
                "auth",
                enterTransition = {
                    if (initialState.destination.route == "dashboard") {
                        EnterTransition.None
                    } else {
                        NavTransitions.enter()
                    }
                },
                exitTransition = {
                    if (targetState.destination.route == "server_connection") {
                        ExitTransition.None
                    } else {
                        NavTransitions.exit()
                    }
                }
            ) {
                AuthScreen(
                    preferSavedServers = true,
                    onAddServer = {
                        navController.navigate("server_connection") {
                            popUpTo("auth") { inclusive = true }
                            launchSingleTop = true
                        }
                    },
                    onAuthSuccess = {
                        navController.enterDashboard()
                    }
                )
            }

            scene(
                "server_connection",
                enterTransition = {
                    if (
                        initialState.destination.route == "auth" ||
                        initialState.destination.route == "splash"
                    ) {
                        EnterTransition.None
                    } else {
                        NavTransitions.enter()
                    }
                },
                exitTransition = { NavTransitions.exit() }
            ) {
                AuthScreen(
                    onAuthSuccess = {
                        navController.enterDashboard()
                    }
                )
            }

            scene(
                "add_user?serverUrl={serverUrl}&serverName={serverName}",
                arguments = listOf(
                    navArgument("serverUrl") {
                        type = NavType.StringType
                        defaultValue = ""
                    },
                    navArgument("serverName") {
                        type = NavType.StringType
                        nullable = true
                        defaultValue = null
                    }
                ),
                enterTransition = { NavTransitions.enter() },
                exitTransition = { NavTransitions.exit() }
            ) { backStackEntry ->
                val encodedServerUrl = backStackEntry.arguments?.getString("serverUrl").orEmpty()
                val serverUrl = runCatching {
                    java.net.URLDecoder.decode(encodedServerUrl, "UTF-8")
                }.getOrDefault(encodedServerUrl)
                val encodedServerName = backStackEntry.arguments?.getString("serverName")
                val serverName = encodedServerName?.let { encodedName ->
                    runCatching { java.net.URLDecoder.decode(encodedName, "UTF-8") }
                        .getOrDefault(encodedName)
                }?.takeIf { it.isNotBlank() }

                AuthScreen(
                    serverUrl = serverUrl.takeIf { it.isNotBlank() },
                    serverName = serverName,
                    startAtLogin = serverUrl.isNotBlank(),
                    onAuthSuccess = {
                        navController.enterDashboard()
                    }
                )
            }

            scene(
                "dashboard",
                enterTransition = { NavTransitions.enter() },
                exitTransition = {
                    if (targetState.destination.route == "auth") {
                        ExitTransition.None
                    } else {
                        NavTransitions.exit()
                    }
                }
            ) {
                LaunchedEffect(Unit) {
                    delay(750L)
                    MpvWarmPool.warmIfPreferred(context.applicationContext)
                }

                DashboardContainer(
                    onLogout = {
                        navController.openServerPicker()
                    },
                    onNavigateToPlayerSettings = { page ->
                        navController.navigate("player_settings/${page.route}")
                    },
                    onNavigateToSubtitleSettings = {
                        navController.navigate("subtitle_settings")
                    },
                    onNavigateToInterfaceSettings = {
                        navController.navigate("interface_settings")
                    },
                    onNavigateToConnections = {
                        navController.navigate("connections_settings")
                    },
                    onNavigateToServers = {
                        navController.openServerPicker()
                    },
                    onNavigateToDownloads = {
                        navController.navigate("downloads")
                    },
                    onNavigateToCacheSettings = {
                        navController.navigate("cache_settings")
                    },
                    onNavigateToAbout = {
                        navController.navigate("about")
                    },
                    onNavigateToServerInfo = {
                        navController.navigate("server_info")
                    },
                    onAddServer = {
                        navController.openServerPicker()
                    },
                    onAddUser = { serverUrl, serverName ->
                        val encodedServerUrl = java.net.URLEncoder.encode(serverUrl, "UTF-8")
                        val encodedServerName = java.net.URLEncoder.encode(serverName.orEmpty(), "UTF-8")
                        navController.navigate(
                            "add_user?serverUrl=$encodedServerUrl&serverName=$encodedServerName"
                        ) {
                            launchSingleTop = true
                        }
                    },
                    onNavigateToDetail = { item -> navController.openMediaItem(item) },
                    onNavigateToMergedDetail = { item -> navController.openMediaItem(item, mergeVersions = true) },
                    onNavigateToViewAll = { contentType, parentId, title ->
                        navController.openViewAll(contentType, parentId, title)
                    },
                    onNavigateToRecentlyAdded = { contentType, parentId, title ->
                        navController.openViewAll(contentType, parentId, title, initialSort = "DateCreated")
                    },
                    onNavigateToSearchCategory = { contentType, searchTerm, title ->
                        navController.openViewAll(
                            contentType = contentType,
                            parentId = null,
                            title = title,
                            searchTerm = searchTerm
                        )
                    },
                    onNavigateToPlayer = { itemId ->
                        PlayerActivity.start(context, itemId)
                    }
                )
            }

            scene(
                "media_library/{kind}?libraryId={libraryId}&itemId={itemId}&nowPlaying={nowPlaying}",
                arguments = listOf(
                    navArgument("kind") { type = NavType.StringType },
                    navArgument("libraryId") { type = NavType.StringType; nullable = true; defaultValue = null },
                    navArgument("itemId") { type = NavType.StringType; nullable = true; defaultValue = null },
                    navArgument("nowPlaying") { type = NavType.BoolType; defaultValue = false }
                )
            ) { entry ->
                MediaLibraryScreen(
                    kind = if (entry.arguments?.getString("kind") == "BOOKS") MediaLibraryKind.BOOKS else MediaLibraryKind.MUSIC,
                    libraryId = entry.arguments?.getString("libraryId")?.takeIf { it.isNotBlank() },
                    initialItemId = entry.arguments?.getString("itemId")?.takeIf { it.isNotBlank() },
                    showNowPlaying = entry.arguments?.getBoolean("nowPlaying") == true,
                    onBack = { navController.popBackStack() }
                )
            }

            scene(
                "player/{itemId}?fromStart={fromStart}",
                arguments = listOf(
                    navArgument("itemId") { type = NavType.StringType },
                    navArgument("fromStart") {
                        type = NavType.BoolType
                        defaultValue = false
                    }
                ),
                enterTransition = { NavTransitions.enter() },
                exitTransition = { NavTransitions.exit() }
            ) { backStackEntry ->
                val itemId = backStackEntry.arguments?.getString("itemId")
                val fromStart = backStackEntry.arguments?.getBoolean("fromStart") ?: false

                if (!itemId.isNullOrBlank()) {
                    val playerContext = LocalContext.current
                    LaunchedEffect(itemId, fromStart) {
                        PlayerActivity.start(
                            context = playerContext,
                            mediaId = itemId,
                            startFromBeginning = fromStart
                        )
                        navController.popBackStack()
                    }
                } else {
                    LaunchedEffect(Unit) {
                        navController.popBackStack()
                    }
                }
            }

            scene(
                "detail/{itemId}?mergeVersions={mergeVersions}",
                arguments = listOf(
                    navArgument("itemId") { type = NavType.StringType },
                    navArgument("mergeVersions") {
                        type = NavType.BoolType
                        defaultValue = false
                    }
                ),
                enterTransition = { NavTransitions.enter() },
                exitTransition = { NavTransitions.exit() }
            ) { backStackEntry ->
                val itemId = backStackEntry.arguments?.getString("itemId")
                val forceMergeVersions = backStackEntry.arguments?.getBoolean("mergeVersions") ?: false

                if (itemId != null) {
                    DetailScreenContainer(
                        itemId = itemId,
                        forceMergeVersions = forceMergeVersions,
                        onNavigateToDetail = { selectedItemId ->
                            if (selectedItemId != itemId) {
                                navController.navigate("detail/$selectedItemId")
                            }
                        },
                        onNavigateToPerson = { personId ->
                            if (personId != itemId) {
                                navController.navigate("person/$personId")
                            }
                        },
                        onNavigateToTag = { tag ->
                            navController.openViewAll(
                                contentType = "ALL",
                                parentId = null,
                                title = tag,
                                tag = tag
                            )
                        },
                        onBackPressed = {
                            navController.popBackStack()
                        }
                    )
                } else {
                    LaunchedEffect(Unit) {
                        navController.popBackStack()
                    }
                }
            }

            scene(
                "episode/{episodeId}",
                arguments = listOf(navArgument("episodeId") { type = NavType.StringType }),
                enterTransition = { NavTransitions.enter() },
                exitTransition = { NavTransitions.exit() }
            ) { backStackEntry ->
                val episodeId = backStackEntry.arguments?.getString("episodeId")

                if (episodeId != null) {
                    DetailScreenContainer(
                        itemId = episodeId,
                        onNavigateToDetail = { selectedItemId ->
                            if (selectedItemId != episodeId) {
                                navController.navigate("detail/$selectedItemId")
                            }
                        },
                        onNavigateToPerson = { personId ->
                            if (personId != episodeId) {
                                navController.navigate("person/$personId")
                            }
                        },
                        onNavigateToTag = { tag ->
                            navController.openViewAll(
                                contentType = "ALL",
                                parentId = null,
                                title = tag,
                                tag = tag
                            )
                        },
                        onBackPressed = {
                            navController.popBackStack()
                        }
                    )
                } else {
                    LaunchedEffect(Unit) {
                        navController.popBackStack()
                    }
                }
            }

            scene(
                "person/{personId}",
                arguments = listOf(navArgument("personId") { type = NavType.StringType }),
                enterTransition = { NavTransitions.enter() },
                exitTransition = { NavTransitions.exit() }
            ) { backStackEntry ->
                val personId = backStackEntry.arguments?.getString("personId")

                if (personId != null) {
                    PersonScreenContainer(
                        personId = personId,
                        onBackPressed = {
                            navController.popBackStack()
                        },
                        onItemClick = { selectedItemId ->
                            navController.navigate("detail/$selectedItemId")
                        },
                        onPlayItem = { itemId ->
                            PlayerActivity.start(context, itemId)
                        }
                    )
                } else {
                    LaunchedEffect(Unit) {
                        navController.popBackStack()
                    }
                }
            }

            scene(
                "viewall/{contentType}?parentId={parentId}&title={title}&genreId={genreId}&searchTerm={searchTerm}&tag={tag}&initialSort={initialSort}",
                arguments = listOf(
                    navArgument("contentType") { type = NavType.StringType },
                    navArgument("parentId") {
                        type = NavType.StringType
                        nullable = true
                        defaultValue = null
                    },
                    navArgument("title") {
                        type = NavType.StringType
                        defaultValue = "View All"
                    },
                    navArgument("genreId") {
                        type = NavType.StringType
                        nullable = true
                        defaultValue = null
                    },
                    navArgument("searchTerm") {
                        type = NavType.StringType
                        nullable = true
                        defaultValue = null
                    },
                    navArgument("tag") {
                        type = NavType.StringType
                        nullable = true
                        defaultValue = null
                    },
                    navArgument("initialSort") {
                        type = NavType.StringType
                        nullable = true
                        defaultValue = null
                    }
                ),
                enterTransition = { NavTransitions.enter() },
                exitTransition = { NavTransitions.exit() }
            ) { backStackEntry ->
                val contentTypeString = backStackEntry.arguments?.getString("contentType") ?: "ALL"
                val parentId = backStackEntry.arguments?.getString("parentId")
                val genreId = backStackEntry.arguments?.getString("genreId")
                val searchTerm = backStackEntry.arguments?.getString("searchTerm")?.let {
                    java.net.URLDecoder.decode(it, "UTF-8")
                }?.takeIf { it.isNotBlank() }
                val tag = backStackEntry.arguments?.getString("tag")?.let {
                    java.net.URLDecoder.decode(it, "UTF-8")
                }?.takeIf { it.isNotBlank() }
                val title = backStackEntry.arguments?.getString("title")?.let {
                    java.net.URLDecoder.decode(it, "UTF-8")
                } ?: "View All"

                val contentType = when (contentTypeString.uppercase()) {
                    "MOVIES" -> com.vela.app.ui.screens.dashboard.media.ContentType.MOVIES
                    "SERIES" -> com.vela.app.ui.screens.dashboard.media.ContentType.SERIES
                    "EPISODES" -> com.vela.app.ui.screens.dashboard.media.ContentType.EPISODES
                    "MOVIES_GENRE" -> com.vela.app.ui.screens.dashboard.media.ContentType.MOVIES_GENRE
                    "TVSHOWS_GENRE" -> com.vela.app.ui.screens.dashboard.media.ContentType.TVSHOWS_GENRE
                    "SEERR_STUDIO" -> com.vela.app.ui.screens.dashboard.media.ContentType.SEERR_STUDIO
                    "SEERR_NETWORK" -> com.vela.app.ui.screens.dashboard.media.ContentType.SEERR_NETWORK
                    "AWARD" -> com.vela.app.ui.screens.dashboard.media.ContentType.AWARD
                    else -> com.vela.app.ui.screens.dashboard.media.ContentType.ALL
                }

                com.vela.app.ui.screens.dashboard.media.ViewAllScreen(
                    contentType = contentType,
                    parentId = parentId,
                    genreId = genreId,
                    searchTerm = searchTerm,
                    tag = tag,
                    title = title,
                    initialSort = backStackEntry.arguments?.getString("initialSort"),
                    onBackPressed = { navController.popBackStack() },
                    onItemClick = { item ->
                        item.id?.let { itemId ->
                            val mergeVersions = parentId == com.vela.app.ui.screens.dashboard.media.WATCHED_VIEW_ALL_PARENT_ID
                            navController.openMediaItem(item, mergeVersions)
                        }
                    },
                    onPlayFromBeginning = { itemId ->
                        PlayerActivity.start(
                            context = context,
                            mediaId = itemId,
                            startFromBeginning = true
                        )
                    }
                )
            }

            scene(
                "player_settings/{page}",
                arguments = listOf(navArgument("page") { type = NavType.StringType }),
                enterTransition = { NavTransitions.enter() },
                exitTransition = { NavTransitions.exit() }
            ) { entry ->
                PlayerSettingsScreen(
                    page = PlayerSettingsPage.fromRoute(entry.arguments?.getString("page")),
                    onBackPressed = {
                        navController.popBackStack()
                    }
                )
            }

            scene(
                "subtitle_settings",
                enterTransition = { NavTransitions.enter() },
                exitTransition = { NavTransitions.exit() }
            ) {
                SubtitleSettingsScreen(
                    onBackPressed = {
                        navController.popBackStack()
                    }
                )
            }

            scene(
                "downloads",
                enterTransition = { NavTransitions.enter() },
                exitTransition = { NavTransitions.exit() }
            ) {
                DownloadsScreen(
                    onBackPressed = {
                        navController.popBackStack()
                    }
                )
            }

            scene(
                "interface_settings",
                enterTransition = { NavTransitions.enter() },
                exitTransition = { NavTransitions.exit() }
            ) {
                InterfaceSettingsScreen(
                    onBackPressed = {
                        navController.popBackStack()
                    }
                )
            }

            scene(
                "servers",
                enterTransition = { NavTransitions.enter() },
                exitTransition = { NavTransitions.exit() }
            ) {
                AppHomeContainer(
                    onServerSwitched = {
                        navController.enterDashboard()
                    },
                    onNavigateToDetail = { item -> navController.openMediaItem(item) },
                    onNavigateToViewAll = { contentType, parentId, title ->
                        navController.openViewAll(contentType, parentId, title)
                    },
                    onNavigateToPlayerSettings = { page ->
                        navController.navigate("player_settings/${page.route}")
                    },
                    onNavigateToSubtitleSettings = {
                        navController.navigate("subtitle_settings")
                    },
                    onNavigateToInterfaceSettings = {
                        navController.navigate("interface_settings")
                    },
                    onNavigateToConnections = {
                        navController.navigate("connections_settings")
                    },
                    onNavigateToDownloads = {
                        navController.navigate("downloads")
                    },
                    onNavigateToCacheSettings = {
                        navController.navigate("cache_settings")
                    },
                    onNavigateToAbout = {
                        navController.navigate("about")
                    },
                    onNavigateToServerInfo = {
                        navController.navigate("server_info")
                    },
                    onAddUser = { serverUrl, serverName ->
                        val encodedServerUrl = java.net.URLEncoder.encode(serverUrl, "UTF-8")
                        val encodedServerName = java.net.URLEncoder.encode(serverName.orEmpty(), "UTF-8")
                        navController.navigate(
                            "add_user?serverUrl=$encodedServerUrl&serverName=$encodedServerName"
                        ) {
                            launchSingleTop = true
                        }
                    }
                )
            }

            scene(
                "connections_settings",
                enterTransition = { NavTransitions.enter() },
                exitTransition = { NavTransitions.exit() }
            ) {
                ConnectionsSettingsScreen(
                    onBackPressed = {
                        navController.popBackStack()
                    },
                    onNavigateToRequestedItem = { item ->
                        navController.openMediaItem(item)
                    }
                )
            }

            scene(
                "cache_settings",
                enterTransition = { NavTransitions.enter() },
                exitTransition = { NavTransitions.exit() }
            ) {
                CacheSettingsScreen(
                    onBackPressed = {
                        navController.popBackStack()
                    }
                )
            }

            scene(
                "about",
                enterTransition = { NavTransitions.enter() },
                exitTransition = { NavTransitions.exit() }
            ) {
                AboutScreen(
                    onBackPressed = {
                        navController.popBackStack()
                    }
                )
            }

            scene(
                "server_info",
                enterTransition = { NavTransitions.enter() },
                exitTransition = { NavTransitions.exit() }
            ) {
                ServerInfoScreen(
                    onBackPressed = {
                        navController.popBackStack()
                    }
                )
            }
        }
        if (musicState.item != null && currentEntry?.destination?.route?.startsWith("media_library/") != true) {
            MusicMiniPlayer(
                onOpen = { navController.navigate("media_library/MUSIC?nowPlaying=true") { launchSingleTop = true } },
                modifier = Modifier.navigationBarsPadding()
            )
        }
    }
    }
}
