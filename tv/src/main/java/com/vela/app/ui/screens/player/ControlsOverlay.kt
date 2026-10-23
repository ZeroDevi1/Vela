package com.vela.app.ui.screens.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import com.vela.shared.R
import com.vela.app.ui.components.TvAccentGreen
import com.vela.app.ui.screens.dashboard.media.TvClock
import android.text.format.DateFormat
import java.util.Date
import com.vela.detail.SpatializationResult
import com.vela.player.core.ChapterMarker
import java.util.Locale
import kotlin.math.abs

private const val TV_SEEK_BAR_HEIGHT_DP = 14
private const val TV_SEEK_STEP_FRACTION = 0.01f

@Composable
fun ControlsOverlay(
    title: String,
    mediaLogoUrl: String? = null,
    seasonEpisodeLabel: String? = null,
    chapterMarkers: List<ChapterMarker> = emptyList(),
    isPlaying: Boolean,
    currentPosition: Long,
    duration: Long,
    onBackClick: () -> Unit,
    onPlayPause: () -> Unit,
    onSeek: (Float) -> Unit,
    modifier: Modifier = Modifier,
    spatializationResult: SpatializationResult? = null,
    isSpatialAudioEnabled: Boolean = false,
    isHdrEnabled: Boolean = false,
    onShowMediaInfo: () -> Unit = {},
    currentStreamingQuality: String = "",
    showPlaybackSettingsButton: Boolean = true,
    onShowPlaybackSettings: () -> Unit = {},
    onShowAudioTrackSelection: () -> Unit = {},
    onShowSubtitleTrackSelection: () -> Unit = {},
    onCycleAspectRatio: () -> Unit = {},
    onSeekBackward: () -> Unit = {},
    onSeekForward: () -> Unit = {},
    seekBackwardSeconds: Int = 30,
    seekForwardSeconds: Int = 30,
    canPlayPreviousEpisode: Boolean = false,
    canPlayNextEpisode: Boolean = false,
    onPlayPreviousEpisode: () -> Unit = {},
    onPlayNextEpisode: () -> Unit = {},
    onScrubStateChange: (Boolean) -> Unit = {}
) {
    val context = LocalContext.current
    val playPauseFocus = remember { FocusRequester() }

    val overlayGradient = remember {
        Brush.verticalGradient(
            listOf(
                Color.Black.copy(alpha = 0.45f),
                Color.Transparent,
                Color.Transparent,
                Color.Black.copy(alpha = 0.55f),
                Color.Black.copy(alpha = 0.92f)
            )
        )
    }
    val logoRequest = remember(context, mediaLogoUrl) {
        mediaLogoUrl?.takeIf { it.isNotBlank() }?.let { url ->
            ImageRequest.Builder(context)
                .data(url)
                .size(coil3.size.Size(480, 140))
                .build()
        }
    }
    val endsAtText = remember(currentPosition / 60_000, duration) {
        if (duration <= 0 || currentPosition >= duration) null
        else DateFormat.getTimeFormat(context).format(Date(System.currentTimeMillis() + duration - currentPosition))
    }

    LaunchedEffect(Unit) {
        playPauseFocus.requestFocus()
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(overlayGradient)
    ) {
        TvClock(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = 24.dp, end = 48.dp)
        )

        // Emby TV 式底部控制面板：标题信息 → 进度条与时间 → 操作按钮
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 48.dp)
                .padding(bottom = 28.dp)
                .align(Alignment.BottomCenter),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Bottom
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    if (logoRequest != null) {
                        AsyncImage(
                            model = logoRequest,
                            contentDescription = "$title logo",
                            contentScale = ContentScale.Fit,
                            alignment = Alignment.CenterStart,
                            modifier = Modifier
                                .height(40.dp)
                                .widthIn(min = 40.dp, max = 280.dp)
                        )
                    } else if (title.isNotBlank()) {
                        Text(
                            text = title,
                            color = Color.White,
                            fontSize = 24.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    if (!seasonEpisodeLabel.isNullOrBlank()) {
                        Text(
                            text = seasonEpisodeLabel,
                            color = Color.White.copy(alpha = 0.8f),
                            fontSize = 16.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                }
                PlaybackBadges(isHdrEnabled = isHdrEnabled, isSpatialAudioEnabled = isSpatialAudioEnabled)
                endsAtText?.let { endsAt ->
                    Text(
                        text = stringResource(R.string.tv_detail_ends_at, endsAt),
                        color = Color.White.copy(alpha = 0.8f),
                        fontSize = 15.sp,
                        modifier = Modifier.padding(start = 16.dp)
                    )
                }
            }

            TvSeekBar(
                progress = if (duration > 0 && currentPosition >= 0) {
                    (currentPosition.toFloat() / duration.toFloat()).coerceIn(0f, 1f)
                } else 0f,
                duration = duration,
                chapterMarkers = chapterMarkers,
                onSeek = onSeek,
                onScrubStateChange = onScrubStateChange
            )

            Row(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = formatTime(currentPosition),
                    color = Color.White.copy(alpha = 0.85f),
                    fontSize = 14.sp
                )
                Spacer(modifier = Modifier.weight(1f))
                Text(
                    text = formatTime(if (duration > 0) duration else 0L),
                    color = Color.White.copy(alpha = 0.85f),
                    fontSize = 14.sp
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (canPlayPreviousEpisode) {
                    OsdButton(
                        onClick = onPlayPreviousEpisode,
                        icon = Icons.Filled.SkipPrevious,
                        contentDescription = stringResource(R.string.player_previous_episode)
                    )
                }
                OsdButton(
                    onClick = onSeekBackward,
                    icon = replayIcon(seekBackwardSeconds),
                    contentDescription = "Rewind $seekBackwardSeconds seconds"
                )
                OsdButton(
                    onClick = onPlayPause,
                    icon = if (isPlaying) Icons.Filled.Pause else Icons.Rounded.PlayArrow,
                    contentDescription = if (isPlaying) "Pause" else "Play",
                    size = 56.dp,
                    iconSize = 34.dp,
                    focusRequester = playPauseFocus
                )
                OsdButton(
                    onClick = onSeekForward,
                    icon = replayforwardIcon(seekForwardSeconds),
                    contentDescription = "Forward $seekForwardSeconds seconds",
                    iconModifier = Modifier.graphicsLayer {
                        if (seekForwardSeconds != 5 &&
                            seekForwardSeconds != 10 &&
                            seekForwardSeconds != 30
                        ) {
                            scaleX = -1f
                        }
                    }
                )
                if (canPlayNextEpisode) {
                    OsdButton(
                        onClick = onPlayNextEpisode,
                        icon = Icons.Filled.SkipNext,
                        contentDescription = stringResource(R.string.player_next_episode)
                    )
                }

                Spacer(modifier = Modifier.weight(1f))

                OsdButton(
                    onClick = onShowSubtitleTrackSelection,
                    icon = Icons.Outlined.Subtitles,
                    contentDescription = "Subtitles"
                )
                OsdButton(
                    onClick = onShowAudioTrackSelection,
                    icon = Icons.Outlined.Audiotrack,
                    contentDescription = "Audio Tracks"
                )
                OsdButton(
                    onClick = onCycleAspectRatio,
                    icon = Icons.Outlined.AspectRatio,
                    contentDescription = "Aspect Ratio"
                )
                if (showPlaybackSettingsButton) {
                    OsdButton(
                        onClick = onShowPlaybackSettings,
                        icon = Icons.Outlined.Settings,
                        contentDescription = "Playback Settings"
                    )
                }
                OsdButton(
                    onClick = onShowMediaInfo,
                    icon = Icons.Outlined.Info,
                    contentDescription = "Media Info"
                )
            }
        }
    }
}

/** HDR / 空间音频状态标签，只在对应功能生效时显示。 */
@Composable
private fun PlaybackBadges(isHdrEnabled: Boolean, isSpatialAudioEnabled: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        if (isHdrEnabled) {
            Surface(
                color = Color(0xFFFFB300).copy(alpha = 0.18f),
                shape = RoundedCornerShape(6.dp)
            ) {
                Icon(
                    imageVector = Icons.Outlined.HdrOn,
                    contentDescription = "HDR",
                    tint = Color(0xFFFFB300),
                    modifier = Modifier.padding(4.dp).size(16.dp)
                )
            }
        }
        if (isSpatialAudioEnabled) {
            Surface(
                color = TvAccentGreen.copy(alpha = 0.18f),
                shape = RoundedCornerShape(6.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Icon(
                        imageVector = ImageVector.vectorResource(R.drawable.ic_spatial_audio),
                        contentDescription = null,
                        tint = TvAccentGreen,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(5.dp))
                    Text(
                        text = "Spatial",
                        color = TvAccentGreen,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }
    }
}

/**
 * 控制面板的圆形按钮（Emby TV 样式）：获得焦点时白底黑图标并放大。
 * 确认键在 onPreviewKeyEvent 中处理，避免按住确认键时被播放器的按键逻辑抢走。
 */
@Composable
private fun OsdButton(
    onClick: () -> Unit,
    icon: ImageVector,
    contentDescription: String,
    size: Dp = 44.dp,
    iconSize: Dp = 26.dp,
    focusRequester: FocusRequester? = null,
    iconModifier: Modifier = Modifier
) {
    var isFocused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (isFocused) 1.1f else 1f, label = "osd_button_scale")

    Box(
        modifier = Modifier
            .size(size)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .then(focusRequester?.let { Modifier.focusRequester(it) } ?: Modifier)
            .onFocusChanged { isFocused = it.isFocused }
            .onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown) {
                    when (event.key) {
                        Key.Enter, Key.NumPadEnter, Key.DirectionCenter -> {
                            onClick()
                            true
                        }
                        else -> false
                    }
                } else false
            }
            .focusable()
            .background(
                color = if (isFocused) Color.White else Color.White.copy(alpha = 0.12f),
                shape = CircleShape
            ),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = if (isFocused) Color.Black else Color.White,
            modifier = iconModifier.size(iconSize)
        )
    }
}

@Composable
private fun TvSeekBar(
    progress: Float,
    duration: Long,
    chapterMarkers: List<ChapterMarker>,
    onSeek: (Float) -> Unit,
    onScrubStateChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    var isFocused by remember { mutableStateOf(false) }
    var scrubProgress by remember { mutableFloatStateOf(progress.coerceIn(0f, 1f)) }
    var isScrubbing by remember { mutableStateOf(false) }

    LaunchedEffect(progress) {
        if (!isScrubbing) {
            scrubProgress = progress.coerceIn(0f, 1f)
        }
    }

    val trackHeight by animateFloatAsState(
        targetValue = if (isFocused) 0.9f else 0.5f,
        label = "tvSeekTrackHeight"
    )

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(TV_SEEK_BAR_HEIGHT_DP.dp)
            .onFocusChanged {
                isFocused = it.isFocused
                if (!it.isFocused && isScrubbing) {
                    isScrubbing = false
                    scrubProgress = progress.coerceIn(0f, 1f)
                    onScrubStateChange(false)
                }
            }
            .onPreviewKeyEvent { event ->
                if (!isFocused) return@onPreviewKeyEvent false
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false

                when (event.key) {
                    Key.DirectionLeft -> {
                        if (!isScrubbing) {
                            isScrubbing = true
                            scrubProgress = progress.coerceIn(0f, 1f)
                            onScrubStateChange(true)
                        }
                        scrubProgress = (scrubProgress - TV_SEEK_STEP_FRACTION).coerceIn(0f, 1f)
                        true
                    }
                    Key.DirectionRight -> {
                        if (!isScrubbing) {
                            isScrubbing = true
                            scrubProgress = progress.coerceIn(0f, 1f)
                            onScrubStateChange(true)
                        }
                        scrubProgress = (scrubProgress + TV_SEEK_STEP_FRACTION).coerceIn(0f, 1f)
                        true
                    }
                    Key.Enter, Key.NumPadEnter, Key.DirectionCenter -> {
                        if (isScrubbing) {
                            isScrubbing = false
                            onSeek(scrubProgress)
                            onScrubStateChange(false)
                        }
                        true
                    }
                    Key.Back -> {
                        if (isScrubbing) {
                            isScrubbing = false
                            scrubProgress = progress.coerceIn(0f, 1f)
                            onScrubStateChange(false)
                            true
                        } else false
                    }
                    else -> false
                }
            }
            .focusable()
    ) {
        val renderedProgress = scrubProgress.coerceIn(0f, 1f)

        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(TV_SEEK_BAR_HEIGHT_DP.dp)
                .align(Alignment.Center)
        ) {
            val yOffset = size.height / 2
            val trackInset = 4.dp.toPx()
            val trackStart = Offset(trackInset, yOffset)
            val trackEnd = Offset(size.width - trackInset, yOffset)
            val currentTrackHeight = size.height * trackHeight
            val markerSpacingPx = 8.dp.toPx()
            val markerStrokeWidth = 2.dp.toPx()
            val markerVerticalInset = 2.dp.toPx()

            drawLine(
                color = if (isFocused) Color.White.copy(alpha = 0.4f)
                else Color.White.copy(alpha = 0.25f),
                start = trackStart,
                end = trackEnd,
                strokeWidth = currentTrackHeight,
                cap = StrokeCap.Round
            )

            if (renderedProgress > 0f) {
                val progressX = trackStart.x + (trackEnd.x - trackStart.x) * renderedProgress
                drawLine(
                    color = TvAccentGreen,
                    start = trackStart,
                    end = Offset(progressX, yOffset),
                    strokeWidth = currentTrackHeight,
                    cap = StrokeCap.Round
                )

                if (isFocused) {
                    drawCircle(
                        color = Color.White,
                        radius = size.height * 0.65f,
                        center = Offset(progressX, yOffset)
                    )
                }
            }

            if (duration > 0L && chapterMarkers.isNotEmpty()) {
                var lastMarkerX = Float.NEGATIVE_INFINITY
                chapterMarkers.forEach { marker ->
                    val markerProgress = (marker.positionMs.toFloat() / duration.toFloat())
                        .coerceIn(0f, 1f)
                    if (markerProgress <= 0f || markerProgress >= 1f) return@forEach

                    val markerX = trackStart.x + (trackEnd.x - trackStart.x) * markerProgress
                    if (abs(markerX - lastMarkerX) < markerSpacingPx) return@forEach

                    drawLine(
                        color = Color.White.copy(alpha = 0.85f),
                        start = Offset(markerX, yOffset - currentTrackHeight / 2f + markerVerticalInset),
                        end = Offset(markerX, yOffset + currentTrackHeight / 2f - markerVerticalInset),
                        strokeWidth = markerStrokeWidth,
                        cap = StrokeCap.Round
                    )
                    lastMarkerX = markerX
                }
            }
        }

        AnimatedVisibility(
            visible = isScrubbing && duration > 0L,
            modifier = Modifier.align(Alignment.TopCenter),
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            Surface(
                color = Color.Black.copy(alpha = 0.90f),
                shape = RoundedCornerShape(10.dp),
                tonalElevation = 4.dp,
                shadowElevation = 6.dp
            ) {
                Text(
                    text = formatTime((duration * renderedProgress).toLong()),
                    color = Color.White,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
                )
            }
        }
    }
}

private fun replayIcon(seconds: Int): ImageVector {
    return when (seconds) {
        5 -> Icons.Filled.Replay5
        10 -> Icons.Filled.Replay10
        30 -> Icons.Filled.Replay30
        else -> Icons.Filled.Replay
    }
}

private fun replayforwardIcon(seconds: Int): ImageVector {
    return when (seconds) {
        5 -> Icons.Filled.Forward5
        10 -> Icons.Filled.Forward10
        30 -> Icons.Filled.Forward30
        else -> Icons.Filled.Replay
    }
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

@Preview(
    name = "TV Controls - Playing",
    showBackground = true,
    widthDp = 960,
    heightDp = 540,
    backgroundColor = 0xFF000000
)
@Composable
fun ControlsOverlayPreviewPlaying() {
    ControlsOverlay(
        title = "The Matrix Reloaded",
        chapterMarkers = listOf(
            ChapterMarker(positionMs = 540000L, label = "Chapter 1"),
            ChapterMarker(positionMs = 1740000L, label = "Chapter 2"),
            ChapterMarker(positionMs = 3120000L, label = "Chapter 3")
        ),
        isPlaying = true,
        currentPosition = 1800000L,
        duration = 8280000L,
        onBackClick = { },
        onPlayPause = { },
        onSeek = { },
        spatializationResult = SpatializationResult(
            canSpatialize = true,
            reason = "Content and device support spatial audio",
            spatialFormat = "Dolby Atmos"
        ),
        isSpatialAudioEnabled = true,
        onShowMediaInfo = { },
        onShowAudioTrackSelection = { },
        onShowSubtitleTrackSelection = { },
        onCycleAspectRatio = { },
        onSeekBackward = { },
        onSeekForward = { },
        canPlayPreviousEpisode = true,
        canPlayNextEpisode = true
    )
}

@Preview(
    name = "TV Controls - Paused",
    showBackground = true,
    widthDp = 960,
    heightDp = 540,
    backgroundColor = 0xFF000000
)
@Composable
fun ControlsOverlayPreviewPaused() {
    ControlsOverlay(
        title = "Inception",
        chapterMarkers = listOf(
            ChapterMarker(positionMs = 600000L, label = "Dream 1"),
            ChapterMarker(positionMs = 2520000L, label = "Dream 2"),
            ChapterMarker(positionMs = 5100000L, label = "Dream 3")
        ),
        isPlaying = false,
        currentPosition = 3600000L,
        duration = 8880000L,
        onBackClick = { },
        onPlayPause = { },
        onSeek = { },
        spatializationResult = SpatializationResult(
            canSpatialize = true,
            reason = "Content and device support spatial audio",
            spatialFormat = "DTS:X"
        ),
        isSpatialAudioEnabled = false,
        onShowMediaInfo = { },
        onShowAudioTrackSelection = { },
        onShowSubtitleTrackSelection = { },
        onCycleAspectRatio = { },
        onSeekBackward = { },
        onSeekForward = { }
    )
}
