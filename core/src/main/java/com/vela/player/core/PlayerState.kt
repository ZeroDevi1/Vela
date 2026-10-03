package com.vela.player.core

import com.vela.data.model.AudioTranscodeMode
import com.vela.detail.SpatializationResult

const val MIN_VIDEO_WIDTH_FRACTION = 0.5f
const val MAX_VIDEO_WIDTH_FRACTION = 2.0f
const val DEFAULT_VIDEO_WIDTH_FRACTION = 1.0f

/**
 * Player state data class
 */
data class PlayerState(
    val isLoading: Boolean = false,
    val isPlaying: Boolean = false,
    val playWhenReady: Boolean = false,
    val hasStartedPlayback: Boolean = false,
    val error: String? = null,
    val mediaTitle: String = "",
    val mediaLogoUrl: String? = null,
    val seasonEpisodeLabel: String? = null,
    /** 剧集所属剧名；仅剧集有值，播放器顶栏用它作主标题、[seasonEpisodeLabel] 作副标题。 */
    val seriesName: String? = null,
    val chapterMarkers: List<ChapterMarker> = emptyList(),
    val isVideoTranscodingAllowed: Boolean = false,
    val isAudioTranscodingAllowed: Boolean = false,
    val currentAudioTranscodeMode: AudioTranscodeMode = AudioTranscodeMode.AUTO,
    val currentPosition: Long = 0L,
    val duration: Long = 0L,
    val recapStartMs: Long? = null,
    val recapEndMs: Long? = null,
    val introStartMs: Long? = null,
    val introEndMs: Long? = null,
    val creditsStartMs: Long? = null,
    val creditsEndMs: Long? = null,
    val previewStartMs: Long? = null,
    val previewEndMs: Long? = null,
    val bufferedPercentage: Int = 0,
    val volume: Float = 1.0f,
    val brightness: Float = 0.5f,
    val showControls: Boolean = true,
    val isSeekingGesture: Boolean = false,
    val isVolumeGesture: Boolean = false,
    val isBrightnessGesture: Boolean = false,
    val gestureSeekPosition: Long = 0L,
    val gestureVolume: Float = 1.0f,
    val gestureBrightness: Float = 0.5f,
    // Spatial audio related fields
    val spatializationResult: SpatializationResult? = null,
    val isSpatialAudioEnabled: Boolean = false,
    val spatialAudioFormat: String = "",
    // HDR related fields
    val isHdrEnabled: Boolean = false,
    val hdrFormat: String = "",
    val isLocked: Boolean = false,
    val playbackSpeed: Float = 1f,
    val hardwareDecoding: String = "mediacodec",
    val currentAudioTrack: AudioTrackInfo? = null,
    val availableAudioTracks: List<AudioTrackInfo> = emptyList(),
    val currentSubtitleTrack: SubtitleTrackInfo? = null,
    val availableSubtitleTracks: List<SubtitleTrackInfo> = emptyList(),
    val currentVideoTrack: VideoTrackInfo? = null,
    val availableVideoTracks: List<VideoTrackInfo> = emptyList(),
    // Video scaling for aspect ratio control
    val videoScale: Float = 1f,
    val videoOffsetX: Float = 0f,
    val videoOffsetY: Float = 0f,
    val videoWidthFraction: Float = DEFAULT_VIDEO_WIDTH_FRACTION,
    val aspectRatioMode: String = "Fit",
    val vrDetected: Boolean = false,
    val vrFlatEnabled: Boolean = false,
    val vrProjectionId: String? = null
)

data class ChapterMarker(
    val positionMs: Long,
    val label: String? = null
)

/**
 * Audio track information
 */
data class AudioTrackInfo(
    val id: String,
    val label: String,
    val language: String?,
    val channelCount: Int,
    val codec: String?,
    val playerTrackId: String? = null,
    val streamIndex: Int? = null,
    val requiresPlaybackRestart: Boolean = false,
    val title: String? = null,
    val bitRate: Int? = null,
    val sampleRate: Int? = null,
    val bitDepth: Int? = null,
    val channelLayout: String? = null,
    val isDefault: Boolean = false
)

/**
 * Subtitle track information
 */
data class SubtitleTrackInfo(
    val id: String,
    val label: String,
    val language: String?,
    val isForced: Boolean = false,
    val isDefault: Boolean = false,
    val playerTrackId: String? = null,
    val streamIndex: Int? = null,
    val requiresPlaybackRestart: Boolean = false,
    val title: String? = null,
    val codec: String? = null,
    val isExternal: Boolean = false
)

/**
 * Video track information
 */
data class VideoTrackInfo(
    val id: String,
    val label: String,
    val width: Int,
    val height: Int,
    val codec: String?
)
