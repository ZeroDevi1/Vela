package com.vela.app.ui.screens.player

import android.content.res.Configuration
import android.content.Context
import android.media.AudioManager
import android.os.SystemClock
import android.provider.Settings
import android.view.View
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.util.UnstableApi
import com.vela.shared.R
import com.vela.app.ui.player.PictureInPictureHost
import com.vela.app.ui.player.applyPlayerPipParams
import com.vela.app.ui.player.enterPlayerPip
import com.vela.app.ui.player.findActivity
import com.vela.app.ui.screens.player.PlayerViewModel
import com.vela.app.player.vr.VrLayoutParser
import com.vela.data.model.AudioTranscodeMode
import com.vela.data.model.BaseItemDto
import com.vela.data.repository.MediaRepositoryProvider
import com.vela.player.core.SkippableSegmentType
import com.vela.player.core.findActiveSkippableSegment
import com.vela.player.preferences.PlayerPreferences
import com.vela.app.playback.SystemMediaSessionEffect
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Player state data class to group related states
 */
data class PlayerUiState(
    val controlsVisible: Boolean = true,
    val isPlaying: Boolean = false,
    val volumeLevel: Float? = null,
    val brightnessLevel: Float? = null,
    val seekPosition: String? = null,
    val seekSide: SeekSide = SeekSide.CENTER,
    val seekFeedbackId: Long = 0L,
    val swipeSeekPositionMs: Long? = null,
    val holdSpeedLabel: String? = null,
    val videoScale: Float = 1f,
    val videoOffsetX: Float = 0f,
    val videoOffsetY: Float = 0f
)

/**
 * 播放进度单独成 state，不放进 [PlayerUiState]：进度每 250ms 刷新一次，
 * 只应让读取它的时间文字和进度条重组/重绘，不能牵动整页和视频 Surface。
 */
@Stable
class PlaybackProgressState {
    /** 当前播放位置，毫秒。 */
    var positionMs by mutableLongStateOf(0L)

    /** 已缓冲到的位置，毫秒。 */
    var bufferedMs by mutableLongStateOf(0L)
}

/**
 * Player Screen with proper immersive mode and gestures
 */
@UnstableApi
@Composable
fun PlayerScreen(
    mediaId: String,
    initialItemDetails: BaseItemDto? = null,
    remoteMediaUrl: String? = null,
    remoteMediaTitle: String? = null,
    preferredAudioStreamIndex: Int? = null,
    preferredSubtitleStreamIndex: Int? = null,
    startFromBeginning: Boolean = false,
    initialSeekPositionMs: Long? = null,
    mediaSourceId: String? = null,
    compactPlayback: Boolean = false,
    onEnterPip: (() -> Unit)? = null,
    onExpandFromMini: () -> Unit = {},
    modifier: Modifier = Modifier,
    viewModel: PlayerViewModel = hiltViewModel(),
    onPreferredStreamIndexesChanged: (Int?, Int?) -> Unit = { _, _ -> },
    onBackPressed: (() -> Unit)? = null,
    onPlaybackCompleted: ((String) -> Unit)? = null,
    previousEpisodeId: String? = null,
    onWatchPreviousEpisode: ((String) -> Unit)? = null,
    nextEpisodeId: String? = null,
    onWatchNextEpisode: ((String) -> Unit)? = null,
    playlist: List<BaseItemDto> = emptyList(),
    onPlaylistItemSelected: (String) -> Unit = {}
) {
    val context = LocalContext.current
    val currentView = LocalView.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // Consolidated UI state
    var uiState by remember { mutableStateOf(PlayerUiState()) }
    val playbackProgress = remember { PlaybackProgressState() }
    var lifecycle by remember { mutableStateOf(Lifecycle.Event.ON_CREATE) }
    var autoHideKey by remember { mutableStateOf(0) }
    var autoHideHeld by remember { mutableStateOf(false) }
    var dismissedCreditsPrompt by remember(mediaId) { mutableStateOf(false) }

    val hideSystemBars: () -> Unit = {
        context.findActivity()?.let { act ->
            val windowInsetsController = WindowCompat.getInsetsController(act.window, act.window.decorView)
            windowInsetsController?.apply {
                hide(WindowInsetsCompat.Type.systemBars())
                systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        }
        Unit
    }

    // Helper function to reset auto-hide timer
    val resetAutoHideTimer = {
        autoHideKey++
        hideSystemBars()
    }

    // Dialog states
    var showAudioTrackDialog by remember { mutableStateOf(false) }
    var showSubtitleTrackDialog by remember { mutableStateOf(false) }
    var showSubtitleStyleSheet by remember { mutableStateOf(false) }
    var showSubtitleDelaySheet by remember { mutableStateOf(false) }
    var showVideoWidthSheet by remember { mutableStateOf(false) }
    var showStreamingQualityDialog by remember { mutableStateOf(false) }
    var showAudioTranscodingDialog by remember { mutableStateOf(false) }
    var pendingStreamingQualitySelection by remember { mutableStateOf<String?>(null) }
    var showMediaInfo by remember { mutableStateOf(false) }
    var showVrProjectionDialog by remember { mutableStateOf(false) }
    /** 定时关闭的截止时刻（elapsedRealtime 毫秒）；null 表示未开启。到点只暂停，不退出播放页。 */
    var sleepTimerDeadline by rememberSaveable { mutableStateOf<Long?>(null) }
    val mediaInfoSnapshot = remember(showMediaInfo, viewModel) {
        if (showMediaInfo) viewModel.getMediaMetadataInfo() else null
    }

    LaunchedEffect(sleepTimerDeadline) {
        val deadline = sleepTimerDeadline ?: return@LaunchedEffect
        delay((deadline - SystemClock.elapsedRealtime()).coerceAtLeast(0L))
        viewModel.pause()
        sleepTimerDeadline = null
    }

    // Player state from ViewModel
    val playerState by viewModel.playerState.collectAsState()
    val preferredStreamIndexes by viewModel.preferredStreamIndexes.collectAsState()
    val sourceVideoHeight = viewModel.getSourceVideoHeight()
    val availableStreamingQualityOptions = remember(
        sourceVideoHeight,
        playerState.isVideoTranscodingAllowed
    ) {
        if (playerState.isVideoTranscodingAllowed) {
            PlayerPreferences.getStreamingQualityOptions(sourceVideoHeight)
        } else {
            listOf(PlayerPreferences.STREAMING_QUALITY_ORIGINAL)
        }
    }

    // System managers
    val audioManager = remember { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    val playerPreferences = remember { PlayerPreferences(context) }
    val localSubtitlePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let(viewModel::addLocalSubtitle)
    }
    val useDeviceVolumeInPlayer = remember { playerPreferences.isUseDeviceVolumeInPlayerEnabled() }
    val useDeviceBrightnessInPlayer = remember { playerPreferences.isUseDeviceBrightnessInPlayerEnabled() }

    // Store original values to restore on exit
    val originalVolume = remember { audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) }

    // Player-level brightness and volume (persistent)
    var playerBrightness by remember(useDeviceBrightnessInPlayer) {
        mutableStateOf(
            if (useDeviceBrightnessInPlayer) {
                readCurrentDeviceBrightness(context)
            } else {
                playerPreferences.getPlayerBrightness()
            }
        )
    }
    var playerVolume by remember(useDeviceVolumeInPlayer) {
        mutableStateOf(
            if (useDeviceVolumeInPlayer) {
                readCurrentDeviceVolume(audioManager)
            } else {
                playerPreferences.getPlayerVolume()
            }
        )
    }
    var currentStreamingQuality by remember { mutableStateOf(playerPreferences.getStreamingQuality()) }
    val skipIntroEnabled = remember { playerPreferences.isSkipIntroEnabled() }
    var currentAudioTranscodeMode by remember {
        mutableStateOf(playerPreferences.getAudioTranscodeMode())
    }
    val seekBackwardSeconds = playerPreferences.getSeekBackwardIntervalSeconds()
    var showPlaybackInfoSheet by remember { mutableStateOf(false) }
    var showChaptersSheet by remember { mutableStateOf(false) }
    var showPlaylistSheet by remember { mutableStateOf(false) }
    val seekForwardSeconds = playerPreferences.getSeekForwardIntervalSeconds()
    val chapterMarkersEnabled = playerPreferences.areChapterMarkersEnabled()
    var playbackOrientation by remember {
        mutableStateOf(playerPreferences.getPlayerOrientation())
    }
    val activity = context.findActivity()
    val hostActivity = activity as? PictureInPictureHost
    val inPip by (hostActivity?.pictureInPictureMode ?: remember { MutableStateFlow(false) })
        .collectAsState()

    // Track initialized media so this screen can switch to a new episode in-place.
    var initializedMediaId by remember { mutableStateOf<String?>(null) }
    var currentPlaybackId by remember { mutableStateOf(mediaId) }
    val mediaRepository = remember { MediaRepositoryProvider.getInstance(context) }

    LaunchedEffect(mediaId) {
        if (mediaId != currentPlaybackId) {
            currentPlaybackId = mediaId
        }
    }
    LaunchedEffect(inPip) {
        if (inPip) {
            showPlaybackInfoSheet = false
        }
    }

    val playEpisodeInPlace: (String) -> Unit = { episodeId ->
        if (episodeId.isNotBlank() && episodeId != currentPlaybackId) {
            currentPlaybackId = episodeId
            onWatchNextEpisode?.invoke(episodeId)
        }
    }

    PlayerScreenEffects(
        context = context,
        currentView = currentView,
        lifecycleOwner = lifecycleOwner,
        mediaId = currentPlaybackId,
        initialItemDetails = initialItemDetails,
        remoteMediaUrl = remoteMediaUrl,
        remoteMediaTitle = remoteMediaTitle,
        preferredAudioStreamIndex = preferredAudioStreamIndex,
        preferredSubtitleStreamIndex = preferredSubtitleStreamIndex,
        startFromBeginning = startFromBeginning,
        initialSeekPositionMs = initialSeekPositionMs,
        viewModel = viewModel,
        onPlaybackCompleted = onPlaybackCompleted,
        preferredStreamIndexes = preferredStreamIndexes,
        playerState = playerState,
        useDeviceVolumeInPlayer = useDeviceVolumeInPlayer,
        audioManager = audioManager,
        originalVolume = originalVolume,
        playerBrightness = playerBrightness,
        playerVolume = playerVolume,
        showAudioTrackDialog = showAudioTrackDialog,
        showSubtitleTrackDialog = showSubtitleTrackDialog,
        showStreamingQualityDialog = showStreamingQualityDialog,
        showAudioTranscodingDialog = showAudioTranscodingDialog,
        showMediaInfo = showMediaInfo,
        showVrProjectionDialog = showVrProjectionDialog,
        autoHideKey = autoHideKey,
        autoHideHeld = autoHideHeld,
        hideSystemBars = hideSystemBars,
        uiStateProvider = { uiState },
        playbackProgress = playbackProgress,
        onUiStateChange = { uiState = it },
        initializedMediaIdProvider = { initializedMediaId },
        onInitializedMediaIdChange = { initializedMediaId = it },
        onLifecycleChange = { lifecycle = it },
        onCurrentAudioTranscodeModeChange = { currentAudioTranscodeMode = it },
        onPreferredStreamIndexesChanged = onPreferredStreamIndexesChanged,
        compactPlayback = compactPlayback && !inPip,
        mediaSourceId = mediaSourceId,
        playerOrientation = playbackOrientation
    )

    val hasPlaybackSettings = playerState.isVideoTranscodingAllowed ||
        playerState.isAudioTranscodingAllowed
    val playbackDuration = viewModel.getDuration()
    // 进度只在 derivedStateOf 里读取：片段切换时才触发整页重组，而不是每次进度刷新。
    val activeSkippableSegment by remember(
        skipIntroEnabled,
        playerState.isLocked,
        playerState.recapStartMs,
        playerState.recapEndMs,
        playerState.introStartMs,
        playerState.introEndMs,
        playerState.creditsStartMs,
        playerState.creditsEndMs,
        playerState.previewStartMs,
        playerState.previewEndMs,
        playbackDuration
    ) {
        derivedStateOf(structuralEqualityPolicy()) {
            if (!skipIntroEnabled || playerState.isLocked) {
                null
            } else {
                playerState.findActiveSkippableSegment(
                    positionMs = playbackProgress.positionMs,
                    durationMs = playbackDuration
                )
            }
        }
    }
    val activeCreditsSegment = activeSkippableSegment?.takeIf {
        it.type == SkippableSegmentType.CREDITS
    }
    val canWatchPreviousEpisode = !previousEpisodeId.isNullOrBlank() && onWatchPreviousEpisode != null
    val canWatchNextEpisode = !nextEpisodeId.isNullOrBlank() && onWatchNextEpisode != null

    SystemMediaSessionEffect(
        mediaId = currentPlaybackId,
        title = playerState.mediaTitle,
        subtitle = playerState.seasonEpisodeLabel?.takeIf { it.isNotBlank() }
            ?: initialItemDetails?.seriesName
            ?: initialItemDetails?.name,
        durationMs = viewModel.getDuration(),
        playing = playerState.isPlaying || playerState.playWhenReady,
        artworkUrl = viewModel.posterUrl,
        canSkip = canWatchNextEpisode || canWatchPreviousEpisode,
        positionProvider = viewModel::getCurrentPosition,
        onPlay = viewModel::play,
        onPause = viewModel::pause,
        onSeek = viewModel::seekTo,
        onSkipNext = {
            nextEpisodeId?.let(playEpisodeInPlace)
        },
        onSkipPrevious = {
            previousEpisodeId?.takeIf { it.isNotBlank() }?.let { id ->
                onWatchPreviousEpisode?.invoke(id)
            }
        },
        onStop = {
            onBackPressed?.invoke()
        }
    )

    LaunchedEffect(playerState.playWhenReady, playerState.isLocked) {
        activity?.let { host ->
            applyPlayerPipParams(host, playerState.playWhenReady && !playerState.isLocked)
        }
    }

    DisposableEffect(hostActivity, playerState.playWhenReady, playerState.isLocked) {
        if (hostActivity == null) {
            return@DisposableEffect onDispose { }
        }
        hostActivity.userLeaveHintHandler = {
            if (playerState.playWhenReady && !playerState.isLocked) {
                activity?.let(::enterPlayerPip)
            }
        }
        onDispose {
            hostActivity.userLeaveHintHandler = null
        }
    }

    LaunchedEffect(inPip) {
        if (inPip) {
            uiState = uiState.copy(controlsVisible = false)
        }
    }

    LaunchedEffect(activeCreditsSegment?.startMs, activeCreditsSegment?.endMs) {
        if (activeCreditsSegment == null) {
            dismissedCreditsPrompt = false
        }
    }

    LaunchedEffect(
        initializedMediaId,
        nextEpisodeId,
        activeCreditsSegment != null,
        canWatchNextEpisode,
        dismissedCreditsPrompt,
        preferredStreamIndexes.audioStreamIndex,
        preferredStreamIndexes.subtitleStreamIndex
    ) {
        viewModel.updateNextEpisodeCache(
            context = context,
            nextEpisodeId = nextEpisodeId.takeIf {
                initializedMediaId == mediaId &&
                    activeCreditsSegment != null &&
                    canWatchNextEpisode &&
                    !dismissedCreditsPrompt
            },
            preferredAudioStreamIndex = preferredStreamIndexes.audioStreamIndex,
            preferredSubtitleStreamIndex = preferredStreamIndexes.subtitleStreamIndex
        )
    }

    val applyPlaybackSettingsSelection: (String, AudioTranscodeMode) -> Unit = applyPlaybackSettingsSelection@{ quality, audioMode ->
        val selectedQuality = quality.trim()
        val qualityChanged = selectedQuality.isNotEmpty() && selectedQuality != currentStreamingQuality
        val audioModeChanged = audioMode != currentAudioTranscodeMode

        pendingStreamingQualitySelection = null
        showStreamingQualityDialog = false
        showAudioTranscodingDialog = false

        if (selectedQuality.isEmpty()) return@applyPlaybackSettingsSelection

        playerPreferences.setStreamingQuality(selectedQuality)
        currentStreamingQuality = playerPreferences.getStreamingQuality()
        playerPreferences.setAudioTranscodeMode(audioMode)
        currentAudioTranscodeMode = playerPreferences.getAudioTranscodeMode()

        if (!qualityChanged && !audioModeChanged) {
            return@applyPlaybackSettingsSelection
        }

        val resumePositionMs = viewModel.getCurrentPosition()
        val shouldResumePlaying = viewModel.isPlayingNow()
        val preferredAudio = preferredStreamIndexes.audioStreamIndex
        val preferredSubtitle = preferredStreamIndexes.subtitleStreamIndex

        uiState = uiState.copy(controlsVisible = true)
        viewModel.releasePlayer()
        initializedMediaId = null
        viewModel.initializePlayer(
            context = context,
            mediaId = currentPlaybackId,
            initialItemDetails = initialItemDetails,
            preferredAudioStreamIndex = preferredAudio,
            preferredSubtitleStreamIndex = preferredSubtitle,
            initialSeekPositionMs = resumePositionMs,
            startPlayback = shouldResumePlaying,
            mediaSourceId = mediaSourceId
        )
        initializedMediaId = mediaId
    }

    val applyStreamingQualitySelection: (String) -> Unit = { selectedQuality ->
        if (!playerState.isVideoTranscodingAllowed) {
            pendingStreamingQualitySelection = null
            showAudioTranscodingDialog = false
            showStreamingQualityDialog = false
        } else {
            val selection = selectedQuality.trim()
            if (selection.isEmpty()) {
                pendingStreamingQualitySelection = null
                showAudioTranscodingDialog = false
                showStreamingQualityDialog = false
            } else {
                val needsAudioPrompt = playerState.isAudioTranscodingAllowed

                if (needsAudioPrompt) {
                    pendingStreamingQualitySelection = selection
                    showStreamingQualityDialog = false
                    showAudioTranscodingDialog = true
                } else {
                    applyPlaybackSettingsSelection(selection, currentAudioTranscodeMode)
                }
            }
        }
    }

    LaunchedEffect(
        playerState.isVideoTranscodingAllowed,
        playerState.isAudioTranscodingAllowed
    ) {
        if (!playerState.isVideoTranscodingAllowed) {
            pendingStreamingQualitySelection = null
            showAudioTranscodingDialog = false
            showStreamingQualityDialog = false
        }
        if (!playerState.isAudioTranscodingAllowed) {
            pendingStreamingQualitySelection = null
            showAudioTranscodingDialog = false
        }
    }

    val miniLayout = compactPlayback && !inPip

    // Back handler
    // 只在有浮层可关时拦截返回；否则交给系统，播放跨 Activity 的原生预测性返回（跟手露出下层详情页）。
    // 退出前的暂停、上报由 PlayerActivity.finish 统一处理。
    BackHandler(enabled = !miniLayout && showPlaybackInfoSheet) {
        showPlaybackInfoSheet = false
    }

    val isPortraitPlayback = LocalConfiguration.current.orientation == Configuration.ORIENTATION_PORTRAIT
    var vrSphericalView by remember { mutableStateOf<View?>(null) }
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusable(),
        // Surface 铺满窗口，由播放器在内部 letterbox；字幕才能画到竖屏上下黑边。
        contentAlignment = Alignment.Center
    ) {
        // 旋转时保留同一个 SurfaceView：尺寸变化走 surfaceChanged 即时重排。不能按方向 key 重建，
        // 否则 mpv 会随 Surface 销毁卸载 vo、重建解码与渲染管线，表现为黑屏后重新加载。
        // 播放器内部按 Fit 做 letterbox（长边贴满屏幕、完整显示画面），这里不再额外放大；
        // 需要铺满时由“画面比例”切到 Zoom 或用捏合/画面尺寸调整。
        VideoSurface(
            player = viewModel.exoPlayer,
            mpvPlayer = viewModel.mpvPlayer,
            lifecycle = lifecycle,
            isInPictureInPictureMode = inPip,
            scale = playerState.videoWidthFraction * playerState.videoScale,
            offsetX = playerState.videoOffsetX,
            offsetY = playerState.videoOffsetY,
            resizeMode = viewModel.getCurrentResizeMode(),
            subtitleAppearanceEpoch = viewModel.subtitleAppearanceEpoch,
            vrFlatEnabled = playerState.vrFlatEnabled,
            vrLayout = playerState.vrProjectionId?.let(VrLayoutParser::layoutForId),
            onSphericalTouchTarget = { vrSphericalView = it },
            modifier = Modifier.fillMaxSize()
        )

        PlayerGestureLayer(
            audioManager = audioManager,
            enabled = !miniLayout &&
                !playerState.isLocked &&
                !inPip &&
                !showPlaybackInfoSheet &&
                !showSubtitleStyleSheet &&
                !showSubtitleDelaySheet &&
                !showVideoWidthSheet,
            onToggleControls = {
                resetAutoHideTimer()
                uiState = uiState.copy(controlsVisible = !uiState.controlsVisible)
            },
            onSeek = { delta ->
                if (!playerState.isLocked) {
                    viewModel.seekBy(delta)
                    playbackProgress.positionMs = viewModel.getCurrentPosition()
                    uiState = uiState.copy(seekPosition = null)
                }
            },
            onVolumeChange = { level ->
                if (!playerState.isLocked) {
                    playerVolume = level.coerceIn(0f, 1f)
                    if (!useDeviceVolumeInPlayer) {
                        playerPreferences.setPlayerVolume(playerVolume)
                    }
                    val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                    val newVolume = (playerVolume * maxVolume).toInt().coerceIn(0, maxVolume)
                    audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, newVolume, 0)
                    uiState = uiState.copy(volumeLevel = playerVolume)
                }
            },
            onBrightnessChange = { delta ->
                if (!playerState.isLocked) {
                    activity?.let { act ->
                        val newPlayerBrightness = (playerBrightness + delta).coerceIn(0.01f, 1f)
                        playerBrightness = newPlayerBrightness
                        if (!useDeviceBrightnessInPlayer) {
                            playerPreferences.setPlayerBrightness(newPlayerBrightness)
                        }
                        val layoutParams = act.window.attributes
                        layoutParams.screenBrightness = newPlayerBrightness
                        act.window.attributes = layoutParams
                        uiState = uiState.copy(brightnessLevel = newPlayerBrightness)
                    }
                }
            },
            getCurrentVolumeLevel = { playerVolume },
            getCurrentBrightnessLevel = { playerBrightness },
            onZoomChange = { isZooming ->
                viewModel.handlePinchZoom(isZooming)
            },
            onTogglePlayPause = viewModel::togglePlayPause,
            getPlaybackPosition = { viewModel.getCurrentPosition() },
            getPlaybackDuration = { viewModel.getDuration() },
            onSeekPreview = { previewMs ->
                uiState = uiState.copy(swipeSeekPositionMs = previewMs)
            },
            onHoldSpeed = { holding ->
                if (holding) {
                    val speed = playerPreferences.getLongPressPlaybackSpeed()
                    viewModel.beginHoldSpeed(speed)
                    uiState = uiState.copy(holdSpeedLabel = String.format(java.util.Locale.US, "%.1fx", speed))
                } else {
                    viewModel.endHoldSpeed()
                    uiState = uiState.copy(holdSpeedLabel = null)
                }
            },
            vrLookAround = playerState.vrFlatEnabled,
            onLookAround = { fingerDx, fingerDy ->
                viewModel.applyVrLookDrag(fingerDx, fingerDy)
            },
            onFovScale = { scaleFactor ->
                if (viewModel.mpvPlayer != null) {
                    viewModel.applyVrFovScale(scaleFactor)
                }
            },
            getVrSurface = { vrSphericalView }
        )

        if (!miniLayout) {
        PlayerOverlayHost(
            uiState = uiState.copy(
                controlsVisible = uiState.controlsVisible &&
                    !inPip &&
                    !showPlaybackInfoSheet &&
                    !showSubtitleStyleSheet &&
                    !showSubtitleDelaySheet &&
                    !showVideoWidthSheet
            ),
            playerState = playerState,
            hasPlaybackSettings = hasPlaybackSettings,
            chapterMarkersEnabled = chapterMarkersEnabled,
            seekBackwardSeconds = seekBackwardSeconds,
            seekForwardSeconds = seekForwardSeconds,
            activeSkippableSegment = activeSkippableSegment,
            activeCreditsSegment = activeCreditsSegment,
            dismissedCreditsPrompt = dismissedCreditsPrompt,
            canWatchPreviousEpisode = canWatchPreviousEpisode,
            canWatchNextEpisode = canWatchNextEpisode,
            viewModel = viewModel,
            onBackPressed = onBackPressed,
            resetAutoHideTimer = resetAutoHideTimer,
            onAutoHideHoldChange = { autoHideHeld = it },
            onWatchCredits = {
                dismissedCreditsPrompt = true
                uiState = uiState.copy(controlsVisible = false)
            },
            onWatchPreviousEpisode = {
                previousEpisodeId
                    ?.takeIf { it.isNotBlank() }
                    ?.let { onWatchPreviousEpisode?.invoke(it) }
            },
            onWatchNextEpisode = {
                nextEpisodeId
                    ?.takeIf { it.isNotBlank() }
                    ?.let { onWatchNextEpisode?.invoke(it) }
            },
            onShowMediaInfo = { showMediaInfo = true },
            onShowStreamingQualityDialog = { showStreamingQualityDialog = true },
            onShowAudioTranscodingDialog = {
                pendingStreamingQualitySelection = null
                showAudioTranscodingDialog = true
            },
            onShowAudioTrackDialog = { showAudioTrackDialog = true },
            onShowSubtitleTrackDialog = { showSubtitleTrackDialog = true },
            onAdjustVideoSize = {
                showVideoWidthSheet = true
                uiState = uiState.copy(controlsVisible = false)
            },
            onToggleOrientation = {
                playbackOrientation = if (
                    playbackOrientation == PlayerPreferences.PLAYER_ORIENTATION_LANDSCAPE
                ) {
                    PlayerPreferences.PLAYER_ORIENTATION_PORTRAIT
                } else {
                    PlayerPreferences.PLAYER_ORIENTATION_LANDSCAPE
                }
            },
            onTitleClick = {
                showPlaybackInfoSheet = true
                uiState = uiState.copy(controlsVisible = false)
            },
            onEnterPip = {
                activity?.let(::enterPlayerPip)
                onEnterPip?.invoke()
            },
            onShowChapters = { showChaptersSheet = true },
            onShowVrProjection = { showVrProjectionDialog = true },
            onShowPlaylist = if (playlist.size > 1) {
                {
                    showPlaylistSheet = true
                    uiState = uiState.copy(controlsVisible = false)
                }
            } else {
                null
            },
            onSeekFeedback = { label, side ->
                // 事件序号保证连续点击同一方向时也会重新开始提示的淡出计时。
                uiState = uiState.copy(
                    seekPosition = label,
                    seekSide = side,
                    seekFeedbackId = uiState.seekFeedbackId + 1L
                )
            },
            onPositionChanged = { position ->
                playbackProgress.positionMs = position
            },
            playbackProgress = playbackProgress,
            sleepTimerDeadline = sleepTimerDeadline,
            onSetSleepTimer = { minutes ->
                sleepTimerDeadline = minutes?.let { SystemClock.elapsedRealtime() + it * 60_000L }
            },
            onAddLocalSubtitle = { localSubtitlePicker.launch(arrayOf("*/*")) },
            onShowSubtitleStyle = {
                showSubtitleStyleSheet = true
                uiState = uiState.copy(controlsVisible = false)
            },
            onShowSubtitleDelay = {
                showSubtitleDelaySheet = true
                uiState = uiState.copy(controlsVisible = false)
            }
        )
        } else {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clickable(onClick = onExpandFromMini)
            )
            IconButton(
                onClick = {
                    onBackPressed?.invoke()
                },
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(4.dp)
                    .size(28.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = stringResource(R.string.player_pip),
                    tint = Color.White,
                    modifier = Modifier.size(18.dp)
                )
            }
        }

        if (!miniLayout) {
        PlayerDialogsHost(
            playerState = playerState,
            showAudioTrackDialog = showAudioTrackDialog,
            showSubtitleTrackDialog = showSubtitleTrackDialog,
            showStreamingQualityDialog = showStreamingQualityDialog,
            showAudioTranscodingDialog = showAudioTranscodingDialog,
            showMediaInfo = showMediaInfo,
            availableStreamingQualityOptions = availableStreamingQualityOptions,
            currentStreamingQuality = currentStreamingQuality,
            currentAudioTranscodeMode = currentAudioTranscodeMode,
            mediaInfoSnapshot = mediaInfoSnapshot,
            onAudioTrackSelected = { trackId ->
                viewModel.selectAudioTrack(trackId)
                showAudioTrackDialog = false
            },
            onSubtitleTrackSelected = { trackId ->
                viewModel.selectSubtitleTrack(trackId)
                showSubtitleTrackDialog = false
            },
            onStreamingQualitySelected = applyStreamingQualitySelection,
            onAudioTranscodingSelected = { selectedMode ->
                val targetQuality = pendingStreamingQualitySelection ?: currentStreamingQuality
                applyPlaybackSettingsSelection(targetQuality, selectedMode)
            },
            onDismissAudioTrackDialog = { showAudioTrackDialog = false },
            onDismissSubtitleTrackDialog = { showSubtitleTrackDialog = false },
            onDismissStreamingQualityDialog = { showStreamingQualityDialog = false },
            onDismissAudioTranscodingDialog = {
                pendingStreamingQualitySelection = null
                showAudioTranscodingDialog = false
            },
            onDismissMediaInfo = { showMediaInfo = false },
            showVrProjectionDialog = showVrProjectionDialog,
            currentVrProjectionId = playerState.vrProjectionId,
            onVrProjectionSelected = { id ->
                viewModel.selectVrProjection(id)
                showVrProjectionDialog = false
            },
            onDismissVrProjectionDialog = { showVrProjectionDialog = false },
            onAddLocalSubtitle = {
                showSubtitleTrackDialog = false
                localSubtitlePicker.launch(arrayOf("*/*"))
            },
            onShowSubtitleStyle = {
                showSubtitleTrackDialog = false
                showSubtitleStyleSheet = true
                uiState = uiState.copy(controlsVisible = false)
            },
            onShowSubtitleDelay = {
                showSubtitleTrackDialog = false
                showSubtitleDelaySheet = true
                uiState = uiState.copy(controlsVisible = false)
            }
        )

        if (showSubtitleStyleSheet) {
            SubtitleStyleSheet(
                playerPreferences = playerPreferences,
                onChanged = { viewModel.refreshSubtitleAppearance() },
                onDismiss = { showSubtitleStyleSheet = false }
            )
        }
        if (showSubtitleDelaySheet) {
            SubtitleDelaySheet(
                playerPreferences = playerPreferences,
                onChanged = { viewModel.refreshSubtitleAppearance() },
                onDismiss = { showSubtitleDelaySheet = false }
            )
        }
        if (showVideoWidthSheet) {
            VideoWidthAdjustOverlay(
                widthFraction = playerState.videoWidthFraction,
                onWidthFractionChange = viewModel::setVideoWidthFraction,
                onDismiss = { showVideoWidthSheet = false }
            )
        }

        if (showPlaybackInfoSheet) {
            PlaybackInfoSheet(
                item = viewModel.playbackItem ?: initialItemDetails,
                title = playerState.mediaTitle,
                isPortrait = isPortraitPlayback,
                currentItemId = currentPlaybackId,
                mediaRepository = mediaRepository,
                onDismiss = { showPlaybackInfoSheet = false },
                onEpisodeSelected = playEpisodeInPlace
            )
        }

        if (showPlaylistSheet) {
            PlayerPlaylistSheet(
                items = playlist,
                currentItemId = currentPlaybackId,
                mediaRepository = mediaRepository,
                onDismiss = { showPlaylistSheet = false },
                onItemSelected = { id ->
                    showPlaylistSheet = false
                    if (id != currentPlaybackId) onPlaylistItemSelected(id)
                }
            )
        }

        if (showChaptersSheet) {
            ChapterListSheet(
                chapters = if (chapterMarkersEnabled) playerState.chapterMarkers else emptyList(),
                onDismiss = { showChaptersSheet = false },
                onChapterSelected = { chapter ->
                    viewModel.seekTo(chapter.positionMs)
                    playbackProgress.positionMs = chapter.positionMs
                    showChaptersSheet = false
                    resetAutoHideTimer()
                }
            )
        }
        }
    }
}

private fun readCurrentDeviceVolume(audioManager: AudioManager): Float {
    val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
    if (maxVolume <= 0) return 0f
    return audioManager.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / maxVolume.toFloat()
}

private fun readCurrentDeviceBrightness(context: Context): Float {
    return runCatching {
        Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS)
            .toFloat()
            .div(255f)
            .coerceIn(0.01f, 1f)
    }.getOrDefault(PlayerPreferences(context).getPlayerBrightness())
}
