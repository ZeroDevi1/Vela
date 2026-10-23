package com.vela.app.ui.screens.dashboard

import android.os.SystemClock
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.vela.app.ui.components.TvFocusRestoreScope
import com.vela.app.ui.screens.dashboard.favorites.Favorites
import com.vela.app.ui.screens.dashboard.home.Dashboard
import com.vela.app.ui.screens.dashboard.media.Discover
import com.vela.app.ui.screens.dashboard.search.SearchContainer
import com.vela.app.ui.screens.dashboard.settings.Settings
import com.vela.data.model.BaseItemDto
import com.vela.data.network.NetworkModule
import com.vela.shared.R

/** 两次返回键之间允许的最长间隔（ms），与常见 TV 应用的"再按一次退出"一致。 */
private const val EXIT_CONFIRM_WINDOW_MS = 2_000L

private fun dashboardEnterTransition(): EnterTransition {
    return fadeIn(animationSpec = tween(durationMillis = 220, easing = FastOutSlowInEasing))
}

private fun dashboardExitTransition(): ExitTransition {
    return fadeOut(animationSpec = tween(durationMillis = 160, easing = LinearOutSlowInEasing))
}

@Composable
fun DashboardContainer(
    onLogout: () -> Unit = {},
    onNavigateToDetail: (BaseItemDto) -> Unit = {},
    onNavigateToMergedDetail: (BaseItemDto) -> Unit = onNavigateToDetail,
    onNavigateToViewAll: (String, String?, String) -> Unit = { _, _, _ -> },
    onNavigateToPlayer: (String) -> Unit = {},
    onNavigateToPlayerSettings: () -> Unit = {},
    onNavigateToInterfaceSettings: () -> Unit = {},
    onNavigateToDownloads: () -> Unit = {},
    onNavigateToCacheSettings: () -> Unit = {},
    onNavigateToAbout: () -> Unit = {},
    onAddServer: () -> Unit = {},
    onAddUser: (serverUrl: String, serverName: String?) -> Unit = { _, _ -> }
) {
    val navController = rememberNavController()
    val dashboardFocus = rememberTvDashboardFocusState()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route ?: DashboardDestination.Home.route
    var railHasFocus by remember { mutableStateOf(false) }

    val context = LocalContext.current
    val appContext = remember(context) { context.applicationContext }
    val networkAvailabilityFlow = remember(appContext) {
        NetworkModule.observeNetworkAvailability(appContext)
    }
    val isNetworkAvailable by networkAvailabilityFlow.collectAsStateWithLifecycle(
        initialValue = NetworkModule.isInternetAvailable(appContext)
    )
    val destinations = remember(isNetworkAvailable) {
        if (isNetworkAvailable) onlineDestinations() else offlineDestinations()
    }

    val navigateToDestination: (DashboardDestination) -> Unit = { destination ->
        if (destination.route != currentRoute) {
            navController.navigate(destination.route) {
                popUpTo(navController.graph.startDestinationId) {
                    saveState = true
                }
                launchSingleTop = true
                restoreState = true
            }
        }
    }

    LaunchedEffect(isNetworkAvailable, currentRoute) {
        val allowedRoutes = destinations.map(DashboardDestination::route).toSet()
        if (!allowedRoutes.contains(currentRoute)) {
            navigateToDestination(DashboardDestination.Home)
        }
    }

    // 遥控器返回键：内容区 → 侧边栏 → 首页 → 再按一次退出。
    // 回调按注册先后决定优先级（后注册的先处理），所以分三层：
    // 1. 这里的回调在 NavHost 之前注册，优先级最低，只在首页（NavHost 无可出栈项）生效；
    // 2. NavHost 自带的出栈：非首页标签时把侧边栏上的返回交给它回到首页；
    // 3. 各标签内容开头的 tabBackToRail 比 NavHost 晚、比页面自身的 BackHandler
    //    （搜索键盘、奖项二级列表）早注册，焦点在内容区时先回侧边栏。
    val activity = LocalActivity.current
    val exitHint = stringResource(R.string.tv_press_back_again_to_exit)
    var lastExitBackAt by remember { mutableStateOf(0L) }
    val focusRail: () -> Boolean = {
        runCatching {
            dashboardFocus.sidebar.requestFocus()
            true
        }.getOrDefault(false)
    }
    val tabBackToRail: @Composable () -> Unit = {
        BackHandler(enabled = !railHasFocus) {
            if (!focusRail()) navigateToDestination(DashboardDestination.Home)
        }
    }

    BackHandler {
        when {
            !railHasFocus -> {
                if (!focusRail()) activity?.finish()
            }
            currentRoute != DashboardDestination.Home.route -> {
                navigateToDestination(DashboardDestination.Home)
            }
            else -> {
                val now = SystemClock.uptimeMillis()
                if (now - lastExitBackAt <= EXIT_CONFIRM_WINDOW_MS) {
                    activity?.finish()
                } else {
                    lastExitBackAt = now
                    Toast.makeText(context, exitHint, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    // 侧边栏获得焦点时切换了路由（如返回键回到首页），entryFocusRequester 已跟随新选中项，
    // 等一帧让它挂上后把焦点移过去，避免焦点停在旧标签上
    LaunchedEffect(currentRoute) {
        if (!railHasFocus) return@LaunchedEffect
        withFrameNanos { }
        focusRail()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0A0A0A))
    ) {
        val railSlotWidth by animateDpAsState(
            targetValue = if (railHasFocus) 130.dp else 48.dp,
            label = "dashboard_rail_slot_width"
        )

        Box(
            modifier = Modifier
                .fillMaxSize()
        ) {
            NavHost(
                navController = navController,
                startDestination = DashboardDestination.Home.route,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(start = railSlotWidth)
            ) {
                composable(
                    route = DashboardDestination.Home.route,
                    enterTransition = { dashboardEnterTransition() },
                    exitTransition = { dashboardExitTransition() }
                ) {
                    TvFocusRestoreScope(fallback = dashboardFocus.homeContent) {
                        Dashboard(
                            onLogout = onLogout,
                            onNavigateToDetail = onNavigateToDetail,
                            onNavigateToViewAll = onNavigateToViewAll,
                            onNavigateToPlayer = onNavigateToPlayer,
                            onAddServer = onAddServer,
                            onAddUser = onAddUser,
                            isTabActive = true,
                            sidebarFocusRequester = dashboardFocus.sidebar,
                            headerFocusRequester = dashboardFocus.homeHeader,
                            headerEndFocusRequester = dashboardFocus.homeHeaderEnd,
                            contentFocusRequester = dashboardFocus.homeContent,
                            onHomeReturnTargetChanged = dashboardFocus::updateHomeReturnFocus
                        )
                    }
                }

                composable(
                    route = DashboardDestination.Library.route,
                    enterTransition = { dashboardEnterTransition() },
                    exitTransition = { dashboardExitTransition() }
                ) {
                    TvFocusRestoreScope {
                        tabBackToRail()
                        Discover(
                            onItemClick = onNavigateToDetail,
                            onWatchedItemClick = onNavigateToMergedDetail,
                            onNavigateToViewAll = { contentType, parentId, title ->
                                onNavigateToViewAll(contentType.name, parentId, title)
                            }
                        )
                    }
                }

                composable(
                    route = DashboardDestination.Search.route,
                    enterTransition = { dashboardEnterTransition() },
                    exitTransition = { dashboardExitTransition() }
                ) {
                    TvFocusRestoreScope {
                        tabBackToRail()
                        SearchContainer(
                            onNavigateToDetail = onNavigateToDetail,
                            onCancel = { navigateToDestination(DashboardDestination.Home) }
                        )
                    }
                }

                composable(
                    route = DashboardDestination.Favorites.route,
                    enterTransition = { dashboardEnterTransition() },
                    exitTransition = { dashboardExitTransition() }
                ) {
                    TvFocusRestoreScope {
                        tabBackToRail()
                        Favorites(onItemClick = onNavigateToDetail)
                    }
                }

                composable(
                    route = DashboardDestination.Settings.route,
                    enterTransition = { dashboardEnterTransition() },
                    exitTransition = { dashboardExitTransition() }
                ) {
                    TvFocusRestoreScope {
                        tabBackToRail()
                        Settings(
                            onLogout = onLogout,
                            onNavigateToPlayerSettings = onNavigateToPlayerSettings,
                            onNavigateToInterfaceSettings = onNavigateToInterfaceSettings,
                            onNavigateToDownloads = onNavigateToDownloads,
                            onNavigateToCacheSettings = onNavigateToCacheSettings,
                            onNavigateToAbout = onNavigateToAbout,
                            onAddServer = onAddServer,
                            onAddUser = onAddUser
                        )
                    }
                }
            }

            Box(
                modifier = Modifier
                    .width(railSlotWidth)
                    .fillMaxHeight()
            ) {
                TvSidebarRail(
                    destinations = destinations,
                    currentRoute = currentRoute,
                    entryFocusRequester = dashboardFocus.sidebar,
                    onMoveRight = {
                        if (currentRoute == DashboardDestination.Home.route) {
                            val restored = dashboardFocus.requestHomeReturnFocus()
                            if (!restored) {
                                val headerRestored = runCatching {
                                    dashboardFocus.homeHeader.requestFocus()
                                    true
                                }.getOrDefault(false)
                                if (!headerRestored) {
                                    runCatching { dashboardFocus.homeContent.requestFocus() }
                                }
                            }
                            true
                        } else {
                            false
                        }
                    },
                    onDestinationSelected = navigateToDestination,
                    onRailFocusChanged = { railHasFocus = it },
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .windowInsetsPadding(WindowInsets.statusBars)
                        .windowInsetsPadding(WindowInsets.navigationBars)
                        .padding(start = 8.dp, top = 112.dp)
                )
            }
        }
    }
}