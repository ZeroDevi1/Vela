package com.vela.app.ui.screens.peach

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.vela.data.peach.PeachRepository
import com.vela.shared.R
import com.vela.shared.ui.components.common.bottomContentPadding
import com.vela.shared.ui.components.common.excludeBottom

/** 当前 Peach 服务器地址，用来把相对图片路由拼成完整地址。 */
@Composable
internal fun rememberPeachBaseUrl(): String? {
    val context = LocalContext.current
    var baseUrl by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        baseUrl = runCatching { PeachRepository.getInstance(context).baseUrl() }.getOrNull()
    }
    return baseUrl
}

internal fun peachUrl(baseUrl: String?, path: String?): String? {
    if (baseUrl == null || path.isNullOrBlank()) return null
    if (path.startsWith("http://") || path.startsWith("https://")) return path
    return "$baseUrl/${path.trimStart('/')}"
}

/** 分页加载的列表状态：首屏加载、触底续页、出错保留已加载内容。 */
internal class PeachPager<T>(
    val items: SnapshotStateList<T>,
    private val loadingState: androidx.compose.runtime.MutableState<Boolean>,
    private val endState: androidx.compose.runtime.MutableState<Boolean>,
    private val errorState: androidx.compose.runtime.MutableState<Throwable?>,
    private val requestState: androidx.compose.runtime.MutableState<Int>
) {
    val loading: Boolean get() = loadingState.value
    val ended: Boolean get() = endState.value
    val error: Throwable? get() = errorState.value
    fun loadMore() {
        if (!loadingState.value && !endState.value) requestState.value += 1
    }
}

@Composable
internal fun <T> rememberPeachPager(
    key: Any?,
    load: suspend PeachRepository.(offset: Int) -> Pair<List<T>, Boolean>
): PeachPager<T> {
    val context = LocalContext.current
    val repository = remember { PeachRepository.getInstance(context) }
    val items = remember(key) { mutableStateListOf<T>() }
    val loading = remember(key) { mutableStateOf(false) }
    val ended = remember(key) { mutableStateOf(false) }
    val error = remember(key) { mutableStateOf<Throwable?>(null) }
    val request = remember(key) { mutableStateOf(0) }
    LaunchedEffect(key, request.value) {
        if (ended.value) return@LaunchedEffect
        loading.value = true
        runCatching { repository.load(items.size) }
            .onSuccess { (page, more) ->
                items.addAll(page)
                ended.value = !more || page.isEmpty()
                error.value = null
            }
            .onFailure { error.value = it }
        loading.value = false
    }
    return remember(key) { PeachPager(items, loading, ended, error, request) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PeachGridScaffold(
    title: String,
    onBack: () -> Unit,
    minCellWidth: androidx.compose.ui.unit.Dp = 110.dp,
    actions: @Composable () -> Unit = {},
    onReachEnd: (() -> Unit)? = null,
    content: LazyGridScope.() -> Unit
) {
    val gridState = rememberLazyGridState()
    val reachedEnd by remember {
        derivedStateOf {
            val info = gridState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: 0
            info.totalItemsCount > 0 && last >= info.totalItemsCount - 6
        }
    }
    LaunchedEffect(reachedEnd) { if (reachedEnd) onReachEnd?.invoke() }
    Scaffold(
        containerColor = Color.Black,
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(title, color = Color.White, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.cd_back_button), tint = Color.White)
                    }
                },
                actions = { actions() },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Black)
            )
        }
    ) { padding ->
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minCellWidth),
            state = gridState,
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .padding(padding.excludeBottom()),
            contentPadding = PaddingValues(
                start = 12.dp,
                end = 12.dp,
                top = 8.dp,
                bottom = padding.bottomContentPadding().calculateBottomPadding() + 24.dp
            ),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            content = content
        )
    }
}

/** 网格底部的加载状态与错误提示。 */
internal fun <T> LazyGridScope.peachPagerFooter(pager: PeachPager<T>, @androidx.annotation.StringRes emptyText: Int) {
    item(span = { GridItemSpan(maxLineSpan) }) {
        Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
            when {
                pager.loading -> CircularProgressIndicator()
                pager.error != null && pager.items.isEmpty() -> PeachErrorCard(pager.error!!, onRetry = null)
                pager.items.isEmpty() && pager.ended -> Text(stringResource(emptyText), color = Color.White.copy(alpha = 0.6f))
            }
        }
    }
}

internal fun LazyGridScope.fullWidth(content: @Composable () -> Unit) {
    item(span = { GridItemSpan(maxLineSpan) }) { content() }
}

/** 作品卡片：Peach 封面多为横版，统一按 4:3 取景。 */
@Composable
internal fun PeachMediaCard(
    title: String,
    subtitle: String?,
    imageUrl: String?,
    aspectRatio: Float = 4f / 3f,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        AsyncImage(
            model = imageUrl,
            contentDescription = title,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(aspectRatio)
                .clip(RoundedCornerShape(10.dp))
                .background(Color(0xFF1A1A1A))
        )
        Text(title, color = Color.White, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (!subtitle.isNullOrBlank()) {
            Text(subtitle, color = Color.White.copy(alpha = 0.55f), style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** 实体卡片：圆形头像加名字与作品数。 */
@Composable
internal fun PeachEntityCard(name: String, count: Int?, imageUrl: String?, round: Boolean = true, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(if (round) 0.8f else 1f)
                .aspectRatio(1f)
                .clip(if (round) CircleShape else RoundedCornerShape(10.dp))
                .background(Color(0xFF1F1F1F)),
            contentAlignment = Alignment.Center
        ) {
            Text(name.take(1), color = Color.White.copy(alpha = 0.5f), style = MaterialTheme.typography.headlineSmall)
            if (imageUrl != null) {
                AsyncImage(
                    model = imageUrl,
                    contentDescription = name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
        Text(name, color = Color.White, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (count != null) {
            Text(count.toString(), color = Color.White.copy(alpha = 0.55f), style = MaterialTheme.typography.labelSmall)
        }
    }
}
