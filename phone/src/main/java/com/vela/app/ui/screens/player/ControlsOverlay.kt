package com.vela.app.ui.screens.player

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.net.TrafficStats
import android.os.BatteryManager
import android.os.SystemClock
import android.text.format.DateFormat
import android.view.MotionEvent
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.automirrored.outlined.PlaylistPlay
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AspectRatio
import androidx.compose.material.icons.outlined.AvTimer
import androidx.compose.material.icons.outlined.Bedtime
import androidx.compose.material.icons.outlined.CropFree
import androidx.compose.material.icons.outlined.FitScreen
import androidx.compose.material.icons.outlined.FormatSize
import androidx.compose.material.icons.outlined.HighQuality
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.PictureInPictureAlt
import androidx.compose.material.icons.outlined.ScreenRotation
import androidx.compose.material.icons.outlined.Subtitles
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material.icons.outlined.PanoramaPhotosphere
import androidx.compose.material.icons.outlined.ViewInAr
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.FastForward
import androidx.compose.material.icons.rounded.FastRewind
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import com.vela.player.core.ChapterMarker
import com.vela.player.core.PlayerConstants.PROGRESS_BAR_HIT_HEIGHT_DP
import com.vela.shared.R
import com.vela.shared.ui.theme.velaMotion
import kotlinx.coroutines.delay
import java.util.Date
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

private val ScrubPreviewWidth = 176.dp
private val ScrubPreviewHeight = 99.dp

/**
 * 中央控制区尺寸。手势层的 ±秒提示与独立加载圈按同一组尺寸定位，
 * 保证控制层显隐前后各元素的中心点重合。
 */
internal val TransportPlayButtonSize = 76.dp
internal val TransportSeekButtonSize = 56.dp

/** 中央三键之间的间距；横屏更宽松。 */
internal fun transportSpacing(landscape: Boolean): Dp = if (landscape) 48.dp else 28.dp

/** 前进/后退键中心相对屏幕中心的水平距离。 */
internal fun transportSeekCenterOffset(landscape: Boolean): Dp =
    TransportPlayButtonSize / 2 + transportSpacing(landscape) + TransportSeekButtonSize / 2

private enum class TransportCenterState { Playing, Paused, Buffering }

/** 播放器统一的缓冲加载圈；独立加载层与播放键内复用，保证尺寸一致。 */
@Composable
internal fun BufferingSpinner() {
    CircularProgressIndicator(
        color = Color.White,
        trackColor = Color.White.copy(alpha = 0.2f),
        strokeWidth = 3.dp,
        strokeCap = StrokeCap.Round,
        modifier = Modifier.size(40.dp)
    )
}

/** 播放器浮层统一使用白色系前景；视频画面颜色不可控，不能依赖主题的 onSurface。 */
private val OverlayContent = Color.White
private val OverlayContentMuted = Color.White.copy(alpha = 0.72f)
private val OverlayContentDisabled = Color.White.copy(alpha = 0.32f)

/** 圆形按钮的半透明底，保证亮场景下图标仍有对比度。 */
private val OverlayButtonFill = Color.Black.copy(alpha = 0.36f)

/** 倍速菜单提供的固定档位；ViewModel 会再夹到 0.25–4x。 */
private val PlaybackSpeedPresets = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f, 3f)
private const val PlaybackSpeedStep = 0.25f
private const val PlaybackSpeedMin = 0.25f
private const val PlaybackSpeedMax = 4f

/** 定时关闭可选时长，单位分钟。 */
private val SleepTimerPresetsMinutes = listOf(15, 30, 45, 60, 90)

private val TabularNumbers = TextStyle(fontFeatureSettings = "tnum")

private enum class MoreMenuPage { Main, SleepTimer }

/**
 * 视频播放控制层。
 *
 * 结构（横竖屏共用，只调整尺寸与安全区）：
 * - 顶部：返回、横屏剧集 Logo；右侧 解码方式 / 画中画 / 媒体信息 / 画面比例 / 旋转 / 章节 / 更多；下方电量、时间、网速。
 * - 左侧：截图（仅 MPV）、锁定；右侧：倍速 +/-；中央：快退、播放暂停、快进。
 * - 底部：上下集 + 标题（点开元数据面板）、跳过片段；时间 + 波浪进度条；画质 / 选集 / 音轨 / 字幕。
 *
 * 动效：[visibilityScope] 非空时，顶栏、底栏、两侧和中央按各自方向滑入/缩放，与外层淡入叠加；
 * 为空时（预览）不做分区动画。
 *
 * 进度通过 [positionMs] / [bufferedMs] 以函数形式传入，只在时间文字和进度条绘制时读取，
 * 进度刷新不会让整个控制层重组。
 *
 * 所有回调都在用户交互时触发；调用方负责重置自动隐藏计时。锁定时只保留解锁按钮。
 * 拖动进度或菜单打开期间通过 [onAutoHideHoldChange] 报告 true，调用方应暂停自动隐藏；
 * 控制层离开组合时会补发 false，避免计时被永久挂起。
 *
 * @param hardwareDecodingLabel 解码方式简写（HW+/HW/SW）；null 时隐藏该入口
 * @param onScreenshot 截图回调；null 时隐藏截图按钮
 * @param onSwitchPlayerEngine 切换播放引擎；null 时菜单不显示该项
 * @param sleepTimerDeadline 定时关闭截止时刻（elapsedRealtime 毫秒）；null 表示未开启
 * @param onSetSleepTimer 设置定时关闭，参数为分钟数，null 表示取消
 * @param onShowPlaylist 打开播放列表（多 CD 分段等）；null 时“选集”按钮打开元数据面板里的剧集列表
 * @param animateProgressWave 进度条波浪是否随播放流动；超高像素或 VR 片源传 false，
 *   避免控制层每帧重绘与视频渲染争抢 GPU（波形仍保留，只是静止）
 */
@Composable
fun ControlsOverlay(
    title: String,
    isPlaying: Boolean,
    positionMs: () -> Long,
    duration: Long,
    onBackClick: () -> Unit,
    onPlayPause: () -> Unit,
    onSeek: (Float) -> Unit,
    modifier: Modifier = Modifier,
    visibilityScope: AnimatedVisibilityScope? = null,
    bufferedMs: () -> Long = { 0L },
    isBuffering: Boolean = false,
    seasonEpisodeLabel: String? = null,
    seriesName: String? = null,
    logoUrl: String? = null,
    chapterMarkers: List<ChapterMarker> = emptyList(),
    isHdrEnabled: Boolean = false,
    hdrFormat: String = "",
    onShowMediaInfo: () -> Unit = {},
    isLocked: Boolean = false,
    onToggleLock: () -> Unit = {},
    showPlaybackSettingsButton: Boolean = true,
    onShowPlaybackSettings: () -> Unit = {},
    onShowAudioTrackSelection: () -> Unit = {},
    onShowSubtitleTrackSelection: () -> Unit = {},
    onAdjustVideoSize: () -> Unit = {},
    aspectZoomed: Boolean = false,
    onCycleAspectRatio: () -> Unit = {},
    onToggleOrientation: () -> Unit = {},
    onTitleClick: () -> Unit = {},
    onShowPlaylist: (() -> Unit)? = null,
    onSeekBackward: () -> Unit = {},
    onSeekForward: () -> Unit = {},
    seekBackwardSeconds: Int = 10,
    seekForwardSeconds: Int = 10,
    canPlayPreviousEpisode: Boolean = false,
    canPlayNextEpisode: Boolean = false,
    onPlayPreviousEpisode: () -> Unit = {},
    onPlayNextEpisode: () -> Unit = {},
    onAutoHideHoldChange: (Boolean) -> Unit = {},
    scrubPreviewFrame: Bitmap? = null,
    onScrubPreviewPositionChange: (Long?) -> Unit = {},
    onEnterPip: () -> Unit = {},
    hardwareDecodingLabel: String? = null,
    onToggleHardwareDecoding: () -> Unit = {},
    onScreenshot: (() -> Unit)? = null,
    mpvEngineActive: Boolean = true,
    onSwitchPlayerEngine: (() -> Unit)? = null,
    sleepTimerDeadline: Long? = null,
    onSetSleepTimer: (Int?) -> Unit = {},
    onAddLocalSubtitle: () -> Unit = {},
    onShowSubtitleStyle: () -> Unit = {},
    onShowSubtitleDelay: () -> Unit = {},
    onShowChapters: () -> Unit = {},
    playbackSpeed: Float = 1f,
    onSetPlaybackSpeed: (Float) -> Unit = {},
    onUserInteraction: () -> Unit = {},
    skipActionLabel: String? = null,
    onSkipAction: () -> Unit = {},
    vrDetected: Boolean = false,
    vrFlatEnabled: Boolean = false,
    onToggleVrFlat: () -> Unit = {},
    onShowVrProjection: () -> Unit = {},
    animateProgressWave: Boolean = true
) {
    val landscape = LocalConfiguration.current.orientation != Configuration.ORIENTATION_PORTRAIT
    val motion = MaterialTheme.velaMotion
    var scrubProgress by remember { mutableStateOf<Float?>(null) }
    val isScrubbing by remember { derivedStateOf { scrubProgress != null } }
    var moreMenuOpen by remember { mutableStateOf(false) }
    var speedMenuOpen by remember { mutableStateOf(false) }
    val holdAutoHide = isScrubbing || moreMenuOpen || speedMenuOpen
    val currentOnAutoHideHoldChange by rememberUpdatedState(onAutoHideHoldChange)
    LaunchedEffect(holdAutoHide) { currentOnAutoHideHoldChange(holdAutoHide) }
    DisposableEffect(Unit) {
        onDispose { currentOnAutoHideHoldChange(false) }
    }
    // 拖动进度时淡出其余控件，让预览帧和时间成为唯一焦点；只在 graphicsLayer 中读取，不触发重组。
    val secondaryChromeAlpha = animateFloatAsState(
        targetValue = if (isScrubbing) 0f else 1f,
        animationSpec = motion.defaultEffectsSpec(),
        label = "secondaryChromeAlpha"
    )
    val edgeInset = if (landscape) 24.dp else 12.dp
    val sideInsets = if (landscape) {
        WindowInsets.displayCutout.only(WindowInsetsSides.Horizontal)
    } else {
        WindowInsets(0, 0, 0, 0)
    }
    val accent = overlayAccentColor()
    // 竖屏两侧按钮收小，窄屏上也不会碰到中央三键。
    val sideButtonSize = if (landscape) 52.dp else 44.dp

    val topEnter = slideInVertically(motion.defaultSpatialSpec()) { -it / 2 }
    val topExit = slideOutVertically(motion.fastEffectsSpec()) { -it / 3 }
    val bottomEnter = slideInVertically(motion.defaultSpatialSpec()) { it / 2 }
    val bottomExit = slideOutVertically(motion.fastEffectsSpec()) { it / 3 }
    val startEnter = slideInHorizontally(motion.defaultSpatialSpec()) { -it }
    val startExit = slideOutHorizontally(motion.fastEffectsSpec()) { -it / 2 }
    val endEnter = slideInHorizontally(motion.defaultSpatialSpec()) { it }
    val endExit = slideOutHorizontally(motion.fastEffectsSpec()) { it / 2 }

    Box(modifier = modifier.fillMaxSize()) {
        if (isLocked) {
            OverlayCircleButton(
                onClick = onToggleLock,
                size = 52.dp,
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .windowInsetsPadding(sideInsets)
                    .padding(start = edgeInset)
                    .enterExit(visibilityScope, startEnter, startExit)
            ) {
                Icon(
                    imageVector = Icons.Rounded.Lock,
                    contentDescription = stringResource(R.string.player_unlock),
                    modifier = Modifier.size(24.dp)
                )
            }
            return@Box
        }

        OverlayScrim(top = true, height = if (landscape) 150.dp else 200.dp)
        OverlayScrim(top = false, height = if (landscape) 200.dp else 300.dp)

        OverlayTopSection(
            landscape = landscape,
            edgeInset = edgeInset,
            logoUrl = logoUrl,
            hasChapters = chapterMarkers.isNotEmpty(),
            hardwareDecodingLabel = hardwareDecodingLabel,
            aspectZoomed = aspectZoomed,
            mpvEngineActive = mpvEngineActive,
            sleepTimerDeadline = sleepTimerDeadline,
            vrDetected = vrDetected,
            vrFlatEnabled = vrFlatEnabled,
            showMore = moreMenuOpen,
            onShowMoreChange = { moreMenuOpen = it },
            onBackClick = onBackClick,
            onToggleHardwareDecoding = onToggleHardwareDecoding,
            onEnterPip = onEnterPip,
            onShowMediaInfo = onShowMediaInfo,
            onCycleAspectRatio = onCycleAspectRatio,
            onToggleOrientation = onToggleOrientation,
            onShowChapters = onShowChapters,
            onSwitchPlayerEngine = onSwitchPlayerEngine,
            onSetSleepTimer = onSetSleepTimer,
            onAdjustVideoSize = onAdjustVideoSize,
            onAddLocalSubtitle = onAddLocalSubtitle,
            onShowSubtitleStyle = onShowSubtitleStyle,
            onShowSubtitleDelay = onShowSubtitleDelay,
            onToggleVrFlat = onToggleVrFlat,
            onShowVrProjection = onShowVrProjection,
            onUserInteraction = onUserInteraction,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .enterExit(visibilityScope, topEnter, topExit)
                .graphicsLayer { alpha = secondaryChromeAlpha.value }
        )

        AspectRatioHint(
            aspectZoomed = aspectZoomed,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = if (landscape) 96.dp else 120.dp)
        )

        Column(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .windowInsetsPadding(sideInsets)
                .padding(start = edgeInset)
                .enterExit(visibilityScope, startEnter, startExit)
                .graphicsLayer { alpha = secondaryChromeAlpha.value },
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            if (onScreenshot != null) {
                OverlayCircleButton(onClick = onScreenshot, size = sideButtonSize) {
                    Icon(
                        imageVector = Icons.Outlined.PhotoCamera,
                        contentDescription = stringResource(R.string.player_screenshot),
                        modifier = Modifier.size(24.dp)
                    )
                }
            }
            OverlayCircleButton(onClick = onToggleLock, size = sideButtonSize) {
                Icon(
                    imageVector = Icons.Rounded.LockOpen,
                    contentDescription = stringResource(R.string.player_lock),
                    modifier = Modifier.size(24.dp)
                )
            }
        }

        PlaybackSpeedControl(
            speed = playbackSpeed,
            buttonSize = if (landscape) 48.dp else 40.dp,
            menuOpen = speedMenuOpen,
            onMenuOpenChange = { speedMenuOpen = it },
            onSetPlaybackSpeed = onSetPlaybackSpeed,
            onUserInteraction = onUserInteraction,
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .windowInsetsPadding(sideInsets)
                .padding(end = edgeInset)
                .enterExit(visibilityScope, endEnter, endExit)
                .graphicsLayer { alpha = secondaryChromeAlpha.value }
        )

        OverlayTransportControls(
            isPlaying = isPlaying,
            isBuffering = isBuffering,
            seekBackwardSeconds = seekBackwardSeconds,
            seekForwardSeconds = seekForwardSeconds,
            spacing = transportSpacing(landscape),
            onSeekBackward = onSeekBackward,
            onPlayPause = onPlayPause,
            onSeekForward = onSeekForward,
            modifier = Modifier
                .align(Alignment.Center)
                .enterExit(
                    visibilityScope,
                    scaleIn(motion.defaultSpatialSpec(), initialScale = 0.8f),
                    scaleOut(motion.fastEffectsSpec(), targetScale = 0.9f)
                )
                .graphicsLayer { alpha = secondaryChromeAlpha.value }
        )

        OverlayBottomSection(
            positionMs = positionMs,
            bufferedMs = bufferedMs,
            duration = duration,
            scrubProgress = { scrubProgress },
            chapterMarkers = chapterMarkers,
            scrubPreviewFrame = scrubPreviewFrame,
            landscape = landscape,
            edgeInset = edgeInset,
            accent = accent,
            isPlaying = isPlaying && !isBuffering && animateProgressWave,
            showPlaybackSettingsButton = showPlaybackSettingsButton,
            canPlayPreviousEpisode = canPlayPreviousEpisode,
            canPlayNextEpisode = canPlayNextEpisode,
            skipActionLabel = skipActionLabel,
            headline = episodeHeadline(seasonEpisodeLabel) ?: title,
            // 横屏有 Logo 时剧名已经显示在顶部，不再重复。
            supportingText = seriesName?.takeIf {
                it.isNotBlank() && !seasonEpisodeLabel.isNullOrBlank() && (!landscape || logoUrl.isNullOrBlank())
            },
            hdrLabel = if (isHdrEnabled) osdHdrLabel(hdrFormat) else "",
            secondaryChromeAlpha = { secondaryChromeAlpha.value },
            onTitleClick = onTitleClick,
            onShowPlaylist = onShowPlaylist,
            onSeek = onSeek,
            onScrubProgressChange = { progress -> scrubProgress = progress },
            onScrubPreviewProgressChange = { progress ->
                onScrubPreviewPositionChange(
                    progress?.takeIf { duration > 0L }?.let { (duration * it).toLong() }
                )
            },
            onShowPlaybackSettings = onShowPlaybackSettings,
            onShowAudioTrackSelection = onShowAudioTrackSelection,
            onShowSubtitleTrackSelection = onShowSubtitleTrackSelection,
            onPlayPreviousEpisode = onPlayPreviousEpisode,
            onPlayNextEpisode = onPlayNextEpisode,
            onSkipAction = onSkipAction,
            onUserInteraction = onUserInteraction,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .enterExit(visibilityScope, bottomEnter, bottomExit)
        )
    }
}

/** 在 [scope] 存在时为子区域声明独立的进出场动画；预览等无外层 AnimatedVisibility 时原样返回。 */
private fun Modifier.enterExit(
    scope: AnimatedVisibilityScope?,
    enter: EnterTransition,
    exit: ExitTransition
): Modifier = if (scope == null) {
    this
} else {
    with(scope) { this@enterExit.animateEnterExit(enter = enter, exit = exit) }
}

/**
 * 进度条等强调色。浮层永远压在深色 scrim 上，浅色主题的 primary 偏深、对比度不足，此时改用 inversePrimary。
 */
@Composable
private fun overlayAccentColor(): Color {
    val scheme = MaterialTheme.colorScheme
    return if (scheme.primary.luminance() >= 0.35f) scheme.primary else scheme.inversePrimary
}

@Composable
private fun BoxScope.OverlayScrim(top: Boolean, height: Dp) {
    val brush = remember(top) {
        val stops = listOf(
            Color.Black.copy(alpha = 0.66f),
            Color.Black.copy(alpha = 0.3f),
            Color.Transparent
        )
        Brush.verticalGradient(if (top) stops else stops.reversed())
    }
    Box(
        modifier = Modifier
            .align(if (top) Alignment.TopCenter else Alignment.BottomCenter)
            .fillMaxWidth()
            .height(height)
            .background(brush)
    )
}

@Composable
private fun OverlayTopSection(
    landscape: Boolean,
    edgeInset: Dp,
    logoUrl: String?,
    hasChapters: Boolean,
    hardwareDecodingLabel: String?,
    aspectZoomed: Boolean,
    mpvEngineActive: Boolean,
    sleepTimerDeadline: Long?,
    vrDetected: Boolean,
    vrFlatEnabled: Boolean,
    showMore: Boolean,
    onShowMoreChange: (Boolean) -> Unit,
    onBackClick: () -> Unit,
    onToggleHardwareDecoding: () -> Unit,
    onEnterPip: () -> Unit,
    onShowMediaInfo: () -> Unit,
    onCycleAspectRatio: () -> Unit,
    onToggleOrientation: () -> Unit,
    onShowChapters: () -> Unit,
    onSwitchPlayerEngine: (() -> Unit)?,
    onSetSleepTimer: (Int?) -> Unit,
    onAdjustVideoSize: () -> Unit,
    onAddLocalSubtitle: () -> Unit,
    onShowSubtitleStyle: () -> Unit,
    onShowSubtitleDelay: () -> Unit,
    onToggleVrFlat: () -> Unit,
    onShowVrProjection: () -> Unit,
    onUserInteraction: () -> Unit,
    modifier: Modifier = Modifier
) {
    // 竖屏一行放不下全部按钮时，媒体信息只留在“更多”菜单里。
    val compactWidth = LocalConfiguration.current.screenWidthDp < 400
    val buttonSize = if (landscape) 48.dp else 40.dp
    val iconSize = if (landscape) 24.dp else 22.dp
    val buttonSpacing = if (landscape) 10.dp else 4.dp
    Column(
        modifier = modifier
            .fillMaxWidth()
            .windowInsetsPadding(
                WindowInsets.displayCutout.only(
                    if (landscape) {
                        WindowInsetsSides.Horizontal + WindowInsetsSides.Top
                    } else {
                        WindowInsetsSides.Top
                    }
                )
            )
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onUserInteraction
            )
            .padding(start = edgeInset, end = edgeInset, top = if (landscape) 12.dp else 8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(buttonSpacing)
        ) {
            OverlayCircleButton(onClick = onBackClick, size = buttonSize) {
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                    contentDescription = stringResource(R.string.cd_back_button),
                    modifier = Modifier.size(iconSize)
                )
            }
            if (landscape && !logoUrl.isNullOrBlank()) {
                MediaLogo(
                    url = logoUrl,
                    modifier = Modifier.padding(start = 6.dp)
                )
            }
            Spacer(Modifier.weight(1f))
            if (hardwareDecodingLabel != null) {
                DecoderChip(label = hardwareDecodingLabel, onClick = onToggleHardwareDecoding)
            }
            OverlayCircleButton(onClick = onEnterPip, size = buttonSize) {
                Icon(
                    imageVector = Icons.Outlined.PictureInPictureAlt,
                    contentDescription = stringResource(R.string.player_pip),
                    modifier = Modifier.size(iconSize)
                )
            }
            if (landscape || !compactWidth) {
                OverlayCircleButton(onClick = onShowMediaInfo, size = buttonSize) {
                    Icon(
                        imageVector = Icons.Outlined.Info,
                        contentDescription = stringResource(R.string.player_media_info),
                        modifier = Modifier.size(iconSize)
                    )
                }
            }
            OverlayCircleButton(onClick = onCycleAspectRatio, size = buttonSize) {
                Icon(
                    imageVector = if (aspectZoomed) Icons.Outlined.FitScreen else Icons.Outlined.CropFree,
                    contentDescription = stringResource(R.string.player_aspect_ratio),
                    modifier = Modifier.size(iconSize)
                )
            }
            OverlayCircleButton(onClick = onToggleOrientation, size = buttonSize) {
                Icon(
                    imageVector = Icons.Outlined.ScreenRotation,
                    contentDescription = stringResource(R.string.player_cd_toggle_orientation),
                    modifier = Modifier.size(iconSize)
                )
            }
            if (hasChapters) {
                OverlayCircleButton(onClick = onShowChapters, size = buttonSize) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Outlined.MenuBook,
                        contentDescription = stringResource(R.string.player_chapters),
                        modifier = Modifier.size(iconSize)
                    )
                }
            }
            Box {
                OverlayCircleButton(
                    onClick = {
                        onUserInteraction()
                        onShowMoreChange(true)
                    },
                    size = buttonSize
                ) {
                    Icon(
                        imageVector = Icons.Rounded.MoreHoriz,
                        contentDescription = stringResource(R.string.player_more),
                        modifier = Modifier.size(iconSize)
                    )
                }
                OverlayMoreMenu(
                    expanded = showMore,
                    onDismissRequest = { onShowMoreChange(false) },
                    mpvEngineActive = mpvEngineActive,
                    sleepTimerDeadline = sleepTimerDeadline,
                    vrDetected = vrDetected,
                    vrFlatEnabled = vrFlatEnabled,
                    onSwitchPlayerEngine = onSwitchPlayerEngine,
                    onSetSleepTimer = onSetSleepTimer,
                    onShowMediaInfo = onShowMediaInfo,
                    onAdjustVideoSize = onAdjustVideoSize,
                    onAddLocalSubtitle = onAddLocalSubtitle,
                    onShowSubtitleStyle = onShowSubtitleStyle,
                    onShowSubtitleDelay = onShowSubtitleDelay,
                    onToggleVrFlat = onToggleVrFlat,
                    onShowVrProjection = onShowVrProjection
                )
            }
        }
        PlayerStatusBar(
            modifier = Modifier
                .align(Alignment.End)
                .padding(top = 10.dp, end = 4.dp)
        )
    }
}

/** 横屏左上角的剧集 Logo；按显示尺寸解码，避免大图占用内存。 */
@Composable
private fun MediaLogo(url: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val request = remember(context, url) {
        ImageRequest.Builder(context)
            .data(url)
            .size(coil3.size.Size(480, 144))
            .build()
    }
    AsyncImage(
        model = request,
        contentDescription = null,
        contentScale = ContentScale.Fit,
        alignment = Alignment.CenterStart,
        modifier = modifier
            .height(64.dp)
            .widthIn(max = 240.dp)
    )
}

/** 解码方式开关：点按在硬解与软解之间切换，文字即当前状态。 */
@Composable
private fun DecoderChip(label: String, onClick: () -> Unit) {
    Text(
        text = label,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Medium,
        color = OverlayContent,
        maxLines = 1,
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .heightIn(min = 40.dp)
            .wrapContentSize(Alignment.Center)
            .padding(horizontal = 8.dp)
    )
}

/**
 * 系统栏隐藏后补回的状态信息：电量、时间、实时网速。
 * 网速取设备总下行流量（TrafficStats），包含其他应用的流量，只作参考。每秒刷新一次，只重组这一行。
 */
@Composable
private fun PlayerStatusBar(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val timeFormat = remember(context) { DateFormat.getTimeFormat(context) }
    var battery by remember { mutableIntStateOf(readBatteryPercent(context)) }
    var charging by remember { mutableStateOf(isBatteryCharging(context)) }
    var clock by remember { mutableStateOf(timeFormat.format(Date())) }
    var speedLabel by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        var lastRx = TrafficStats.getTotalRxBytes()
        var lastTime = SystemClock.elapsedRealtime()
        while (true) {
            delay(1_000)
            val now = SystemClock.elapsedRealtime()
            val rx = TrafficStats.getTotalRxBytes()
            speedLabel = if (rx >= 0L && lastRx >= 0L) {
                formatTransferRate((rx - lastRx).coerceAtLeast(0L) * 1000.0 / (now - lastTime).coerceAtLeast(1L))
            } else {
                null
            }
            lastRx = rx
            lastTime = now
            clock = timeFormat.format(Date())
            battery = readBatteryPercent(context)
            charging = isBatteryCharging(context)
        }
    }
    val statusStyle = MaterialTheme.typography.labelLarge.merge(TabularNumbers)
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (battery >= 0) {
            BatteryBadge(percent = battery, charging = charging)
        }
        Text(text = clock, style = statusStyle, color = OverlayContent)
        speedLabel?.let { Text(text = it, style = statusStyle, color = OverlayContent) }
    }
}

@Composable
private fun BatteryBadge(percent: Int, charging: Boolean) {
    val color = when {
        charging || percent > 20 -> Color(0xFF34C759)
        else -> Color(0xFFFF453A)
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = percent.toString(),
            style = MaterialTheme.typography.labelSmall.merge(TabularNumbers),
            fontWeight = FontWeight.Bold,
            color = Color.Black,
            modifier = Modifier
                .background(color, RoundedCornerShape(4.dp))
                .padding(horizontal = 5.dp, vertical = 1.dp)
        )
        // 电池正极的小凸起
        Box(
            modifier = Modifier
                .padding(start = 1.dp)
                .size(width = 2.dp, height = 6.dp)
                .background(color, RoundedCornerShape(topEnd = 1.dp, bottomEnd = 1.dp))
        )
    }
}

private fun readBatteryPercent(context: Context): Int {
    val manager = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
    return manager?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)?.takeIf { it in 0..100 } ?: -1
}

private fun isBatteryCharging(context: Context): Boolean {
    val manager = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
    return manager?.isCharging == true
}

/** 画面比例切换后的短暂提示；首次组合不提示，只在比例实际变化时出现 1.2 秒。 */
@Composable
private fun AspectRatioHint(aspectZoomed: Boolean, modifier: Modifier = Modifier) {
    val motion = MaterialTheme.velaMotion
    var shownFor by remember { mutableStateOf(aspectZoomed) }
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(aspectZoomed) {
        if (shownFor == aspectZoomed) return@LaunchedEffect
        shownFor = aspectZoomed
        visible = true
        delay(1_200)
        visible = false
    }
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(motion.fastEffectsSpec()) + scaleIn(motion.fastSpatialSpec(), initialScale = 0.9f),
        exit = fadeOut(motion.defaultEffectsSpec()),
        modifier = modifier
    ) {
        Text(
            text = stringResource(if (aspectZoomed) R.string.player_aspect_zoom else R.string.player_aspect_fit),
            style = MaterialTheme.typography.labelLarge,
            color = OverlayContent,
            modifier = Modifier
                .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(50))
                .padding(horizontal = 16.dp, vertical = 8.dp)
        )
    }
}

@Composable
private fun OverlayMoreMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    mpvEngineActive: Boolean,
    sleepTimerDeadline: Long?,
    vrDetected: Boolean,
    vrFlatEnabled: Boolean,
    onSwitchPlayerEngine: (() -> Unit)?,
    onSetSleepTimer: (Int?) -> Unit,
    onShowMediaInfo: () -> Unit,
    onAdjustVideoSize: () -> Unit,
    onAddLocalSubtitle: () -> Unit,
    onShowSubtitleStyle: () -> Unit,
    onShowSubtitleDelay: () -> Unit,
    onToggleVrFlat: () -> Unit,
    onShowVrProjection: () -> Unit
) {
    var page by remember { mutableStateOf(MoreMenuPage.Main) }
    LaunchedEffect(expanded) {
        if (expanded) page = MoreMenuPage.Main
    }
    val dismissThen: (() -> Unit) -> Unit = { action ->
        onDismissRequest()
        action()
    }
    OverlayMenu(expanded = expanded, onDismissRequest = onDismissRequest) {
        when (page) {
            MoreMenuPage.Main -> {
                if (onSwitchPlayerEngine != null) {
                    OverlayMenuItem(
                        text = stringResource(
                            if (mpvEngineActive) R.string.player_use_exoplayer else R.string.player_use_mpv
                        ),
                        icon = Icons.Outlined.SwapHoriz,
                        onClick = { dismissThen(onSwitchPlayerEngine) }
                    )
                }
                OverlayMenuItem(
                    text = stringResource(R.string.player_sleep_timer),
                    icon = Icons.Outlined.Bedtime,
                    trailingText = sleepTimerDeadline?.let { deadline ->
                        val remainingMinutes = ceil(
                            (deadline - SystemClock.elapsedRealtime()).coerceAtLeast(0L) / 60_000.0
                        ).toInt()
                        stringResource(R.string.player_sleep_timer_minutes, remainingMinutes)
                    },
                    showChevron = true,
                    onClick = { page = MoreMenuPage.SleepTimer }
                )
                OverlayMenuItem(
                    text = stringResource(R.string.player_media_info),
                    icon = Icons.Outlined.Info,
                    onClick = { dismissThen(onShowMediaInfo) }
                )
                OverlayMenuItem(
                    text = stringResource(R.string.player_adjust_video_size),
                    icon = Icons.Outlined.AspectRatio,
                    onClick = { dismissThen(onAdjustVideoSize) }
                )
                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 4.dp),
                    color = MaterialTheme.colorScheme.outlineVariant
                )
                OverlayMenuItem(
                    text = stringResource(R.string.player_subtitle_add_local),
                    icon = Icons.Outlined.Add,
                    onClick = { dismissThen(onAddLocalSubtitle) }
                )
                OverlayMenuItem(
                    text = stringResource(R.string.player_subtitle_scale_position),
                    icon = Icons.Outlined.FormatSize,
                    onClick = { dismissThen(onShowSubtitleStyle) }
                )
                OverlayMenuItem(
                    text = stringResource(R.string.player_subtitle_time_offset),
                    icon = Icons.Outlined.AvTimer,
                    onClick = { dismissThen(onShowSubtitleDelay) }
                )
                if (vrDetected) {
                    HorizontalDivider(
                        modifier = Modifier.padding(vertical = 4.dp),
                        color = MaterialTheme.colorScheme.outlineVariant
                    )
                    // 开关项点击后保持菜单打开，勾选状态即时反馈切换结果。
                    OverlayMenuItem(
                        text = stringResource(R.string.player_vr_chip_enable),
                        icon = Icons.Outlined.ViewInAr,
                        checked = vrFlatEnabled,
                        onClick = onToggleVrFlat
                    )
                    OverlayMenuItem(
                        text = stringResource(R.string.player_vr_projection_title),
                        icon = Icons.Outlined.PanoramaPhotosphere,
                        onClick = { dismissThen(onShowVrProjection) }
                    )
                }
            }
            MoreMenuPage.SleepTimer -> {
                DropdownMenuItem(
                    text = {
                        Text(
                            text = stringResource(R.string.player_sleep_timer),
                            style = MaterialTheme.typography.titleSmall
                        )
                    },
                    leadingIcon = {
                        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowLeft, contentDescription = null)
                    },
                    onClick = { page = MoreMenuPage.Main },
                    modifier = Modifier.widthIn(min = 220.dp)
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                OverlayMenuItem(
                    text = stringResource(R.string.player_sleep_timer_off),
                    icon = null,
                    checked = sleepTimerDeadline == null,
                    onClick = {
                        onDismissRequest()
                        onSetSleepTimer(null)
                    }
                )
                SleepTimerPresetsMinutes.forEach { minutes ->
                    OverlayMenuItem(
                        text = stringResource(R.string.player_sleep_timer_minutes, minutes),
                        icon = null,
                        onClick = {
                            onDismissRequest()
                            onSetSleepTimer(minutes)
                        }
                    )
                }
            }
        }
    }
}

/**
 * 标题块：主标题 + 剧名/HDR 信息，末尾的箭头提示可点开元数据面板。
 * 点击区域向左外扩，与进度条起点对齐的同时保持 48dp 触控高度。
 */
@Composable
private fun OverlayTitle(
    headline: String,
    supportingText: String?,
    hdrLabel: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .heightIn(min = 48.dp)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f, fill = false)) {
            if (!supportingText.isNullOrBlank()) {
                Text(
                    text = supportingText,
                    style = MaterialTheme.typography.labelMedium,
                    color = OverlayContentMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = headline,
                    style = MaterialTheme.typography.titleMedium,
                    color = OverlayContent,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                if (hdrLabel.isNotBlank()) {
                    HdrBadge(label = hdrLabel)
                }
            }
        }
        Icon(
            imageVector = Icons.Rounded.ChevronRight,
            contentDescription = null,
            tint = OverlayContentMuted,
            modifier = Modifier.size(20.dp)
        )
    }
}

@Composable
private fun HdrBadge(label: String) {
    Text(
        text = label,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Bold,
        color = OverlayContent,
        maxLines = 1,
        modifier = Modifier
            .border(1.dp, OverlayContentMuted, RoundedCornerShape(4.dp))
            .padding(horizontal = 4.dp)
    )
}

@Composable
private fun OverlayTransportControls(
    isPlaying: Boolean,
    isBuffering: Boolean,
    seekBackwardSeconds: Int,
    seekForwardSeconds: Int,
    spacing: Dp,
    onSeekBackward: () -> Unit,
    onPlayPause: () -> Unit,
    onSeekForward: () -> Unit,
    modifier: Modifier = Modifier
) {
    val motion = MaterialTheme.velaMotion
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(spacing)
    ) {
        OverlayCircleButton(onClick = onSeekBackward, size = TransportSeekButtonSize) {
            Icon(
                imageVector = Icons.Rounded.FastRewind,
                contentDescription = stringResource(R.string.player_cd_seek_backward, seekBackwardSeconds),
                modifier = Modifier.size(32.dp)
            )
        }
        OverlayCircleButton(onClick = onPlayPause, size = TransportPlayButtonSize) {
            // 缓冲时加载圈直接占据播放键的位置，与控制层隐藏时的独立加载圈同心同尺寸。
            val centerState = when {
                isBuffering -> TransportCenterState.Buffering
                isPlaying -> TransportCenterState.Playing
                else -> TransportCenterState.Paused
            }
            AnimatedContent(
                targetState = centerState,
                transitionSpec = {
                    (fadeIn(motion.fastEffectsSpec()) + scaleIn(motion.fastSpatialSpec(), initialScale = 0.6f))
                        .togetherWith(fadeOut(motion.fastEffectsSpec()) + scaleOut(targetScale = 0.6f))
                },
                contentAlignment = Alignment.Center,
                label = "playPauseIcon"
            ) { state ->
                when (state) {
                    TransportCenterState.Buffering -> BufferingSpinner()
                    TransportCenterState.Playing, TransportCenterState.Paused -> {
                        val playing = state == TransportCenterState.Playing
                        Icon(
                            imageVector = if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                            contentDescription = stringResource(if (playing) R.string.pause else R.string.play),
                            modifier = Modifier.size(44.dp)
                        )
                    }
                }
            }
        }
        OverlayCircleButton(onClick = onSeekForward, size = TransportSeekButtonSize) {
            Icon(
                imageVector = Icons.Rounded.FastForward,
                contentDescription = stringResource(R.string.player_cd_seek_forward, seekForwardSeconds),
                modifier = Modifier.size(32.dp)
            )
        }
    }
}

/** 右侧倍速控制：上下 ±0.25x 微调，中间点按展开预设档位。 */
@Composable
private fun PlaybackSpeedControl(
    speed: Float,
    buttonSize: Dp,
    menuOpen: Boolean,
    onMenuOpenChange: (Boolean) -> Unit,
    onSetPlaybackSpeed: (Float) -> Unit,
    onUserInteraction: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isDefault = abs(speed - 1f) < 0.01f
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        OverlayCircleButton(
            onClick = { onSetPlaybackSpeed(steppedSpeed(speed, PlaybackSpeedStep)) },
            size = buttonSize,
            enabled = speed < PlaybackSpeedMax - 0.001f
        ) {
            Icon(
                imageVector = Icons.Rounded.Add,
                contentDescription = stringResource(R.string.player_speed_increase),
                modifier = Modifier.size(24.dp)
            )
        }
        Box {
            Text(
                text = formatPlaybackSpeed(speed),
                style = MaterialTheme.typography.titleSmall.merge(TabularNumbers),
                fontWeight = if (isDefault) FontWeight.Medium else FontWeight.Bold,
                color = OverlayContent,
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .clickable {
                        onUserInteraction()
                        onMenuOpenChange(true)
                    }
                    .heightIn(min = 40.dp)
                    .widthIn(min = 40.dp)
                    .wrapContentSize(Alignment.Center)
            )
            OverlayMenu(expanded = menuOpen, onDismissRequest = { onMenuOpenChange(false) }) {
                Text(
                    text = stringResource(R.string.player_playback_speed),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
                PlaybackSpeedPresets.forEach { preset ->
                    OverlayMenuItem(
                        text = formatPlaybackSpeed(preset),
                        icon = null,
                        checked = abs(speed - preset) < 0.01f,
                        onClick = {
                            onMenuOpenChange(false)
                            onSetPlaybackSpeed(preset)
                        }
                    )
                }
            }
        }
        OverlayCircleButton(
            onClick = { onSetPlaybackSpeed(steppedSpeed(speed, -PlaybackSpeedStep)) },
            size = buttonSize,
            enabled = speed > PlaybackSpeedMin + 0.001f
        ) {
            Icon(
                imageVector = Icons.Rounded.Remove,
                contentDescription = stringResource(R.string.player_speed_decrease),
                modifier = Modifier.size(24.dp)
            )
        }
    }
}

/** 按步长调整倍速并对齐到 0.05x，消除浮点累加误差。 */
private fun steppedSpeed(speed: Float, delta: Float): Float =
    (((speed + delta) * 20f).roundToInt() / 20f).coerceIn(PlaybackSpeedMin, PlaybackSpeedMax)

@Composable
private fun OverlayBottomSection(
    positionMs: () -> Long,
    bufferedMs: () -> Long,
    duration: Long,
    scrubProgress: () -> Float?,
    chapterMarkers: List<ChapterMarker>,
    scrubPreviewFrame: Bitmap?,
    landscape: Boolean,
    edgeInset: Dp,
    accent: Color,
    isPlaying: Boolean,
    showPlaybackSettingsButton: Boolean,
    canPlayPreviousEpisode: Boolean,
    canPlayNextEpisode: Boolean,
    skipActionLabel: String?,
    headline: String,
    supportingText: String?,
    hdrLabel: String,
    secondaryChromeAlpha: () -> Float,
    onTitleClick: () -> Unit,
    onShowPlaylist: (() -> Unit)?,
    onSeek: (Float) -> Unit,
    onScrubProgressChange: (Float?) -> Unit,
    onScrubPreviewProgressChange: (Float?) -> Unit,
    onShowPlaybackSettings: () -> Unit,
    onShowAudioTrackSelection: () -> Unit,
    onShowSubtitleTrackSelection: () -> Unit,
    onPlayPreviousEpisode: () -> Unit,
    onPlayNextEpisode: () -> Unit,
    onSkipAction: () -> Unit,
    onUserInteraction: () -> Unit,
    modifier: Modifier = Modifier
) {
    val hasEpisodeNavigation = canPlayPreviousEpisode || canPlayNextEpisode
    val progressProvider: () -> Float = {
        if (duration > 0L) (positionMs().toFloat() / duration).coerceIn(0f, 1f) else 0f
    }
    val bufferedProvider: () -> Float = {
        if (duration > 0L) (bufferedMs().toFloat() / duration).coerceIn(0f, 1f) else 0f
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .then(
                if (landscape) {
                    Modifier
                        .windowInsetsPadding(WindowInsets.displayCutout.only(WindowInsetsSides.Horizontal))
                        .windowInsetsPadding(WindowInsets.navigationBars.only(WindowInsetsSides.Bottom))
                } else {
                    Modifier.windowInsetsPadding(WindowInsets.navigationBars)
                }
            )
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onUserInteraction
            )
            .padding(start = edgeInset, end = edgeInset, bottom = if (landscape) 12.dp else 16.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .graphicsLayer { alpha = secondaryChromeAlpha() },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (hasEpisodeNavigation) {
                OverlayCircleButton(
                    onClick = onPlayPreviousEpisode,
                    size = 48.dp,
                    enabled = canPlayPreviousEpisode
                ) {
                    Icon(
                        imageVector = Icons.Rounded.SkipPrevious,
                        contentDescription = stringResource(R.string.player_previous_episode),
                        modifier = Modifier.size(24.dp)
                    )
                }
                OverlayCircleButton(
                    onClick = onPlayNextEpisode,
                    size = 48.dp,
                    enabled = canPlayNextEpisode
                ) {
                    Icon(
                        imageVector = Icons.Rounded.SkipNext,
                        contentDescription = stringResource(R.string.player_next_episode),
                        modifier = Modifier.size(24.dp)
                    )
                }
            }
            OverlayTitle(
                headline = headline,
                supportingText = supportingText,
                hdrLabel = hdrLabel,
                onClick = onTitleClick,
                modifier = Modifier
                    .weight(1f)
                    .padding(start = if (hasEpisodeNavigation) 4.dp else 0.dp)
                    .offset(x = if (hasEpisodeNavigation) 0.dp else (-8).dp)
            )
            if (!skipActionLabel.isNullOrBlank()) {
                Button(
                    onClick = onSkipAction,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = OverlayContent,
                        contentColor = Color.Black
                    ),
                    contentPadding = PaddingValues(start = 16.dp, end = 20.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.SkipNext,
                        contentDescription = null,
                        modifier = Modifier.size(ButtonDefaults.IconSize)
                    )
                    Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                    Text(text = skipActionLabel, style = MaterialTheme.typography.labelLarge)
                }
            }
        }

        Spacer(Modifier.height(4.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            PlaybackTimeLabel(
                positionMs = positionMs,
                duration = duration,
                scrubProgress = scrubProgress
            )
            WavySeekBar(
                progress = progressProvider,
                bufferedProgress = bufferedProvider,
                duration = duration,
                chapterMarkers = chapterMarkers,
                previewFrame = scrubPreviewFrame,
                accent = accent,
                animateWave = isPlaying,
                onSeek = onSeek,
                onScrubProgressChange = onScrubProgressChange,
                onScrubPreviewProgressChange = onScrubPreviewProgressChange,
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 12.dp, end = if (landscape) 16.dp else 4.dp)
            )
            if (landscape) {
                BottomActions(
                    showPlaybackSettingsButton = showPlaybackSettingsButton,
                    showEpisodes = hasEpisodeNavigation || onShowPlaylist != null,
                    onShowPlaybackSettings = onShowPlaybackSettings,
                    onShowEpisodes = onShowPlaylist ?: onTitleClick,
                    onShowAudioTrackSelection = onShowAudioTrackSelection,
                    onShowSubtitleTrackSelection = onShowSubtitleTrackSelection,
                    modifier = Modifier.graphicsLayer { alpha = secondaryChromeAlpha() }
                )
            }
        }

        if (!landscape) {
            BottomActions(
                showPlaybackSettingsButton = showPlaybackSettingsButton,
                showEpisodes = hasEpisodeNavigation || onShowPlaylist != null,
                onShowPlaybackSettings = onShowPlaybackSettings,
                onShowEpisodes = onShowPlaylist ?: onTitleClick,
                onShowAudioTrackSelection = onShowAudioTrackSelection,
                onShowSubtitleTrackSelection = onShowSubtitleTrackSelection,
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .padding(top = 8.dp)
                    .graphicsLayer { alpha = secondaryChromeAlpha() }
            )
        }
    }
}

@Composable
private fun BottomActions(
    showPlaybackSettingsButton: Boolean,
    showEpisodes: Boolean,
    onShowPlaybackSettings: () -> Unit,
    onShowEpisodes: () -> Unit,
    onShowAudioTrackSelection: () -> Unit,
    onShowSubtitleTrackSelection: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (showPlaybackSettingsButton) {
            OverlayCircleButton(onClick = onShowPlaybackSettings, size = 48.dp) {
                Icon(
                    imageVector = Icons.Outlined.HighQuality,
                    contentDescription = stringResource(R.string.player_settings_streaming_quality),
                    modifier = Modifier.size(24.dp)
                )
            }
        }
        if (showEpisodes) {
            OverlayCircleButton(onClick = onShowEpisodes, size = 48.dp) {
                Icon(
                    imageVector = Icons.AutoMirrored.Outlined.PlaylistPlay,
                    contentDescription = stringResource(R.string.player_episodes),
                    modifier = Modifier.size(24.dp)
                )
            }
        }
        OverlayCircleButton(onClick = onShowAudioTrackSelection, size = 48.dp) {
            Icon(
                imageVector = Icons.Outlined.MusicNote,
                contentDescription = stringResource(R.string.player_dialog_audio_title),
                modifier = Modifier.size(24.dp)
            )
        }
        OverlayCircleButton(onClick = onShowSubtitleTrackSelection, size = 48.dp) {
            Icon(
                imageVector = Icons.Outlined.Subtitles,
                contentDescription = stringResource(R.string.player_dialog_subtitles_title),
                modifier = Modifier.size(24.dp)
            )
        }
    }
}

/**
 * “当前 / 总时长”。点按在总时长与剩余时长之间切换。
 * 进度只在这里读取，刷新时只重组这一段文字。
 */
@Composable
private fun PlaybackTimeLabel(
    positionMs: () -> Long,
    duration: Long,
    scrubProgress: () -> Float?
) {
    var showRemaining by rememberSaveable { mutableStateOf(false) }
    val displayedPosition = scrubProgress()
        ?.takeIf { duration > 0L }
        ?.let { (duration * it).toLong() }
        ?: positionMs()
    val total = if (showRemaining && duration > 0L) {
        "-" + formatTime((duration - displayedPosition).coerceAtLeast(0L))
    } else {
        formatTime(duration.coerceAtLeast(0L))
    }
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable { showRemaining = !showRemaining }
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = formatTime(displayedPosition),
            style = MaterialTheme.typography.labelLarge.merge(TabularNumbers),
            color = OverlayContent
        )
        Text(
            text = " / $total",
            style = MaterialTheme.typography.labelLarge.merge(TabularNumbers),
            color = OverlayContentMuted
        )
    }
}

@Composable
private fun OverlayMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    content: @Composable () -> Unit
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        shape = RoundedCornerShape(16.dp),
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
    ) {
        content()
    }
}

/**
 * 浮层菜单项。[checked] 为 null 时是普通动作；非 null 时为开关/单选，选中显示勾。
 * [showChevron] 表示点击后进入二级页面。
 */
@Composable
private fun OverlayMenuItem(
    text: String,
    icon: ImageVector?,
    onClick: () -> Unit,
    checked: Boolean? = null,
    trailingText: String? = null,
    showChevron: Boolean = false
) {
    DropdownMenuItem(
        text = { Text(text, style = MaterialTheme.typography.bodyLarge) },
        onClick = onClick,
        leadingIcon = icon?.let { { Icon(it, contentDescription = null) } },
        trailingIcon = when {
            checked == true -> {
                { Icon(Icons.Rounded.Check, contentDescription = null) }
            }
            trailingText != null || showChevron -> {
                {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        trailingText?.let {
                            Text(
                                text = it,
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        if (showChevron) {
                            Icon(
                                imageVector = Icons.Rounded.ChevronRight,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
            else -> null
        },
        modifier = Modifier.widthIn(min = 220.dp)
    )
}

/**
 * 圆形按钮：半透明底保证对比度。按下时圆形收成圆角方形并轻微缩小（M3 Expressive 的形变反馈），
 * 两者都用 spatial spring 驱动。
 */
@Composable
private fun OverlayCircleButton(
    onClick: () -> Unit,
    size: Dp,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val motion = MaterialTheme.velaMotion
    val scale = animateFloatAsState(
        targetValue = if (pressed) 0.9f else 1f,
        animationSpec = motion.fastSpatialSpec(),
        label = "overlayButtonScale"
    )
    val cornerRadius by animateDpAsState(
        targetValue = if (pressed) size * 0.3f else size / 2,
        animationSpec = motion.fastSpatialSpec(),
        label = "overlayButtonCorner"
    )
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(cornerRadius),
        color = OverlayButtonFill,
        contentColor = if (enabled) OverlayContent else OverlayContentDisabled,
        interactionSource = interactionSource,
        modifier = modifier
            .size(size)
            .graphicsLayer {
                scaleX = scale.value
                scaleY = scale.value
            }
    ) {
        Box(contentAlignment = Alignment.Center) {
            content()
        }
    }
}

/**
 * M3 Expressive 风格进度条：已播放部分是随播放流动的波浪线，暂停或拖动时平滑收成直线；
 * 未播放部分是直线，章节处断开；竖条手柄两侧留缝，拖动时轨道加粗、手柄拉长。
 *
 * [progress] / [bufferedProgress] 只在绘制阶段读取，进度刷新只触发重绘、不触发重组。
 * 拖动期间只更新本地进度和预览，抬手时才回调 [onSeek]，避免主画面在拖动中反复跳转。
 */
@Composable
private fun WavySeekBar(
    progress: () -> Float,
    bufferedProgress: () -> Float,
    duration: Long,
    chapterMarkers: List<ChapterMarker>,
    previewFrame: Bitmap?,
    accent: Color,
    animateWave: Boolean,
    onSeek: (Float) -> Unit,
    onScrubProgressChange: (Float?) -> Unit,
    onScrubPreviewProgressChange: (Float?) -> Unit,
    modifier: Modifier = Modifier
) {
    var scrubProgress by remember { mutableFloatStateOf(0f) }
    var dragActive by remember { mutableStateOf(false) }
    var widthPx by remember { mutableIntStateOf(0) }
    /** 按下时的播放进度，用来计算预览气泡上的偏移量。 */
    var anchorProgress by remember { mutableFloatStateOf(0f) }
    val density = LocalDensity.current
    val motion = MaterialTheme.velaMotion
    val trackThickness = animateDpAsState(
        targetValue = if (dragActive) 8.dp else 4.dp,
        animationSpec = motion.fastSpatialSpec(),
        label = "seekTrackThickness"
    )
    val handleHeight = animateDpAsState(
        targetValue = if (dragActive) 32.dp else 22.dp,
        animationSpec = motion.fastSpatialSpec(),
        label = "seekHandleHeight"
    )
    val waveActive = animateWave && !dragActive
    val amplitude = animateDpAsState(
        targetValue = if (waveActive) 3.dp else 0.dp,
        animationSpec = motion.defaultSpatialSpec(),
        label = "seekWaveAmplitude"
    )
    // 相位只在绘制阶段读取：波浪流动只重绘进度条，不重组任何 composable。
    val phase = remember { mutableFloatStateOf(0f) }
    LaunchedEffect(waveActive) {
        if (!waveActive) return@LaunchedEffect
        var lastFrame = withFrameNanos { it }
        while (true) {
            withFrameNanos { now ->
                val deltaSeconds = (now - lastFrame) / 1_000_000_000f
                lastFrame = now
                phase.floatValue = (phase.floatValue + deltaSeconds * WavePhaseRadiansPerSecond) % (2f * PI.toFloat())
            }
        }
    }
    val chapterFractions = remember(chapterMarkers, duration) {
        if (duration > 0L) chapterMarkers.map { it.positionMs.toFloat() / duration }.sorted() else emptyList()
    }
    val wavePath = remember { Path() }
    val previewWidthPx = with(density) { ScrubPreviewWidth.roundToPx() }
    val previewYOffsetPx = with(density) {
        (if (previewFrame != null) (-152).dp else (-60).dp).roundToPx()
    }
    val renderedProgress: () -> Float = {
        if (dragActive) scrubProgress.coerceIn(0f, 1f) else progress().coerceIn(0f, 1f)
    }

    Box(
        modifier = modifier
            .height(PROGRESS_BAR_HIT_HEIGHT_DP.dp)
            .onSizeChanged { widthPx = it.width }
            .pointerInteropFilter { event ->
                if (widthPx <= 0) return@pointerInteropFilter false

                val newProgress = (event.x / widthPx.toFloat()).coerceIn(0f, 1f)
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        dragActive = true
                        anchorProgress = progress().coerceIn(0f, 1f)
                        scrubProgress = newProgress
                        onScrubProgressChange(scrubProgress)
                        onScrubPreviewProgressChange(scrubProgress)
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        scrubProgress = newProgress
                        onScrubProgressChange(scrubProgress)
                        onScrubPreviewProgressChange(scrubProgress)
                        true
                    }
                    MotionEvent.ACTION_UP -> {
                        scrubProgress = newProgress
                        dragActive = false
                        onSeek(scrubProgress)
                        onScrubProgressChange(null)
                        onScrubPreviewProgressChange(null)
                        true
                    }
                    MotionEvent.ACTION_CANCEL -> {
                        dragActive = false
                        onScrubProgressChange(null)
                        onScrubPreviewProgressChange(null)
                        true
                    }
                    else -> false
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawWavyTrack(
                progress = renderedProgress(),
                bufferedProgress = bufferedProgress(),
                chapterFractions = chapterFractions,
                trackThicknessPx = trackThickness.value.toPx(),
                handleHeightPx = handleHeight.value.toPx(),
                amplitudePx = amplitude.value.toPx(),
                phase = phase.floatValue,
                accent = accent,
                path = wavePath
            )
        }

        AnimatedVisibility(
            visible = dragActive && duration > 0L && widthPx > 0,
            modifier = Modifier
                .align(Alignment.TopStart)
                .wrapContentSize(unbounded = true)
                .zIndex(1f)
                .offset {
                    val thumbCenterX = (widthPx * renderedProgress()).roundToInt()
                    val maxPreviewX = (widthPx - previewWidthPx).coerceAtLeast(0)
                    IntOffset(
                        x = (thumbCenterX - previewWidthPx / 2).coerceIn(0, maxPreviewX),
                        y = previewYOffsetPx
                    )
                },
            enter = fadeIn(motion.fastEffectsSpec()) + scaleIn(motion.fastSpatialSpec(), initialScale = 0.92f),
            exit = fadeOut(motion.fastEffectsSpec())
        ) {
            val targetMs = (duration * scrubProgress.coerceIn(0f, 1f)).toLong()
            val deltaMs = targetMs - (duration * anchorProgress.coerceIn(0f, 1f)).toLong()
            val chapterLabel = chapterMarkers
                .lastOrNull { it.positionMs <= targetMs }
                ?.label
                ?.takeIf { it.isNotBlank() }
            ScrubPreviewBubble(
                frame = previewFrame,
                time = formatTime(targetMs),
                delta = formatSignedOffset(deltaMs),
                chapterLabel = chapterLabel
            )
        }
    }
}

/** 波浪流动速度：每秒推进的相位（弧度），约 1.6 秒流过一个波长。 */
private const val WavePhaseRadiansPerSecond = (2 * PI / 1.6).toFloat()

@Composable
private fun ScrubPreviewBubble(
    frame: Bitmap?,
    time: String,
    delta: String,
    chapterLabel: String?
) {
    Surface(
        color = Color.Black.copy(alpha = 0.86f),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.width(ScrubPreviewWidth)
    ) {
        Column {
            frame?.let {
                Image(
                    bitmap = it.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .padding(4.dp)
                        .fillMaxWidth()
                        .height(ScrubPreviewHeight - 8.dp)
                        .clip(RoundedCornerShape(12.dp))
                )
            }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 40.dp)
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = time,
                        style = MaterialTheme.typography.titleSmall.merge(TabularNumbers),
                        color = OverlayContent
                    )
                    Text(
                        text = delta,
                        style = MaterialTheme.typography.labelMedium.merge(TabularNumbers),
                        color = OverlayContentMuted
                    )
                }
                chapterLabel?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.labelSmall,
                        color = OverlayContentMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

private val TrackHandle = Color.White
private val TrackBuffered = Color.White.copy(alpha = 0.45f)
private val TrackInactive = Color.White.copy(alpha = 0.22f)

/**
 * 绘制进度条。
 *
 * - 已播放段：振幅 [amplitudePx] 的正弦波（振幅为 0 时退化为直线），波长 28dp，圆头描边；
 * - 未播放段：按章节点切成多段直线（间隔 3dp，过密的章节点忽略），先画缓冲再画剩余；
 * - 手柄两侧各留 4dp 空隙。
 *
 * @param path 复用的 Path，避免每帧分配
 */
private fun DrawScope.drawWavyTrack(
    progress: Float,
    bufferedProgress: Float,
    chapterFractions: List<Float>,
    trackThicknessPx: Float,
    handleHeightPx: Float,
    amplitudePx: Float,
    phase: Float,
    accent: Color,
    path: Path
) {
    val handleWidth = 4.dp.toPx()
    val handleGap = 4.dp.toPx()
    val chapterGap = 3.dp.toPx()
    val minChapterSpacing = 8.dp.toPx()
    val wavelength = 28.dp.toPx()
    val start = handleWidth / 2
    val end = size.width - handleWidth / 2
    val width = (end - start).coerceAtLeast(0f)
    val centerY = size.height / 2

    val handleX = start + width * progress
    val activeEnd = handleX - handleWidth / 2 - handleGap
    val inactiveStart = handleX + handleWidth / 2 + handleGap

    if (activeEnd > start) {
        if (amplitudePx < 0.25f) {
            drawLine(
                color = accent,
                start = Offset(start, centerY),
                end = Offset(activeEnd, centerY),
                strokeWidth = trackThicknessPx,
                cap = StrokeCap.Round
            )
        } else {
            path.reset()
            val step = 2.dp.toPx()
            val k = 2f * PI.toFloat() / wavelength
            // 波形从左端 0 振幅渐入，避免起点突兀的竖直落差。
            val rampLength = wavelength / 2
            var x = start
            path.moveTo(x, centerY)
            while (x < activeEnd) {
                x = min(x + step, activeEnd)
                val ramp = ((x - start) / rampLength).coerceIn(0f, 1f)
                path.lineTo(x, centerY + amplitudePx * ramp * sin(k * (x - start) - phase))
            }
            drawPath(
                path = path,
                color = accent,
                style = Stroke(width = trackThicknessPx, cap = StrokeCap.Round, join = StrokeJoin.Round)
            )
        }
    }

    val segments = buildList {
        var segmentStart = start
        var lastCut = Float.NEGATIVE_INFINITY
        chapterFractions.forEach { fraction ->
            if (fraction <= 0f || fraction >= 1f) return@forEach
            val x = start + width * fraction
            if (x - lastCut < minChapterSpacing || x - segmentStart < minChapterSpacing) return@forEach
            add(segmentStart to x - chapterGap / 2)
            segmentStart = x + chapterGap / 2
            lastCut = x
        }
        add(segmentStart to end)
    }

    fun drawRange(from: Float, to: Float, color: Color) {
        if (to <= from) return
        segments.forEach { (segStart, segEnd) ->
            val left = max(segStart, from)
            val right = min(segEnd, to)
            val length = right - left
            if (length < 0.5f) return@forEach
            val radius = min(trackThicknessPx, length) / 2
            drawRoundRect(
                color = color,
                topLeft = Offset(left, centerY - trackThicknessPx / 2),
                size = Size(length, trackThicknessPx),
                cornerRadius = CornerRadius(radius, radius)
            )
        }
    }

    val bufferedX = start + width * bufferedProgress
    drawRange(inactiveStart, bufferedX, TrackBuffered)
    drawRange(max(inactiveStart, bufferedX), end, TrackInactive)
    drawRoundRect(
        color = TrackHandle,
        topLeft = Offset(handleX - handleWidth / 2, centerY - handleHeightPx / 2),
        size = Size(handleWidth, handleHeightPx),
        cornerRadius = CornerRadius(handleWidth / 2, handleWidth / 2)
    )
}

/**
 * 把服务端的 `S1:E18 - 标题` 整理成 `S1E18：标题` 这样的单行标题；不是剧集格式时返回 null。
 */
@Composable
private fun episodeHeadline(seasonEpisodeLabel: String?): String? {
    val label = seasonEpisodeLabel?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val separator = label.indexOf(" - ")
    val code = (if (separator >= 0) label.substring(0, separator) else label).replace(":E", "E")
    val name = if (separator >= 0) label.substring(separator + 3).trim() else ""
    return if (name.isEmpty()) code else stringResource(R.string.player_episode_headline, code, name)
}

/**
 * 把拖动偏移格式化成带符号的时间。
 *
 * @param deltaMs 相对按下位置的偏移，单位毫秒。负数表示往回拖
 * @return 例如 `+01:12` 或 `-00:05`
 */
private fun formatSignedOffset(deltaMs: Long): String {
    val sign = if (deltaMs < 0) "-" else "+"
    return sign + formatTime(abs(deltaMs))
}

private fun formatTime(timeMs: Long): String {
    val totalSeconds = timeMs / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60

    return if (hours > 0) {
        String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.US, "%02d:%02d", minutes, seconds)
    }
}

private fun formatPlaybackSpeed(speed: Float): String {
    val hundredths = (speed * 100f).roundToInt()
    return if (hundredths % 10 == 0) {
        String.format(Locale.US, "%.1fx", speed)
    } else {
        String.format(Locale.US, "%.2fx", speed)
    }
}

private fun osdHdrLabel(format: String): String {
    val trimmedFormat = format.trim()
    return when {
        trimmedFormat.contains("dolby vision", ignoreCase = true) -> "DV"
        trimmedFormat.contains("hdr10+", ignoreCase = true) -> "HDR10+"
        trimmedFormat.contains("hdr10", ignoreCase = true) -> "HDR10"
        trimmedFormat.equals("hdr", ignoreCase = true) -> "HDR"
        else -> ""
    }
}

@Preview(
    name = "Controls - Landscape",
    showBackground = true,
    widthDp = 800,
    heightDp = 380,
    backgroundColor = 0xFF1B2433
)
@Composable
private fun ControlsOverlayPreviewLandscape() {
    ControlsOverlay(
        title = "再见了，王子殿下",
        seriesName = "网球王子",
        seasonEpisodeLabel = "S7:E13 - 再见了，王子殿下",
        chapterMarkers = listOf(
            ChapterMarker(positionMs = 90_000L, label = "片头"),
            ChapterMarker(positionMs = 600_000L, label = "第二幕"),
            ChapterMarker(positionMs = 1_200_000L, label = "片尾")
        ),
        isPlaying = true,
        positionMs = { 1_010_000L },
        bufferedMs = { 1_100_000L },
        duration = 1_295_000L,
        isHdrEnabled = true,
        hdrFormat = "Dolby Vision",
        hardwareDecodingLabel = "HW+",
        onScreenshot = {},
        onBackClick = { },
        onPlayPause = { },
        onSeek = { },
        canPlayPreviousEpisode = true,
        canPlayNextEpisode = true,
        skipActionLabel = "跳过片头"
    )
}

@Preview(
    name = "Controls - Portrait",
    showBackground = true,
    widthDp = 400,
    heightDp = 860,
    backgroundColor = 0xFF1B2433
)
@Composable
private fun ControlsOverlayPreviewPortrait() {
    ControlsOverlay(
        title = "Inception",
        isPlaying = false,
        positionMs = { 3_600_000L },
        duration = 8_880_000L,
        onBackClick = { },
        onPlayPause = { },
        onSeek = { },
        playbackSpeed = 1.5f
    )
}
