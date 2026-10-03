package com.vela.shared.ui.theme

import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.spring
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Vela 的 M3 Expressive motion scheme。
 *
 * Material3 1.4.0 的 `MotionScheme` 与 `MaterialTheme(motionScheme = ...)` 仍是 internal，
 * 无法注入给 M3 组件；这里按 M3 Expressive 的 spring token 自行提供给页面代码：
 * - spatial（位置/尺寸/缩放）带轻微回弹，fast 0.6/800、default 0.8/380、slow 0.8/200；
 * - effects（透明度/颜色）无回弹，fast 3800、default 1600、slow 800。
 *
 * 页面不得再自建时长或弹簧参数，统一通过 [velaMotion] 取规格。升级到公开 MotionScheme 的版本后，
 * 应改为实现该接口并传给 MaterialTheme，使组件与页面共用同一套规格。
 */
@Immutable
class VelaMotionScheme internal constructor() {
    fun <T> fastSpatialSpec(): FiniteAnimationSpec<T> =
        spring(dampingRatio = 0.6f, stiffness = 800f)

    fun <T> defaultSpatialSpec(): FiniteAnimationSpec<T> =
        spring(dampingRatio = 0.8f, stiffness = 380f)

    fun <T> slowSpatialSpec(): FiniteAnimationSpec<T> =
        spring(dampingRatio = 0.8f, stiffness = 200f)

    fun <T> fastEffectsSpec(): FiniteAnimationSpec<T> =
        spring(dampingRatio = 1f, stiffness = 3800f)

    fun <T> defaultEffectsSpec(): FiniteAnimationSpec<T> =
        spring(dampingRatio = 1f, stiffness = 1600f)

    fun <T> slowEffectsSpec(): FiniteAnimationSpec<T> =
        spring(dampingRatio = 1f, stiffness = 800f)
}

internal val VelaMotion = VelaMotionScheme()
internal val LocalVelaMotion = staticCompositionLocalOf { VelaMotion }

val MaterialTheme.velaMotion: VelaMotionScheme
    @Composable
    @ReadOnlyComposable
    get() = LocalVelaMotion.current
