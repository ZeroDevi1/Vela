package com.vela.shared.ui.components.common

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Scaffold 内容的外层内边距：保留顶部（app bar）与左右，去掉底部。
 *
 * 底部是系统导航条（小白条）的 inset；若作为外层 padding，滚动内容会在小白条上方被截断，
 * 留出一条与背景不连续的空带。底部应改用 [bottomContentPadding] 作为列表的 contentPadding，
 * 让内容可以滚到小白条下方。
 */
@Composable
fun PaddingValues.excludeBottom(): PaddingValues {
    val direction = LocalLayoutDirection.current
    return PaddingValues(
        start = calculateStartPadding(direction),
        top = calculateTopPadding(),
        end = calculateEndPadding(direction)
    )
}

/**
 * 滚动列表的 contentPadding：底部系统 inset 加上 [extra] 的留白，保证最后一项能完整滚出小白条。
 *
 * @param extra 内容末尾额外留白；默认 16dp 与页面 8dp 节奏对齐
 * @param top 列表顶部留白
 */
fun PaddingValues.bottomContentPadding(extra: Dp = 16.dp, top: Dp = 0.dp): PaddingValues =
    PaddingValues(top = top, bottom = calculateBottomPadding() + extra)
