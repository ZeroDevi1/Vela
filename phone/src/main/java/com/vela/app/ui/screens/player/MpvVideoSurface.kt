package com.vela.app.ui.screens.player

import android.graphics.PixelFormat
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.ViewGroup
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import com.vela.app.player.mpv.MpvPlayerController

/**
 * mpv 渲染用的 SurfaceView。Surface 的生命周期即 mpv vo 的生命周期：销毁会卸载 vo，
 * 所以调用方不能因横竖屏等原因重建它，尺寸变化交给 surfaceChanged。
 */
@UnstableApi
@Composable
fun MpvVideoSurface(
    player: MpvPlayerController,
    resizeMode: Int,
    subtitleAppearanceEpoch: Int,
    modifier: Modifier
) {
    // Surface 回调捕获的是创建时的控制器；换实例（下一集、换引擎）时必须换一个 SurfaceView 挂到新实例上。
    key(player) {
    AndroidView(
        factory = { context ->
            SurfaceView(context).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                holder.setFormat(PixelFormat.RGBA_8888)
                // Gestures are handled by PlayerGestureLayer so portrait letterbox swipes work.
                holder.addCallback(object : SurfaceHolder.Callback {
                    override fun surfaceCreated(holder: SurfaceHolder) {
                        val frame = holder.surfaceFrame
                        player.attachSurface(
                            surface = holder.surface,
                            width = frame.width(),
                            height = frame.height()
                        )
                    }

                    override fun surfaceChanged(
                        holder: SurfaceHolder,
                        format: Int,
                        width: Int,
                        height: Int
                    ) {
                        player.resizeSurface(width, height)
                    }

                    override fun surfaceDestroyed(holder: SurfaceHolder) {
                        player.detachSurface()
                    }
                })
            }
        },
        modifier = modifier
    )
    }

    // 只在真正变化时写 mpv 属性。放在 AndroidView.update 里会随每次重组（进度刷新、手势）
    // 重复设置字幕样式，触发 mpv 重新排版字幕并造成掉帧。尺寸由 surfaceChanged 负责同步。
    LaunchedEffect(player, subtitleAppearanceEpoch) {
        player.applySubtitlePreferences()
    }
    LaunchedEffect(player, resizeMode) {
        player.setZoomMode(resizeMode == AspectRatioFrameLayout.RESIZE_MODE_ZOOM)
    }
}
