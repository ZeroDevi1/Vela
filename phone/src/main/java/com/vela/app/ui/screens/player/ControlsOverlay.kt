package com.vela.app.ui.screens.player

import android.content.res.Configuration
import android.graphics.Bitmap
import android.text.format.DateFormat
import android.view.MotionEvent
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
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
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Forward10
import androidx.compose.material.icons.rounded.Forward30
import androidx.compose.material.icons.rounded.Forward5
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material.icons.rounded.Replay10
import androidx.compose.material.icons.rounded.Replay30
import androidx.compose.material.icons.rounded.Replay5
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.outlined.AspectRatio
import androidx.compose.material.icons.outlined.Bookmarks
import androidx.compose.material.icons.outlined.ClosedCaption
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.PictureInPictureAlt
import androidx.compose.material.icons.outlined.ScreenRotation
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.ViewInAr
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
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
import com.vela.player.core.ChapterMarker
import com.vela.player.core.PlayerConstants.PROGRESS_BAR_HIT_HEIGHT_DP
import com.vela.shared.R
import com.vela.shared.ui.theme.velaMotion
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

private val ScrubPreviewWidth = 176.dp

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
private val ScrubPreviewHeight = 99.dp

/** 播放器浮层统一使用白色系前景；视频画面颜色不可控，不能依赖主题的 onSurface。 */
private val OverlayContent = Color.White
private val OverlayContentMuted = Color.White.copy(alpha = 0.72f)
private val OverlayContentDisabled = Color.White.copy(alpha = 0.32f)

/** 中央按钮的半透明底，保证亮场景下图标仍有对比度。 */
private val OverlayButtonFill = Color.Black.copy(alpha = 0.32f)

/** 倍速菜单提供的固定档位；ViewModel 会再夹到 0.25–4x。 */
private val PlaybackSpeedPresets = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f, 3f)

private val TabularNumbers = TextStyle(fontFeatureSettings = "tnum")

/**
 * 视频播放控制层。
 *
 * 结构：顶部（返回、两级标题、画中画/旋转/更多）、中央（锁定、后退、播放暂停、前进）、
 * 底部（时间与进度条，一行播放操作）。上下两条渐变 scrim 托住文字和图标，控件本身不再套玻璃底。
 * 横竖屏共用同一结构，只调整安全区与间距。
 *
 * 所有回调都在用户交互时触发；调用方负责重置自动隐藏计时。锁定时只保留解锁按钮。
 * 拖动进度或菜单打开期间通过 [onAutoHideHoldChange] 报告 true，调用方应暂停自动隐藏；
 * 控制层离开组合时会补发 false，避免计时被永久挂起。
 */
@Composable
fun ControlsOverlay(
    title: String,
    isPlaying: Boolean,
    currentPosition: Long,
    duration: Long,
    onBackClick: () -> Unit,
    isBuffering: Boolean = false,
    onPlayPause: () -> Unit,
    onSeek: (Float) -> Unit,
    modifier: Modifier = Modifier,
    seasonEpisodeLabel: String? = null,
    seriesName: String? = null,
    chapterMarkers: List<ChapterMarker> = emptyList(),
    bufferedPosition: Long = 0L,
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
    onToggleOrientation: () -> Unit = {},
    onTitleClick: () -> Unit = {},
    onSeekBackward: () -> Unit = {},
    onSeekForward: () -> Unit = {},
    seekBackwardSeconds: Int = 30,
    seekForwardSeconds: Int = 30,
    canPlayPreviousEpisode: Boolean = false,
    canPlayNextEpisode: Boolean = false,
    onPlayPreviousEpisode: () -> Unit = {},
    onPlayNextEpisode: () -> Unit = {},
    onAutoHideHoldChange: (Boolean) -> Unit = {},
    scrubPreviewFrame: Bitmap? = null,
    onScrubPreviewPositionChange: (Long?) -> Unit = {},
    onEnterPip: () -> Unit = {},
    onToggleHardwareDecoding: () -> Unit = {},
    onShowChapters: () -> Unit = {},
    playbackSpeed: Float = 1f,
    onSetPlaybackSpeed: (Float) -> Unit = {},
    hardwareDecodingEnabled: Boolean = true,
    onUserInteraction: () -> Unit = {},
    skipActionLabel: String? = null,
    onSkipAction: () -> Unit = {},
    vrDetected: Boolean = false,
    vrFlatEnabled: Boolean = false,
    onToggleVrFlat: () -> Unit = {},
    onShowVrProjection: () -> Unit = {}
) {
    val landscape = LocalConfiguration.current.orientation != Configuration.ORIENTATION_PORTRAIT
    val motion = MaterialTheme.velaMotion
    var scrubProgress by remember { mutableStateOf<Float?>(null) }
    val isScrubbing = scrubProgress != null
    var moreMenuOpen by remember { mutableStateOf(false) }
    var speedMenuOpen by remember { mutableStateOf(false) }
    val holdAutoHide = isScrubbing || moreMenuOpen || speedMenuOpen
    val currentOnAutoHideHoldChange by rememberUpdatedState(onAutoHideHoldChange)
    LaunchedEffect(holdAutoHide) { currentOnAutoHideHoldChange(holdAutoHide) }
    DisposableEffect(Unit) {
        onDispose { currentOnAutoHideHoldChange(false) }
    }
    // 拖动进度时淡出顶栏和中央按钮，让预览帧和时间成为唯一焦点。
    val secondaryChromeAlpha by animateFloatAsState(
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

    Box(modifier = modifier.fillMaxSize()) {
        if (isLocked) {
            OverlayCircleButton(
                onClick = onToggleLock,
                size = 48.dp,
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .windowInsetsPadding(sideInsets)
                    .padding(start = edgeInset)
            ) {
                Icon(
                    imageVector = Icons.Rounded.Lock,
                    contentDescription = stringResource(R.string.player_unlock),
                    tint = OverlayContent,
                    modifier = Modifier.size(22.dp)
                )
            }
            return@Box
        }

        OverlayScrim(top = true, height = if (landscape) 140.dp else 180.dp)
        OverlayScrim(top = false, height = if (landscape) 180.dp else 240.dp)

        OverlayTopBar(
            landscape = landscape,
            edgeInset = edgeInset,
            chapterCount = chapterMarkers.size,
            hardwareDecodingEnabled = hardwareDecodingEnabled,
            vrDetected = vrDetected,
            vrFlatEnabled = vrFlatEnabled,
            onBackClick = onBackClick,
            onEnterPip = onEnterPip,
            onToggleOrientation = onToggleOrientation,
            onShowChapters = onShowChapters,
            onShowMediaInfo = onShowMediaInfo,
            onAdjustVideoSize = onAdjustVideoSize,
            onToggleHardwareDecoding = onToggleHardwareDecoding,
            onToggleVrFlat = onToggleVrFlat,
            onShowVrProjection = onShowVrProjection,
            onUserInteraction = onUserInteraction,
            showMore = moreMenuOpen,
            onShowMoreChange = { moreMenuOpen = it },
            modifier = Modifier
                .align(Alignment.TopCenter)
                .graphicsLayer { alpha = secondaryChromeAlpha }
        )

        OverlayCircleButton(
            onClick = onToggleLock,
            size = 48.dp,
            modifier = Modifier
                .align(Alignment.CenterStart)
                .windowInsetsPadding(sideInsets)
                .padding(start = edgeInset)
                .graphicsLayer { alpha = secondaryChromeAlpha }
        ) {
            Icon(
                imageVector = Icons.Rounded.LockOpen,
                contentDescription = stringResource(R.string.player_lock),
                tint = OverlayContent,
                modifier = Modifier.size(22.dp)
            )
        }

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
                .graphicsLayer { alpha = secondaryChromeAlpha }
        )

        OverlayBottomBar(
            currentPosition = currentPosition,
            duration = duration,
            bufferedPosition = bufferedPosition,
            scrubProgress = scrubProgress,
            chapterMarkers = chapterMarkers,
            scrubPreviewFrame = scrubPreviewFrame,
            landscape = landscape,
            edgeInset = edgeInset,
            playbackSpeed = playbackSpeed,
            showPlaybackSettingsButton = showPlaybackSettingsButton,
            canPlayPreviousEpisode = canPlayPreviousEpisode,
            canPlayNextEpisode = canPlayNextEpisode,
            skipActionLabel = skipActionLabel,
            headline = seriesName?.takeIf { it.isNotBlank() }
                ?: seasonEpisodeLabel?.takeIf { it.isNotBlank() }
                ?: title,
            subtitle = seasonEpisodeLabel?.takeIf { it.isNotBlank() && !seriesName.isNullOrBlank() }
                ?.replace(" - ", " · "),
            hdrLabel = if (isHdrEnabled) osdHdrLabel(hdrFormat) else "",
            titleAlpha = secondaryChromeAlpha,
            onTitleClick = onTitleClick,
            onSeek = onSeek,
            onScrubProgressChange = { progress -> scrubProgress = progress },
            onScrubPreviewProgressChange = { progress ->
                onScrubPreviewPositionChange(
                    progress?.takeIf { duration > 0L }?.let { (duration * it).toLong() }
                )
            },
            onSetPlaybackSpeed = onSetPlaybackSpeed,
            speedMenuOpen = speedMenuOpen,
            onSpeedMenuOpenChange = { speedMenuOpen = it },
            onShowPlaybackSettings = onShowPlaybackSettings,
            onShowAudioTrackSelection = onShowAudioTrackSelection,
            onShowSubtitleTrackSelection = onShowSubtitleTrackSelection,
            onPlayPreviousEpisode = onPlayPreviousEpisode,
            onPlayNextEpisode = onPlayNextEpisode,
            onSkipAction = onSkipAction,
            onUserInteraction = onUserInteraction,
            modifier = Modifier.align(Alignment.BottomCenter)
        )
    }
}

@Composable
private fun BoxScope.OverlayScrim(top: Boolean, height: Dp) {
    val brush = remember(top) {
        val stops = listOf(
            Color.Black.copy(alpha = 0.62f),
            Color.Black.copy(alpha = 0.28f),
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
private fun OverlayTopBar(
    landscape: Boolean,
    edgeInset: Dp,
    chapterCount: Int,
    hardwareDecodingEnabled: Boolean,
    vrDetected: Boolean,
    vrFlatEnabled: Boolean,
    onBackClick: () -> Unit,
    onEnterPip: () -> Unit,
    onToggleOrientation: () -> Unit,
    onShowChapters: () -> Unit,
    onShowMediaInfo: () -> Unit,
    onAdjustVideoSize: () -> Unit,
    onToggleHardwareDecoding: () -> Unit,
    onToggleVrFlat: () -> Unit,
    onShowVrProjection: () -> Unit,
    onUserInteraction: () -> Unit,
    showMore: Boolean,
    onShowMoreChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
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
            .padding(start = edgeInset - 8.dp, end = edgeInset - 8.dp, top = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        OverlayIconButton(
            onClick = onBackClick,
            icon = Icons.AutoMirrored.Rounded.ArrowBack,
            contentDescription = stringResource(R.string.cd_back_button)
        )
        Spacer(Modifier.weight(1f))
        OverlayIconButton(
            onClick = onEnterPip,
            icon = Icons.Outlined.PictureInPictureAlt,
            contentDescription = stringResource(R.string.player_pip)
        )
        OverlayIconButton(
            onClick = onToggleOrientation,
            icon = Icons.Outlined.ScreenRotation,
            contentDescription = stringResource(R.string.player_cd_toggle_orientation)
        )
        Box {
            OverlayIconButton(
                onClick = {
                    onUserInteraction()
                    onShowMoreChange(true)
                },
                icon = Icons.Outlined.MoreVert,
                contentDescription = stringResource(R.string.player_more)
            )
            OverlayMenu(expanded = showMore, onDismissRequest = { onShowMoreChange(false) }) {
                OverlayMenuItem(
                    text = stringResource(R.string.player_chapters),
                    icon = Icons.Outlined.Bookmarks,
                    trailingText = chapterCount.takeIf { it > 0 }?.toString(),
                    onClick = {
                        onShowMoreChange(false)
                        onShowChapters()
                    }
                )
                OverlayMenuItem(
                    text = stringResource(R.string.player_media_info),
                    icon = Icons.Outlined.Info,
                    onClick = {
                        onShowMoreChange(false)
                        onShowMediaInfo()
                    }
                )
                OverlayMenuItem(
                    text = stringResource(R.string.player_adjust_video_size),
                    icon = Icons.Outlined.AspectRatio,
                    onClick = {
                        onShowMoreChange(false)
                        onAdjustVideoSize()
                    }
                )
                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 4.dp),
                    color = MaterialTheme.colorScheme.outlineVariant
                )
                // 开关项点击后保持菜单打开，勾选状态即时反馈切换结果。
                OverlayMenuItem(
                    text = stringResource(R.string.player_settings_hardware_acceleration),
                    icon = Icons.Outlined.Memory,
                    checked = hardwareDecodingEnabled,
                    onClick = onToggleHardwareDecoding
                )
                if (vrDetected) {
                    OverlayMenuItem(
                        text = stringResource(R.string.player_vr_chip_enable),
                        icon = Icons.Outlined.ViewInAr,
                        checked = vrFlatEnabled,
                        onClick = onToggleVrFlat
                    )
                    OverlayMenuItem(
                        text = stringResource(R.string.player_vr_projection_title),
                        icon = null,
                        onClick = {
                            onShowMoreChange(false)
                            onShowVrProjection()
                        }
                    )
                }
            }
        }
    }
}

/**
 * 进度条上方的标题块：主标题 + 季集/HDR 信息，末尾的箭头提示可点开元数据面板。
 * 点击区域向左外扩，与进度条起点对齐的同时保持 48dp 触控高度。
 */
@Composable
private fun OverlayTitle(
    headline: String,
    subtitle: String?,
    hdrLabel: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .offset(x = (-8).dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .heightIn(min = 48.dp)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f, fill = false)) {
            Text(
                text = headline,
                style = MaterialTheme.typography.titleMedium,
                color = OverlayContent,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (!subtitle.isNullOrBlank() || hdrLabel.isNotBlank()) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    if (hdrLabel.isNotBlank()) {
                        HdrBadge(label = hdrLabel)
                    }
                    if (!subtitle.isNullOrBlank()) {
                        Text(
                            text = subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = OverlayContentMuted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
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
                imageVector = replayIcon(seekBackwardSeconds),
                contentDescription = stringResource(R.string.player_cd_seek_backward, seekBackwardSeconds),
                tint = OverlayContent,
                modifier = Modifier.size(30.dp)
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
                            tint = OverlayContent,
                            modifier = Modifier.size(44.dp)
                        )
                    }
                }
            }
        }
        OverlayCircleButton(onClick = onSeekForward, size = TransportSeekButtonSize) {
            Icon(
                imageVector = forwardIcon(seekForwardSeconds),
                contentDescription = stringResource(R.string.player_cd_seek_forward, seekForwardSeconds),
                tint = OverlayContent,
                modifier = Modifier
                    .size(30.dp)
                    // 非 5/10/30 秒没有专用前进图标，镜像通用的 Replay 图标表达方向。
                    .graphicsLayer { if (seekForwardSeconds !in setOf(5, 10, 30)) scaleX = -1f }
            )
        }
    }
}

@Composable
private fun OverlayBottomBar(
    currentPosition: Long,
    duration: Long,
    bufferedPosition: Long,
    scrubProgress: Float?,
    chapterMarkers: List<ChapterMarker>,
    scrubPreviewFrame: Bitmap?,
    landscape: Boolean,
    edgeInset: Dp,
    playbackSpeed: Float,
    showPlaybackSettingsButton: Boolean,
    canPlayPreviousEpisode: Boolean,
    canPlayNextEpisode: Boolean,
    skipActionLabel: String?,
    headline: String,
    subtitle: String?,
    hdrLabel: String,
    titleAlpha: Float,
    onTitleClick: () -> Unit,
    onSeek: (Float) -> Unit,
    onScrubProgressChange: (Float?) -> Unit,
    onScrubPreviewProgressChange: (Float?) -> Unit,
    onSetPlaybackSpeed: (Float) -> Unit,
    speedMenuOpen: Boolean,
    onSpeedMenuOpenChange: (Boolean) -> Unit,
    onShowPlaybackSettings: () -> Unit,
    onShowAudioTrackSelection: () -> Unit,
    onShowSubtitleTrackSelection: () -> Unit,
    onPlayPreviousEpisode: () -> Unit,
    onPlayNextEpisode: () -> Unit,
    onSkipAction: () -> Unit,
    onUserInteraction: () -> Unit,
    modifier: Modifier = Modifier
) {
    val progress = if (duration > 0L && currentPosition >= 0L) {
        (currentPosition.toFloat() / duration).coerceIn(0f, 1f)
    } else {
        0f
    }
    val bufferedProgress = if (duration > 0L && bufferedPosition > 0L) {
        (bufferedPosition.toFloat() / duration).coerceIn(0f, 1f)
    } else {
        0f
    }
    val displayedPosition = scrubProgress
        ?.takeIf { duration > 0L }
        ?.let { (duration * it).toLong() }
        ?: currentPosition
    var showRemaining by rememberSaveable { mutableStateOf(true) }
    val hasEpisodeNavigation = canPlayPreviousEpisode || canPlayNextEpisode

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
            .padding(start = edgeInset, end = edgeInset, bottom = if (landscape) 8.dp else 12.dp)
    ) {
        // 标题放在进度条正上方：横竖屏一致，点按即打开元数据面板；拖动时让位给预览气泡。
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .graphicsLayer { alpha = titleAlpha },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OverlayTitle(
                headline = headline,
                subtitle = subtitle,
                hdrLabel = hdrLabel,
                onClick = onTitleClick,
                modifier = Modifier.weight(1f)
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

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = formatTime(displayedPosition),
                style = MaterialTheme.typography.labelLarge.merge(TabularNumbers),
                color = OverlayContent,
                modifier = Modifier.widthIn(min = 44.dp)
            )
            SeekBar(
                progress = progress,
                bufferedProgress = bufferedProgress,
                duration = duration,
                chapterMarkers = chapterMarkers,
                previewFrame = scrubPreviewFrame,
                onSeek = onSeek,
                onScrubProgressChange = onScrubProgressChange,
                onScrubPreviewProgressChange = onScrubPreviewProgressChange,
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 12.dp)
            )
            Text(
                text = if (showRemaining && duration > 0L) {
                    "-" + formatTime((duration - displayedPosition).coerceAtLeast(0L))
                } else {
                    formatTime(duration.coerceAtLeast(0L))
                },
                style = MaterialTheme.typography.labelLarge.merge(TabularNumbers),
                color = OverlayContent,
                modifier = Modifier
                    .widthIn(min = 48.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { showRemaining = !showRemaining }
                    .padding(vertical = 6.dp)
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (hasEpisodeNavigation) {
                OverlayIconButton(
                    onClick = onPlayPreviousEpisode,
                    enabled = canPlayPreviousEpisode,
                    icon = Icons.Rounded.SkipPrevious,
                    contentDescription = stringResource(R.string.player_previous_episode)
                )
                OverlayIconButton(
                    onClick = onPlayNextEpisode,
                    enabled = canPlayNextEpisode,
                    icon = Icons.Rounded.SkipNext,
                    contentDescription = stringResource(R.string.player_next_episode)
                )
            }
            EndsAtLabel(
                remainingMs = (duration - displayedPosition).coerceAtLeast(0L),
                playbackSpeed = playbackSpeed,
                visible = duration > 0L,
                modifier = Modifier.padding(start = if (hasEpisodeNavigation) 4.dp else 0.dp)
            )
            Spacer(Modifier.weight(1f))
            PlaybackSpeedButton(
                speed = playbackSpeed,
                expanded = speedMenuOpen,
                onExpandedChange = onSpeedMenuOpenChange,
                onSetPlaybackSpeed = onSetPlaybackSpeed,
                onUserInteraction = onUserInteraction
            )
            if (showPlaybackSettingsButton) {
                OverlayIconButton(
                    onClick = onShowPlaybackSettings,
                    icon = Icons.Outlined.Tune,
                    contentDescription = stringResource(R.string.player_settings_streaming_quality)
                )
            }
            OverlayIconButton(
                onClick = onShowAudioTrackSelection,
                icon = Icons.Outlined.MusicNote,
                contentDescription = stringResource(R.string.player_dialog_audio_title)
            )
            OverlayIconButton(
                onClick = onShowSubtitleTrackSelection,
                icon = Icons.Outlined.ClosedCaption,
                contentDescription = stringResource(R.string.player_dialog_subtitles_title)
            )
        }
    }
}

/**
 * “xx:xx 结束”：按当前倍速折算剩余时长，跟随系统 12/24 小时制。
 * 每次进度刷新都会重组，因此无需单独计时器。
 */
@Composable
private fun EndsAtLabel(
    remainingMs: Long,
    playbackSpeed: Float,
    visible: Boolean,
    modifier: Modifier = Modifier
) {
    if (!visible) return
    val context = LocalContext.current
    val timeFormat = remember(context) { DateFormat.getTimeFormat(context) }
    val effectiveRemaining = (remainingMs / playbackSpeed.coerceAtLeast(0.25f)).toLong()
    val endsAt = timeFormat.format(Date(System.currentTimeMillis() + effectiveRemaining))
    Text(
        text = stringResource(R.string.player_ends_at, endsAt),
        style = MaterialTheme.typography.labelMedium,
        color = OverlayContentMuted,
        maxLines = 1,
        modifier = modifier
    )
}

@Composable
private fun PlaybackSpeedButton(
    speed: Float,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onSetPlaybackSpeed: (Float) -> Unit,
    onUserInteraction: () -> Unit
) {
    val isDefault = abs(speed - 1f) < 0.01f
    Box {
        TextButton(
            onClick = {
                onUserInteraction()
                onExpandedChange(true)
            },
            colors = ButtonDefaults.textButtonColors(contentColor = OverlayContent),
            contentPadding = PaddingValues(horizontal = 12.dp)
        ) {
            Text(
                text = formatPlaybackSpeed(speed),
                style = MaterialTheme.typography.labelLarge.merge(TabularNumbers),
                fontWeight = if (isDefault) FontWeight.Medium else FontWeight.Bold
            )
        }
        OverlayMenu(expanded = expanded, onDismissRequest = { onExpandedChange(false) }) {
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
                        onExpandedChange(false)
                        onSetPlaybackSpeed(preset)
                    }
                )
            }
        }
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
 */
@Composable
private fun OverlayMenuItem(
    text: String,
    icon: ImageVector?,
    onClick: () -> Unit,
    checked: Boolean? = null,
    trailingText: String? = null
) {
    DropdownMenuItem(
        text = { Text(text, style = MaterialTheme.typography.bodyLarge) },
        onClick = onClick,
        leadingIcon = icon?.let { { Icon(it, contentDescription = null) } },
        trailingIcon = when {
            checked == true -> {
                { Icon(Icons.Rounded.Check, contentDescription = null) }
            }
            trailingText != null -> {
                {
                    Text(
                        text = trailingText,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            else -> null
        },
        modifier = Modifier.widthIn(min = 200.dp)
    )
}

@Composable
private fun OverlayIconButton(
    onClick: () -> Unit,
    icon: ImageVector,
    contentDescription: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    IconButton(
        onClick = onClick,
        enabled = enabled,
        colors = IconButtonDefaults.iconButtonColors(
            contentColor = OverlayContent,
            disabledContentColor = OverlayContentDisabled
        ),
        modifier = modifier
    ) {
        Icon(imageVector = icon, contentDescription = contentDescription)
    }
}

/**
 * 中央圆形按钮：半透明底保证对比度，按下时以 spatial spring 轻微缩小，表达 M3 Expressive 的触感反馈。
 */
@Composable
private fun OverlayCircleButton(
    onClick: () -> Unit,
    size: Dp,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.88f else 1f,
        animationSpec = MaterialTheme.velaMotion.fastSpatialSpec(),
        label = "overlayButtonScale"
    )
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = OverlayButtonFill,
        contentColor = OverlayContent,
        interactionSource = interactionSource,
        modifier = modifier
            .size(size)
            .scale(scale)
    ) {
        Box(contentAlignment = Alignment.Center) {
            content()
        }
    }
}

private fun replayIcon(seconds: Int): ImageVector = when (seconds) {
    5 -> Icons.Rounded.Replay5
    10 -> Icons.Rounded.Replay10
    30 -> Icons.Rounded.Replay30
    else -> Icons.Rounded.Replay
}

private fun forwardIcon(seconds: Int): ImageVector = when (seconds) {
    5 -> Icons.Rounded.Forward5
    10 -> Icons.Rounded.Forward10
    30 -> Icons.Rounded.Forward30
    else -> Icons.Rounded.Replay
}

/**
 * M3 Expressive 风格进度条：竖条手柄、手柄两侧留缝、章节处断开分段；拖动时轨道加粗、手柄拉长。
 *
 * 拖动期间只更新本地进度和预览，抬手时才回调 [onSeek]，避免主画面在拖动中反复跳转。
 */
@Composable
private fun SeekBar(
    progress: Float,
    duration: Long,
    chapterMarkers: List<ChapterMarker>,
    previewFrame: Bitmap?,
    onSeek: (Float) -> Unit,
    onScrubProgressChange: (Float?) -> Unit,
    onScrubPreviewProgressChange: (Float?) -> Unit,
    modifier: Modifier = Modifier,
    bufferedProgress: Float = 0f
) {
    var scrubProgress by remember { mutableFloatStateOf(progress.coerceIn(0f, 1f)) }
    var dragActive by remember { mutableStateOf(false) }
    var widthPx by remember { mutableIntStateOf(0) }
    /** 按下时的播放进度，用来计算预览气泡上的偏移量。 */
    var anchorProgress by remember { mutableFloatStateOf(progress.coerceIn(0f, 1f)) }
    val density = LocalDensity.current
    val motion = MaterialTheme.velaMotion
    val trackThickness by animateDpAsState(
        targetValue = if (dragActive) 8.dp else 4.dp,
        animationSpec = motion.fastSpatialSpec(),
        label = "seekTrackThickness"
    )
    val handleHeight by animateDpAsState(
        targetValue = if (dragActive) 28.dp else 18.dp,
        animationSpec = motion.fastSpatialSpec(),
        label = "seekHandleHeight"
    )
    val previewWidthPx = with(density) { ScrubPreviewWidth.roundToPx() }
    val previewYOffsetPx = with(density) {
        (if (previewFrame != null) (-152).dp else (-60).dp).roundToPx()
    }

    LaunchedEffect(progress) {
        if (!dragActive) {
            scrubProgress = progress.coerceIn(0f, 1f)
        }
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
                        anchorProgress = progress.coerceIn(0f, 1f)
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
                        scrubProgress = progress.coerceIn(0f, 1f)
                        onScrubProgressChange(null)
                        onScrubPreviewProgressChange(null)
                        true
                    }
                    else -> false
                }
            },
        contentAlignment = Alignment.Center
    ) {
        val renderedProgress = scrubProgress.coerceIn(0f, 1f)

        Canvas(modifier = Modifier.fillMaxSize()) {
            drawExpressiveTrack(
                progress = renderedProgress,
                bufferedProgress = bufferedProgress.coerceIn(0f, 1f),
                chapterFractions = if (duration > 0L) {
                    chapterMarkers.map { it.positionMs.toFloat() / duration }
                } else {
                    emptyList()
                },
                trackThicknessPx = trackThickness.toPx(),
                handleHeightPx = handleHeight.toPx()
            )
        }

        AnimatedVisibility(
            visible = dragActive && duration > 0L && widthPx > 0,
            modifier = Modifier
                .align(Alignment.TopStart)
                .wrapContentSize(unbounded = true)
                .zIndex(1f)
                .offset {
                    val thumbCenterX = (widthPx * renderedProgress).roundToInt()
                    val maxPreviewX = (widthPx - previewWidthPx).coerceAtLeast(0)
                    IntOffset(
                        x = (thumbCenterX - previewWidthPx / 2).coerceIn(0, maxPreviewX),
                        y = previewYOffsetPx
                    )
                },
            enter = fadeIn(motion.fastEffectsSpec()) + scaleIn(motion.fastSpatialSpec(), initialScale = 0.92f),
            exit = fadeOut(motion.fastEffectsSpec())
        ) {
            val targetMs = (duration * renderedProgress).toLong()
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

private val TrackActive = Color.White
private val TrackBuffered = Color.White.copy(alpha = 0.45f)
private val TrackInactive = Color.White.copy(alpha = 0.22f)

/**
 * 绘制分段轨道。章节点把轨道切成多段（间隔 3dp，过密的章节点忽略），
 * 手柄两侧各留 4dp 空隙；每段都是独立圆角矩形，因此断口两端同样是圆角。
 */
private fun DrawScope.drawExpressiveTrack(
    progress: Float,
    bufferedProgress: Float,
    chapterFractions: List<Float>,
    trackThicknessPx: Float,
    handleHeightPx: Float
) {
    val handleWidth = 4.dp.toPx()
    val handleGap = 4.dp.toPx()
    val chapterGap = 3.dp.toPx()
    val minChapterSpacing = 8.dp.toPx()
    val start = handleWidth / 2
    val end = size.width - handleWidth / 2
    val width = (end - start).coerceAtLeast(0f)
    val centerY = size.height / 2

    val segments = buildList {
        var segmentStart = start
        var lastCut = Float.NEGATIVE_INFINITY
        chapterFractions.sorted().forEach { fraction ->
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

    val handleX = start + width * progress
    val activeEnd = handleX - handleWidth / 2 - handleGap
    val inactiveStart = handleX + handleWidth / 2 + handleGap
    val bufferedX = start + width * bufferedProgress

    drawRange(start, activeEnd, TrackActive)
    drawRange(inactiveStart, bufferedX, TrackBuffered)
    drawRange(max(inactiveStart, bufferedX), end, TrackInactive)
    drawRoundRect(
        color = TrackActive,
        topLeft = Offset(handleX - handleWidth / 2, centerY - handleHeightPx / 2),
        size = Size(handleWidth, handleHeightPx),
        cornerRadius = CornerRadius(handleWidth / 2, handleWidth / 2)
    )
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
        currentPosition = 1_010_000L,
        duration = 1_295_000L,
        bufferedPosition = 1_100_000L,
        isHdrEnabled = true,
        hdrFormat = "Dolby Vision",
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
        currentPosition = 3_600_000L,
        duration = 8_880_000L,
        onBackClick = { },
        onPlayPause = { },
        onSeek = { },
        playbackSpeed = 1.5f
    )
}
