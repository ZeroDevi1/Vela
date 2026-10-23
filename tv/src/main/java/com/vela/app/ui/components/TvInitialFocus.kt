package com.vela.app.ui.components

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged

/**
 * 页面打开后把遥控器焦点放到该容器内的第一个可聚焦项，免得用户先按一次方向键才看到焦点。
 *
 * 挂在内容容器（LazyColumn 等）上：FocusRequester 落在不可聚焦的容器时，Compose 会把焦点交给第一个子焦点目标。
 * 只在容器内尚无焦点时请求，不会抢走用户已经移动到的位置。
 *
 * @param key 内容异步加载的页面传入"内容是否就绪"之类的值；值变化后若仍无焦点会再请求一次。
 */
fun Modifier.tvInitialFocus(key: Any? = Unit): Modifier = composed {
    val requester = remember { FocusRequester() }
    val state = remember { FocusFlag() }
    LaunchedEffect(key) {
        // 等一帧让列表完成首次测量、子项挂上焦点节点
        withFrameNanos { }
        if (!state.hasFocus) runCatching { requester.requestFocus() }
    }
    onFocusChanged { state.hasFocus = it.hasFocus }
        .focusRequester(requester)
}

/** 只供 LaunchedEffect 读取的焦点标记，不需要触发重组。 */
private class FocusFlag {
    var hasFocus: Boolean = false
}
