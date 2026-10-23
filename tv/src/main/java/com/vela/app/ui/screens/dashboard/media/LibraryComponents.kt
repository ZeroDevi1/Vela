package com.vela.app.ui.screens.dashboard.media

import android.text.format.DateFormat
import androidx.annotation.StringRes
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.FilterAlt
import androidx.compose.material.icons.filled.SortByAlpha
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.zIndex
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.vela.app.ui.components.tvFocusHighlight
import com.vela.app.ui.components.tvRestorableFocus
import com.vela.data.model.BaseItemDto
import com.vela.data.repository.MediaRepository
import com.vela.shared.R
import com.vela.shared.util.image.DisableEmbyPosterEnhancers
import java.util.Date
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first

/** 选中页签、焦点描边、角标的强调色（Emby TV 的绿色）。 */
internal val LibraryAccent = Color(0xFF52B54B)

private val CardCorner = 6.dp
private val FocusBorderWidth = 3.dp

/** 获得焦点的卡片放大比例：经验值，放大后不压到相邻卡片（网格间距 16dp）。 */
private const val FocusedCardScale = 1.06f

/**
 * 顶部页签行：选中页签为绿色；焦点停留 [TAB_SELECT_DELAY_MS] 后切换内容（与 Emby TV 一致，
 * 遥控器左右扫过页签时不会每一个都触发加载），确认键立即切换。
 */
@Composable
internal fun LibraryTabRow(
    tabs: List<LibraryBrowseTab>,
    selected: LibraryBrowseTab,
    contentType: ContentType,
    onSelect: (LibraryBrowseTab) -> Unit,
    modifier: Modifier = Modifier
) {
    var focusedTab by remember { mutableStateOf<LibraryBrowseTab?>(null) }
    LaunchedEffect(focusedTab) {
        val tab = focusedTab ?: return@LaunchedEffect
        delay(TAB_SELECT_DELAY_MS)
        onSelect(tab)
    }
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        tabs.forEach { tab ->
            val isSelected = tab == selected
            Box(
                modifier = Modifier
                    .onFocusChanged { state ->
                        if (state.isFocused) focusedTab = tab else if (focusedTab == tab) focusedTab = null
                    }
                    .tvFocusHighlight(RoundedCornerShape(8.dp))
                    .clickable { onSelect(tab) }
                    .padding(horizontal = 14.dp, vertical = 6.dp)
            ) {
                Text(
                    text = stringResource(tab.labelRes(contentType)),
                    color = if (isSelected) LibraryAccent else Color.White.copy(alpha = 0.85f),
                    fontSize = 22.sp,
                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal
                )
            }
        }
    }
}

private const val TAB_SELECT_DELAY_MS = 350L

/** 右上角时钟，按系统 12/24 小时制显示，每分钟整点刷新。 */
@Composable
internal fun TvClock(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(60_000L - now % 60_000L)
            now = System.currentTimeMillis()
        }
    }
    Text(
        text = DateFormat.getTimeFormat(context).format(Date(now)),
        color = Color.White.copy(alpha = 0.9f),
        fontSize = 26.sp,
        fontWeight = FontWeight.Light,
        modifier = modifier
    )
}

/**
 * 左侧首字符索引栏（Emby TV 样式）：竖排列出列表中出现过的首字符，确认键跳到该字符的第一个条目。
 *
 * @param heads 按列表顺序排列、去重后的首字符。
 * @param activeHead 当前位置所属首字符，绿色高亮并自动滚动到可见处。
 * @param onEnterGrid 在索引栏上按右键时调用，由页面把焦点交给网格中对应的条目。
 */
@Composable
internal fun TitleIndexRail(
    heads: List<String>,
    activeHead: String?,
    onSelect: (String) -> Unit,
    onEnterGrid: () -> Unit,
    modifier: Modifier = Modifier
) {
    val listState = rememberLazyListState()
    var railFocused by remember { mutableStateOf(false) }
    LaunchedEffect(activeHead, heads) {
        val index = heads.indexOf(activeHead)
        // 遥控器在索引栏内移动时由焦点自己滚动，不在这里抢
        if (index >= 0 && !railFocused) listState.animateScrollToItem((index - 3).coerceAtLeast(0))
    }
    LazyColumn(
        state = listState,
        modifier = modifier
            .width(36.dp)
            .onFocusChanged { railFocused = it.hasFocus }
            .onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionRight) {
                    onEnterGrid()
                    true
                } else false
            },
        horizontalAlignment = Alignment.CenterHorizontally,
        contentPadding = PaddingValues(vertical = 8.dp)
    ) {
        itemsIndexed(heads, key = { _, head -> head }) { _, head ->
            val isActive = head == activeHead
            Box(
                modifier = Modifier
                    .size(30.dp, 24.dp)
                    .tvFocusHighlight(RoundedCornerShape(6.dp))
                    .clickable { onSelect(head) },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = head,
                    color = if (isActive) LibraryAccent else Color.White.copy(alpha = 0.6f),
                    fontSize = 14.sp,
                    fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal
                )
            }
        }
    }
}

/** 右侧竖排工具栏：排序、仅未观看、流派筛选。开启中的筛选图标为绿色。 */
@Composable
internal fun LibraryToolbar(
    unplayedOnly: Boolean,
    genreFilterActive: Boolean,
    onSort: () -> Unit,
    onToggleUnplayed: () -> Unit,
    onFilter: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.width(48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        ToolbarButton(Icons.Filled.SortByAlpha, R.string.view_all_sort, active = false, onClick = onSort)
        ToolbarButton(Icons.Filled.VisibilityOff, R.string.tv_library_filter_unplayed, active = unplayedOnly, onClick = onToggleUnplayed)
        ToolbarButton(Icons.Filled.FilterAlt, R.string.view_all_genres, active = genreFilterActive, onClick = onFilter)
    }
}

@Composable
private fun ToolbarButton(
    icon: ImageVector,
    @StringRes labelRes: Int,
    active: Boolean,
    onClick: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    val background by animateColorAsState(if (focused) Color.White else Color.Transparent, label = "toolbar_bg")
    Box(
        modifier = Modifier
            .size(40.dp)
            .onFocusChanged { focused = it.isFocused }
            .clip(CircleShape)
            .background(background)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = stringResource(labelRes),
            tint = when {
                focused -> Color.Black
                active -> LibraryAccent
                else -> Color.White.copy(alpha = 0.8f)
            },
            modifier = Modifier.size(22.dp)
        )
    }
}

/**
 * 解析条目图片地址；单集在竖版卡片中用所属剧集的海报。
 * 返回 null 表示尚未解析或没有可用地址，调用方显示占位。
 */
@Composable
private fun rememberItemImageUrl(
    item: BaseItemDto,
    mediaRepository: MediaRepository,
    landscape: Boolean
): String? {
    val disableEnhancers = DisableEmbyPosterEnhancers()
    val itemId = item.id ?: return null
    val targetId = if (!landscape && item.type == "Episode" && !item.seriesId.isNullOrBlank()) item.seriesId!! else itemId
    val url by produceState<String?>(null, targetId, landscape, disableEnhancers) {
        value = runCatching {
            mediaRepository.getImageUrl(
                itemId = targetId,
                width = if (landscape) 480 else 300,
                height = if (landscape) 270 else 450,
                quality = 90,
                enableImageEnhancers = !disableEnhancers && item.type != "Episode"
            ).first()
        }.getOrNull()
    }
    return url
}

/**
 * Emby TV 样式的海报 / 横版卡片：图片下方是标题与「年份 · 分级」；获得焦点时放大并描绿边。
 *
 * @param focusRequester 页面需要主动把焦点交给这张卡片时传入（首个条目、索引跳转目标）。
 * @param onFocused 获得焦点时回调，页面据此更新右下角计数与索引高亮。
 */
@Composable
internal fun LibraryGridCard(
    item: BaseItemDto,
    mediaRepository: MediaRepository,
    landscape: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
    onFocused: () -> Unit = {},
    showWatchedState: Boolean = true
) {
    val context = LocalContext.current
    val imageUrl = rememberItemImageUrl(item, mediaRepository, landscape)
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) FocusedCardScale else 1f, label = "card_scale")
    val unknownTitle = stringResource(R.string.search_result_unknown_title)
    val title = if (landscape && item.type == "Episode") item.seriesName ?: item.name ?: unknownTitle
        else item.name ?: unknownTitle
    val subtitle = item.cardSubtitle()

    Column(modifier = modifier.fillMaxWidth().zIndex(if (focused) 1f else 0f)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(if (landscape) 16f / 9f else 2f / 3f)
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                }
                .then(focusRequester?.let { Modifier.focusRequester(it) } ?: Modifier)
                .then(item.id?.let { Modifier.tvRestorableFocus("item:$it") } ?: Modifier)
                .onFocusChanged { state ->
                    focused = state.isFocused
                    if (state.isFocused) onFocused()
                }
                .border(FocusBorderWidth, if (focused) LibraryAccent else Color.Transparent, RoundedCornerShape(CardCorner))
                .clip(RoundedCornerShape(CardCorner))
                .background(Brush.verticalGradient(listOf(Color(0xFF2A2A2E), Color(0xFF1A1A1D))))
                .clickable(onClick = onClick)
        ) {
            // 占位：图片缺失或加载失败时显示标题
            Text(
                text = title,
                color = Color.White.copy(alpha = 0.7f),
                fontSize = 14.sp,
                textAlign = TextAlign.Center,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.align(Alignment.Center).padding(8.dp)
            )
            if (imageUrl != null) {
                AsyncImage(
                    model = ImageRequest.Builder(context).data(imageUrl).crossfade(true).build(),
                    contentDescription = title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            }
            if (showWatchedState) {
                ItemStateBadge(item, Modifier.align(Alignment.TopEnd).padding(6.dp))
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = title,
            color = if (focused) Color.White else Color.White.copy(alpha = 0.9f),
            fontSize = 15.sp,
            fontWeight = if (focused) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
        if (subtitle.isNotEmpty()) {
            Text(
                text = subtitle,
                color = Color.White.copy(alpha = 0.55f),
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(top = 2.dp)
            )
        }
    }
}

/** 卡片副标题：单集为「S1E2 · 集名」，其余为「年份 · 分级」。 */
private fun BaseItemDto.cardSubtitle(): String {
    if (type == "Episode") {
        val code = listOfNotNull(
            parentIndexNumber?.let { "S$it" },
            indexNumber?.let { "E$it" }
        ).joinToString("")
        return listOf(code, name.orEmpty()).filter(String::isNotBlank).joinToString(" · ")
    }
    return listOfNotNull(productionYear?.toString(), officialRating?.takeIf(String::isNotBlank)).joinToString(" · ")
}

/** 右上角状态：剧集显示未看集数（绿色圆形），已看完的条目显示绿色对勾。 */
@Composable
private fun ItemStateBadge(item: BaseItemDto, modifier: Modifier) {
    val unplayed = item.userData?.unplayedItemCount
    when {
        item.type == "Series" && unplayed != null && unplayed > 0 -> Surface(
            modifier = modifier.heightIn(min = 22.dp),
            shape = CircleShape,
            color = LibraryAccent
        ) {
            Text(
                text = if (unplayed > 99) "99+" else unplayed.toString(),
                color = Color.White,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp)
            )
        }
        item.userData?.played == true || (item.type == "Series" && unplayed == 0) -> Surface(
            modifier = modifier.size(22.dp),
            shape = CircleShape,
            color = LibraryAccent
        ) {
            Icon(Icons.Filled.Check, contentDescription = null, tint = Color.White, modifier = Modifier.padding(3.dp))
        }
    }
}

/**
 * 排序方式弹窗：选中当前字段再按一次切换升降序，选其他字段使用该字段的默认顺序。
 * 打开时焦点落在当前字段上。
 */
@Composable
internal fun SortDialog(
    sortBy: String,
    sortOrder: String,
    onSelect: (sortBy: String, sortOrder: String) -> Unit,
    onDismiss: () -> Unit
) {
    val fields = remember { librarySortFields() }
    val selectedRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { selectedRequester.requestFocus() } }
    Dialog(onDismissRequest = onDismiss) {
        DialogPanel(title = stringResource(R.string.view_all_sort)) {
            fields.forEach { field ->
                val isSelected = field.sortBy == sortBy
                DialogRow(
                    label = stringResource(field.labelRes),
                    selected = isSelected,
                    trailingIcon = when {
                        !isSelected || !field.supportsOrder -> null
                        sortOrder == "Ascending" -> Icons.Filled.ArrowUpward
                        else -> Icons.Filled.ArrowDownward
                    },
                    modifier = if (isSelected) Modifier.focusRequester(selectedRequester) else Modifier,
                    onClick = {
                        val order = when {
                            !isSelected -> field.defaultOrder
                            !field.supportsOrder -> sortOrder
                            sortOrder == "Ascending" -> "Descending"
                            else -> "Ascending"
                        }
                        onSelect(field.sortBy, order)
                    }
                )
            }
        }
    }
}

/** 流派多选筛选弹窗；切换即生效，返回键关闭。 */
@Composable
internal fun GenreFilterDialog(
    loadGenres: suspend () -> List<String>,
    selected: Set<String>,
    onToggle: (String) -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit
) {
    val genres by produceState<List<String>?>(null) { value = loadGenres() }
    val firstRequester = remember { FocusRequester() }
    LaunchedEffect(genres) { if (genres != null) runCatching { firstRequester.requestFocus() } }
    Dialog(onDismissRequest = onDismiss) {
        DialogPanel(title = stringResource(R.string.view_all_genres)) {
            DialogRow(
                label = stringResource(R.string.view_all_clear_filters),
                selected = false,
                modifier = Modifier.focusRequester(firstRequester),
                onClick = onClear
            )
            val loaded = genres
            when {
                loaded == null -> Text(
                    text = stringResource(R.string.loading),
                    color = Color.White.copy(alpha = 0.6f),
                    modifier = Modifier.padding(16.dp)
                )
                loaded.isEmpty() -> Text(
                    text = stringResource(R.string.view_all_no_genres),
                    color = Color.White.copy(alpha = 0.6f),
                    modifier = Modifier.padding(16.dp)
                )
                else -> loaded.forEach { genre ->
                    DialogRow(
                        label = genre,
                        selected = genre in selected,
                        trailingIcon = if (genre in selected) Icons.Filled.Check else null,
                        onClick = { onToggle(genre) }
                    )
                }
            }
        }
    }
}

@Composable
private fun DialogPanel(title: String, content: @Composable () -> Unit) {
    Surface(
        color = Color(0xFF1E1E21),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.width(DialogWidth)
    ) {
        Column(modifier = Modifier.padding(vertical = 16.dp)) {
            Text(
                text = title,
                color = Color.White,
                fontSize = 20.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)
            )
            // 流派很多时超出屏幕高度，可滚动列会随焦点移动自动滚动
            Column(
                modifier = Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState())
            ) { content() }
        }
    }
}

@Composable
private fun DialogRow(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    trailingIcon: ImageVector? = null
) {
    var focused by remember { mutableStateOf(false) }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .onFocusChanged { focused = it.isFocused }
            .clip(RoundedCornerShape(8.dp))
            .background(if (focused) Color.White else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            color = when {
                focused -> Color.Black
                selected -> LibraryAccent
                else -> Color.White
            },
            fontSize = 16.sp,
            modifier = Modifier.weight(1f)
        )
        trailingIcon?.let {
            Icon(it, contentDescription = null, tint = if (focused) Color.Black else LibraryAccent, modifier = Modifier.size(18.dp))
        }
    }
}

private val DialogWidth: Dp = 360.dp
