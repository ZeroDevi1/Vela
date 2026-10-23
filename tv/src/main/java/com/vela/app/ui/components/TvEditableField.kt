package com.vela.app.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.dp

/**
 * 输入框的 TV 交互：遥控器经过时只停在外框上、不弹键盘，按确认键才进入编辑并弹出键盘。
 *
 * TextField 一获得焦点就会请求软键盘（Compose foundation 1.12 的旧版 TextField 不遵守
 * `KeyboardOptions.showKeyboardOnFocus`），所以编辑前让它不可聚焦，焦点只落在外框；
 * 编辑中焦点移走（方向键、IME「下一项」）后退回未编辑状态。
 * 输入框是单行的，上下键在框内没有用途却会被 TextField 吞掉，这里改为直接移动焦点。
 *
 * @param enabled false 时外框不可点击，不会进入编辑。
 * @param field 输入框本体，必须把传入的 Modifier 用在 TextField 上。
 */
@Composable
fun TvEditableField(
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    field: @Composable (Modifier) -> Unit
) {
    var editing by remember { mutableStateOf(false) }
    // 本轮编辑中输入框是否真正拿到过焦点；用来区分"还没聚焦"和"聚焦后又离开"
    var fieldFocused by remember { mutableStateOf(false) }
    val fieldFocus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current

    LaunchedEffect(editing) {
        if (!editing) return@LaunchedEffect
        // 等 focusProperties 以 canFocus = true 重组后再请求焦点
        withFrameNanos { }
        runCatching { fieldFocus.requestFocus() }
    }

    Box(
        modifier = modifier
            .tvFocusHighlight(RoundedCornerShape(12.dp))
            // 编辑中外框让出焦点，方向键从输入框直接移到相邻控件，而不是先落回外框
            .focusProperties { canFocus = !editing }
            .clickable(
                enabled = enabled,
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) { editing = true }
    ) {
        field(
            Modifier
                .focusRequester(fieldFocus)
                .focusProperties { canFocus = editing }
                .onPreviewKeyEvent { event ->
                    val direction = when (event.key) {
                        Key.DirectionUp -> FocusDirection.Up
                        Key.DirectionDown -> FocusDirection.Down
                        else -> null
                    }
                    if (direction != null && event.type == KeyEventType.KeyDown) {
                        focusManager.moveFocus(direction)
                        true
                    } else {
                        false
                    }
                }
                .onFocusChanged { state ->
                    if (state.isFocused) {
                        fieldFocused = true
                    } else if (fieldFocused) {
                        fieldFocused = false
                        editing = false
                    }
                }
        )
    }
}
