package com.vela.app.ui.screens.player

import android.view.LayoutInflater
import android.view.View
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.material3.MaterialTheme
import com.vela.shared.ui.theme.velaMotion
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.vela.player.preferences.PlayerPreferences
import androidx.lifecycle.Lifecycle
import androidx.media3.common.C
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.video.spherical.SphericalGLSurfaceView
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.PlayerView
import androidx.media3.ui.SubtitleView
import androidx.media3.common.util.UnstableApi
import com.vela.app.R
import com.vela.app.player.mpv.MpvPlayerController
import com.vela.app.player.vr.VrLayout
import com.vela.app.player.vr.VrStereo
import kotlin.math.roundToInt

/**
 * 视频画面层。只负责渲染与缩放平移；手势全部由 [PlayerGestureLayer] 处理。
 *
 * 缩放/平移动画只在 graphicsLayer 中读取，动画过程不触发重组，也不会让 AndroidView 重新执行 update。
 */
@UnstableApi
@Composable
fun VideoSurface(
    player: ExoPlayer?,
    mpvPlayer: MpvPlayerController? = null,
    lifecycle: Lifecycle.Event,
    isInPictureInPictureMode: Boolean,
    scale: Float,
    offsetX: Float,
    offsetY: Float,
    resizeMode: Int = AspectRatioFrameLayout.RESIZE_MODE_FIT,
    subtitleAppearanceEpoch: Int = 0,
    vrFlatEnabled: Boolean = false,
    vrLayout: VrLayout? = null,
    videoRotationDegrees: Int = 0,
    onSphericalTouchTarget: ((View?) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    // VR 转平面时画面由投影决定，不叠加旋转。
    val rotationDegrees = if (vrFlatEnabled) 0 else videoRotationDegrees
    val motion = MaterialTheme.velaMotion
    val animatedScale = animateFloatAsState(
        targetValue = scale,
        animationSpec = motion.defaultSpatialSpec(),
        label = "video_scale"
    )
    val animatedOffsetX = animateFloatAsState(
        targetValue = offsetX,
        animationSpec = motion.defaultSpatialSpec(),
        label = "video_offset_x"
    )
    val animatedOffsetY = animateFloatAsState(
        targetValue = offsetY,
        animationSpec = motion.defaultSpatialSpec(),
        label = "video_offset_y"
    )
    val surfaceModifier = Modifier
        .fillMaxSize()
        .clipToBounds()
        .graphicsLayer {
            scaleX = animatedScale.value
            scaleY = animatedScale.value
            translationX = animatedOffsetX.value
            translationY = animatedOffsetY.value
        }

    Box(
        modifier = modifier.background(Color.Black)
    ) {
        if (mpvPlayer != null) {
            MpvVideoSurface(
                player = mpvPlayer,
                resizeMode = resizeMode,
                subtitleAppearanceEpoch = subtitleAppearanceEpoch,
                videoRotationDegrees = rotationDegrees,
                modifier = surfaceModifier
            )
        } else if (player != null) {
            ExoPlayerView(
                player = player,
                lifecycle = lifecycle,
                isInPictureInPictureMode = isInPictureInPictureMode,
                resizeMode = resizeMode,
                subtitleAppearanceEpoch = subtitleAppearanceEpoch,
                vrFlatEnabled = vrFlatEnabled,
                vrLayout = vrLayout,
                rotationDegrees = rotationDegrees,
                onSphericalTouchTarget = onSphericalTouchTarget,
                modifier = surfaceModifier
            )
        }
    }
}

@UnstableApi
@Composable
private fun ExoPlayerView(
    player: ExoPlayer?,
    lifecycle: Lifecycle.Event,
    isInPictureInPictureMode: Boolean,
    resizeMode: Int,
    @Suppress("UNUSED_PARAMETER") subtitleAppearanceEpoch: Int,
    vrFlatEnabled: Boolean,
    vrLayout: VrLayout?,
    rotationDegrees: Int,
    onSphericalTouchTarget: ((View?) -> Unit)?,
    modifier: Modifier
) {
    val context = LocalContext.current
    val playerPreferences = remember { PlayerPreferences(context) }
    var playerViewRef by remember { mutableStateOf<PlayerView?>(null) }

    // 画面旋转需要 TextureView：SurfaceView 的画面由系统合成，不跟随视图旋转。只在旋转时切换，
    // 平时仍用 SurfaceView，保留 HDR 直出与更低的功耗；切换会重建 PlayerView，画面短暂黑一下。
    val useTextureView = rotationDegrees != 0 && !vrFlatEnabled

    DisposableEffect(vrFlatEnabled, useTextureView) {
        onDispose {
            playerViewRef?.player = null
            playerViewRef = null
            onSphericalTouchTarget?.invoke(null)
        }
    }

    key(vrFlatEnabled, useTextureView) {
        AndroidView(
            factory = { viewContext ->
                val playerView = if (vrFlatEnabled) {
                    LayoutInflater.from(viewContext)
                        .inflate(R.layout.player_view_spherical, null, false) as PlayerView
                } else if (useTextureView) {
                    LayoutInflater.from(viewContext)
                        .inflate(R.layout.player_view_texture, null, false) as PlayerView
                } else {
                    PlayerView(viewContext)
                }
                playerView.apply {
                    useController = false
                    setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
                    setKeepContentOnPlayerReset(true)
                    this.resizeMode = resizeMode
                    setBackgroundColor(android.graphics.Color.BLACK)
                    setPadding(0, 0, 0, 0)
                    layoutParams = android.view.ViewGroup.LayoutParams(
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    setDefaultArtwork(null)
                }
            },
            update = { playerView ->
                playerViewRef = playerView
                playerView.player = player
                playerView.resizeMode = resizeMode
                playerView.applySubtitlePreferences(playerPreferences)
                val spherical = playerView.videoSurfaceView as? SphericalGLSurfaceView
                spherical?.setUseSensorRotation(false)
                spherical?.setDefaultStereoMode(vrLayout.toExoStereoMode())
                onSphericalTouchTarget?.invoke(if (vrFlatEnabled) spherical else null)

                when (lifecycle) {
                    Lifecycle.Event.ON_STOP -> {
                        if (!isInPictureInPictureMode) playerView.onPause()
                    }
                    Lifecycle.Event.ON_RESUME -> playerView.onResume()
                    else -> Unit
                }
            },
            modifier = modifier.rotatedVideo(if (useTextureView) rotationDegrees else 0)
        )
    }
}

/**
 * 把画面顺时针旋转 [degrees]。90° / 270° 时先按交换后的宽高测量（宽 = 容器高，高 = 容器宽）再旋转，
 * 使 PlayerView 在新方向上仍按原有缩放模式适应容器（与 iOS `RotatableSurface` 相同）。
 * 只对 TextureView 有效；字幕在 PlayerView 内部，会随画面一起旋转。
 */
private fun Modifier.rotatedVideo(degrees: Int): Modifier {
    if (degrees == 0) return this
    val quarterTurn = degrees % 180 != 0
    return this
        .layout { measurable, constraints ->
            if (!quarterTurn || !constraints.hasBoundedWidth || !constraints.hasBoundedHeight) {
                val placeable = measurable.measure(constraints)
                return@layout layout(placeable.width, placeable.height) { placeable.place(0, 0) }
            }
            val width = constraints.maxWidth
            val height = constraints.maxHeight
            val placeable = measurable.measure(Constraints.fixed(width = height, height = width))
            layout(width, height) {
                placeable.place((width - placeable.width) / 2, (height - placeable.height) / 2)
            }
        }
        .graphicsLayer { rotationZ = degrees.toFloat() }
}

@UnstableApi
private fun VrLayout?.toExoStereoMode(): Int {
    return when (this?.stereo) {
        VrStereo.TopBottom -> C.STEREO_MODE_TOP_BOTTOM
        VrStereo.SideBySide -> C.STEREO_MODE_LEFT_RIGHT
        else -> C.STEREO_MODE_MONO
    }
}

@UnstableApi
private fun PlayerView.applySubtitlePreferences(playerPreferences: PlayerPreferences) {
    subtitleView?.apply {
        val assCompatible = playerPreferences.isSubtitleAssCompatible()
        setApplyEmbeddedStyles(true)
        setApplyEmbeddedFontSizes(assCompatible)
        setFractionalTextSize(
            PlayerPreferences.exoTextSizeFractionForWindow(
                playerPreferences.getSubtitleScale(),
                width,
                height
            )
        )
        setStyle(
            CaptionStyleCompat(
                subtitleTextColorArgb(
                    playerPreferences.getSubtitleTextColor(),
                    playerPreferences.getSubtitleTextOpacityPercent()
                ),
                subtitleBackgroundColorArgb(playerPreferences.getSubtitleBackgroundColor()),
                android.graphics.Color.TRANSPARENT,
                subtitleEdgeType(playerPreferences.getSubtitleEdgeType()),
                subtitleEdgeColor(playerPreferences.getSubtitleEdgeType()),
                null
            )
        )
        if (assCompatible) {
            setBottomPaddingFraction(SubtitleView.DEFAULT_BOTTOM_PADDING_FRACTION)
            if (paddingTop != 0 || paddingBottom != 0) {
                setPadding(paddingLeft, 0, paddingRight, 0)
            }
        } else if (width > 0 && height > 0) {
            val bottomPad = PlayerPreferences.subtitleViewBottomPaddingPx(
                playerPreferences.getSubtitlePosition(),
                width,
                height,
                userScale = playerPreferences.getSubtitleScale()
            )
            val topPad = PlayerPreferences.subtitleViewTopPaddingPx(
                playerPreferences.getSubtitleTopEdgePositionPercent(),
                width,
                height
            )
            setBottomPaddingFraction(0f)
            if (paddingTop != topPad || paddingBottom != bottomPad) {
                setPadding(paddingLeft, topPad, paddingRight, bottomPad)
            }
        }
    }
}

private fun subtitleTextColorArgb(color: String, opacityPercent: Int): Int {
    val baseColor = when (color) {
        PlayerPreferences.SUBTITLE_TEXT_COLOR_YELLOW -> android.graphics.Color.YELLOW
        PlayerPreferences.SUBTITLE_TEXT_COLOR_GREEN -> android.graphics.Color.GREEN
        PlayerPreferences.SUBTITLE_TEXT_COLOR_CYAN -> android.graphics.Color.CYAN
        PlayerPreferences.SUBTITLE_TEXT_COLOR_BLACK -> android.graphics.Color.BLACK
        else -> android.graphics.Color.WHITE
    }
    return applyAlphaToColor(baseColor, opacityPercent)
}

private fun subtitleBackgroundColorArgb(color: String): Int {
    return when (color) {
        PlayerPreferences.SUBTITLE_BACKGROUND_BLACK -> android.graphics.Color.BLACK
        PlayerPreferences.SUBTITLE_BACKGROUND_WHITE -> android.graphics.Color.WHITE
        else -> android.graphics.Color.TRANSPARENT
    }
}

private fun subtitleEdgeType(edgeType: String): Int {
    return when (edgeType) {
        PlayerPreferences.SUBTITLE_EDGE_TYPE_OUTLINE -> CaptionStyleCompat.EDGE_TYPE_OUTLINE
        PlayerPreferences.SUBTITLE_EDGE_TYPE_DROP_SHADOW -> CaptionStyleCompat.EDGE_TYPE_DROP_SHADOW
        PlayerPreferences.SUBTITLE_EDGE_TYPE_RAISED -> CaptionStyleCompat.EDGE_TYPE_RAISED
        PlayerPreferences.SUBTITLE_EDGE_TYPE_DEPRESSED -> CaptionStyleCompat.EDGE_TYPE_DEPRESSED
        else -> CaptionStyleCompat.EDGE_TYPE_NONE
    }
}

private fun subtitleEdgeColor(edgeType: String): Int {
    return if (edgeType == PlayerPreferences.SUBTITLE_EDGE_TYPE_NONE) {
        android.graphics.Color.TRANSPARENT
    } else {
        android.graphics.Color.BLACK
    }
}

private fun applyAlphaToColor(color: Int, opacityPercent: Int): Int {
    val alpha = ((opacityPercent.coerceIn(0, 100) / 100f) * 255f).roundToInt().coerceIn(0, 255)
    return android.graphics.Color.argb(
        alpha,
        android.graphics.Color.red(color),
        android.graphics.Color.green(color),
        android.graphics.Color.blue(color)
    )
}
