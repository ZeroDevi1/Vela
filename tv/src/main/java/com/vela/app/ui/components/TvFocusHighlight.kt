package com.vela.app.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.ui.graphics.graphicsLayer
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

/** Emby TV 的强调绿：卡片焦点描边、选中状态。 */
val TvAccentGreen = Color(0xFF52B54B)

/**
 * 卡片式焦点（Emby TV 样式）：获得焦点时描 3dp 绿边并轻微放大，用于海报、剧照、演员头像等卡片。
 *
 * 与 [tvFocusHighlight] 一样须放在 `clickable` / `focusable` 之前；判断用 `hasFocus`，
 * 传给共享组件的 itemModifier 时内部子控件获得焦点同样生效。
 *
 * @param shape 描边形状，同时裁剪其后的点击水波纹。
 */
fun Modifier.tvCardFocus(shape: Shape = RoundedCornerShape(6.dp)): Modifier = composed {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) 1.06f else 1f, label = "tv_card_focus_scale")
    graphicsLayer {
        scaleX = scale
        scaleY = scale
    }
        .onFocusChanged { focused = it.hasFocus }
        .border(width = 3.dp, color = if (focused) TvAccentGreen else Color.Transparent, shape = shape)
        .clip(shape)
}
