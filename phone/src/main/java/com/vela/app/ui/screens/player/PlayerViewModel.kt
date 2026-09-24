package com.vela.app.ui.screens.player

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.ui.AspectRatioFrameLayout
import dagger.hilt.android.lifecycle.HiltViewModel
import com.vela.app.playback.ActivePlayback
import com.vela.app.player.mpv.MPVPlayer
import com.vela.app.player.mpv.MpvPlayerController
import com.vela.app.player.mpv.MpvWarmPool
import com.vela.app.player.vr.VrFlattenFilter
import com.vela.app.player.vr.VrLayout
import com.vela.app.player.vr.VrLayoutParser
import com.vela.data.model.AudioTranscodeMode
import com.vela.data.model.BaseItemDto
import com.vela.data.model.MediaSource
import com.vela.data.model.MediaStream
import com.vela.data.model.PlaybackRequest
import com.vela.data.model.selectedMediaSource
import com.vela.data.repository.MediaRepository
import com.vela.data.network.NetworkModule
import com.vela.detail.CodecCapabilityManager
import com.vela.player.audio.SpatializerHelper
import com.vela.player.core.PlaybackMarkerUtils
import com.vela.player.core.applyingPlaybackSegments
import com.vela.player.core.PlayerState
import com.vela.player.core.PlayerTrack
import com.vela.player.core.AudioTrackInfo
import com.vela.player.core.PlayerUtils
import com.vela.player.core.RemoteTrailerUrl
import com.vela.player.core.TrackDetails
import com.vela.player.core.DEFAULT_VIDEO_WIDTH_FRACTION
import com.vela.player.core.MAX_VIDEO_WIDTH_FRACTION
import com.vela.player.core.MIN_VIDEO_WIDTH_FRACTION
import com.vela.player.preferences.PlayerPreferences
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.vela.app.download.DownloadRepository
import com.vela.app.download.DownloadRepositoryProvider
import java.io.File
import java.util.LinkedHashMap
import java.util.Locale
import javax.inject.Inject

/**
 * Player ViewModel
 */
@UnstableApi
@HiltViewModel
class PlayerViewModel @Inject constructor(
    private val mediaRepository: MediaRepository
) : ViewModel() {
    companion object {
        private const val TAG = "PlayerViewModel"
        private const val MPV_FALLBACK_FIRST_FRAME_TIMEOUT_MS = 2_500L
    }

    private data class ScrubPreviewRequest(
        /** 用来抽关键帧的片源。 */
        val source: ScrubPreviewSource,
        /** 预览位置，单位毫秒。 */
        val positionMs: Long,
        /** 发起预览时的版本。松手或换片后旧请求不再写回画面。 */
        val version: Long
    )

    private val _playerState = MutableStateFlow(PlayerState())
    val playerState: StateFlow<PlayerState> = _playerState.asStateFlow()
    private val _preferredStreamIndexes = MutableStateFlow(PreferredStreamIndexes())
    val preferredStreamIndexes: StateFlow<PreferredStreamIndexes> = _preferredStreamIndexes.asStateFlow()
    private val _playbackCompletedEvents = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val playbackCompletedEvents: SharedFlow<String> = _playbackCompletedEvents.asSharedFlow()

    var exoPlayer: ExoPlayer? by mutableStateOf(null)
        private set
    var mpvPlayer: MpvPlayerController? by mutableStateOf(null)
        private set
    var subtitleAppearanceEpoch by mutableIntStateOf(0)
        private set
    var scrubPreviewFrame: Bitmap? by mutableStateOf(null)
        private set
    private var activePlayerEngine: String = PlayerPreferences.DEFAULT_PLAYER_ENGINE

    private val trackSelectionCoordinator = PlayerTrackSelection()
    private var playbackSession = PlaybackSessionContext()
    private val playbackReporter = PlayerPlaybackReporter(
        mediaRepository = mediaRepository,
        scope = viewModelScope,
        positionProvider = { getCurrentPosition() },
        isPausedProvider = { !isPlayingNow() },
        onScrobble = { action ->
            val context = playerContext
            val item = currentItemDetails
            if (context != null && item != null) {
                try {
                    com.vela.data.repository.TraktRepository.getInstance(context)
                        .scrobble(action, item, getCurrentPosition(), getDuration())
                } catch (error: Exception) {
                    // 可选跟踪失败不能打断播放或媒体服务器自身的进度上报。
                    Log.w(TAG, "Trakt reporting unavailable: ${error.javaClass.simpleName}")
                }
            }
        }
    )
    private var spatializerHelper: SpatializerHelper? = null
    private var playerContext: Context? = null
    private var apiMediaStreams: List<MediaStream>? = null
    private var defaultAudioStreamIndex: Int? = null
    private var defaultSubtitleStreamIndex: Int? = null
    private var hasHandledPlaybackCompletion = false
    private var videoTranscodingAllowed: Boolean? = null
    private var audioTranscodingAllowed: Boolean? = null
    private var audioDiagnosticsSignature: String? = null
    private var downloadRepository: DownloadRepository? = null
    private var communityPlaybackSegmentsJob: Job? = null
    private var spatialAudioAnalysisJob: Job? = null
    private var currentItemDetails: BaseItemDto? = null
    val playbackItem: BaseItemDto?
        get() = currentItemDetails
    var discordPosterUrl: String? = null
        private set
    private var nextEpisodePrefetchJob: Job? = null
    private var nextEpisodePrefetchSignature: String? = null
    private var mpvWatchdogJob: Job? = null
    private var cachePolicyJob: Job? = null
    private var hasRenderedFirstFrame = false
    private var mpvExternalSubtitleUrls: Map<Int, String> = emptyMap()
    private var remotePlaybackRequestKey: String? = null
    private var playbackSpeed = 1f
    private var speedBeforeHold = 1f
    private var lastHardwareDecoding = PlayerPreferences.DEFAULT_MPV_HARDWARE_DECODING
    private var detectedVrLayout: VrLayout? = null
    private var vrYaw = 0f
    private var vrPitch = 0f
    private var vrOutputFov = VrFlattenFilter.DEFAULT_OUTPUT_FOV
    private val scrubPreviewRequests = Channel<ScrubPreviewRequest>(Channel.CONFLATED)
    private val scrubGrabberLock = Any()
    private var scrubPreviewSource: ScrubPreviewSource? = null
    /** 独立硬解抽帧器。换片和退出播放时释放。 */
    private var scrubGrabber: ScrubFrameGrabber? = null
    private var scrubPreviewVersion = 0L
    /** 还有尚未处理的拖动请求时，预热和相邻预取都让路。 */
    @Volatile
    private var scrubPreviewQueued = false
    private var scrubWarmJob: Job? = null
    /** 保护关键帧缓存。拖动线程和抽帧线程都会读写。 */
    private val scrubFrameLock = Any()
    /**
     * 已抽出的关键帧，键是秒级格号。
     * 访问顺序作为淘汰顺序，最近用过的留在后面。
     */
    private val scrubFrames = LinkedHashMap<Long, Bitmap>(SCRUB_FRAME_CACHE_CAPACITY, 0.75f, true)

    init {
        viewModelScope.launch(Dispatchers.IO) {
            for (request in scrubPreviewRequests) {
                scrubPreviewQueued = false
                val cached = cachedScrubFrame(request.positionMs, exact = true)
                if (cached != null) {
                    publishScrubFrame(request.version, cached)
                    continue
                }
                if (request.version != scrubPreviewVersion) continue
                val frame = runCatching {
                    loadKeyframeFrame(request.source, request.positionMs)
                }.getOrNull()
                if (frame != null) {
                    rememberScrubFrame(request.positionMs, frame)
                    publishScrubFrame(request.version, frame)
                }
            }
        }
    }

    private fun isMpvPlayback(): Boolean {
        return activePlayerEngine == PlayerPreferences.PLAYER_ENGINE_MPV
    }

    private fun isOpticalDiscPlayback(
        itemDetails: BaseItemDto?,
        mediaSource: MediaSource?,
        mediaUri: Uri?
    ): Boolean {
        val videoType = mediaSource?.videoType.orEmpty()
        val container = mediaSource?.container.orEmpty()
        return !itemDetails?.isoType.isNullOrBlank() ||
            videoType.equals("BluRay", ignoreCase = true) ||
            videoType.equals("Dvd", ignoreCase = true) ||
            container.equals("iso", ignoreCase = true) ||
            container.equals("bluray", ignoreCase = true) ||
            container.equals("dvd", ignoreCase = true) ||
            mediaSource?.path?.endsWith(".iso", ignoreCase = true) == true ||
            mediaUri?.path?.endsWith(".iso", ignoreCase = true) == true
    }

    private fun resolveMpvHdrFormatLabel(): String {
        return CodecCapabilityManager.detectBestSourceHDRFormat(apiMediaStreams)
            .ifBlank { if (MPVPlayer.isHdr(apiMediaStreams)) "HDR" else "" }
    }

    private fun resolveExoHdrFormatLabel(): String {
        val runtimeFormat = PlayerMetadata.currentPlaybackHdrFormat(exoPlayer)
        val sourceFormat = CodecCapabilityManager.detectBestSourceHDRFormat(apiMediaStreams)

        return when {
            runtimeFormat.isNotBlank() -> runtimeFormat
            sourceFormat.equals("Dolby Vision", ignoreCase = true) -> ""
            sourceFormat.isNotBlank() && PlayerMetadata.hasSelectedVideoTrack(exoPlayer) -> sourceFormat
            else -> ""
        }
    }

    private fun downloadLocationUri(location: String): Uri {
        val parsed = Uri.parse(location)
        return if (parsed.scheme.isNullOrBlank()) {
            Uri.fromFile(File(location))
        } else {
            parsed
        }
    }

    private var requestedMediaSourceId: String? = null

    fun initializePlayer(
        context: Context,
        mediaId: String,
        initialItemDetails: BaseItemDto? = null,
        preferredAudioStreamIndex: Int? = null,
        preferredSubtitleStreamIndex: Int? = null,
        initialSeekPositionMs: Long? = null,
        startPlayback: Boolean = true,
        startFromBeginning: Boolean = false,
        forcedPlayerEngine: String? = null,
        mediaSourceId: String? = null
    ) {
        viewModelScope.launch {
            try {
                ActivePlayback.setActive(true)
                _playerState.value = _playerState.value.copy(
                    isLoading = true,
                    isPlaying = false,
                    playWhenReady = startPlayback,
                    hasStartedPlayback = false,
                    error = null,
                    videoWidthFraction = DEFAULT_VIDEO_WIDTH_FRACTION
                )
                _playerState.value = _playerState.value.copy(
                    recapStartMs = null,
                    recapEndMs = null,
                    introStartMs = null,
                    introEndMs = null,
                    creditsStartMs = null,
                    creditsEndMs = null,
                    previewStartMs = null,
                    previewEndMs = null,
                    chapterMarkers = emptyList()
                )

                playerContext = context
                hasHandledPlaybackCompletion = false
                remotePlaybackRequestKey = null
                requestedMediaSourceId = mediaSourceId?.takeIf { it.isNotBlank() }
                resetVrPlayback()
                val playerPreferences = PlayerPreferences(context)
                activePlayerEngine = forcedPlayerEngine ?: playerPreferences.getPlayerEngine()
                var resolvedPreferredAudioStreamIndex = preferredAudioStreamIndex
                var activePreferredSubtitleStreamIndex = preferredSubtitleStreamIndex
                val isVideoTranscodingAllowed = isVideoTranscodingAllowedForUser()
                val isAudioTranscodingAllowed = isAudioTranscodingAllowedForUser()
                val audioTranscodeMode = if (isAudioTranscodingAllowed) {
                    playerPreferences.getAudioTranscodeMode()
                } else {
                    AudioTranscodeMode.AUTO
                }
                val maxStreamingBitrate = if (isVideoTranscodingAllowed) {
                    playerPreferences.getMaxStreamingBitrate()
                } else {
                    null
                }
                val maxStreamingHeight = if (isVideoTranscodingAllowed) {
                    playerPreferences.getStreamingQualityMaxHeight()
                } else {
                    null
                }
                trackSelectionCoordinator.resetPendingSelections(
                    preferredAudioStreamIndex = resolvedPreferredAudioStreamIndex,
                    preferredSubtitleStreamIndex = activePreferredSubtitleStreamIndex
                )
                _preferredStreamIndexes.value = PreferredStreamIndexes(
                    audioStreamIndex = resolvedPreferredAudioStreamIndex,
                    subtitleStreamIndex = activePreferredSubtitleStreamIndex
                )

                audioDiagnosticsSignature = null
                currentItemDetails = null
                cancelNextEpisodePrefetch()
                playbackReporter.reset()
                communityPlaybackSegmentsJob?.cancel()
                communityPlaybackSegmentsJob = null
                spatialAudioAnalysisJob?.cancel()
                spatialAudioAnalysisJob = null
                cancelMpvWatchdog()
                hasRenderedFirstFrame = false
                spatializerHelper = SpatializerHelper(context)
                downloadRepository = DownloadRepositoryProvider.getInstance(context)
                val offlinePath = downloadRepository?.getOfflineFilePath(mediaId)
                val hasOfflineFile = !offlinePath.isNullOrBlank()
                val offlineItemDetails = if (hasOfflineFile) {
                    downloadRepository?.offlineItemMetadata(mediaId)
                } else {
                    null
                }

                // Get item details to check for resume position
                val itemDetails = if (initialItemDetails?.id == mediaId) {
                    initialItemDetails
                } else if (hasOfflineFile) {
                    offlineItemDetails ?: mediaRepository.getItemById(mediaId).getOrNull()
                } else {
                    mediaRepository.getItemById(mediaId).getOrNull()
                }
                currentItemDetails = itemDetails
                val seriesPreferenceId = TrackDetails.seriesPreferenceId(
                    itemType = itemDetails?.type,
                    seriesId = itemDetails?.seriesId
                )
                val preferenceStreams = itemDetails?.mediaStreams.orEmpty().ifEmpty {
                    itemDetails?.mediaSources?.firstOrNull()?.mediaStreams.orEmpty()
                }
                resolvedPreferredAudioStreamIndex =
                    playerPreferences.matchSeriesAudioStreamIndex(seriesPreferenceId, preferenceStreams)
                        ?: resolvedPreferredAudioStreamIndex
                        ?: playerPreferences.getPreferredAudioStreamIndex(mediaId)
                activePreferredSubtitleStreamIndex =
                    playerPreferences.matchSeriesSubtitleStreamIndex(seriesPreferenceId, preferenceStreams)
                        ?: activePreferredSubtitleStreamIndex
                        ?: playerPreferences.getPreferredSubtitleStreamIndex(mediaId)
                trackSelectionCoordinator.resetPendingSelections(
                    preferredAudioStreamIndex = resolvedPreferredAudioStreamIndex,
                    preferredSubtitleStreamIndex = activePreferredSubtitleStreamIndex
                )
                _preferredStreamIndexes.value = PreferredStreamIndexes(
                    audioStreamIndex = resolvedPreferredAudioStreamIndex,
                    subtitleStreamIndex = activePreferredSubtitleStreamIndex
                )
                val resumePositionTicks = itemDetails?.userData?.playbackPositionTicks
                val storedResumePositionMs = if (startFromBeginning) {
                    null
                } else if (resumePositionTicks != null && resumePositionTicks > 0) {
                    resumePositionTicks / 10000L
                } else {
                    null
                }
                val mediaTitle = itemDetails?.name ?: "Unknown Title"
                val logoSourceId = when {
                    itemDetails?.imageTags?.containsKey("Logo") == true && !itemDetails.id.isNullOrBlank() -> itemDetails.id
                    !itemDetails?.parentLogoItemId.isNullOrBlank() && !itemDetails?.parentLogoImageTag.isNullOrBlank() -> itemDetails?.parentLogoItemId
                    else -> null
                }
                val mediaLogoUrl = logoSourceId?.let { sourceId ->
                    mediaRepository.getImageUrlString(
                        itemId = sourceId,
                        imageType = "Logo",
                        width = 320,
                        quality = 90,
                        enableImageEnhancers = false
                    )
                } ?: itemDetails?.let { mediaRepository.getTmdbLogoUrl(it) }
                val posterItemId = if (itemDetails?.type.equals("Episode", ignoreCase = true)) {
                    itemDetails?.seriesId ?: itemDetails?.id
                } else {
                    itemDetails?.id
                }
                discordPosterUrl = posterItemId?.let { id ->
                    mediaRepository.getImageUrlString(
                        itemId = id,
                        imageType = "Primary",
                        width = 300,
                        quality = 80
                    )
                }
                val seasonEpisodeLabel = itemDetails?.let { item ->
                    val isEpisodeItem = item.type.equals("Episode", ignoreCase = true)
                    val season = item.parentIndexNumber
                    val episode = item.indexNumber
                    if (isEpisodeItem && season != null && episode != null) {
                        val episodeName = item.episodeTitle
                            ?.takeIf { it.isNotBlank() }
                            ?: item.name?.takeIf { it.isNotBlank() }
                        buildString {
                            append("S")
                            append(season)
                            append(":E")
                            append(episode)
                            episodeName?.let {
                                append(" - ")
                                append(it)
                            }
                        }
                    } else {
                        null
                    }
                }
                val chapterMarkers = PlaybackMarkerUtils.buildChapterMarkers(itemDetails?.chapters)
                val markerSegments = PlaybackMarkerUtils.extractMarkerSegments(itemDetails?.chapters)
                val playerStartPositionMs = if (startFromBeginning) {
                    0L
                } else {
                    initialSeekPositionMs ?: storedResumePositionMs
                }
                var primaryMediaSource: MediaSource? = null
                var sessionPlaySessionId: String? = null
                var sessionMediaSourceId: String? = null
                var sessionMediaSourceContainer: String? = null
                var sessionMediaSourceBitrateKbps: Int? = null
                var sessionPlayMethod = PlayMethod.DIRECT_PLAY
                var sessionIsOfflinePlayback = false
                var streamingMediaSource: androidx.media3.exoplayer.source.MediaSource? = null
                var playbackRequest: PlaybackRequest? = null
                defaultAudioStreamIndex = null
                defaultSubtitleStreamIndex = null

                val mediaItem = if (hasOfflineFile) {
                    val localFilePath = requireNotNull(offlinePath)
                    sessionIsOfflinePlayback = true
                    sessionPlayMethod = PlayMethod.OFFLINE
                    if (downloadRepository?.isTranscodedDownload(mediaId) == true) {
                        activePlayerEngine = PlayerPreferences.PLAYER_ENGINE_MPV
                    }
                    MediaItem.fromUri(downloadLocationUri(localFilePath))
                } else {
                    sessionIsOfflinePlayback = false

                    // Get playback info first to obtain session details
                    val playbackInfoResult = mediaRepository.getPlaybackInfo(
                        itemId = mediaId,
                        maxStreamingBitrate = maxStreamingBitrate,
                        audioStreamIndex = resolvedPreferredAudioStreamIndex,
                        // MPV 在原容器内按 sid 选轨；向服务端提交 ASS index 会触发 External/Encode 并改变时间基准。
                        subtitleStreamIndex = if (isMpvPlayback()) null else activePreferredSubtitleStreamIndex,
                        audioTranscodeMode = audioTranscodeMode,
                        mediaSourceId = requestedMediaSourceId
                    )
                    if (playbackInfoResult.isFailure) {
                        val error = playbackInfoResult.exceptionOrNull()?.message ?: "Failed to get playback info"
                        _playerState.value = _playerState.value.copy(isLoading = false, error = error)
                        return@launch
                    }

                    val playbackInfo = playbackInfoResult.getOrNull()
                    if (playbackInfo == null) {
                        _playerState.value = _playerState.value.copy(isLoading = false, error = "Playback info is null")
                        return@launch
                    }

                    primaryMediaSource = playbackInfo.selectedMediaSource(requestedMediaSourceId)
                    
                    apiMediaStreams = PlayerTrack.resolveApiMediaStreams(
                        itemDetails = itemDetails,
                        playbackMediaSource = primaryMediaSource
                    )
                    
                    defaultAudioStreamIndex = primaryMediaSource?.defaultAudioStreamIndex
                    defaultSubtitleStreamIndex = primaryMediaSource?.defaultSubtitleStreamIndex
                    sessionPlaySessionId = playbackInfo.playSessionId
                    sessionMediaSourceId = primaryMediaSource?.id
                    sessionMediaSourceContainer = primaryMediaSource?.container
                    sessionMediaSourceBitrateKbps = primaryMediaSource?.bitrate?.div(1000)
                    sessionPlayMethod = when {
                        primaryMediaSource?.supportsDirectPlay == true -> PlayMethod.DIRECT_PLAY
                        primaryMediaSource?.supportsDirectStream == true -> PlayMethod.DIRECT_STREAM
                        else -> PlayMethod.TRANSCODE
                    }
                    val playbackRequestResult = mediaRepository.getPlaybackRequest(
                        itemId = mediaId,
                        maxStreamingBitrate = maxStreamingBitrate,
                        maxStreamingHeight = maxStreamingHeight,
                        audioStreamIndex = resolvedPreferredAudioStreamIndex,
                        subtitleStreamIndex = activePreferredSubtitleStreamIndex,
                        audioTranscodeMode = audioTranscodeMode,
                        playbackInfo = playbackInfo,
                        includeAccessToken = isMpvPlayback(),
                        mediaSourceId = requestedMediaSourceId
                    )
                    if (playbackRequestResult.isFailure) {
                        val error = playbackRequestResult.exceptionOrNull()?.message ?: "Failed to get playback request"
                        _playerState.value = _playerState.value.copy(isLoading = false, error = error)
                        return@launch
                    }

                    playbackRequest = playbackRequestResult.getOrNull()
                    val streamingUrl = playbackRequest?.url
                    if (streamingUrl.isNullOrEmpty()) {
                        _playerState.value = _playerState.value.copy(isLoading = false, error = "Failed to get playback URL")
                        return@launch
                    }
                    val streamUri = Uri.parse(streamingUrl)
                    val streamPlaySessionId = streamUri.getQueryParameter("PlaySessionId")
                        ?: streamUri.getQueryParameter("playSessionId")
                    if (!streamPlaySessionId.isNullOrBlank()) {
                        sessionPlaySessionId = streamPlaySessionId
                    }
                    sessionPlayMethod = getPlayMethod(
                        streamingUrl = streamingUrl,
                        fallback = sessionPlayMethod
                    )

                    val activeSubtitleStreamIndex = MPVPlayer.resolvedSubtitleStreamIndex(
                        preferredIndex = activePreferredSubtitleStreamIndex,
                        mediaSourceDefaultIndex = primaryMediaSource?.defaultSubtitleStreamIndex,
                        mediaStreams = apiMediaStreams
                    )?.takeIf { it >= 0 }
                    
                    val activeSubtitleStream = apiMediaStreams
                        ?.firstOrNull { stream ->
                            stream.type.equals("Subtitle", ignoreCase = true) &&
                                stream.index == activeSubtitleStreamIndex
                        }

                    val streamingMediaItem = streamingMediaItem(
                        streamingUrl = streamingUrl,
                        itemId = mediaId,
                        mediaSourceId = sessionMediaSourceId,
                        selectedSubtitleStream = activeSubtitleStream,
                        requestHeaders = playbackRequest?.requestHeaders.orEmpty()
                    )
                    if (!isMpvPlayback()) {
                        streamingMediaSource = PlayerUtils.createStreamingMediaSource(
                            context = context,
                            mediaItem = streamingMediaItem,
                            requestHeaders = playbackRequest?.requestHeaders.orEmpty()
                        )
                    }
                    streamingMediaItem
                }
                configureScrubPreviewSource(
                    context = context,
                    uri = mediaItem.localConfiguration?.uri,
                    requestHeaders = playbackRequest?.requestHeaders.orEmpty(),
                    warmPositionMs = playerStartPositionMs ?: 0L,
                    cacheKey = mediaItem.localConfiguration?.customCacheKey
                )

                playbackSession = PlaybackSessionContext(
                    mediaId = mediaId,
                    playSessionId = sessionPlaySessionId,
                    mediaSourceId = sessionMediaSourceId,
                    mediaSourceContainer = sessionMediaSourceContainer,
                    mediaSourceBitrateKbps = sessionMediaSourceBitrateKbps,
                    playMethod = sessionPlayMethod,
                    isOfflinePlayback = sessionIsOfflinePlayback
                )
                playbackReporter.updateSession(playbackSession)
                
                mpvExternalSubtitleUrls = MPVPlayer.externalSubtitleUrls(
                    playbackRequest = playbackRequest,
                    mediaStreams = apiMediaStreams.orEmpty(),
                    itemId = mediaId,
                    mediaSourceId = sessionMediaSourceId
                )
                if (isMpvPlayback()) {
                    mpvExternalSubtitleUrls = withContext(Dispatchers.IO) {
                        MPVPlayer.materializeSubtitleFiles(
                            cacheDir = context.cacheDir,
                            urls = mpvExternalSubtitleUrls
                        )
                    }
                }

                if (isMpvPlayback()) {
                    val selectedAudioStreamIndex = _preferredStreamIndexes.value.audioStreamIndex
                        ?: defaultAudioStreamIndex
                    val selectedSubtitleStreamIndex = MPVPlayer.resolvedSubtitleStreamIndex(
                        preferredIndex = _preferredStreamIndexes.value.subtitleStreamIndex,
                        mediaSourceDefaultIndex = defaultSubtitleStreamIndex,
                        mediaStreams = apiMediaStreams
                    )
                    mpvPlayer = createMpvPlayer(context).also { player ->
                        ActivePlayback.setActive(true)
                        val strmHardwareDecoding = MPVPlayer.hardwareDecodingFor(
                            mediaSource = primaryMediaSource,
                            userPreference = playerPreferences.getMpvHardwareDecoding(),
                            mediaStreams = apiMediaStreams
                        )
                        player.setHardwareDecoding(strmHardwareDecoding)
                        player.applyStreamColorPolicy(apiMediaStreams)
                        Log.i(
                            "JellyCine-Sub",
                            "mpv subtitle plan index=$selectedSubtitleStreamIndex " +
                                "sid=${MPVPlayer.subtitleTrackId(apiMediaStreams, selectedSubtitleStreamIndex)} " +
                                "url=${
                                    MPVPlayer.redactPlaybackSecret(
                                        selectedSubtitleStreamIndex?.let(mpvExternalSubtitleUrls::get)
                                    )
                                } " +
                                "streams=${
                                    apiMediaStreams.orEmpty()
                                        .filter { it.type.equals("Subtitle", ignoreCase = true) }
                                        .joinToString { stream ->
                                            "#${stream.index} codec=${stream.codec} " +
                                                "ext=${stream.isExternal} method=${stream.deliveryMethod} " +
                                                "text=${stream.isTextSubtitleStream} " +
                                                "supportExt=${stream.supportsExternalStream}"
                                        }
                                } source=${primaryMediaSource?.container} " +
                                "direct=${primaryMediaSource?.supportsDirectPlay}/" +
                                "${primaryMediaSource?.supportsDirectStream} " +
                                "requestPath=${mediaItem.localConfiguration?.uri?.path}"
                        )
                        player.load(
                            url = mediaItem.localConfiguration?.uri?.toString().orEmpty(),
                            requestHeaders = playbackRequest?.requestHeaders.orEmpty(),
                            subtitleUrls = mpvExternalSubtitleUrls.values.toList(),
                            audioTrackId = MPVPlayer.audioTrackId(
                                apiMediaStreams,
                                selectedAudioStreamIndex
                            ),
                            subtitleTrackId = MPVPlayer.subtitleTrackId(
                                apiMediaStreams,
                                selectedSubtitleStreamIndex
                            ),
                            selectedSubtitleUrl = selectedSubtitleStreamIndex?.let(
                                mpvExternalSubtitleUrls::get
                            ),
                            startPositionMs = playerStartPositionMs,
                            opticalDiscPlayback = isOpticalDiscPlayback(
                                itemDetails = itemDetails,
                                mediaSource = primaryMediaSource,
                                mediaUri = mediaItem.localConfiguration?.uri
                            ),
                            startPlayback = startPlayback,
                            remoteHttpPlayback = MPVPlayer.isRemoteHttpPlayback(primaryMediaSource)
                        )
                    }
                    observePlaybackCachePolicy(context)
                } else {
                    exoPlayer = PlayerUtils.createPlayer(
                        context = context
                    )
                    ActivePlayback.setActive(true)
                    exoPlayer?.apply {
                        addListener(playerListener)
                        if (streamingMediaSource != null) {
                            setMediaSource(streamingMediaSource!!)
                        } else {
                            setMediaItem(mediaItem)
                        }
                        prepare()

                        if (playerStartPositionMs != null && playerStartPositionMs > 0) {
                            seekTo(playerStartPositionMs)
                        }

                        playWhenReady = startPlayback
                    }
                }

                // Spatial audio analysis and device capabilities
                val usesMpv = isMpvPlayback()
                if (!usesMpv) {
                    updateTrackInformation()
                }
                val hdrFormat = if (usesMpv) {
                    resolveMpvHdrFormatLabel()
                } else {
                    resolveExoHdrFormatLabel()
                }
                val isHdrPlayback = hdrFormat.isNotBlank()
                
                // Apply start maximized setting if enabled
                applyStartMaximizedSetting(context)

                playbackSpeed = 1f
                speedBeforeHold = 1f
                val userHardwareDecoding = playerPreferences.getMpvHardwareDecoding()
                if (userHardwareDecoding != PlayerPreferences.MPV_HARDWARE_DECODING_NONE) {
                    lastHardwareDecoding = userHardwareDecoding
                }
                val hardwareDecoding = MPVPlayer.hardwareDecodingFor(
                    mediaSource = primaryMediaSource,
                    userPreference = userHardwareDecoding,
                    mediaStreams = apiMediaStreams
                )
                val vrLayout = VrLayoutParser.parse(
                    mediaSourcePath = primaryMediaSource?.path,
                    itemPath = itemDetails?.path,
                    itemName = itemDetails?.name,
                    mediaSourceName = primaryMediaSource?.name,
                    tags = itemDetails?.tags,
                    video3DFormat = itemDetails?.video3DFormat
                )
                detectedVrLayout = vrLayout
                _playerState.value = _playerState.value.copy(
                    isLoading = true,
                    isPlaying = false,
                    playWhenReady = startPlayback,
                    hasStartedPlayback = false,
                    mediaTitle = mediaTitle,
                    mediaLogoUrl = mediaLogoUrl,
                    seasonEpisodeLabel = seasonEpisodeLabel,
                    chapterMarkers = chapterMarkers,
                    recapStartMs = markerSegments.recap?.startMs,
                    recapEndMs = markerSegments.recap?.endMs,
                    introStartMs = markerSegments.intro?.startMs,
                    introEndMs = markerSegments.intro?.endMs,
                    creditsStartMs = markerSegments.credits?.startMs,
                    creditsEndMs = markerSegments.credits?.endMs,
                    previewStartMs = markerSegments.preview?.startMs,
                    previewEndMs = markerSegments.preview?.endMs,
                    isVideoTranscodingAllowed = isVideoTranscodingAllowed,
                    isAudioTranscodingAllowed = isAudioTranscodingAllowed,
                    currentAudioTranscodeMode = audioTranscodeMode,
                    spatializationResult = null,
                    isSpatialAudioEnabled = false,
                    spatialAudioFormat = "",
                    isHdrEnabled = isHdrPlayback,
                    hdrFormat = hdrFormat,
                    playbackSpeed = 1f,
                    hardwareDecoding = hardwareDecoding,
                    vrDetected = vrLayout != null,
                    vrFlatEnabled = false,
                    vrProjectionId = vrLayout?.id
                )
                if (usesMpv) {
                    updateApiTrackInformation()
                }
                if (itemDetails != null) {
                    applyCommunityPlaybackSegments(mediaId = mediaId, itemDetails = itemDetails)
                }
                analyzeSpatialAudioAsync(
                    context = context,
                    mediaId = mediaId,
                    mediaStreams = apiMediaStreams,
                    helper = spatializerHelper
                )

            } catch (e: Exception) {
                Log.e(TAG, "Player initialization failed", e)
                if (exoPlayer == null && mpvPlayer == null) {
                    ActivePlayback.setActive(false)
                }
                _playerState.value = _playerState.value.copy(
                    isLoading = false,
                    error = e.message ?: "Unknown error occurred"
                )
            }
        }
    }

    fun initializeRemotePlayer(
        context: Context,
        mediaId: String,
        remoteUrl: String,
        title: String? = null,
        startPlayback: Boolean = true
    ) {
        viewModelScope.launch {
            try {
                val mediaTitle = title?.takeIf { it.isNotBlank() } ?: "Trailer"
                releasePlayer()
                ActivePlayback.setActive(true)
                remotePlaybackRequestKey = mediaId
                _playerState.value = PlayerState(
                    isLoading = true,
                    playWhenReady = startPlayback,
                    mediaTitle = mediaTitle
                )

                playerContext = context
                activePlayerEngine = PlayerPreferences.PLAYER_ENGINE_EXO
                hasHandledPlaybackCompletion = false
                hasRenderedFirstFrame = false
                currentItemDetails = null
                apiMediaStreams = null
                defaultAudioStreamIndex = null
                defaultSubtitleStreamIndex = null
                playbackSession = PlaybackSessionContext()
                playbackReporter.updateSession(playbackSession)

                val playbackStream = RemoteTrailerUrl.resolve(remoteUrl)
                if (remotePlaybackRequestKey != mediaId) return@launch
                configureScrubPreviewSource(
                    context = context,
                    uri = Uri.parse(playbackStream.url),
                    requestHeaders = emptyMap(),
                    warmPositionMs = 0L
                )
                fun mediaSource(url: String, mimeType: String?) =
                    PlayerUtils.createStreamingMediaSource(
                        context = context,
                        mediaItem = MediaItem.Builder()
                            .setUri(Uri.parse(url))
                            .setMimeType(mimeType)
                            .build()
                    )

                val videoSource = mediaSource(playbackStream.url, playbackStream.mimeType)
                val playbackMediaSource = playbackStream.audioUrl
                    ?.takeIf { it.isNotBlank() }
                    ?.let { audioUrl ->
                        MergingMediaSource(
                            videoSource,
                            mediaSource(audioUrl, playbackStream.audioMimeType)
                        )
                    }
                    ?: videoSource

                exoPlayer = PlayerUtils.createPlayer(
                    context = context,
                    bufferOverride = if (PlayerUtils.isUnmeteredNetwork(context)) {
                        null
                    } else {
                        PlayerUtils.PlaybackBufferOverride(
                            minBufferMs = 30_000,
                            maxBufferMs = 180_000,
                            bufferForPlaybackMs = 2_500,
                            bufferForPlaybackAfterRebufferMs = 5_000
                        )
                    }
                )
                ActivePlayback.setActive(true)
                exoPlayer?.apply {
                    addListener(playerListener)
                    trackSelectionParameters = trackSelectionParameters
                        .buildUpon()
                        .setForceHighestSupportedBitrate(true)
                        .build()
                    setMediaSource(playbackMediaSource)
                    prepare()
                    playWhenReady = startPlayback
                }

                applyStartMaximizedSetting(context)
            } catch (e: Exception) {
                Log.e(TAG, "Remote trailer initialization failed", e)
                if (exoPlayer == null && mpvPlayer == null) {
                    ActivePlayback.setActive(false)
                }
                _playerState.value = _playerState.value.copy(
                    isLoading = false,
                    error = e.message ?: "Unable to play remote trailer",
                    playWhenReady = false,
                    isPlaying = false
                )
            }
        }
    }

    private fun analyzeSpatialAudioAsync(
        context: Context,
        mediaId: String,
        mediaStreams: List<MediaStream>?,
        helper: SpatializerHelper?
    ) {
        spatialAudioAnalysisJob?.cancel()
        val primaryAudioStream = mediaStreams
            ?.firstOrNull { it.type == "Audio" }
            ?: return

        spatialAudioAnalysisJob = viewModelScope.launch {
            val spatializationResult = withContext(Dispatchers.Default) {
                CodecCapabilityManager.canSpatializeAudioStream(
                    context = context,
                    audioStream = primaryAudioStream,
                    spatializerHelper = helper
                )
            }
            if (playbackSession.mediaId != mediaId) return@launch

            _playerState.value = _playerState.value.copy(
                spatializationResult = spatializationResult,
                isSpatialAudioEnabled = spatializationResult.canSpatialize,
                spatialAudioFormat = spatializationResult.spatialFormat
            )
        }
    }

    private fun updateMpvWatchdog() {
        val player = exoPlayer
        val mediaId = playbackSession.mediaId
        val shouldWatch = player != null &&
            mediaId != null &&
            !isMpvPlayback() &&
            !hasRenderedFirstFrame &&
            currentMediaHasVideo() &&
            player.playWhenReady &&
            player.playbackState == Player.STATE_READY

        if (!shouldWatch) {
            cancelMpvWatchdog()
            return
        }
        if (mpvWatchdogJob?.isActive == true) return

        cancelMpvWatchdog()
        mpvWatchdogJob = viewModelScope.launch {
            delay(MPV_FALLBACK_FIRST_FRAME_TIMEOUT_MS)
            val currentPlayer = exoPlayer ?: return@launch
            if (
                playbackSession.mediaId == mediaId &&
                !isMpvPlayback() &&
                !hasRenderedFirstFrame &&
                currentMediaHasVideo() &&
                currentPlayer.playWhenReady &&
                currentPlayer.playbackState == Player.STATE_READY
            ) {
                triggerMpvFallback()
            }
        }
    }

    private fun triggerMpvFallback(): Boolean {
        if (isMpvPlayback()) return false
        val context = playerContext ?: return false
        val mediaId = playbackSession.mediaId ?: return false

        cancelMpvWatchdog()

        val itemDetails = currentItemDetails
        val resumePositionMs = getCurrentPosition()
        val shouldResumePlaying = _playerState.value.playWhenReady || isPlayingNow()
        val preferredAudioStreamIndex = _preferredStreamIndexes.value.audioStreamIndex
        val preferredSubtitleStreamIndex = _preferredStreamIndexes.value.subtitleStreamIndex

        releasePlayer()
        initializePlayer(
            context = context,
            mediaId = mediaId,
            initialItemDetails = itemDetails,
            preferredAudioStreamIndex = preferredAudioStreamIndex,
            preferredSubtitleStreamIndex = preferredSubtitleStreamIndex,
            initialSeekPositionMs = resumePositionMs,
            startPlayback = shouldResumePlaying,
            forcedPlayerEngine = PlayerPreferences.PLAYER_ENGINE_MPV,
            mediaSourceId = requestedMediaSourceId
        )
        return true
    }

    private fun cancelMpvWatchdog() {
        mpvWatchdogJob?.cancel()
        mpvWatchdogJob = null
    }

    private fun observePlaybackCachePolicy(context: Context) {
        cachePolicyJob?.cancel()
        cachePolicyJob = viewModelScope.launch {
            NetworkModule.observeNetworkAccess(context)
                .distinctUntilChanged()
                .collect {
                    mpvPlayer?.refreshCachePolicy()
                }
        }
    }

    private fun currentMediaHasVideo(): Boolean =
        apiMediaStreams.isNullOrEmpty() || apiMediaStreams.orEmpty().any { stream ->
            stream.type.equals("Video", ignoreCase = true)
        }

    private fun applyCommunityPlaybackSegments(mediaId: String, itemDetails: BaseItemDto) {
        communityPlaybackSegmentsJob?.cancel()
        communityPlaybackSegmentsJob = viewModelScope.launch {
            val serverSegments = mediaRepository.getServerPlaybackSegments(mediaId)
            if (playbackSession.mediaId == mediaId && serverSegments != null && serverSegments.hasAnySegments()) {
                _playerState.value = _playerState.value.applyingPlaybackSegments(
                    segments = serverSegments,
                    overrideExisting = true
                )
            }
            if (!itemDetails.type.equals("Episode", ignoreCase = true)) return@launch
            val playbackSegments = mediaRepository.getCommunityPlaybackSegments(itemDetails).getOrNull()
                ?: return@launch
            if (playbackSession.mediaId != mediaId) return@launch
            _playerState.value = _playerState.value.applyingPlaybackSegments(
                segments = playbackSegments,
                overrideExisting = false
            )
        }
    }

    fun seekTo(position: Long, exact: Boolean = true) {
        val target = position.coerceAtLeast(0L)
        exoPlayer?.let { player ->
            // 已缓冲位置用精确 seek，拖动预览才能对上那一帧；未缓冲保持默认容差，避免为了精确帧去补关键帧。
            player.setSeekParameters(
                when {
                    exact && isPositionBuffered(target) -> SeekParameters.EXACT
                    exact -> SeekParameters.DEFAULT
                    else -> SeekParameters.CLOSEST_SYNC
                }
            )
            player.seekTo(target)
        }
        mpvPlayer?.seekTo(target, exact)
        _playerState.value = _playerState.value.copy(currentPosition = target)
        if (_playerState.value.playWhenReady) {
            mpvPlayer?.play()
            exoPlayer?.play()
        }
    }

    fun play() {
        exoPlayer?.play()
        mpvPlayer?.play()
        if (isMpvPlayback()) {
            _playerState.value = _playerState.value.copy(isPlaying = true, playWhenReady = true)
        }
    }

    fun pause() {
        exoPlayer?.pause()
        mpvPlayer?.pause()
        if (isMpvPlayback()) {
            _playerState.value = _playerState.value.copy(isPlaying = false, playWhenReady = false)
        }
        persistPosition()
    }

    fun seekToProgress(progress: Float, exact: Boolean = true) {
        val duration = getDuration()
        if (duration > 0L) {
            seekTo((duration * progress).toLong(), exact)
        }
    }

    fun seekBy(deltaMs: Long) {
        val currentPosition = getCurrentPosition()
        val duration = getDuration()
        val targetPosition = if (duration > 0L) {
            (currentPosition + deltaMs).coerceIn(0L, duration)
        } else {
            (currentPosition + deltaMs).coerceAtLeast(0L)
        }
        seekTo(targetPosition)
    }

    fun setPlaybackSpeed(speed: Float, retunePerformance: Boolean = true) {
        playbackSpeed = speed.coerceIn(0.25f, 4f)
        exoPlayer?.setPlaybackSpeed(playbackSpeed)
        mpvPlayer?.setSpeed(playbackSpeed.toDouble(), retunePerformance = retunePerformance)
        _playerState.value = _playerState.value.copy(playbackSpeed = playbackSpeed)
    }

    fun nudgePlaybackSpeed(delta: Float) {
        val stepped = ((playbackSpeed + delta) * 20f).toInt() / 20f
        setPlaybackSpeed(stepped)
    }

    fun toggleHardwareDecoding() {
        val context = playerContext ?: return
        val preferences = PlayerPreferences(context)
        val current = preferences.getMpvHardwareDecoding()
        val next = if (current == PlayerPreferences.MPV_HARDWARE_DECODING_NONE) {
            lastHardwareDecoding.takeIf {
                it != PlayerPreferences.MPV_HARDWARE_DECODING_NONE
            } ?: PlayerPreferences.DEFAULT_MPV_HARDWARE_DECODING
        } else {
            lastHardwareDecoding = current
            PlayerPreferences.MPV_HARDWARE_DECODING_NONE
        }
        preferences.setMpvHardwareDecoding(next)
        val applied = MPVPlayer.hardwareDecodingFor(
            mediaSource = null,
            userPreference = next,
            mediaStreams = apiMediaStreams
        )
        mpvPlayer?.setHardwareDecoding(applied)
        mpvPlayer?.applyStreamColorPolicy(apiMediaStreams)
        _playerState.value = _playerState.value.copy(hardwareDecoding = applied)
    }

    fun toggleVrFlatPlayback() {
        val layout = detectedVrLayout ?: return
        if (_playerState.value.vrFlatEnabled) {
            disableVrFlatPlayback()
        } else {
            enableVrFlatPlayback(layout)
        }
    }

    fun selectVrProjection(id: String) {
        val layout = VrLayoutParser.layoutForId(id) ?: return
        detectedVrLayout = layout
        _playerState.value = _playerState.value.copy(
            vrDetected = true,
            vrProjectionId = layout.id
        )
        if (_playerState.value.vrFlatEnabled) {
            enableVrFlatPlayback(layout)
        }
    }

    fun applyVrLookDelta(deltaYaw: Float, deltaPitch: Float) {
        if (!_playerState.value.vrFlatEnabled) return
        val layout = detectedVrLayout ?: return
        vrYaw = (vrYaw + deltaYaw).coerceIn(-layout.yawLimit, layout.yawLimit)
        vrPitch = (vrPitch + deltaPitch).coerceIn(-85f, 85f)
        mpvPlayer?.setVrLook(layout, vrYaw, vrPitch, vrOutputFov)
    }

    fun applyVrFovScale(scaleFactor: Float) {
        if (!_playerState.value.vrFlatEnabled) return
        val layout = detectedVrLayout ?: return
        if (scaleFactor <= 0f) return
        vrOutputFov = (vrOutputFov / scaleFactor).coerceIn(
            VrFlattenFilter.MIN_OUTPUT_FOV,
            VrFlattenFilter.MAX_OUTPUT_FOV
        )
        mpvPlayer?.setVrLook(layout, vrYaw, vrPitch, vrOutputFov)
    }

    private fun enableVrFlatPlayback(layout: VrLayout) {
        resetVrLook()
        mpvPlayer?.setVrFlattenShader(layout, vrYaw, vrPitch, vrOutputFov)
        _playerState.value = _playerState.value.copy(
            vrDetected = true,
            vrFlatEnabled = true,
            vrProjectionId = layout.id
        )
    }

    private fun disableVrFlatPlayback() {
        mpvPlayer?.setVrFlattenShader(null)
        resetVrLook()
        _playerState.value = _playerState.value.copy(vrFlatEnabled = false)
    }

    private fun resetVrPlayback() {
        detectedVrLayout = null
        resetVrLook()
        _playerState.value = _playerState.value.copy(
            vrDetected = false,
            vrFlatEnabled = false,
            vrProjectionId = null
        )
    }

    private fun resetVrLook() {
        vrYaw = 0f
        vrPitch = 0f
        vrOutputFov = VrFlattenFilter.DEFAULT_OUTPUT_FOV
    }

    fun beginHoldSpeed(speed: Float) {
        speedBeforeHold = playbackSpeed
        setPlaybackSpeed(speed, retunePerformance = false)
    }

    fun endHoldSpeed() {
        setPlaybackSpeed(speedBeforeHold, retunePerformance = false)
    }

    fun getCurrentPosition(): Long = exoPlayer?.currentPosition ?: mpvPlayer?.currentPosition ?: 0L

    fun getBufferedPosition(): Long {
        val duration = getDuration()
        val exo = exoPlayer
        val raw = if (exo != null) {
            exo.bufferedPosition.coerceAtLeast(exo.currentPosition)
        } else {
            val mpv = mpvPlayer ?: return 0L
            mpv.bufferedPosition.coerceAtLeast(mpv.currentPosition)
        }
        return if (duration > 0L) raw.coerceAtMost(duration) else raw
    }

    fun isPlayingNow(): Boolean = exoPlayer?.isPlaying == true || mpvPlayer?.isPlaying == true

    fun getDuration(): Long {
        val playerDuration = exoPlayer?.duration?.coerceAtLeast(0L) ?: mpvPlayer?.duration ?: 0L
        if (playerDuration > 0L) return playerDuration
        return currentItemDetails?.runTimeTicks?.takeIf { it > 0L }?.div(10_000L) ?: 0L
    }

    fun updateNextEpisodeCache(
        context: Context,
        nextEpisodeId: String?,
        preferredAudioStreamIndex: Int?,
        preferredSubtitleStreamIndex: Int?
    ) {
        val playerPreferences = PlayerPreferences(context)
        val targetEpisodeId = nextEpisodeId?.takeIf { it.isNotBlank() }
        if (
            targetEpisodeId == null ||
            targetEpisodeId == playbackSession.mediaId ||
            !playerPreferences.isCacheNextEpisodeEnabled()
        ) {
            cancelNextEpisodePrefetch()
            return
        }
        val prefetchSignature = buildString {
            append(targetEpisodeId)
            append('|')
            append(preferredAudioStreamIndex ?: "auto")
            append('|')
            append(preferredSubtitleStreamIndex ?: "auto")
            append('|')
            append(playerPreferences.getStreamingQuality())
            append('|')
            append(playerPreferences.getAudioTranscodeMode().name)
        }

        if (
            nextEpisodePrefetchSignature == prefetchSignature &&
            nextEpisodePrefetchJob?.isActive == true
        ) {
            return
        }

        cancelNextEpisodePrefetch()
        nextEpisodePrefetchSignature = prefetchSignature
        nextEpisodePrefetchJob = viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                prefetchNextEpisode(
                    context = context.applicationContext,
                    nextEpisodeId = targetEpisodeId,
                    preferredAudioStreamIndex = preferredAudioStreamIndex,
                    preferredSubtitleStreamIndex = preferredSubtitleStreamIndex,
                    playerPreferences = playerPreferences
                )
            }.onFailure { error ->
                Log.d(TAG, "Skipping next-episode cache prefetch for $targetEpisodeId", error)
            }
        }
    }

    private suspend fun prefetchNextEpisode(
        context: Context,
        nextEpisodeId: String,
        preferredAudioStreamIndex: Int?,
        preferredSubtitleStreamIndex: Int?,
        playerPreferences: PlayerPreferences
    ) {
        val nextDownloadRepository = downloadRepository ?: DownloadRepositoryProvider.getInstance(context)
        val offlinePath = nextDownloadRepository.getOfflineFilePath(nextEpisodeId)
        if (!offlinePath.isNullOrBlank()) {
            return
        }

        val isVideoTranscodingAllowed = isVideoTranscodingAllowedForUser()
        val isAudioTranscodingAllowed = isAudioTranscodingAllowedForUser()
        val audioTranscodeMode = if (isAudioTranscodingAllowed) {
            playerPreferences.getAudioTranscodeMode()
        } else {
            AudioTranscodeMode.AUTO
        }
        val maxStreamingBitrate = if (isVideoTranscodingAllowed) {
            playerPreferences.getMaxStreamingBitrate()
        } else {
            null
        }
        val maxStreamingHeight = if (isVideoTranscodingAllowed) {
            playerPreferences.getStreamingQualityMaxHeight()
        } else {
            null
        }

        val playbackInfo = mediaRepository.getPlaybackInfo(
            itemId = nextEpisodeId,
            maxStreamingBitrate = maxStreamingBitrate,
            audioStreamIndex = preferredAudioStreamIndex,
            subtitleStreamIndex = preferredSubtitleStreamIndex,
            audioTranscodeMode = audioTranscodeMode
        ).getOrNull() ?: return

        val playbackRequest = mediaRepository.getPlaybackRequest(
            itemId = nextEpisodeId,
            maxStreamingBitrate = maxStreamingBitrate,
            maxStreamingHeight = maxStreamingHeight,
            audioStreamIndex = preferredAudioStreamIndex,
            subtitleStreamIndex = preferredSubtitleStreamIndex,
            audioTranscodeMode = audioTranscodeMode,
            playbackInfo = playbackInfo
        ).getOrNull() ?: return

        val streamingUrl = playbackRequest.url?.takeIf { it.isNotBlank() } ?: return
        val nextMediaItem = streamingMediaItem(streamingUrl = streamingUrl)
        val localConfiguration = nextMediaItem.localConfiguration ?: return
        val prefetchBytes = nextEpisodePrefetchBytes(
            playerPreferences = playerPreferences,
            sourceBitrate = playbackInfo.mediaSources?.firstOrNull()?.bitrate,
            maxStreamingBitrate = maxStreamingBitrate
        )

        PlayerUtils.prefetchStreamingMedia(
            context = context,
            streamUri = localConfiguration.uri,
            cacheKey = localConfiguration.customCacheKey,
            maxBytes = prefetchBytes,
            requestHeaders = playbackRequest.requestHeaders
        )
    }

    private fun nextEpisodePrefetchBytes(
        playerPreferences: PlayerPreferences,
        sourceBitrate: Int?,
        maxStreamingBitrate: Int?
    ): Long {
        val bitrateBitsPerSecond = when {
            maxStreamingBitrate != null && maxStreamingBitrate > 0 -> maxStreamingBitrate.toLong()
            sourceBitrate != null && sourceBitrate > 0 -> sourceBitrate.toLong()
            else -> 8_000_000L
        }.coerceAtLeast(2_000_000L)
        val prefetchWindowSeconds = minOf(playerPreferences.getPlayerCacheTimeSeconds(), 45)
        val desiredBytes = bitrateBitsPerSecond
            .times(prefetchWindowSeconds.toLong())
            .div(8L)
        val cacheBudgetBytes = playerPreferences.getPlayerCacheSizeMb()
            .toLong()
            .times(1024L * 1024L)
            .div(3L)

        return minOf(desiredBytes, cacheBudgetBytes).coerceAtLeast(8L * 1024L * 1024L)
    }

    private fun cancelNextEpisodePrefetch() {
        nextEpisodePrefetchJob?.cancel()
        nextEpisodePrefetchJob = null
        nextEpisodePrefetchSignature = null
    }

    private fun getPlayMethod(
        streamingUrl: String,
        fallback: PlayMethod
    ): PlayMethod {
        val streamUri = Uri.parse(streamingUrl)
        val path = streamUri.encodedPath.orEmpty().lowercase()
        val isTranscodingUrl =
            path.contains("master.m3u8") ||
                path.contains("transcode") ||
                path.contains("transcoding")

        if (isTranscodingUrl) {
            return PlayMethod.TRANSCODE
        }

        return when (streamUri.getQueryParameter("static")?.lowercase()) {
            "true" -> PlayMethod.DIRECT_PLAY
            "false" -> PlayMethod.DIRECT_STREAM
            else -> fallback
        }
    }

    private suspend fun isVideoTranscodingAllowedForUser(): Boolean {
        videoTranscodingAllowed?.let { return it }
        mediaRepository.loadPersistedHomeSnapshot()?.isVideoTranscodingAllowed?.let {
            videoTranscodingAllowed = it
            return it
        }

        val user = mediaRepository.getCurrentUser().getOrNull()
        val allowed = user?.policy?.enableVideoPlaybackTranscoding
            ?: user?.let { true }
            ?: false

        videoTranscodingAllowed = allowed
        mediaRepository.persistHomeSnapshot(isVideoTranscodingAllowed = allowed)
        return allowed
    }

    private suspend fun isAudioTranscodingAllowedForUser(): Boolean {
        audioTranscodingAllowed?.let { return it }
        mediaRepository.loadPersistedHomeSnapshot()?.isAudioTranscodingAllowed?.let {
            audioTranscodingAllowed = it
            return it
        }

        val user = mediaRepository.getCurrentUser().getOrNull()
        val allowed = user?.policy?.enableAudioPlaybackTranscoding
            ?: user?.let { true }
            ?: false

        audioTranscodingAllowed = allowed
        mediaRepository.persistHomeSnapshot(isAudioTranscodingAllowed = allowed)
        return allowed
    }

    fun togglePlayPause() {
        if (exoPlayer != null || mpvPlayer != null) {
            if (isPlayingNow()) pause() else play()
            playbackReporter.onPlaybackPauseStateChanged()
        }
    }

    fun setVolume(volume: Float) {
        exoPlayer?.volume = volume
        mpvPlayer?.setVolume(volume)
        _playerState.value = _playerState.value.copy(volume = volume)
    }

    fun setBrightness(brightness: Float) {
        _playerState.value = _playerState.value.copy(brightness = brightness)
    }

    fun toggleControls() {
        _playerState.value = _playerState.value.copy(showControls = !_playerState.value.showControls)
    }

    /**
     * 登记进度预览的片源，并在后台预热抽帧器。
     *
     * 换片时丢掉上一份抽帧器和关键帧缓存。拖动期间不 seek 主播放器。
     *
     * @param context 用于打开本地地址和播放器缓存
     * @param uri 当前播放地址；没有可独立打开的地址时为空
     * @param requestHeaders 打开直链时附带的请求头
     * @param warmPositionMs 预热时抽取的位置，单位毫秒，一般是起播点
     * @param cacheKey 主播放器对这条直链使用的缓存键，抽帧读取沿用它命中同一份缓存
     */
    private fun configureScrubPreviewSource(
        context: Context,
        uri: Uri?,
        requestHeaders: Map<String, String>,
        warmPositionMs: Long,
        cacheKey: String? = null
    ) {
        scrubWarmJob?.cancel()
        scrubPreviewVersion++
        scrubPreviewFrame = null
        clearScrubFrames()
        val source = uri?.let {
            val remote = it.scheme?.lowercase(Locale.ROOT) in setOf("http", "https")
            ScrubPreviewSource(
                context = context.applicationContext,
                uri = it,
                requestHeaders = requestHeaders,
                cacheKey = cacheKey,
                dataSourceFactory = if (remote) {
                    PlayerUtils.createCachedDataSourceFactory(context.applicationContext, requestHeaders)
                } else {
                    null
                }
            )
        }
        synchronized(scrubGrabberLock) {
            scrubGrabber?.reset()
            scrubPreviewSource = source
        }
        if (source != null && supportsKeyframeScrub(source.uri)) {
            warmScrubFrames(source, warmPositionMs)
        }
    }

    /**
     * 请求进度条拖动预览帧。
     *
     * 同一秒内的关键帧直接复用。没有精确缓存时先显示 8 秒内的邻近帧，再在后台抽新的关键帧。
     * 不移动主播放器。
     *
     * @param positionMs 预览位置，单位毫秒
     */
    fun requestScrubPreview(positionMs: Long) {
        val source = scrubPreviewSource
        if (source == null || !supportsKeyframeScrub(source.uri)) {
            scrubPreviewVersion++
            scrubPreviewFrame = null
            return
        }
        val version = ++scrubPreviewVersion
        cachedScrubFrame(positionMs, exact = false)?.let { scrubPreviewFrame = it }
        if (cachedScrubFrame(positionMs, exact = true) != null) return
        scrubPreviewQueued = true
        scrubPreviewRequests.trySend(
            ScrubPreviewRequest(
                source = source,
                positionMs = positionMs,
                version = version
            )
        )
    }

    /**
     * 开播后延迟打开抽帧器，并先抽出起播位置的一帧。
     *
     * 拖动已经开始时不再预热，避免和用户请求抢同一把抽帧锁。
     *
     * @param source 当前片源
     * @param positionMs 预热位置，单位毫秒
     */
    private fun warmScrubFrames(source: ScrubPreviewSource, positionMs: Long) {
        scrubWarmJob = viewModelScope.launch(Dispatchers.IO) {
            delay(SCRUB_FRAME_WARM_DELAY_MS)
            if (scrubPreviewSource !== source || scrubPreviewQueued) return@launch
            if (cachedScrubFrame(positionMs, exact = true) != null) return@launch
            val frame = loadKeyframeFrame(source, positionMs) ?: return@launch
            try {
                ensureActive()
            } catch (cancelled: CancellationException) {
                if (!frame.isRecycled) frame.recycle()
                throw cancelled
            }
            if (scrubPreviewSource !== source) {
                if (!frame.isRecycled) frame.recycle()
                return@launch
            }
            rememberScrubFrame(positionMs, frame)
        }
    }

    /**
     * 读取缓存帧。
     *
     * @param positionMs 目标位置，单位毫秒
     * @param exact 为 true 时只返回同一秒的帧；为 false 时允许 8 秒内的邻近帧
     * @return 可显示的位图；没有缓存时为 null
     */
    private fun cachedScrubFrame(positionMs: Long, exact: Boolean): Bitmap? {
        synchronized(scrubFrameLock) {
            val target = scrubFrameBucket(positionMs)
            val bucket = if (exact) {
                target.takeIf { bucket ->
                    scrubFrames[bucket]?.isRecycled == false
                }
            } else {
                nearestScrubFrameBucket(scrubFrames.keys.toSet(), positionMs)
            }
            return bucket?.let { scrubFrames[it] }?.takeIf { !it.isRecycled }
        }
    }

    /**
     * 把刚抽出的关键帧放进秒级缓存，并淘汰最久未用的帧。
     *
     * @param positionMs 该帧对应的请求位置，单位毫秒
     * @param frame 预览位图。调用后由缓存负责回收
     */
    private fun rememberScrubFrame(positionMs: Long, frame: Bitmap) {
        synchronized(scrubFrameLock) {
            val bucket = scrubFrameBucket(positionMs)
            val previous = scrubFrames.put(bucket, frame)
            if (previous != null && previous !== frame && previous !== scrubPreviewFrame && !previous.isRecycled) {
                previous.recycle()
            }
            while (scrubFrames.size > SCRUB_FRAME_CACHE_CAPACITY) {
                val eldest = scrubFrames.entries.firstOrNull() ?: break
                scrubFrames.remove(eldest.key)
                val bitmap = eldest.value
                if (bitmap !== frame && bitmap !== scrubPreviewFrame && !bitmap.isRecycled) {
                    bitmap.recycle()
                }
            }
        }
    }

    /** 清空关键帧缓存并回收位图。 */
    private fun clearScrubFrames() {
        synchronized(scrubFrameLock) {
            scrubFrames.values.forEach { bitmap ->
                if (!bitmap.isRecycled) bitmap.recycle()
            }
            scrubFrames.clear()
        }
    }

    /**
     * 只有仍是最新拖动请求时才替换预览图。失败时保留已经显示的邻近帧。
     *
     * @param version 发起请求时的版本
     * @param frame 要显示的位图
     */
    private suspend fun publishScrubFrame(version: Long, frame: Bitmap) {
        withContext(Dispatchers.Main.immediate) {
            if (version == scrubPreviewVersion && !frame.isRecycled) {
                scrubPreviewFrame = frame
            }
        }
    }

    /**
     * 判断地址能否用系统抽帧器做关键帧预览。
     *
     * HLS 播放列表没有稳定的随机关键帧，打开它还会卡住抽帧器，因此跳过。
     *
     * @param uri 播放地址
     * @return 本地文件或渐进式 HTTP(S) 时为 true
     */
    private fun supportsKeyframeScrub(uri: Uri): Boolean {
        val name = uri.lastPathSegment?.lowercase(Locale.ROOT).orEmpty()
        if (name.endsWith(".m3u8")) return false
        return when (uri.scheme?.lowercase(Locale.ROOT)) {
            "file", "content", "http", "https" -> true
            else -> false
        }
    }

    /**
     * 从片源抽取离目标最近的关键帧。
     *
     * 抽帧器保持打开。同一张关键帧不会再次硬解。
     *
     * @param source 播放地址和请求头
     * @param positionMs 目标位置，单位毫秒
     * @return 预览位图；地址已更换或这一帧解不出来时为 null
     */
    private fun loadKeyframeFrame(source: ScrubPreviewSource, positionMs: Long): Bitmap? {
        return synchronized(scrubGrabberLock) {
            if (source !== scrubPreviewSource) return@synchronized null
            val grabber = scrubGrabber ?: ScrubFrameGrabber().also { scrubGrabber = it }
            grabber.frameAt(source, positionMs, SCRUB_PREVIEW_WIDTH_PX)
        }
    }

    /**
     * 判断时间点是否落在当前已缓冲区间内。
     *
     * @param positionMs 目标位置，单位毫秒
     * @return 不超过缓冲末端（含进度条同等容差）时为 true
     */
    private fun isPositionBuffered(positionMs: Long): Boolean {
        val duration = getDuration()
        val toleranceMs = if (duration > 0L) {
            (duration * 0.0005f).toLong().coerceAtLeast(400L)
        } else {
            400L
        }
        return positionMs <= getBufferedPosition() + toleranceMs
    }

    fun clearScrubPreview() {
        scrubPreviewVersion++
        scrubPreviewFrame = null
    }

    private fun releaseScrubPreview() {
        scrubWarmJob?.cancel()
        scrubWarmJob = null
        scrubPreviewQueued = false
        clearScrubPreview()
        clearScrubFrames()
        synchronized(scrubGrabberLock) {
            scrubPreviewSource = null
            scrubGrabber?.release()
            scrubGrabber = null
        }
    }

    fun releasePlayer() {
        ActivePlayback.setActive(false)
        releaseScrubPreview()
        persistPosition()
        playbackReporter.reportPlaybackStopped()
        cancelNextEpisodePrefetch()
        communityPlaybackSegmentsJob?.cancel()
        communityPlaybackSegmentsJob = null
        spatialAudioAnalysisJob?.cancel()
        spatialAudioAnalysisJob = null
        cachePolicyJob?.cancel()
        cachePolicyJob = null
        cancelMpvWatchdog()
        exoPlayer?.apply {
            removeListener(playerListener)
            release()
        }
        exoPlayer = null
        mpvPlayer?.release()
        mpvPlayer = null
        spatializerHelper?.cleanup()
        spatializerHelper = null
        playbackSession = PlaybackSessionContext()
        playbackReporter.reset()
        trackSelectionCoordinator.clear()
        apiMediaStreams = null
        defaultAudioStreamIndex = null
        defaultSubtitleStreamIndex = null
        mpvExternalSubtitleUrls = emptyMap()
        playerContext = null
        downloadRepository = null
        hasHandledPlaybackCompletion = false
        hasRenderedFirstFrame = false
        audioDiagnosticsSignature = null
        remotePlaybackRequestKey = null
        _preferredStreamIndexes.value = PreferredStreamIndexes()
        _playerState.value = PlayerState()
    }

    private fun handlePlaybackCompleted() {
        if (hasHandledPlaybackCompletion) return
        hasHandledPlaybackCompletion = true
        persistPosition(markCompleted = true)
        playbackReporter.reportPlaybackStopped()
        playbackSession.mediaId?.let { completedMediaId ->
            _playbackCompletedEvents.tryEmit(completedMediaId)
        }
    }

    private fun persistPosition(markCompleted: Boolean = false) {
        val session = playbackSession
        if (!session.isOfflinePlayback) return
        val mediaId = session.mediaId ?: return
        downloadRepository?.updatePlaybackPosition(
            itemId = mediaId,
            positionMs = getCurrentPosition(),
            markCompleted = markCompleted
        )
    }

    fun clearError() {
        _playerState.value = _playerState.value.copy(error = null)
    }

    /**
     * Toggle lock state - when locked, disable all gestures and hide controls
     */
    fun toggleLock() {
        val currentState = _playerState.value
        _playerState.value = currentState.copy(
            isLocked = !currentState.isLocked,
            showControls = if (!currentState.isLocked) false else currentState.showControls
        )
    }

    /**
     * Update track information from ExoPlayer
     */
    private fun updateTrackInformation() {
        exoPlayer?.let { player ->
            try {
                val hdrFormat = resolveExoHdrFormatLabel()
                val isHdrPlayback = hdrFormat.isNotBlank()
                val selectedAudioSignature = buildSelectedAudioSignature(player)
                if (selectedAudioSignature != null && selectedAudioSignature != audioDiagnosticsSignature) {
                    PlayerUtils.logAudioPlaybackDiagnostics(player, reason = "track_changed")
                    audioDiagnosticsSignature = selectedAudioSignature
                }
                val resolvedTracks = PlayerTrack.currentTrackState(
                    exoPlayer = player,
                    mediaStreams = apiMediaStreams,
                    isTranscoding = playbackSession.playMethod == PlayMethod.TRANSCODE,
                    selectedAudioStreamIndex = _preferredStreamIndexes.value.audioStreamIndex,
                    selectedSubtitleStreamIndex = _preferredStreamIndexes.value.subtitleStreamIndex,
                    defaultAudioStreamIndex = defaultAudioStreamIndex,
                    defaultSubtitleStreamIndex = defaultSubtitleStreamIndex
                )
                val syncedPreferredIndexes = trackSelectionCoordinator.syncPreferredIndexesFromCurrentTracks(
                    context = playerContext,
                    mediaId = playbackSession.mediaId,
                    currentAudioTrack = resolvedTracks.currentAudioTrack
                        ?.takeUnless { it.requiresPlaybackRestart },
                    currentSubtitleTrack = resolvedTracks.currentSubtitleTrack
                        ?.takeUnless { it.requiresPlaybackRestart },
                    currentPublished = _preferredStreamIndexes.value
                )
                if (syncedPreferredIndexes != _preferredStreamIndexes.value) {
                    _preferredStreamIndexes.value = syncedPreferredIndexes
                }

                _playerState.value = _playerState.value.copy(
                    availableAudioTracks = resolvedTracks.availableAudioTracks,
                    currentAudioTrack = resolvedTracks.currentAudioTrack,
                    availableSubtitleTracks = resolvedTracks.availableSubtitleTracks,
                    currentSubtitleTrack = resolvedTracks.currentSubtitleTrack,
                    availableVideoTracks = resolvedTracks.availableVideoTracks,
                    isHdrEnabled = isHdrPlayback,
                    hdrFormat = hdrFormat
                )
            } catch (e: Exception) {
                Log.e("PlayerViewModel", "Failed to update track information", e)
            }
        }
    }

    @UnstableApi
    private fun buildSelectedAudioSignature(player: ExoPlayer): String? {
        player.currentTracks.groups.forEachIndexed { groupIndex, group ->
            if (group.type != C.TRACK_TYPE_AUDIO) return@forEachIndexed
            val trackIndex = (0 until group.mediaTrackGroup.length)
                .firstOrNull(group::isTrackSelected)
                ?: return@forEachIndexed
            val format = group.mediaTrackGroup.getFormat(trackIndex)
            return "$groupIndex:$trackIndex|${format.channelCount}|${format.bitrate}|${format.sampleRate}|${format.codecs ?: format.sampleMimeType.orEmpty()}"
        }
        return null
    }

    private fun applyPendingTrackSelectionsIfNeeded() {
        val player = exoPlayer ?: return
        val appliedAnySelection = trackSelectionCoordinator.applyInitialSelections(
            player = player,
            mediaStreams = apiMediaStreams,
            isTranscoding = playbackSession.playMethod == PlayMethod.TRANSCODE
        )
        if (appliedAnySelection) {
            viewModelScope.launch {
                delay(250)
                updateTrackInformation()
            }
        }
    }

    private fun updateApiTrackInformation() {
        val trackState = MPVPlayer.trackState(
            mediaStreams = apiMediaStreams,
            selectedAudioStreamIndex = _preferredStreamIndexes.value.audioStreamIndex,
            selectedSubtitleStreamIndex = _preferredStreamIndexes.value.subtitleStreamIndex,
            defaultAudioStreamIndex = defaultAudioStreamIndex,
            defaultSubtitleStreamIndex = defaultSubtitleStreamIndex
        )
        val hdrFormat = resolveMpvHdrFormatLabel()

        _playerState.value = _playerState.value.copy(
            availableAudioTracks = trackState.availableAudioTracks,
            currentAudioTrack = trackState.currentAudioTrack,
            availableSubtitleTracks = trackState.availableSubtitleTracks,
            currentSubtitleTrack = trackState.currentSubtitleTrack,
            availableVideoTracks = trackState.availableVideoTracks,
            isHdrEnabled = hdrFormat.isNotBlank(),
            hdrFormat = hdrFormat
        )
    }

    private suspend fun createMpvPlayer(context: Context): MpvPlayerController {
        return MpvWarmPool.obtain(
            context = context,
            listener = createMpvListener()
        )
    }

    private fun createMpvListener(): MpvPlayerController.Listener {
        return object : MpvPlayerController.Listener {
            override fun onBuffering() {
                _playerState.value = _playerState.value.copy(isLoading = true)
            }

            override fun onReady() {
                val wasPlaying = _playerState.value.isPlaying
                _playerState.value = _playerState.value.copy(
                    isLoading = false,
                    isPlaying = isPlayingNow(),
                    playWhenReady = isPlayingNow(),
                    hasStartedPlayback = true,
                    duration = getDuration()
                )
                if (!playbackReporter.hasReportedStart() && isPlayingNow()) {
                    playbackReporter.reportPlaybackStatus()
                }
                if (wasPlaying != isPlayingNow()) {
                    playbackReporter.onPlaybackPauseStateChanged()
                }
            }

            override fun onEnded() {
                _playerState.value = _playerState.value.copy(
                    isPlaying = false,
                    playWhenReady = false,
                    isLoading = false
                )
                handlePlaybackCompleted()
            }
        }
    }

    /**
     * Select audio track by ID
     */
    /**
     * 按音轨 id 切换音频；id 为静音项时关闭音频输出并记住选择。
     *
     * @param trackId 音轨 id，静音项为 [TrackDetails.AUDIO_OFF_ID]
     */
    fun selectAudioTrack(trackId: String) {
        if (trackId == _playerState.value.currentAudioTrack?.id) return
        val selectedTrack = _playerState.value.availableAudioTracks.firstOrNull { it.id == trackId } ?: return
        if (TrackDetails.isMutedAudio(selectedTrack)) {
            applyMutedAudio(selectedTrack)
            return
        }
        if (isMpvPlayback()) {
            val streamIndex = MPVPlayer.selectAudioTrack(mpvPlayer, selectedTrack) ?: return
            persistAudioPreference(streamIndex)
            _playerState.value = _playerState.value.copy(currentAudioTrack = selectedTrack)
            return
        }
        if (selectedTrack.requiresPlaybackRestart) {
            playbackTrackSelection(
                audioStreamIndex = selectedTrack.streamIndex,
                subtitleStreamIndex = _preferredStreamIndexes.value.subtitleStreamIndex
            )
            return
        }
        exoPlayer?.let { player ->
            val playerTrackId = selectedTrack.playerTrackId ?: return
            trackSelectionCoordinator.markManualTrackSelection()
            PlayerUtils.selectAudioTrack(player, playerTrackId)
            persistAudioPreference(selectedTrack.streamIndex)
            viewModelScope.launch {
                delay(500)
                updateTrackInformation()
            }
        }
    }

    /**
     * 关闭当前音频输出并记住静音选择。
     *
     * @param selectedTrack 静音音轨项，用于刷新当前选中状态
     */
    private fun applyMutedAudio(selectedTrack: AudioTrackInfo) {
        if (isMpvPlayback()) {
            mpvPlayer?.selectAudioTrack("no")
            persistAudioPreference(-1)
            _playerState.value = _playerState.value.copy(currentAudioTrack = selectedTrack)
            return
        }
        exoPlayer?.let { player ->
            trackSelectionCoordinator.markManualTrackSelection()
            PlayerUtils.selectAudioTrack(player, TrackDetails.AUDIO_OFF_ID)
            persistAudioPreference(-1)
            _playerState.value = _playerState.value.copy(currentAudioTrack = selectedTrack)
        }
    }

    /**
     * Select subtitle track by ID
     */
    fun selectSubtitleTrack(trackId: String) {
        Log.d(TAG, "Selecting subtitle track: $trackId")
        if (trackId == _playerState.value.currentSubtitleTrack?.id) {
            Log.d(TAG, "Subtitle track $trackId already selected")
            return
        }
        val selectedTrack = _playerState.value.availableSubtitleTracks.firstOrNull { it.id == trackId } ?: run {
            Log.w(TAG, "Could not find subtitle track with id $trackId in available tracks")
            return
        }
        
        Log.d(TAG, "Selected track info: label=${selectedTrack.label} streamIndex=${selectedTrack.streamIndex} requiresRestart=${selectedTrack.requiresPlaybackRestart}")

        if (isMpvPlayback()) {
            val streamIndex = MPVPlayer.selectSubtitleTrack(
                controller = mpvPlayer,
                track = selectedTrack,
                externalSubtitleUrls = mpvExternalSubtitleUrls
            ) ?: return
            persistSubtitlePreference(streamIndex.takeUnless { it < 0 } ?: -1)
            _playerState.value = _playerState.value.copy(currentSubtitleTrack = selectedTrack)
            return
        }
        if (selectedTrack.requiresPlaybackRestart) {
            Log.d(TAG, "Subtitle selection requires playback restart for stream index: ${selectedTrack.streamIndex}")
            playbackTrackSelection(
                audioStreamIndex = _preferredStreamIndexes.value.audioStreamIndex,
                subtitleStreamIndex = selectedTrack.streamIndex
            )
            return
        }
        exoPlayer?.let { player ->
            val playerTrackId = selectedTrack.playerTrackId ?: return
            Log.d(TAG, "Applying subtitle selection to ExoPlayer: $playerTrackId")
            trackSelectionCoordinator.markManualTrackSelection()
            PlayerUtils.selectSubtitleTrack(player, playerTrackId)
            persistSubtitlePreference(selectedTrack.streamIndex ?: -1)
            viewModelScope.launch {
                delay(500)
                updateTrackInformation()
            }
        }
    }

    private fun persistAudioPreference(streamIndex: Int?) {
        val (preferences, mediaId) = currentMediaPreferences() ?: return
        preferences.persistAudioSelection(
            itemId = mediaId,
            seriesId = seriesPreferenceId(),
            streams = apiMediaStreams.orEmpty(),
            streamIndex = streamIndex
        )
        _preferredStreamIndexes.value = _preferredStreamIndexes.value.copy(audioStreamIndex = streamIndex)
    }

    private fun persistSubtitlePreference(streamIndex: Int?) {
        val (preferences, mediaId) = currentMediaPreferences() ?: return
        preferences.persistSubtitleSelection(
            itemId = mediaId,
            seriesId = seriesPreferenceId(),
            streams = apiMediaStreams.orEmpty(),
            streamIndex = streamIndex
        )
        _preferredStreamIndexes.value = _preferredStreamIndexes.value.copy(subtitleStreamIndex = streamIndex)
    }

    private fun seriesPreferenceId(): String? {
        return TrackDetails.seriesPreferenceId(
            itemType = currentItemDetails?.type,
            seriesId = currentItemDetails?.seriesId
        )
    }

    private fun currentMediaPreferences(): Pair<PlayerPreferences, String>? {
        val context = playerContext ?: return null
        val mediaId = playbackSession.mediaId ?: return null
        return PlayerPreferences(context) to mediaId
    }

    private fun playbackTrackSelection(
        audioStreamIndex: Int?,
        subtitleStreamIndex: Int?
    ) {
        val context = playerContext ?: return
        val mediaId = playbackSession.mediaId ?: return
        val resumePositionMs = getCurrentPosition()
        val shouldResumePlaying = isPlayingNow()

        PlayerPreferences(context).apply {
            persistAudioSelection(
                itemId = mediaId,
                seriesId = seriesPreferenceId(),
                streams = apiMediaStreams.orEmpty(),
                streamIndex = audioStreamIndex
            )
            persistSubtitleSelection(
                itemId = mediaId,
                seriesId = seriesPreferenceId(),
                streams = apiMediaStreams.orEmpty(),
                streamIndex = subtitleStreamIndex
            )
        }

        releasePlayer()
        initializePlayer(
            context = context,
            mediaId = mediaId,
            initialItemDetails = currentItemDetails,
            preferredAudioStreamIndex = audioStreamIndex,
            preferredSubtitleStreamIndex = subtitleStreamIndex,
            initialSeekPositionMs = resumePositionMs,
            startPlayback = shouldResumePlaying,
            mediaSourceId = requestedMediaSourceId
        )
    }

    private var currentAspectRatio by mutableIntStateOf(0)
    private val aspectRatioModes = listOf("Fit", "Zoom")

    private var currentResizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT

    fun setVideoWidthFraction(fraction: Float) {
        val widthFraction = fraction.coerceIn(MIN_VIDEO_WIDTH_FRACTION, MAX_VIDEO_WIDTH_FRACTION)
        _playerState.value = _playerState.value.copy(videoWidthFraction = widthFraction)
    }

    /**
     * Toggle between fit and zoom modes
     * Uses ExoPlayer's native AspectRatioFrameLayout resize modes for proper aspect ratio handling
     */
    fun cycleAspectRatio() {
        setAspectRatioMode((currentAspectRatio + 1) % aspectRatioModes.size)
    }
    
    /**
     * Get current resize mode for VideoSurface
     */
    fun getCurrentResizeMode(): Int = currentResizeMode
    
    /**
     * Handle pinch-to-zoom gesture to set appropriate resize mode
     */
    fun handlePinchZoom(isZooming: Boolean) {
        if (isZooming && currentAspectRatio == 0) {
            setAspectRatioMode(1)
        } else if (!isZooming && currentAspectRatio == 1) {
            setAspectRatioMode(0)
        }
    }

    /**
     * Apply start maximized setting based on user preference
     * Uses ExoPlayer's native resize modes for proper aspect ratio handling
     */
    private fun applyStartMaximizedSetting(context: Context) {
        val playerPreferences = PlayerPreferences(context)
        val startMaximized = playerPreferences.isStartMaximizedEnabled()
        
        setAspectRatioMode(if (startMaximized) 1 else 0)
    }

    private fun setAspectRatioMode(modeIndex: Int) {
        currentAspectRatio = modeIndex.coerceIn(0, aspectRatioModes.lastIndex)
        currentResizeMode = when (currentAspectRatio) {
            1 -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
            else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
        }
        _playerState.value = _playerState.value.copy(
            aspectRatioMode = aspectRatioModes[currentAspectRatio],
            videoScale = 1f,
            videoOffsetX = 0f,
            videoOffsetY = 0f
        )
    }

    /**
     * Seek backward by the configured interval
     */
    fun seekBackward() {
        val seconds = PlayerPreferences(playerContext ?: return)
            .getSeekBackwardIntervalSeconds()
        seekBy(deltaMs = -(seconds * 1000L))
    }

    /**
     * Seek forward by the configured interval
     */
    fun seekForward() {
        val seconds = PlayerPreferences(playerContext ?: return)
            .getSeekForwardIntervalSeconds()
        seekBy(deltaMs = seconds * 1000L)
    }

    fun captureScreenshot() {
        val context = playerContext ?: return
        val controller = mpvPlayer ?: return
        val pictures = context.getExternalFilesDir(android.os.Environment.DIRECTORY_PICTURES) ?: return
        val dir = java.io.File(pictures, "Vela")
        if (!dir.exists() && !dir.mkdirs()) return
        val file = java.io.File(dir, "Vela_${System.currentTimeMillis()}.jpg")
        controller.screenshotToFile(file.absolutePath)
        android.widget.Toast.makeText(
            context,
            context.getString(com.vela.shared.R.string.player_screenshot_saved),
            android.widget.Toast.LENGTH_SHORT
        ).show()
    }

    fun refreshSubtitleAppearance() {
        mpvPlayer?.applySubtitlePreferences()
        subtitleAppearanceEpoch += 1
    }

    fun addLocalSubtitle(uri: android.net.Uri) {
        val context = playerContext ?: return
        val controller = mpvPlayer
        if (controller == null) {
            android.widget.Toast.makeText(
                context,
                context.getString(com.vela.shared.R.string.player_subtitle_local_mpv_only),
                android.widget.Toast.LENGTH_SHORT
            ).show()
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            val copied = runCatching {
                val extension = uri.lastPathSegment
                    ?.substringAfterLast('.', missingDelimiterValue = "ass")
                    ?.lowercase()
                    ?.takeIf { it in setOf("ass", "ssa", "srt", "vtt", "sub") }
                    ?: "ass"
                val dir = java.io.File(context.cacheDir, "subtitles").apply { mkdirs() }
                val dest = java.io.File(dir, "local-${System.currentTimeMillis()}.$extension")
                context.contentResolver.openInputStream(uri)?.use { input ->
                    dest.outputStream().use { output -> input.copyTo(output) }
                } ?: error("open")
                dest
            }.getOrNull()
            withContext(Dispatchers.Main) {
                if (copied == null) {
                    android.widget.Toast.makeText(
                        context,
                        context.getString(com.vela.shared.R.string.player_subtitle_local_failed),
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                    return@withContext
                }
                controller.selectSubtitleTrack(
                    trackId = "local",
                    externalUrl = copied.absolutePath
                )
            }
        }
    }


    private val playerListener = object : Player.Listener {
        override fun onTracksChanged(tracks: Tracks) {
            applyPendingTrackSelectionsIfNeeded()
            updateTrackInformation()
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            val currentState = _playerState.value
            val wasPlaying = currentState.isPlaying
            val playWhenReady = exoPlayer?.playWhenReady == true
            val isNowPlaying = playbackState == Player.STATE_READY && playWhenReady
            val hasReportedStart = playbackReporter.hasReportedStart()
            val shouldShowLoading = when (playbackState) {
                Player.STATE_IDLE -> !hasReportedStart
                Player.STATE_BUFFERING -> playWhenReady || !hasReportedStart
                Player.STATE_READY -> playWhenReady && !hasRenderedFirstFrame
                else -> false
            }
            
            _playerState.value = currentState.copy(
                isLoading = shouldShowLoading,
                isPlaying = isNowPlaying,
                playWhenReady = playWhenReady,
                hasStartedPlayback = currentState.hasStartedPlayback || hasRenderedFirstFrame,
                duration = getDuration().takeIf { it > 0L } ?: currentState.duration
            )

            if (
                playbackState == Player.STATE_READY && isNowPlaying && !hasReportedStart &&
                (hasRenderedFirstFrame || !currentMediaHasVideo())
            ) {
                playbackReporter.reportPlaybackStatus()
            }

            if (wasPlaying != isNowPlaying) {
                playbackReporter.onPlaybackPauseStateChanged()
            }

            if (playbackState == Player.STATE_READY) {
                hasHandledPlaybackCompletion = false
                applyPendingTrackSelectionsIfNeeded()
                updateTrackInformation()
            }
            updateMpvWatchdog()

            if (playbackState == Player.STATE_ENDED) {
                handlePlaybackCompleted()
            }
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            val currentState = _playerState.value
            val playbackState = exoPlayer?.playbackState ?: Player.STATE_IDLE
            val hasReportedStart = playbackReporter.hasReportedStart()
            val shouldShowLoading = when (playbackState) {
                Player.STATE_IDLE -> !hasReportedStart
                Player.STATE_BUFFERING -> playWhenReady || !hasReportedStart
                Player.STATE_READY -> playWhenReady && !hasRenderedFirstFrame
                else -> false
            }
            _playerState.value = currentState.copy(
                playWhenReady = playWhenReady,
                isPlaying = playWhenReady && playbackState == Player.STATE_READY,
                isLoading = shouldShowLoading
            )
            updateMpvWatchdog()
        }

        override fun onRenderedFirstFrame() {
            cancelMpvWatchdog()
            hasRenderedFirstFrame = true
            _playerState.value = _playerState.value.copy(
                isLoading = false,
                hasStartedPlayback = true,
                duration = getDuration().takeIf { it > 0L } ?: _playerState.value.duration
            )
            if (
                exoPlayer?.playWhenReady == true &&
                exoPlayer?.playbackState == Player.STATE_READY &&
                !playbackReporter.hasReportedStart()
            ) {
                playbackReporter.reportPlaybackStatus()
            }
        }

        override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
            hasRenderedFirstFrame = false
            if (triggerMpvFallback()) {
                return
            }
            _playerState.value = _playerState.value.copy(
                error = error.message ?: "Playback error occurred",
                isLoading = false,
                playWhenReady = false,
                isPlaying = false
            )

            if (playbackReporter.hasReportedStart()) {
                playbackReporter.reportPlaybackStopped(failed = true)
            }
        }

        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int
        ) {
            _playerState.value = _playerState.value.copy(
                currentPosition = newPosition.positionMs,
                duration = getDuration()
            )

            if (reason == Player.DISCONTINUITY_REASON_SEEK) {
                playbackReporter.onPlaybackPositionDiscontinuity()
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        releasePlayer()
        scrubPreviewRequests.close()
    }

    fun getHdrFormatInfo(): String {
        return PlayerMetadata.buildHdrFormatInfo(
            context = playerContext,
            exoPlayer = exoPlayer
        )
    }

    /**
     * Get unified media metadata information for the modern bubble dialog
     */
    fun getMediaMetadataInfo(): MediaMetadataInfo {
        val info = PlayerMetadata.buildMediaMetadataInfo(
            context = playerContext,
            exoPlayer = exoPlayer,
            mediaStreams = apiMediaStreams,
            mediaSourceContainer = playbackSession.mediaSourceContainer,
            mediaSourceBitrateKbps = playbackSession.mediaSourceBitrateKbps,
            playMethodDisplayName = playbackSession.playMethod.displayName
        )
        if (!isMpvPlayback()) return info
        val hwdec = _playerState.value.hardwareDecoding
        val usingHardware = hwdec != PlayerPreferences.MPV_HARDWARE_DECODING_NONE
        return info.copy(
            hardwareAcceleration = info.hardwareAcceleration?.copy(
                isHardwareDecoding = usingHardware,
                decoderType = if (usingHardware) hwdec else "Software"
            ) ?: HardwareAccelerationInfo(
                isHardwareDecoding = usingHardware,
                activeVideoCodec = info.videoFormat?.codec,
                activeAudioCodec = info.audioFormat?.codec,
                decoderType = if (usingHardware) hwdec else "Software",
                asyncModeEnabled = false
            )
        )
    }

    fun getSourceVideoHeight(): Int? {
        return PlayerMetadata.getSourceVideoHeight(apiMediaStreams)
    }

    fun getSourceVideoAspectRatio(): Float? {
        return PlayerMetadata.getSourceVideoAspectRatio(apiMediaStreams)
    }

}

/** 关键帧预览长边上限，单位像素。 */
private const val SCRUB_PREVIEW_WIDTH_PX = 320

/**
 * 进度预览要打开的片源。
 *
 * @param context 用于打开本地地址，只使用 applicationContext
 * @param uri 当前播放地址
 * @param requestHeaders 打开直链时附带的请求头
 * @param cacheKey 主播放器对这条直链使用的缓存键；没有时为 null
 * @param dataSourceFactory 读穿播放器磁盘缓存的数据源工厂；本地文件时为 null
 */
@UnstableApi
internal data class ScrubPreviewSource(
    val context: Context,
    val uri: Uri,
    val requestHeaders: Map<String, String>,
    val cacheKey: String? = null,
    val dataSourceFactory: DataSource.Factory? = null
)
