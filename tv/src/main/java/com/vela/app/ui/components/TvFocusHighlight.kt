package com.vela.app.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp

/**
 * 遥控器焦点高亮：获得焦点时铺浅白底并描白边，与详情页操作按钮的焦点样式一致。
 *
 * 必须放在 `clickable` / `toggleable` / `focusable` 之前，`onFocusChanged` 才能观察到其后的焦点目标。
 * 判断用 `hasFocus`，行内子控件（Slider、默认值按钮等）获得焦点时整行同样高亮。
 *
 * @param shape 高亮底色与描边的形状，同时裁剪其后的点击水波纹。
 */
fun Modifier.tvFocusHighlight(shape: Shape = RoundedCornerShape(12.dp)): Modifier = composed {
    var focused by remember { mutableStateOf(false) }
    val fill by animateColorAsState(
        targetValue = if (focused) Color.White.copy(alpha = 0.14f) else Color.Transparent,
        label = "tv_focus_fill"
    )
    val stroke by animateColorAsState(
        targetValue = if (focused) Color.White else Color.Transparent,
        label = "tv_focus_stroke"
    )
    onFocusChanged { focused = it.hasFocus }
        .border(width = 2.dp, color = stroke, shape = shape)
        .background(color = fill, shape = shape)
        .clip(shape)
}
