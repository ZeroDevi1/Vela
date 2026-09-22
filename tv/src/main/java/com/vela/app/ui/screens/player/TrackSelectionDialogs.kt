package com.vela.app.ui.screens.player

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.rounded.ClosedCaption
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vela.shared.R
import com.vela.data.model.AudioTranscodeMode
import com.vela.player.core.AudioTrackInfo
import com.vela.player.core.SubtitleTrackInfo
import com.vela.player.core.TrackDetails
import com.vela.player.preferences.PlayerPreferences

@Composable
fun AudioTrackSelectionDialog(
    isVisible: Boolean,
    audioTracks: List<AudioTrackInfo>,
    currentAudioTrack: AudioTrackInfo?,
    onTrackSelected: (String) -> Unit,
    onDismiss: () -> Unit
) {
    if (!isVisible) return

    TvSidePanel(
        title = stringResource(R.string.player_dialog_audio_title),
        icon = Icons.Rounded.GraphicEq,
        accentColor = Color(0xFF00A9D6),
        trackCount = audioTracks.size,
        onDismiss = onDismiss
    ) {
        itemsIndexed(audioTracks, key = { _, t -> t.id }) { index, track ->
            val isSelected = track.id == currentAudioTrack?.id
            val lines = if (TrackDetails.isMutedAudio(track)) {
                Triple(stringResource(R.string.player_audio_mute), "", "")
            } else {
                TrackDetails.audioDialogLines(track)
            }
            TvTrackItem(
                title = lines.first.ifBlank { "Track ${index + 1}" },
                subtitle = lines.second,
                description = lines.third,
                isSelected = isSelected,
                accentColor = Color(0xFF00A9D6),
                requestFocus = isSelected || (currentAudioTrack == null && index == 0),
                onSelect = { onTrackSelected(track.id) }
            )
        }
    }
}

@Composable
fun SubtitleTrackSelectionDialog(
    isVisible: Boolean,
    subtitleTracks: List<SubtitleTrackInfo>,
    currentSubtitleTrack: SubtitleTrackInfo?,
    onTrackSelected: (String) -> Unit,
    onDismiss: () -> Unit
) {
    if (!isVisible) return

    TvSidePanel(
        title = stringResource(R.string.player_dialog_subtitles_title),
        icon = Icons.Rounded.ClosedCaption,
        accentColor = Color(0xFFFF6B3B),
        trackCount = subtitleTracks.size,
        onDismiss = onDismiss
    ) {
        itemsIndexed(subtitleTracks, key = { _, t -> t.id }) { index, track ->
            val isSelected = track.id == currentSubtitleTrack?.id
            val lines = TrackDetails.subtitleDialogLines(track)
            TvTrackItem(
                title = lines.first.ifBlank { "Track ${index + 1}" },
                subtitle = lines.second,
                description = lines.third,
                isSelected = isSelected,
                accentColor = Color(0xFFFF6B3B),
                requestFocus = isSelected || (currentSubtitleTrack == null && index == 0),
                onSelect = { onTrackSelected(track.id) }
            )
        }
    }
}

@Composable
fun StreamingQualitySelectionDialog(
    isVisible: Boolean,
    qualityOptions: List<String>,
    currentQuality: String,
    onQualitySelected: (String) -> Unit,
    onDismiss: () -> Unit
) {
    if (!isVisible) return

    TvSidePanel(
        title = stringResource(R.string.player_dialog_streaming_quality_title),
        icon = Icons.Rounded.Tune,
        accentColor = Color(0xFF3B82F6),
        trackCount = qualityOptions.size,
        onDismiss = onDismiss
    ) {
        itemsIndexed(qualityOptions) { index, quality ->
            val isSelected = quality == currentQuality
            TvTrackItem(
                title = quality,
                subtitle = if (quality.equals(PlayerPreferences.STREAMING_QUALITY_ORIGINAL, ignoreCase = true)) {
                    stringResource(R.string.player_dialog_streaming_quality_original_summary)
                } else "",
                description = "",
                isSelected = isSelected,
                accentColor = Color(0xFF3B82F6),
                requestFocus = isSelected || index == 0,
                onSelect = { onQualitySelected(quality) }
            )
        }
    }
}

@Composable
fun AudioTranscodingModeDialog(
    isVisible: Boolean,
    currentMode: AudioTranscodeMode,
    onModeSelected: (AudioTranscodeMode) -> Unit,
    onDismiss: () -> Unit
) {
    if (!isVisible) return

    val modes = AudioTranscodeMode.entries

    TvSidePanel(
        title = stringResource(R.string.player_dialog_audio_transcoding_title),
        icon = Icons.Rounded.GraphicEq,
        accentColor = Color(0xFF0EA5E9),
        trackCount = modes.size,
        onDismiss = onDismiss
    ) {
        itemsIndexed(modes.toList()) { _, mode ->
            val isSelected = mode == currentMode
            TvTrackItem(
                title = mode.displayName,
                subtitle = when (mode) {
                    AudioTranscodeMode.AUTO -> stringResource(R.string.player_dialog_audio_mode_auto_summary)
                    AudioTranscodeMode.STEREO -> stringResource(R.string.player_dialog_audio_mode_stereo_summary)
                    AudioTranscodeMode.SURROUND_5_1 -> stringResource(R.string.player_dialog_audio_mode_surround_summary)
                    AudioTranscodeMode.PASSTHROUGH -> stringResource(R.string.player_dialog_audio_mode_passthrough_summary)
                },
                description = when (mode.maxAudioChannels) {
                    "2" -> stringResource(R.string.player_dialog_audio_mode_channels_2)
                    "6" -> stringResource(R.string.player_dialog_audio_mode_channels_6)
                    "8" -> stringResource(R.string.player_dialog_audio_mode_channels_8)
                    else -> ""
                },
                isSelected = isSelected,
                accentColor = Color(0xFF0EA5E9),
                requestFocus = isSelected,
                onSelect = { onModeSelected(mode) }
            )
        }
    }
}

@Composable
private fun TvSidePanel(
    title: String,
    icon: ImageVector,
    accentColor: Color,
    trackCount: Int,
    onDismiss: () -> Unit,
    content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.7f))
    ) {
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .width(320.dp)
                .align(Alignment.CenterEnd)
                .background(
                    Color.Black,
                    RoundedCornerShape(topStart = 14.dp, bottomStart = 14.dp)
                )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(vertical = 20.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 18.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(30.dp)
                            .background(accentColor.copy(alpha = 0.15f), RoundedCornerShape(8.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = icon,
                            contentDescription = null,
                            tint = accentColor,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                    Column {
                        Text(
                            text = title,
                            color = Color.White,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = "$trackCount available",
                            color = Color.White.copy(alpha = 0.5f),
                            fontSize = 11.sp
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(Color.White.copy(alpha = 0.08f))
                )

                Spacer(modifier = Modifier.height(10.dp))

                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(horizontal = 10.dp)
                        .onPreviewKeyEvent { event ->
                            if (event.type == KeyEventType.KeyDown && event.key == Key.Back) {
                                onDismiss()
                                true
                            } else false
                        },
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    content = content
                )
            }
        }
    }
}

@Composable
private fun TvTrackItem(
    title: String,
    subtitle: String,
    description: String,
    isSelected: Boolean,
    accentColor: Color,
    requestFocus: Boolean,
    onSelect: () -> Unit
) {
    var isFocused by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }

    if (requestFocus) {
        LaunchedEffect(Unit) {
            focusRequester.requestFocus()
        }
    }

    val bgColor = when {
        isFocused -> Color.White.copy(alpha = 0.12f)
        isSelected -> accentColor.copy(alpha = 0.08f)
        else -> Color.Transparent
    }
    val borderColor = when {
        isFocused -> Color.White.copy(alpha = 0.6f)
        isSelected -> accentColor.copy(alpha = 0.5f)
        else -> Color.White.copy(alpha = 0.06f)
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .focusRequester(focusRequester)
            .onFocusChanged { isFocused = it.isFocused }
            .onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown) {
                    when (event.key) {
                        Key.Enter, Key.NumPadEnter, Key.DirectionCenter -> {
                            onSelect()
                            true
                        }
                        else -> false
                    }
                } else false
            }
            .focusable()
            .background(bgColor, RoundedCornerShape(10.dp))
            .border(1.dp, borderColor, RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = Color.White,
                fontSize = 13.sp,
                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (subtitle.isNotEmpty()) {
                Text(
                    text = subtitle,
                    color = Color.White.copy(alpha = 0.6f),
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (description.isNotEmpty()) {
                Text(
                    text = description,
                    color = Color.White.copy(alpha = 0.4f),
                    fontSize = 10.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        if (isSelected) {
            Box(
                modifier = Modifier
                    .size(18.dp)
                    .background(accentColor, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(12.dp)
                )
            }
        }
    }
}

