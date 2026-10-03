@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.vela.app.ui.screens.player

import android.app.ActivityManager
import android.content.Context
import android.content.pm.ActivityInfo
import android.media.AudioManager
import android.net.TrafficStats
import android.os.Build
import android.os.SystemClock
import android.view.View
import android.view.WindowManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import coil3.SingletonImageLoader
import com.vela.shared.R
import com.vela.shared.ui.theme.velaMotion
import com.vela.data.model.AudioTranscodeMode
import com.vela.data.model.BaseItemDto
import com.vela.player.core.PlayerConstants.CONTROLS_AUTO_HIDE_DELAY
import com.vela.player.core.PlayerConstants.GESTURE_INDICATOR_HIDE_DELAY
import com.vela.player.core.PlayerConstants.NEXT_EPISODE_AUTOPLAY_DELAY
import com.vela.player.core.PlayerConstants.NEXT_EPISODE_PROGRESS_UPDATE_DELAY
import com.vela.player.core.PlayerState
import com.vela.player.core.SkippableSegmentAction
import com.vela.player.core.SkippableSegmentType
import com.vela.player.preferences.PlayerPreferences
import com.vela.app.ui.player.findActivity
import kotlinx.coroutines.delay
import java.util.Locale

private const val PLAYER_POSITION_UPDATE_ACTIVE_MS = 250L
private const val PLAYER_POSITION_UPDATE_IDLE_MS = 750L

/**
 * 低内存设备上起播前清空图片内存缓存，给解码器和 Surface 腾空间。
 * 内存充裕的设备保留缓存：否则返回详情页时所有图片都要重新解码，表现为闪烁和掉帧；
 * 真正吃紧时 Coil 会通过 onTrimMemory 自行回收。
 */
private fun trimImageMemoryCacheForPlayback(context: Context) {
    val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
    val constrained = activityManager == null ||
        activityManager.isLowRamDevice ||
        activityManager.memoryClass <= LOW_MEMORY_CLASS_MB
    if (!constrained) return
    runCatching {
        SingletonImageLoader.get(context).memoryCache?.clear()
    }
}

/** 应用堆上限不超过该值（MB）时视为内存吃紧。 */
private const val LOW_MEMORY_CLASS_MB = 192

@Composable
internal fun PlayerScreenEffects(
    context: Context,
    currentView: View,
    lifecycleOwner: LifecycleOwner,
    mediaId: String,
    initialItemDetails: BaseItemDto?,
    remoteMediaUrl: String?,
    remoteMediaTitle: String?,
    preferredAudioStreamIndex: Int?,
    preferredSubtitleStreamIndex: Int?,
    startFromBeginning: Boolean = false,
    initialSeekPositionMs: Long? = null,
    mediaSourceId: String? = null,
    compactPlayback: Boolean = false,
    viewModel: PlayerViewModel,
    onPlaybackCompleted: ((String) -> Unit)?,
    preferredStreamIndexes: PreferredStreamIndexes,
    playerState: PlayerState,
    useDeviceVolumeInPlayer: Boolean,
    audioManager: AudioManager,
    originalVolume: Int,
    playerBrightness: Float,
    playerVolume: Float,
    showAudioTrackDialog: Boolean,
    showSubtitleTrackDialog: Boolean,
    showStreamingQualityDialog: Boolean,
    showAudioTranscodingDialog: Boolean,
    showMediaInfo: Boolean,
    showVrProjectionDialog: Boolean = false,
    autoHideKey: Int,
    /** 拖动进度或菜单打开时为 true，期间不自动隐藏控制层。 */
    autoHideHeld: Boolean,
    hideSystemBars: () -> Unit,
    uiStateProvider: () -> PlayerUiState,
    onUiStateChange: (PlayerUiState) -> Unit,
    playbackProgress: PlaybackProgressState,
    initializedMediaIdProvider: () -> String?,
    onInitializedMediaIdChange: (String?) -> Unit,
    onLifecycleChange: (Lifecycle.Event) -> Unit,
    onCurrentAudioTranscodeModeChange: (AudioTranscodeMode) -> Unit,
    onPreferredStreamIndexesChanged: (Int?, Int?) -> Unit,
    playerOrientation: String = PlayerPreferences.DEFAULT_PLAYER_ORIENTATION
) {
    // 方向切换只发一次请求：恢复原方向放在离开播放页时的 onDispose 里，
    // 不能跟随 playerOrientation 重启，否则每次旋转都会先转回原方向再转到目标方向。
    LaunchedEffect(playerOrientation, compactPlayback) {
        if (compactPlayback) return@LaunchedEffect
        val activity = context.findActivity() ?: return@LaunchedEffect
        val requested = requestedOrientationFor(playerOrientation)
        if (activity.requestedOrientation != requested) {
            activity.requestedOrientation = requested
        }
        if (playerOrientation != PlayerPreferences.PLAYER_ORIENTATION_PORTRAIT) {
            hideSystemBars()
        }
    }

    DisposableEffect(Unit) {
        currentView.keepScreenOn = true
        val activity = context.findActivity()
        val originalRequestedOrientation = activity?.requestedOrientation
        activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        onDispose {
            currentView.keepScreenOn = false
            activity?.let { act ->
                // 正在关闭的播放页不再改方向：否则退出动画期间窗口会多转一次，下层页面按自己的方向恢复。
                if (!act.isFinishing) {
                    act.requestedOrientation =
                        originalRequestedOrientation ?: ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                }
                act.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                WindowCompat.getInsetsController(act.window, act.window.decorView)
                    .show(WindowInsetsCompat.Type.systemBars())
            }
        }
    }

    DisposableEffect(useDeviceVolumeInPlayer) {
        val activity = context.findActivity()
        onDispose {
            if (!useDeviceVolumeInPlayer) {
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, originalVolume, 0)
            }
            activity?.let { act ->
                val layoutParams = act.window.attributes
                layoutParams.screenBrightness = -1f
                act.window.attributes = layoutParams
            }
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            onLifecycleChange(event)
            val activity = context.findActivity()
            if (
                event == Lifecycle.Event.ON_STOP &&
                activity?.isInPictureInPictureMode != true &&
                activity?.isChangingConfigurations != true
            ) {
                // ON_PAUSE 也会在进入 PiP 时触发；仅真正退到后台后暂停，并保留用户原有的暂停状态。
                viewModel.pause()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    val hdrPassthrough = remember { !PlayerPreferences(context).getMpvHdrToSdrTonemapping() }
    DisposableEffect(viewModel.mpvPlayer, playerState.isHdrEnabled, hdrPassthrough) {
        val activity = context.findActivity()
        val shouldUseHdrColorMode = viewModel.mpvPlayer != null && playerState.isHdrEnabled && hdrPassthrough
        val originalColorMode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            activity?.window?.colorMode
        } else {
            null
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && activity != null) {
            if (shouldUseHdrColorMode) {
                activity.window.colorMode = ActivityInfo.COLOR_MODE_HDR
                if (Build.VERSION.SDK_INT >= 34) {
                    activity.window.setDesiredHdrHeadroom(4.0f)
                }
            } else {
                activity.window.colorMode = ActivityInfo.COLOR_MODE_DEFAULT
                if (Build.VERSION.SDK_INT >= 34) {
                    activity.window.setDesiredHdrHeadroom(1.0f)
                }
            }
        }

        onDispose {
            if (
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                activity != null &&
                originalColorMode != null
            ) {
                activity.window.colorMode = originalColorMode
                if (Build.VERSION.SDK_INT >= 34) {
                    activity.window.setDesiredHdrHeadroom(1.0f)
                }
            }
        }
    }

    val initializationKey = remoteMediaUrl
        ?.takeIf { it.isNotBlank() }
        ?.let { "remote:$it" }
        ?: mediaId

    LaunchedEffect(initializationKey) {
        if (initializedMediaIdProvider() == initializationKey) return@LaunchedEffect

        try {
            trimImageMemoryCacheForPlayback(context)
            if (initializedMediaIdProvider() != null) {
                viewModel.releasePlayer()
            }
            playbackProgress.positionMs = 0L
            playbackProgress.bufferedMs = 0L
            onUiStateChange(uiStateProvider().copy(isPlaying = false))
            if (!remoteMediaUrl.isNullOrBlank()) {
                viewModel.initializeRemotePlayer(
                    context = context,
                    mediaId = initializationKey,
                    remoteUrl = remoteMediaUrl,
                    title = remoteMediaTitle
                )
            } else {
                viewModel.initializePlayer(
                    context = context,
                    mediaId = mediaId,
                    initialItemDetails = initialItemDetails,
                    preferredAudioStreamIndex = preferredAudioStreamIndex,
                    preferredSubtitleStreamIndex = preferredSubtitleStreamIndex,
                    initialSeekPositionMs = initialSeekPositionMs,
                    startFromBeginning = startFromBeginning,
                    mediaSourceId = mediaSourceId
                )
            }
            onInitializedMediaIdChange(initializationKey)
        } catch (_: Exception) {
        }
    }

    LaunchedEffect(viewModel, onPlaybackCompleted) {
        viewModel.playbackCompletedEvents.collect { completedMediaId ->
            onPlaybackCompleted?.invoke(completedMediaId)
        }
    }

    LaunchedEffect(viewModel.exoPlayer, viewModel.mpvPlayer) {
        while (true) {
            val currentPosition = viewModel.getCurrentPosition()
            val bufferedPosition = viewModel.getBufferedPosition()
            val isPlayingNow = viewModel.isPlayingNow()
            val uiState = uiStateProvider()
            // 进度写入独立 state，只有读取它的进度条/时间会刷新；播放状态变化才更新整页 uiState。
            playbackProgress.positionMs = currentPosition
            playbackProgress.bufferedMs = bufferedPosition
            if (uiState.isPlaying != isPlayingNow) {
                onUiStateChange(uiState.copy(isPlaying = isPlayingNow))
            }
            // 控件显示时保持进度灵敏；隐藏后降低整屏重组频率，跳过片段判断仍保持亚秒级响应。
            delay(
                if (uiState.controlsVisible || playerState.isLocked) {
                    PLAYER_POSITION_UPDATE_ACTIVE_MS
                } else {
                    PLAYER_POSITION_UPDATE_IDLE_MS
                }
            )
        }
    }

    LaunchedEffect(
        initializedMediaIdProvider(),
        preferredStreamIndexes.audioStreamIndex,
        preferredStreamIndexes.subtitleStreamIndex
    ) {
        if (initializedMediaIdProvider() == initializationKey && remoteMediaUrl.isNullOrBlank()) {
            onPreferredStreamIndexesChanged(
                preferredStreamIndexes.audioStreamIndex,
                preferredStreamIndexes.subtitleStreamIndex
            )
        }
    }

    LaunchedEffect(playerState.currentAudioTranscodeMode) {
        onCurrentAudioTranscodeModeChange(playerState.currentAudioTranscodeMode)
    }

    LaunchedEffect(playerVolume, playerOrientation) {
        val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val appliedVolume = (playerVolume * maxVolume).toInt().coerceIn(0, maxVolume)
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, appliedVolume, 0)
    }

    LaunchedEffect(Unit) {
        val activity = context.findActivity()
        activity?.let { act ->
            val layoutParams = act.window.attributes
            layoutParams.screenBrightness = playerBrightness
            act.window.attributes = layoutParams
        }
    }

    LaunchedEffect(uiStateProvider().volumeLevel) {
        if (uiStateProvider().volumeLevel != null) {
            delay(CONTROLS_AUTO_HIDE_DELAY)
            onUiStateChange(uiStateProvider().copy(volumeLevel = null))
        }
    }

    LaunchedEffect(uiStateProvider().brightnessLevel) {
        if (uiStateProvider().brightnessLevel != null) {
            delay(CONTROLS_AUTO_HIDE_DELAY)
            onUiStateChange(uiStateProvider().copy(brightnessLevel = null))
        }
    }

    LaunchedEffect(
        uiStateProvider().seekPosition,
        uiStateProvider().seekFeedbackId
    ) {
        if (uiStateProvider().seekPosition != null) {
            delay(GESTURE_INDICATOR_HIDE_DELAY)
            onUiStateChange(uiStateProvider().copy(seekPosition = null))
        }
    }

    LaunchedEffect(
        uiStateProvider().controlsVisible,
        showAudioTrackDialog,
        showSubtitleTrackDialog,
        showStreamingQualityDialog,
        showAudioTranscodingDialog,
        showMediaInfo,
        showVrProjectionDialog
    ) {
        if (
            uiStateProvider().controlsVisible ||
            showAudioTrackDialog ||
            showSubtitleTrackDialog ||
            showStreamingQualityDialog ||
            showAudioTranscodingDialog ||
            showMediaInfo ||
            showVrProjectionDialog
        ) {
            hideSystemBars()
        }
    }

    LaunchedEffect(
        uiStateProvider().controlsVisible,
        playerState.hasStartedPlayback,
        autoHideKey,
        autoHideHeld
    ) {
        if (uiStateProvider().controlsVisible && playerState.hasStartedPlayback && !autoHideHeld) {
            delay(CONTROLS_AUTO_HIDE_DELAY)
            onUiStateChange(uiStateProvider().copy(controlsVisible = false))
        }
    }
}

@Composable
internal fun BoxScope.PlayerOverlayHost(
    uiState: PlayerUiState,
    playerState: PlayerState,
    hasPlaybackSettings: Boolean,
    chapterMarkersEnabled: Boolean,
    seekBackwardSeconds: Int,
    seekForwardSeconds: Int,
    activeSkippableSegment: SkippableSegmentAction?,
    activeCreditsSegment: SkippableSegmentAction?,
    dismissedCreditsPrompt: Boolean,
    canWatchPreviousEpisode: Boolean,
    canWatchNextEpisode: Boolean,
    viewModel: PlayerViewModel,
    onBackPressed: (() -> Unit)?,
    resetAutoHideTimer: () -> Unit,
    onAutoHideHoldChange: (Boolean) -> Unit,
    onWatchCredits: () -> Unit,
    onWatchPreviousEpisode: () -> Unit,
    onWatchNextEpisode: () -> Unit,
    onShowMediaInfo: () -> Unit,
    onShowStreamingQualityDialog: () -> Unit,
    onShowAudioTranscodingDialog: () -> Unit,
    onShowAudioTrackDialog: () -> Unit,
    onShowSubtitleTrackDialog: () -> Unit,
    onAdjustVideoSize: () -> Unit = {},
    onToggleOrientation: () -> Unit = {},
    onTitleClick: () -> Unit = {},
    onEnterPip: () -> Unit = {},
    onShowChapters: () -> Unit = {},
    onShowVrProjection: () -> Unit = {},
    onSeekFeedback: (String, SeekSide) -> Unit = { _, _ -> },
    onPositionChanged: (Long) -> Unit = {},
    playbackProgress: PlaybackProgressState,
    sleepTimerDeadline: Long? = null,
    onSetSleepTimer: (Int?) -> Unit = {},
    onAddLocalSubtitle: () -> Unit = {},
    onShowSubtitleStyle: () -> Unit = {},
    onShowSubtitleDelay: () -> Unit = {}
): Unit {
    var nextEpisodeButtonProgress by remember(
        activeCreditsSegment?.startMs,
        activeCreditsSegment?.endMs,
        canWatchNextEpisode,
        dismissedCreditsPrompt
    ) {
        mutableFloatStateOf(0f)
    }

    LaunchedEffect(
        activeCreditsSegment?.startMs,
        activeCreditsSegment?.endMs,
        canWatchNextEpisode,
        dismissedCreditsPrompt
    ) {
        nextEpisodeButtonProgress = 0f

        if (activeCreditsSegment == null || !canWatchNextEpisode || dismissedCreditsPrompt) {
            return@LaunchedEffect
        }

        var elapsedMs = 0L
        while (elapsedMs < NEXT_EPISODE_AUTOPLAY_DELAY) {
            nextEpisodeButtonProgress =
                elapsedMs.toFloat() / NEXT_EPISODE_AUTOPLAY_DELAY.toFloat()
            delay(NEXT_EPISODE_PROGRESS_UPDATE_DELAY)
            elapsedMs += NEXT_EPISODE_PROGRESS_UPDATE_DELAY
        }

        nextEpisodeButtonProgress = 1f
        onWatchNextEpisode()
    }

    // 短暂加载（<280ms）不出加载圈，避免每次 seek 都闪一下。
    var showLoadingOverlay by remember { mutableStateOf(false) }
    LaunchedEffect(playerState.isLoading) {
        if (playerState.isLoading) {
            delay(280)
            showLoadingOverlay = true
        } else {
            showLoadingOverlay = false
        }
    }

    // 控制层整体淡入淡出；顶栏、底栏、两侧和中央按各自方向做位移/缩放，由 ControlsOverlay 内部声明。
    val controlsMotion = MaterialTheme.velaMotion
    val mpvActive = viewModel.mpvPlayer != null
    AnimatedVisibility(
        visible = uiState.controlsVisible,
        enter = fadeIn(controlsMotion.defaultEffectsSpec()),
        exit = fadeOut(controlsMotion.fastEffectsSpec()),
        modifier = Modifier.fillMaxSize()
    ) {
        ControlsOverlay(
            visibilityScope = this,
            title = playerState.mediaTitle,
            seasonEpisodeLabel = playerState.seasonEpisodeLabel,
            seriesName = playerState.seriesName,
            logoUrl = playerState.mediaLogoUrl,
            chapterMarkers = if (chapterMarkersEnabled) playerState.chapterMarkers else emptyList(),
            isPlaying = playerState.playWhenReady,
            isBuffering = showLoadingOverlay,
            positionMs = { playbackProgress.positionMs },
            bufferedMs = { playbackProgress.bufferedMs },
            duration = viewModel.getDuration(),
            onBackClick = {
                onBackPressed?.invoke()
            },
            onPlayPause = {
                resetAutoHideTimer()
                if (playerState.playWhenReady) {
                    viewModel.pause()
                } else {
                    viewModel.play()
                }
            },
            onSeek = { progress ->
                resetAutoHideTimer()
                viewModel.seekToProgress(progress, exact = true)
                onPositionChanged(viewModel.getCurrentPosition())
            },
            onAutoHideHoldChange = { held ->
                onAutoHideHoldChange(held)
                resetAutoHideTimer()
            },
            scrubPreviewFrame = viewModel.scrubPreviewFrame,
            onScrubPreviewPositionChange = { positionMs ->
                if (positionMs == null) {
                    viewModel.clearScrubPreview()
                } else {
                    viewModel.requestScrubPreview(positionMs)
                }
            },
            isHdrEnabled = playerState.isHdrEnabled,
            hdrFormat = playerState.hdrFormat,
            onShowMediaInfo = {
                resetAutoHideTimer()
                onShowMediaInfo()
            },
            isLocked = playerState.isLocked,
            onToggleLock = {
                resetAutoHideTimer()
                viewModel.toggleLock()
            },
            showPlaybackSettingsButton = hasPlaybackSettings,
            onShowPlaybackSettings = {
                resetAutoHideTimer()
                if (playerState.isVideoTranscodingAllowed) {
                    onShowStreamingQualityDialog()
                } else if (playerState.isAudioTranscodingAllowed) {
                    onShowAudioTranscodingDialog()
                }
            },
            onShowAudioTrackSelection = {
                resetAutoHideTimer()
                onShowAudioTrackDialog()
            },
            onShowSubtitleTrackSelection = {
                resetAutoHideTimer()
                onShowSubtitleTrackDialog()
            },
            onAdjustVideoSize = {
                resetAutoHideTimer()
                onAdjustVideoSize()
            },
            aspectZoomed = playerState.aspectRatioMode == "Zoom",
            onCycleAspectRatio = {
                resetAutoHideTimer()
                viewModel.cycleAspectRatio()
            },
            onToggleOrientation = {
                resetAutoHideTimer()
                onToggleOrientation()
            },
            onTitleClick = {
                resetAutoHideTimer()
                onTitleClick()
            },
            onSeekBackward = {
                resetAutoHideTimer()
                viewModel.seekBackward()
                onPositionChanged(viewModel.getCurrentPosition())
                onSeekFeedback("-${seekBackwardSeconds}s", SeekSide.LEFT)
            },
            onSeekForward = {
                resetAutoHideTimer()
                viewModel.seekForward()
                onPositionChanged(viewModel.getCurrentPosition())
                onSeekFeedback("+${seekForwardSeconds}s", SeekSide.RIGHT)
            },
            seekBackwardSeconds = seekBackwardSeconds,
            seekForwardSeconds = seekForwardSeconds,
            canPlayPreviousEpisode = canWatchPreviousEpisode,
            canPlayNextEpisode = canWatchNextEpisode,
            onPlayPreviousEpisode = {
                resetAutoHideTimer()
                onWatchPreviousEpisode()
            },
            onPlayNextEpisode = {
                resetAutoHideTimer()
                onWatchNextEpisode()
            },
            onEnterPip = {
                resetAutoHideTimer()
                onEnterPip()
            },
            // 硬解开关和截图只对 MPV 生效；ExoPlayer 下隐藏入口，避免点了没反应。
            hardwareDecodingLabel = if (mpvActive) hardwareDecodingLabel(playerState.hardwareDecoding) else null,
            onToggleHardwareDecoding = {
                resetAutoHideTimer()
                viewModel.toggleHardwareDecoding()
            },
            onScreenshot = if (mpvActive) {
                {
                    resetAutoHideTimer()
                    viewModel.captureScreenshot()
                }
            } else {
                null
            },
            mpvEngineActive = mpvActive,
            onSwitchPlayerEngine = if (viewModel.canSwitchPlayerEngine()) {
                {
                    resetAutoHideTimer()
                    viewModel.switchPlayerEngine()
                }
            } else {
                null
            },
            sleepTimerDeadline = sleepTimerDeadline,
            onSetSleepTimer = { minutes ->
                resetAutoHideTimer()
                onSetSleepTimer(minutes)
            },
            onAddLocalSubtitle = {
                resetAutoHideTimer()
                onAddLocalSubtitle()
            },
            onShowSubtitleStyle = onShowSubtitleStyle,
            onShowSubtitleDelay = onShowSubtitleDelay,
            onShowChapters = {
                resetAutoHideTimer()
                onShowChapters()
            },
            onSetPlaybackSpeed = { speed ->
                resetAutoHideTimer()
                viewModel.setPlaybackSpeed(speed)
            },
            playbackSpeed = playerState.playbackSpeed,
            vrDetected = playerState.vrDetected,
            vrFlatEnabled = playerState.vrFlatEnabled,
            onToggleVrFlat = {
                resetAutoHideTimer()
                viewModel.toggleVrFlatPlayback()
            },
            onShowVrProjection = {
                resetAutoHideTimer()
                onShowVrProjection()
            },
            onUserInteraction = resetAutoHideTimer,
            skipActionLabel = when (activeSkippableSegment?.type) {
                SkippableSegmentType.RECAP -> stringResource(R.string.player_skip_recap)
                SkippableSegmentType.PREVIEW -> stringResource(R.string.player_skip_preview)
                SkippableSegmentType.INTRO -> stringResource(R.string.player_skip_intro)
                else -> null
            },
            onSkipAction = {
                resetAutoHideTimer()
                activeSkippableSegment?.seekToMs?.let(viewModel::seekTo)
            },
            modifier = Modifier.fillMaxSize()
        )
    }

    AnimatedVisibility(
        visible = activeCreditsSegment != null &&
            !dismissedCreditsPrompt,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = Modifier
            .align(Alignment.BottomEnd)
            .padding(end = 24.dp, bottom = 28.dp)
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Button(
                onClick = {
                    resetAutoHideTimer()
                    onWatchCredits()
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color.Black.copy(alpha = 0.55f),
                    contentColor = Color.White
                ),
                contentPadding = PaddingValues(horizontal = 20.dp)
            ) {
                Text(
                    text = stringResource(R.string.player_watch_credits),
                    style = MaterialTheme.typography.labelLarge
                )
            }

            if (canWatchNextEpisode) {
                NextEpisodeProgressPill(
                    label = stringResource(R.string.player_watch_next_episode),
                    progressFraction = nextEpisodeButtonProgress,
                    onClick = {
                        resetAutoHideTimer()
                        onWatchNextEpisode()
                    }
                )
            }
        }
    }

    GestureIndicators(
        volumeLevel = uiState.volumeLevel,
        brightnessLevel = uiState.brightnessLevel,
        seekPosition = uiState.seekPosition,
        seekSide = uiState.seekSide,
        swipeSeekPositionMs = uiState.swipeSeekPositionMs,
        swipeSeekDurationMs = playerState.duration.takeIf { it > 0L } ?: viewModel.getDuration(),
        holdSpeedLabel = uiState.holdSpeedLabel,
        controlsVisible = uiState.controlsVisible
    )

    // 控制层可见时加载圈显示在播放键内，这里只负责控制层隐藏时的独立加载圈。
    AnimatedVisibility(
        visible = showLoadingOverlay && !uiState.controlsVisible,
        enter = fadeIn(controlsMotion.defaultEffectsSpec()),
        exit = fadeOut(controlsMotion.fastEffectsSpec()),
        modifier = Modifier.fillMaxSize()
    ) {
        BufferingIndicator()
    }
}

/**
 * 缓冲指示：只在加载超过 280ms 后出现，并附带当前下行速率，帮助判断是网络慢还是解码慢。
 * 速率取设备总下行流量（TrafficStats），包含其他应用流量，只作参考。
 */
@Composable
private fun BufferingIndicator() {
    var speedLabel by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        var lastRx = TrafficStats.getTotalRxBytes()
        var lastTime = SystemClock.elapsedRealtime()
        while (true) {
            delay(1_000)
            val now = SystemClock.elapsedRealtime()
            val rx = TrafficStats.getTotalRxBytes()
            speedLabel = if (rx >= 0L && lastRx >= 0L) {
                val bytesPerSecond = (rx - lastRx).coerceAtLeast(0L) * 1000.0 / (now - lastTime).coerceAtLeast(1L)
                formatTransferRate(bytesPerSecond)
            } else {
                null
            }
            lastRx = rx
            lastTime = now
        }
    }
    // 加载圈与播放键同尺寸、同在屏幕正中；速率文字只做偏移绘制，不参与居中计算。
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Box(
            modifier = Modifier
                .size(TransportPlayButtonSize)
                .background(Color.Black.copy(alpha = 0.32f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            BufferingSpinner()
        }
        Text(
            text = speedLabel.orEmpty(),
            style = MaterialTheme.typography.labelMedium.merge(TextStyle(fontFeatureSettings = "tnum")),
            color = Color.White.copy(alpha = 0.8f),
            modifier = Modifier.offset(y = TransportPlayButtonSize / 2 + 18.dp)
        )
    }
}

/**
 * mpv 解码方式的简写，沿用 mpv-android 的约定：mediacodec 直出为 HW+，copy 回读为 HW，软解为 SW。
 */
private fun hardwareDecodingLabel(mode: String): String = when (mode) {
    PlayerPreferences.MPV_HARDWARE_DECODING_NONE -> "SW"
    PlayerPreferences.MPV_HARDWARE_DECODING_MEDIACODEC_COPY -> "HW"
    else -> "HW+"
}

internal fun formatTransferRate(bytesPerSecond: Double): String {
    val kb = bytesPerSecond / 1024.0
    return if (kb < 1024.0) {
        String.format(Locale.US, "%.0f KB/s", kb)
    } else {
        String.format(Locale.US, "%.1f MB/s", kb / 1024.0)
    }
}

@Composable
private fun NextEpisodeProgressPill(
    label: String,
    progressFraction: Float,
    onClick: () -> Unit
) {
    val clampedProgress = progressFraction.coerceIn(0f, 1f)
    val pillShape = RoundedCornerShape(999.dp)

    Box(
        modifier = Modifier
            .clip(pillShape)
            .drawBehind {
                drawRect(color = Color.Black.copy(alpha = 0.55f))
                if (clampedProgress > 0f) {
                    drawRect(
                        color = Color.White,
                        size = Size(size.width * clampedProgress, size.height)
                    )
                }
            }
            .clickable(onClick = onClick)
            .heightIn(min = ButtonDefaults.MinHeight)
            .padding(horizontal = 20.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.drawWithContent {
                val progressEdge = (size.width * clampedProgress).coerceIn(0f, size.width)
                clipRect(left = progressEdge) {
                    this@drawWithContent.drawContent()
                }
            },
            color = Color.White
        )

        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.drawWithContent {
                val progressEdge = (size.width * clampedProgress).coerceIn(0f, size.width)
                val overlapPx = 1.dp.toPx()
                clipRect(right = (progressEdge + overlapPx).coerceAtMost(size.width)) {
                    this@drawWithContent.drawContent()
                }
            },
            color = Color.Black
        )
    }
}

@Composable
internal fun PlayerDialogsHost(
    playerState: PlayerState,
    showAudioTrackDialog: Boolean,
    showSubtitleTrackDialog: Boolean,
    showStreamingQualityDialog: Boolean,
    showAudioTranscodingDialog: Boolean,
    showMediaInfo: Boolean,
    availableStreamingQualityOptions: List<String>,
    currentStreamingQuality: String,
    currentAudioTranscodeMode: AudioTranscodeMode,
    mediaInfoSnapshot: MediaMetadataInfo?,
    onAudioTrackSelected: (String) -> Unit,
    onSubtitleTrackSelected: (String) -> Unit,
    onStreamingQualitySelected: (String) -> Unit,
    onAudioTranscodingSelected: (AudioTranscodeMode) -> Unit,
    onDismissAudioTrackDialog: () -> Unit,
    onDismissSubtitleTrackDialog: () -> Unit,
    onDismissStreamingQualityDialog: () -> Unit,
    onDismissAudioTranscodingDialog: () -> Unit,
    onDismissMediaInfo: () -> Unit,
    showVrProjectionDialog: Boolean = false,
    currentVrProjectionId: String? = null,
    onVrProjectionSelected: (String) -> Unit = {},
    onDismissVrProjectionDialog: () -> Unit = {},
    onAddLocalSubtitle: (() -> Unit)? = null,
    onShowSubtitleStyle: (() -> Unit)? = null,
    onShowSubtitleDelay: (() -> Unit)? = null
) {
    AudioTrackSelectionDialog(
        isVisible = showAudioTrackDialog,
        audioTracks = playerState.availableAudioTracks,
        currentAudioTrack = playerState.currentAudioTrack,
        onTrackSelected = onAudioTrackSelected,
        onDismiss = onDismissAudioTrackDialog
    )

    SubtitleTrackSelectionDialog(
        isVisible = showSubtitleTrackDialog,
        subtitleTracks = playerState.availableSubtitleTracks,
        currentSubtitleTrack = playerState.currentSubtitleTrack,
        onTrackSelected = onSubtitleTrackSelected,
        onDismiss = onDismissSubtitleTrackDialog,
        onAddLocalSubtitle = onAddLocalSubtitle,
        onShowSubtitleStyle = onShowSubtitleStyle,
        onShowSubtitleDelay = onShowSubtitleDelay
    )

    StreamingQualitySelectionDialog(
        isVisible = showStreamingQualityDialog && playerState.isVideoTranscodingAllowed,
        qualityOptions = availableStreamingQualityOptions,
        currentQuality = currentStreamingQuality,
        onQualitySelected = onStreamingQualitySelected,
        onDismiss = onDismissStreamingQualityDialog
    )

    AudioTranscodingModeDialog(
        isVisible = showAudioTranscodingDialog && playerState.isAudioTranscodingAllowed,
        currentMode = currentAudioTranscodeMode,
        onModeSelected = onAudioTranscodingSelected,
        onDismiss = onDismissAudioTranscodingDialog
    )

    if (showMediaInfo) {
        mediaInfoSnapshot?.let { mediaInfo ->
            MediaInfoDialog(
                mediaInfo = mediaInfo,
                onDismiss = onDismissMediaInfo
            )
        }
    }

    VrProjectionSelectionDialog(
        isVisible = showVrProjectionDialog,
        currentProjectionId = currentVrProjectionId,
        onProjectionSelected = onVrProjectionSelected,
        onDismiss = onDismissVrProjectionDialog
    )
}

internal fun requestedOrientationFor(orientation: String): Int {
    return when (orientation) {
        PlayerPreferences.PLAYER_ORIENTATION_LANDSCAPE ->
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        PlayerPreferences.PLAYER_ORIENTATION_AUTO ->
            ActivityInfo.SCREEN_ORIENTATION_SENSOR
        else -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
    }
}
