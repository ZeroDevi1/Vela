package com.vela.app.ui.screens.player

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.vela.shared.R
import java.util.Locale

data class MediaMetadataInfo(
    val hdrFormat: HdrFormatInfo? = null,
    val videoFormat: VideoFormatInfo? = null,
    val audioFormat: AudioFormatInfo? = null,
    val hardwareAcceleration: HardwareAccelerationInfo? = null,
    val streamContainer: String? = null,
    val streamBitrateKbps: Int? = null,
    val playMethod: String = "Direct Play"
)

data class HdrFormatInfo(
    val isSupported: Boolean,
    val currentFormat: String? = null,
    val deviceCapabilities: String,
    val analysisResult: String? = null
)

data class VideoFormatInfo(
    val codec: String,
    val resolution: String,
    val mimeType: String,
    val colorInfo: String? = null,
    val profile: String? = null,
    val frameRate: Float? = null,
    val bitrateKbps: Int? = null,
    val bitDepth: Int? = null
)

data class AudioFormatInfo(
    val codec: String,
    val channels: String,
    val bitrate: String? = null,
    val sampleRate: String? = null,
    val language: String? = null,
    val isDefault: Boolean = false
)

data class HardwareAccelerationInfo(
    val isHardwareDecoding: Boolean,
    val activeVideoCodec: String? = null,
    val activeAudioCodec: String? = null,
    val decoderType: String,
    val asyncModeEnabled: Boolean,
    val performanceMetrics: String? = null
)

/**
 * 媒体信息浮层。不拦截触摸、不抢焦点，视频照常播放；内容超出时在面板内滚动。
 * 竖屏居中铺宽，横屏靠左，避开右侧倍速控件。
 */
@Composable
fun MediaInfoDialog(
    mediaInfo: MediaMetadataInfo,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val configuration = LocalConfiguration.current
    val density = LocalDensity.current
    val screenWidth = configuration.screenWidthDp.dp
    val screenHeight = configuration.screenHeightDp.dp
    val landscape = screenWidth > screenHeight
    val panelWidth = if (landscape) {
        (screenWidth * 0.42f).coerceIn(320.dp, 440.dp)
    } else {
        (screenWidth - 32.dp).coerceAtMost(480.dp)
    }
    val popupOffset = with(density) { IntOffset(if (landscape) 32.dp.roundToPx() else 0, 0) }

    Popup(
        alignment = if (landscape) Alignment.CenterStart else Alignment.Center,
        offset = popupOffset,
        onDismissRequest = onDismiss,
        properties = PopupProperties(
            focusable = false,
            dismissOnBackPress = false,
            dismissOnClickOutside = false,
            clippingEnabled = false
        )
    ) {
        Surface(
            modifier = modifier
                .width(panelWidth)
                .heightIn(max = screenHeight * 0.6f),
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.94f),
            contentColor = MaterialTheme.colorScheme.onSurface,
            tonalElevation = 3.dp
        ) {
            Column {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 20.dp, end = 8.dp, top = 8.dp)
                ) {
                    Text(
                        text = stringResource(R.string.player_media_info),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.cancel))
                    }
                }
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(start = 20.dp, end = 20.dp, bottom = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    InfoSection(stringResource(R.string.player_info_stream)) {
                        InfoRow(stringResource(R.string.player_info_container), buildStreamLine(mediaInfo))
                        InfoRow(stringResource(R.string.player_info_play_method), mediaInfo.playMethod)
                    }
                    InfoSection(stringResource(R.string.player_info_video)) {
                        InfoRow(stringResource(R.string.player_info_format), buildVideoTitle(mediaInfo.videoFormat, mediaInfo.hdrFormat))
                        buildVideoDetails(mediaInfo.videoFormat)?.let {
                            InfoRow(stringResource(R.string.player_info_details), it)
                        }
                        mediaInfo.hardwareAcceleration?.let {
                            InfoRow(
                                stringResource(R.string.player_info_renderer),
                                when {
                                    !it.isHardwareDecoding -> "Software"
                                    it.decoderType.contains("copy", ignoreCase = true) -> "MediaCodec (copy)"
                                    else -> "MediaCodec"
                                }
                            )
                        }
                        buildDisplayMode(mediaInfo.videoFormat)?.let {
                            InfoRow(stringResource(R.string.player_info_display_mode), it)
                        }
                        mediaInfo.hdrFormat?.deviceCapabilities?.takeIf { it.isNotBlank() }?.let {
                            InfoRow(stringResource(R.string.player_info_display_hdr), it)
                        }
                    }
                    InfoSection(stringResource(R.string.player_info_audio)) {
                        InfoRow(stringResource(R.string.player_info_track), buildAudioTitle(mediaInfo.audioFormat))
                        mediaInfo.audioFormat?.sampleRate?.let {
                            InfoRow(stringResource(R.string.player_info_sample_rate), it)
                        }
                        mediaInfo.audioFormat?.bitrate?.let {
                            InfoRow(stringResource(R.string.player_info_bitrate), it)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun InfoSection(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary
        )
        content()
    }
}

/** 左侧标签、右侧数值；数值过长时在右栏内换行，标签不被挤压。 */
@Composable
private fun InfoRow(label: String, value: String) {
    if (value.isBlank()) return
    Row(
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.widthIn(min = 72.dp, max = 120.dp)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(1f)
        )
    }
}

private fun buildStreamLine(mediaInfo: MediaMetadataInfo): String {
    val container = mediaInfo.streamContainer?.uppercase(Locale.US)
        ?: mediaInfo.videoFormat?.mimeType?.substringAfter("/", "")?.uppercase(Locale.US)
        ?: "STREAM"

    val bitrateKbps = mediaInfo.streamBitrateKbps ?: mediaInfo.videoFormat?.bitrateKbps
    return if (bitrateKbps != null && bitrateKbps > 0) {
        "$container (${formatMbps(bitrateKbps)} mbps)"
    } else {
        container
    }
}

private fun buildVideoTitle(videoInfo: VideoFormatInfo?, hdrInfo: HdrFormatInfo?): String {
    if (videoInfo == null) return "Unknown"

    val resolutionTag = when {
        videoInfo.resolution.startsWith("3840x", true) || videoInfo.resolution.startsWith("4096x", true) -> "4K"
        videoInfo.resolution.startsWith("2560x", true) -> "1440p"
        videoInfo.resolution.startsWith("1920x", true) -> "1080p"
        else -> videoInfo.resolution
    }

    val hdrTag = hdrInfo?.currentFormat?.takeIf { !it.isNullOrBlank() }
        ?: if (hdrInfo?.isSupported == true) "HDR" else null

    val codecTag = mapCodecForDisplay(videoInfo.codec)

    return listOfNotNull(resolutionTag, hdrTag, codecTag)
        .joinToString(" ")
        .trim()
}

private fun buildVideoDetails(videoInfo: VideoFormatInfo?): String? {
    if (videoInfo == null) return null

    val parts = mutableListOf<String>()
    videoInfo.profile?.takeIf { it.isNotBlank() }?.let { parts.add(it) }
    videoInfo.bitDepth?.let { parts.add("${it}bit") }
    videoInfo.bitrateKbps?.takeIf { it > 0 }?.let { parts.add("${formatMbps(it)} mbps") }
    videoInfo.frameRate?.takeIf { it > 0f }?.let { parts.add(String.format(Locale.US, "%.3f fps", it)) }

    return parts.joinToString(" ").takeIf { it.isNotBlank() }
}

private fun buildDisplayMode(videoInfo: VideoFormatInfo?): String? {
    if (videoInfo == null) return null

    val width = videoInfo.resolution.substringBefore("x", "").trim().takeIf { it.isNotBlank() }
    val fps = videoInfo.frameRate?.takeIf { it > 0f }?.let { String.format(Locale.US, "%.2f", it) }

    return when {
        width != null && fps != null -> "$width/$fps"
        videoInfo.resolution.isNotBlank() && fps != null -> "${videoInfo.resolution}/$fps"
        else -> null
    }
}

private fun buildAudioTitle(audioInfo: AudioFormatInfo?): String {
    if (audioInfo == null) return "Unknown"

    val lang = audioInfo.language?.takeIf { it.isNotBlank() }?.replaceFirstChar {
        if (it.isLowerCase()) it.titlecase(Locale.US) else it.toString()
    }
    val defaultTag = if (audioInfo.isDefault) " (Default)" else ""

    return listOfNotNull(lang, audioInfo.codec, audioInfo.channels)
        .joinToString(" ")
        .trim() + defaultTag
}

private fun mapCodecForDisplay(codec: String): String {
    return when (codec.uppercase(Locale.US)) {
        "H.265", "H265", "HEVC" -> "HEVC"
        "H.264", "H264", "AVC" -> "AVC"
        else -> codec.uppercase(Locale.US)
    }
}

private fun formatMbps(kbps: Int): String {
    val mbps = kbps / 1000f
    return if (mbps >= 10f) String.format(Locale.US, "%.0f", mbps) else String.format(Locale.US, "%.1f", mbps)
}

