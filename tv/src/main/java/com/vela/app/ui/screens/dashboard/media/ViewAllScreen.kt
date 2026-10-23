package com.vela.app.ui.screens.dashboard.media

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vela.app.ui.components.TvFocusRestoreScope
import com.vela.app.util.text.TitleIndex
import com.vela.data.model.BaseItemDto
import com.vela.data.repository.MediaRepository
import com.vela.data.repository.MediaRepositoryProvider
import com.vela.shared.R
import com.vela.shared.playback.UserDataRefreshSignals
import kotlinx.coroutines.launch

/**
 * TV「查看全部 / 媒体库」页，布局参照 Emby TV：顶部页签与时钟，左侧首字符索引，中间海报网格，
 * 右侧排序 / 筛选工具栏，右下角「当前位置 | 总数」。遥控器方向键在这几个区域之间自然移动。
 */
@Composable
fun ViewAllScreen(
    contentType: ContentType,
    parentId: String? = null,
    title: String = "",
    genreId: String? = null,
    onItemClick: (BaseItemDto) -> Unit,
    viewModel: ViewAllViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val items by viewModel.items.collectAsStateWithLifecycle()
    val userDataRefreshEvent by UserDataRefreshSignals.refreshEvent.collectAsState()
    val context = LocalContext.current
    val mediaRepository = remember { MediaRepositoryProvider.getInstance(context) }
    val scope = rememberCoroutineScope()

    val tab = uiState.browseTab
    val isWatchedList = parentId == WATCHED_VIEW_ALL_PARENT_ID
    val genreOverview = tab == LibraryBrowseTab.GENRES && uiState.drillTitle == null
    val landscape = tab.usesLandscapeCards() || contentType == ContentType.EPISODES || genreOverview
    val showToolbar = tab.supportsSort() && !isWatchedList && !genreOverview
    val nameIndexed = viewModel.isNameIndexed(uiState)
    val displayItems = remember(items) { items.distinctBy(::viewAllItemKey) }

    // 每个页签 / 层级各自的滚动位置；从详情页返回时由 rememberSaveable 恢复
    val gridState = rememberSaveable(tab, uiState.drillTitle, saver = LazyGridState.Saver) { LazyGridState() }
    var focusedIndex by remember(tab, uiState.drillTitle) { mutableStateOf<Int?>(null) }
    // 在索引栏上选中的跳转目标；网格获得焦点后清空
    var railTarget by remember(tab, uiState.drillTitle) { mutableStateOf<Int?>(null) }
    // 需要主动获得焦点的网格位置（从索引栏按右键进入网格）
    var focusTarget by remember { mutableStateOf<Int?>(null) }
    val focusTargetRequester = remember { FocusRequester() }
    val firstItemRequester = remember { FocusRequester() }
    var initialFocusDone by rememberSaveable { mutableStateOf(false) }

    val firstIndexByHead = remember(displayItems, nameIndexed) {
        if (!nameIndexed) emptyMap() else LinkedHashMap<String, Int>().apply {
            displayItems.forEachIndexed { index, item -> putIfAbsent(TitleIndex.headOf(item.indexTitle()), index) }
        }
    }
    val heads = remember(firstIndexByHead) { firstIndexByHead.keys.toList() }
    val activeHead by remember(displayItems, nameIndexed, gridState) {
        derivedStateOf {
            if (!nameIndexed) null
            else displayItems.getOrNull(railTarget ?: focusedIndex ?: gridState.firstVisibleItemIndex)
                ?.let { TitleIndex.headOf(it.indexTitle()) }
        }
    }

    var showSortDialog by remember { mutableStateOf(false) }
    var showGenreDialog by remember { mutableStateOf(false) }

    LaunchedEffect(contentType, parentId, genreId) {
        viewModel.ensureItemsLoaded(contentType, parentId, genreId)
    }

    // 只响应进入页面之后的播放状态变化（如看完一集返回），进入前的旧信号不触发重复加载
    val initialRefreshEvent = remember { userDataRefreshEvent }
    LaunchedEffect(userDataRefreshEvent) {
        if (userDataRefreshEvent != initialRefreshEvent && tab.supportsSort()) viewModel.refresh()
    }

    // 排序或筛选变化后回到列表顶部：网格按 key 保持滚动位置，否则会停在旧焦点条目重排后的位置；
    // 旧的焦点序号与跳转目标也随之失效
    val listSignature = listOf(
        uiState.sortBy,
        uiState.sortOrder,
        uiState.unplayedOnly,
        uiState.selectedGenres.sorted().joinToString("|")
    ).joinToString("::")
    var appliedListSignature by rememberSaveable { mutableStateOf(listSignature) }
    LaunchedEffect(listSignature) {
        if (appliedListSignature == listSignature) return@LaunchedEffect
        appliedListSignature = listSignature
        focusedIndex = null
        railTarget = null
        gridState.scrollToItem(0)
    }

    // 首次进入时焦点落在第一个条目上；之后切换页签焦点留在页签行
    LaunchedEffect(displayItems.isNotEmpty()) {
        if (initialFocusDone || displayItems.isEmpty()) return@LaunchedEffect
        withFrameNanos { }
        runCatching { firstItemRequester.requestFocus() }
        initialFocusDone = true
    }

    // 滚动接近末尾时加载下一页（名称排序为全量加载，不分页）
    val latestState by rememberUpdatedState(uiState)
    val latestCount by rememberUpdatedState(displayItems.size)
    LaunchedEffect(gridState) {
        snapshotFlow { gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }
            .collect { lastVisible ->
                if (latestState.hasMorePages && !latestState.isLoading && lastVisible >= latestCount - 12) {
                    viewModel.loadMoreItems()
                }
            }
    }

    BackHandler(enabled = uiState.canGoBack) { viewModel.popBrowseLevel() }

    fun openItem(item: BaseItemDto) {
        when {
            genreOverview -> viewModel.openGenre(item)
            item.opensAsFolder() -> viewModel.openFolder(item)
            else -> onItemClick(item)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF101012))
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // 顶栏：媒体库显示页签，其他入口显示标题；右侧时钟
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 40.dp, end = 40.dp, top = 24.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (uiState.isLibrary) {
                    LibraryTabRow(
                        tabs = uiState.tabs,
                        selected = tab,
                        contentType = contentType,
                        onSelect = viewModel::setBrowseTab,
                        modifier = Modifier.weight(1f)
                    )
                } else {
                    Text(
                        text = title.ifBlank { stringResource(R.string.view_all_title) },
                        color = Color.White,
                        fontSize = 24.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f).padding(start = 14.dp)
                    )
                }
                TvClock(modifier = Modifier.padding(start = 24.dp))
            }
            uiState.drillTitle?.let { drillTitle ->
                Text(
                    text = "‹ $drillTitle",
                    color = Color.White.copy(alpha = 0.7f),
                    fontSize = 16.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 54.dp, bottom = 4.dp)
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(top = 8.dp)
            ) {
                if (heads.isNotEmpty()) {
                    TitleIndexRail(
                        heads = heads,
                        activeHead = activeHead,
                        onSelect = { head ->
                            val index = firstIndexByHead[head] ?: return@TitleIndexRail
                            railTarget = index
                            scope.launch { gridState.scrollToItem(index) }
                        },
                        onEnterGrid = {
                            val visible = gridState.layoutInfo.visibleItemsInfo.map { it.index }
                            focusTarget = railTarget?.takeIf { it in visible } ?: gridState.firstVisibleItemIndex
                            scope.launch {
                                // 等目标卡片挂上 focusRequester 再请求焦点
                                withFrameNanos { }
                                runCatching { focusTargetRequester.requestFocus() }
                            }
                        },
                        modifier = Modifier
                            .fillMaxHeight()
                            .padding(start = 12.dp)
                    )
                } else {
                    Spacer(Modifier.width(48.dp))
                }

                Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                    TvFocusRestoreScope {
                        LibraryContent(
                            uiState = uiState,
                            items = displayItems,
                            tab = tab,
                            landscape = landscape,
                            showWatchedState = !genreOverview,
                            gridState = gridState,
                            mediaRepository = mediaRepository,
                            focusRequesterFor = { index ->
                                when (index) {
                                    focusTarget -> focusTargetRequester
                                    0 -> firstItemRequester
                                    else -> null
                                }
                            },
                            onItemFocused = { index ->
                                focusedIndex = index
                                railTarget = null
                            },
                            onItemClick = ::openItem,
                            onRetry = viewModel::refresh
                        )
                    }
                }

                if (showToolbar) {
                    LibraryToolbar(
                        unplayedOnly = uiState.unplayedOnly,
                        genreFilterActive = uiState.selectedGenres.isNotEmpty(),
                        onSort = { showSortDialog = true },
                        onToggleUnplayed = viewModel::toggleUnplayedOnly,
                        onFilter = { showGenreDialog = true },
                        modifier = Modifier.padding(start = 8.dp, end = 16.dp, top = 8.dp)
                    )
                } else {
                    Spacer(Modifier.width(40.dp))
                }
            }
        }

        if (displayItems.isNotEmpty() && tab != LibraryBrowseTab.RECOMMENDED) {
            Text(
                text = "${(railTarget ?: focusedIndex ?: 0) + 1} | ${maxOf(uiState.totalItems, displayItems.size)}",
                color = Color.White.copy(alpha = 0.6f),
                fontSize = 14.sp,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 28.dp, bottom = 14.dp)
            )
        }
    }

    if (showSortDialog) {
        SortDialog(
            sortBy = uiState.sortBy,
            sortOrder = uiState.sortOrder,
            onSelect = { sortBy, sortOrder ->
                viewModel.setSort(sortBy, sortOrder)
                showSortDialog = false
            },
            onDismiss = { showSortDialog = false }
        )
    }
    if (showGenreDialog) {
        GenreFilterDialog(
            loadGenres = viewModel::loadGenreNames,
            selected = uiState.selectedGenres,
            onToggle = viewModel::toggleGenreFilter,
            onClear = viewModel::clearGenreFilters,
            onDismiss = { showGenreDialog = false }
        )
    }
}

/** 中间内容区：按加载状态与页签显示骨架、错误、空状态、推荐行或条目网格。 */
@Composable
private fun LibraryContent(
    uiState: ViewAllUiState,
    items: List<BaseItemDto>,
    tab: LibraryBrowseTab,
    landscape: Boolean,
    showWatchedState: Boolean,
    gridState: LazyGridState,
    mediaRepository: MediaRepository,
    focusRequesterFor: (Int) -> FocusRequester?,
    onItemFocused: (Int) -> Unit,
    onItemClick: (BaseItemDto) -> Unit,
    onRetry: () -> Unit
) {
    val minCellWidth = if (landscape) LANDSCAPE_MIN_WIDTH else POSTER_MIN_WIDTH
    when {
        uiState.isLoading && items.isEmpty() -> LazyVerticalGrid(
            columns = GridCells.Adaptive(minCellWidth),
            contentPadding = GridPadding,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
            userScrollEnabled = false,
            modifier = Modifier.fillMaxSize()
        ) {
            items(18) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(if (landscape) 16f / 9f else 2f / 3f)
                        .background(Color.White.copy(alpha = 0.06f), RoundedCornerShape(6.dp))
                )
            }
        }

        uiState.error != null && items.isEmpty() -> CenteredMessage(
            title = stringResource(R.string.view_all_error_title),
            message = uiState.error,
            actionLabel = stringResource(R.string.try_again),
            onAction = onRetry
        )

        items.isEmpty() -> CenteredMessage(
            title = stringResource(R.string.view_all_empty_title),
            message = stringResource(R.string.view_all_empty_message)
        )

        tab == LibraryBrowseTab.RECOMMENDED -> RecommendationRows(
            sections = uiState.recommendationSections,
            mediaRepository = mediaRepository,
            onItemClick = onItemClick
        )

        else -> Box(Modifier.fillMaxSize()) {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minCellWidth),
                state = gridState,
                contentPadding = GridPadding,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalArrangement = Arrangement.spacedBy(24.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                itemsIndexed(items, key = { _, item -> viewAllItemKey(item) }) { index, item ->
                    LibraryGridCard(
                        item = item,
                        mediaRepository = mediaRepository,
                        landscape = landscape,
                        showWatchedState = showWatchedState,
                        focusRequester = focusRequesterFor(index),
                        onFocused = { onItemFocused(index) },
                        onClick = { onItemClick(item) }
                    )
                }
            }
            if (uiState.isLoading) {
                CircularProgressIndicator(
                    color = LibraryAccent,
                    strokeWidth = 3.dp,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 16.dp)
                        .size(28.dp)
                )
            }
        }
    }
}

/** 推荐页签：每个推荐理由一行横向海报。 */
@Composable
private fun RecommendationRows(
    sections: List<LibraryRecommendationSection>,
    mediaRepository: MediaRepository,
    onItemClick: (BaseItemDto) -> Unit
) {
    LazyColumn(
        contentPadding = PaddingValues(bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        items(sections) { section ->
            val titleRes = recommendationTitleRes(section.recommendationType)
            val sectionTitle = if (titleRes == R.string.library_tab_recommended) {
                stringResource(titleRes)
            } else {
                stringResource(titleRes, section.baselineName.orEmpty())
            }
            Column {
                Text(
                    text = sectionTitle,
                    color = Color.White,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(start = 16.dp, bottom = 10.dp)
                )
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    items(section.items, key = ::viewAllItemKey) { item ->
                        LibraryGridCard(
                            item = item,
                            mediaRepository = mediaRepository,
                            landscape = false,
                            onClick = { onItemClick(item) },
                            modifier = Modifier.width(POSTER_MIN_WIDTH)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CenteredMessage(
    title: String,
    message: String,
    actionLabel: String? = null,
    onAction: () -> Unit = {}
) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(32.dp)) {
            Text(text = title, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            Text(
                text = message,
                color = Color.White.copy(alpha = 0.7f),
                fontSize = 14.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 8.dp)
            )
            if (actionLabel != null) {
                Button(
                    onClick = onAction,
                    modifier = Modifier.padding(top = 24.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = LibraryAccent),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(actionLabel, color = Color.White, fontWeight = FontWeight.Medium)
                }
            }
        }
    }
}

/** 海报最小宽度：1080p 电视（约 960dp 宽）扣除索引栏与工具栏后一行 6 张，与 Emby TV 密度一致。 */
private val POSTER_MIN_WIDTH = 120.dp
/** 横版卡片（单集、流派）最小宽度：一行 3–4 张。 */
private val LANDSCAPE_MIN_WIDTH = 200.dp
/** 留出卡片聚焦放大与描边的空间，避免被网格边缘裁掉。 */
private val GridPadding = PaddingValues(start = 12.dp, top = 12.dp, end = 12.dp, bottom = 48.dp)

private fun viewAllItemKey(item: BaseItemDto): String =
    item.id ?: "${item.name}_${item.type}_${item.seriesId}_${item.indexNumber ?: 0}"
