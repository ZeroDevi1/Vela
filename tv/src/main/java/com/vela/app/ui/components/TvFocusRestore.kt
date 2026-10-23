package com.vela.app.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 等待目标条目重新组合出来的最长时间（ms）。列表数据通常来自内存缓存，1.5s 内足够；
 * 超时说明条目已不在列表里（如继续观看已更新），此时退回 fallback。
 */
private const val RESTORE_WAIT_MS = 1_500L

/**
 * 一个页面内的遥控器焦点恢复：记住最后获得焦点的条目 key，页面重建（从详情、子页面返回）后把焦点还给它。
 *
 * Compose 在页面重建后会把焦点交给第一个可聚焦元素（顶栏或侧边栏），TV 上表现为"返回后焦点跑丢"。
 * key 存在 rememberSaveable 里，随所在导航条目保存；FocusRequester 每次重建重新登记。
 * 生命周期与 [TvFocusRestoreScope] 的组合一致，只在主线程使用。
 */
@Stable
class TvFocusRestorer internal constructor(private val savedKey: MutableState<String?>) {
    /** 当前已组合的可恢复条目；同一 key 出现多次时保留先登记的那个。 */
    internal val requesters = mutableStateMapOf<String, FocusRequester>()

    internal val lastFocusedKey: String? get() = savedKey.value

    internal fun onFocused(key: String) {
        savedKey.value = key
    }
}

private val LocalTvFocusRestorer = staticCompositionLocalOf<TvFocusRestorer?> { null }

/**
 * 为 [content] 提供焦点恢复：进入时若记录过焦点条目，就在它组合出来后请求焦点；
 * 没有记录时请求 [fallback]（如首页第一个内容卡片），为 null 则不动焦点。
 *
 * @param fallback 首次进入时的默认焦点；须挂在 content 内的某个条目上，条目出现后才会请求。
 */
@Composable
fun TvFocusRestoreScope(
    fallback: FocusRequester? = null,
    content: @Composable () -> Unit
) {
    val savedKey = rememberSaveable { mutableStateOf<String?>(null) }
    val restorer = remember { TvFocusRestorer(savedKey) }

    LaunchedEffect(restorer) {
        val wanted = restorer.lastFocusedKey
        // 等目标条目（或任意条目，代表内容已加载）登记出来
        val appeared = withTimeoutOrNull(RESTORE_WAIT_MS) {
            snapshotFlow { restorer.requesters.keys.toSet() }
                .first { keys -> if (wanted != null) wanted in keys else keys.isNotEmpty() }
        }
        // 再等一帧，让条目的焦点节点挂到布局树上
        withFrameNanos { }
        val target = wanted?.let { restorer.requesters[it] }
        when {
            target != null -> runCatching { target.requestFocus() }
            appeared != null || wanted != null -> fallback?.let { runCatching { it.requestFocus() } }
        }
    }

    CompositionLocalProvider(LocalTvFocusRestorer provides restorer, content = content)
}

/**
 * 把当前元素登记为可恢复焦点的条目；不在 [TvFocusRestoreScope] 内时什么也不做。
 * 需放在 clickable / focusable 之前。
 *
 * @param key 在所在页面内稳定且唯一的标识（如条目 id）。
 */
fun Modifier.tvRestorableFocus(key: String): Modifier = composed {
    val restorer = LocalTvFocusRestorer.current ?: return@composed Modifier
    val requester = remember { FocusRequester() }
    DisposableEffect(restorer, key) {
        if (key !in restorer.requesters) restorer.requesters[key] = requester
        onDispose {
            if (restorer.requesters[key] === requester) restorer.requesters.remove(key)
        }
    }
    focusRequester(requester)
        .onFocusChanged { if (it.isFocused) restorer.onFocused(key) }
}
