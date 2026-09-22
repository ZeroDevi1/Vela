package com.vela.app.ui.screens.player

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.rounded.ClosedCaption
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.ViewInAr
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.vela.shared.R
import com.vela.data.model.AudioTranscodeMode
import com.vela.player.core.AudioTrackInfo
import com.vela.player.core.SubtitleTrackInfo
import com.vela.player.core.TrackDetails
import com.vela.player.preferences.PlayerPreferences
import com.vela.app.player.vr.VrLayout
import com.vela.app.player.vr.VrLayoutParser
import com.vela.app.player.vr.VrProjection
import com.vela.app.player.vr.VrStereo

@Composable
fun AudioTrackSelectionDialog(
    isVisible: Boolean,
    audioTracks: List<AudioTrackInfo>,
    currentAudioTrack: AudioTrackInfo?,
    onTrackSelected: (String) -> Unit,
    onDismiss: () -> Unit
) {
    if (!isVisible) return

    val muteLabel = stringResource(R.string.player_audio_mute)
    TrackSelectionDialog(
        title = stringResource(R.string.player_dialog_audio_title),
        helperText = stringResource(R.string.player_dialog_audio_summary),
        itemCountLabel = stringResource(R.string.player_dialog_tracks_count),
        icon = Icons.Rounded.GraphicEq,
        accentColor = Color(0xFF00A9D6),
        tracks = audioTracks,
        currentTrack = currentAudioTrack,
        onTrackSelected = onTrackSelected,
        onDismiss = onDismiss,
        trackKey = { track -> track.id },
        isTrackSelected = { track, selected -> track.id == selected?.id },
        trackDisplayInfo = { track ->
            if (TrackDetails.isMutedAudio(track)) {
                TrackDisplayInfo(
                    title = muteLabel,
                    subtitle = "",
                    description = ""
                )
            } else {
                val lines = TrackDetails.audioDialogLines(track)
                TrackDisplayInfo(
                    title = lines.first,
                    subtitle = lines.second,
                    description = lines.third
                )
            }
        }
    )
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

    TrackSelectionDialog(
        title = stringResource(R.string.player_dialog_subtitles_title),
        helperText = stringResource(R.string.player_dialog_subtitles_summary),
        itemCountLabel = stringResource(R.string.player_dialog_tracks_count),
        icon = Icons.Rounded.ClosedCaption,
        accentColor = Color(0xFFFF6B3B),
        tracks = subtitleTracks,
        currentTrack = currentSubtitleTrack,
        onTrackSelected = onTrackSelected,
        onDismiss = onDismiss,
        trackKey = { track -> track.id },
        isTrackSelected = { track, selected -> track.id == selected?.id },
                        trackDisplayInfo = { track ->
            val lines = TrackDetails.subtitleDialogLines(track)
            TrackDisplayInfo(
                title = lines.first,
                subtitle = lines.second,
                description = lines.third
            )
        }
    )
}

@Composable
fun VrProjectionSelectionDialog(
    isVisible: Boolean,
    currentProjectionId: String?,
    onProjectionSelected: (String) -> Unit,
    onDismiss: () -> Unit
) {
    if (!isVisible) return

    val options = VrLayoutParser.manualOptions().map { layout ->
        VrProjectionOption(
            id = layout.id,
            label = vrLayoutLabel(layout)
        )
    }
    val selected = options.firstOrNull { it.id == currentProjectionId } ?: options.first()

    TrackSelectionDialog(
        title = stringResource(R.string.player_vr_projection_title),
        helperText = stringResource(R.string.player_vr_projection_summary),
        itemCountLabel = stringResource(R.string.player_vr_projection_count),
        icon = Icons.Rounded.ViewInAr,
        accentColor = Color(0xFF8B5CF6),
        tracks = options,
        currentTrack = selected,
        onTrackSelected = onProjectionSelected,
        onDismiss = onDismiss,
        trackKey = { option -> option.id },
        isTrackSelected = { option, current -> option.id == current?.id },
        trackDisplayInfo = { option ->
            TrackDisplayInfo(
                title = option.label,
                subtitle = "",
                description = ""
            )
        }
    )
}

@Composable
private fun vrLayoutLabel(layout: VrLayout): String {
    return when (layout.projection) {
        VrProjection.HalfEquirect -> when (layout.stereo) {
            VrStereo.SideBySide -> stringResource(R.string.player_vr_layout_180_sbs)
            VrStereo.TopBottom -> stringResource(R.string.player_vr_layout_180_tb)
            VrStereo.Mono -> stringResource(R.string.player_vr_layout_180_mono)
        }
        VrProjection.Equirect -> when (layout.stereo) {
            VrStereo.SideBySide -> stringResource(R.string.player_vr_layout_360_sbs)
            VrStereo.TopBottom -> stringResource(R.string.player_vr_layout_360_tb)
            VrStereo.Mono -> stringResource(R.string.player_vr_layout_360_mono)
        }
        VrProjection.Fisheye -> when (layout.inputFov) {
            200 -> stringResource(R.string.player_vr_layout_fisheye_200_sbs)
            220 -> stringResource(R.string.player_vr_layout_fisheye_220_sbs)
            else -> stringResource(R.string.player_vr_layout_fisheye_190_sbs)
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

    val options = qualityOptions.map { quality ->
        StreamingQualityOption(
            id = quality,
            label = quality,
            description = if (quality.equals(PlayerPreferences.STREAMING_QUALITY_ORIGINAL, ignoreCase = true)) {
                stringResource(R.string.player_dialog_streaming_quality_original_summary)
            } else {
                ""
            }
        )
    }
    val selectedOption = options.firstOrNull { it.id == currentQuality }

    TrackSelectionDialog(
        title = stringResource(R.string.player_dialog_streaming_quality_title),
        helperText = stringResource(R.string.player_dialog_streaming_quality_summary),
        itemCountLabel = stringResource(R.string.player_dialog_streaming_quality_count),
        icon = Icons.Rounded.Tune,
        accentColor = Color(0xFF3B82F6),
        tracks = options,
        currentTrack = selectedOption,
        onTrackSelected = onQualitySelected,
        onDismiss = onDismiss,
        trackKey = { option -> option.id },
        isTrackSelected = { option, selected -> option.id == selected?.id },
        trackDisplayInfo = { option ->
            TrackDisplayInfo(
                title = option.label,
                subtitle = option.description,
                description = ""
            )
        }
    )
}

@Composable
fun AudioTranscodingModeDialog(
    isVisible: Boolean,
    currentMode: AudioTranscodeMode,
    onModeSelected: (AudioTranscodeMode) -> Unit,
    onDismiss: () -> Unit
) {
    if (!isVisible) return

    val options = AudioTranscodeMode.entries.map { mode ->
        AudioTranscodingModeOption(
            id = mode.preferenceValue,
            mode = mode,
            label = mode.displayName,
            description = when (mode) {
                AudioTranscodeMode.AUTO -> stringResource(R.string.player_dialog_audio_mode_auto_summary)
                AudioTranscodeMode.STEREO -> stringResource(R.string.player_dialog_audio_mode_stereo_summary)
                AudioTranscodeMode.SURROUND_5_1 -> stringResource(R.string.player_dialog_audio_mode_surround_summary)
                AudioTranscodeMode.PASSTHROUGH -> stringResource(R.string.player_dialog_audio_mode_passthrough_summary)
            },
            channelSummary = when (mode.maxAudioChannels) {
                "2" -> stringResource(R.string.player_dialog_audio_mode_channels_2)
                "6" -> stringResource(R.string.player_dialog_audio_mode_channels_6)
                "8" -> stringResource(R.string.player_dialog_audio_mode_channels_8)
                else -> ""
            }
        )
    }
    val selectedOption = options.firstOrNull { it.mode == currentMode }

    TrackSelectionDialog(
        title = stringResource(R.string.player_dialog_audio_transcoding_title),
        helperText = stringResource(R.string.player_dialog_audio_transcoding_summary),
        itemCountLabel = stringResource(R.string.player_dialog_audio_transcoding_count),
        icon = Icons.Rounded.GraphicEq,
        accentColor = Color(0xFF0EA5E9),
        tracks = options,
        currentTrack = selectedOption,
        onTrackSelected = { selectedId ->
            options.firstOrNull { it.id == selectedId }?.mode?.let(onModeSelected)
        },
        onDismiss = onDismiss,
        trackKey = { option -> option.id },
        isTrackSelected = { option, selected -> option.id == selected?.id },
        trackDisplayInfo = { option ->
            TrackDisplayInfo(
                title = option.label,
                subtitle = option.description,
                description = option.channelSummary
            )
        }
    )
}

@Composable
private fun <T> TrackSelectionDialog(
    title: String,
    helperText: String,
    itemCountLabel: String,
    icon: ImageVector,
    accentColor: Color,
    tracks: List<T>,
    currentTrack: T?,
    onTrackSelected: (String) -> Unit,
    onDismiss: () -> Unit,
    trackKey: (T) -> String,
    isTrackSelected: (T, T?) -> Boolean,
    trackDisplayInfo: (T) -> TrackDisplayInfo
) where T : Any {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
        HideSystemBarsForDialogWindow()

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.64f))
                .clickable(onClick = onDismiss),
            contentAlignment = Alignment.Center
        ) {
            BoxWithConstraints {
                val isLandscape = maxWidth > maxHeight
                val dialogWidthFraction = if (isLandscape) 0.68f else 0.84f
                val dialogMaxWidth: Dp = if (isLandscape) 380.dp else 460.dp
                val listMaxHeight: Dp = if (isLandscape) 220.dp else 300.dp

                Surface(
                    modifier = Modifier
                        .fillMaxWidth(dialogWidthFraction)
                        .widthIn(max = dialogMaxWidth)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = {}
                        ),
                    shape = RoundedCornerShape(24.dp),
                    tonalElevation = 12.dp,
                    shadowElevation = 22.dp,
                    color = MaterialTheme.colorScheme.surface,
                    border = androidx.compose.foundation.BorderStroke(
                        width = 1.dp,
                        color = accentColor.copy(alpha = 0.25f)
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        DialogHeader(
                            title = title,
                            helperText = helperText,
                            itemCountLabel = itemCountLabel,
                            icon = icon,
                            accentColor = accentColor,
                            trackCount = tracks.size,
                            onClose = onDismiss
                        )

                        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.22f))

                        if (tracks.isEmpty()) {
                            EmptyState()
                        } else {
                            LazyColumn(
                                modifier = Modifier.heightIn(max = listMaxHeight),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                itemsIndexed(
                                    items = tracks,
                                    key = { _, track ->
                                        trackKey(track)
                                    }
                                ) { index, track ->
                                    val displayInfo = trackDisplayInfo(track)
                                    val isSelected = isTrackSelected(track, currentTrack)
                                    val trackId = trackKey(track)

                                    TrackRow(
                                        indexLabel = (index + 1).toString(),
                                        title = displayInfo.title,
                                        subtitle = displayInfo.subtitle,
                                        description = displayInfo.description,
                                        isSelected = isSelected,
                                        accentColor = accentColor,
                                        onSelected = { onTrackSelected(trackId) }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HideSystemBarsForDialogWindow() {
    val view = LocalView.current
    DisposableEffect(view) {
        val window = (view.parent as? DialogWindowProvider)?.window
        window?.let { dialogWindow ->
            val controller = WindowCompat.getInsetsController(dialogWindow, dialogWindow.decorView)
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        onDispose { }
    }
}

@Composable
private fun DialogHeader(
    title: String,
    helperText: String,
    itemCountLabel: String,
    icon: ImageVector,
    accentColor: Color,
    trackCount: Int,
    onClose: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .background(
                        color = accentColor.copy(alpha = 0.12f),
                        shape = RoundedCornerShape(12.dp)
                    )
                    .border(
                        width = 1.dp,
                        color = accentColor.copy(alpha = 0.34f),
                        shape = RoundedCornerShape(12.dp)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = accentColor,
                    modifier = Modifier.size(18.dp)
                )
            }

            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                val helperLine = if (helperText.isBlank()) {
                    "$trackCount $itemCountLabel"
                } else {
                    "$trackCount $itemCountLabel - $helperText"
                }
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = helperLine,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        IconButton(onClick = onClose) {
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = "Close",
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun TrackRow(
    indexLabel: String,
    title: String,
    subtitle: String,
    description: String,
    isSelected: Boolean,
    accentColor: Color,
    onSelected: () -> Unit
) {
    val containerColor = if (isSelected) {
        accentColor.copy(alpha = 0.11f)
    } else {
        MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.46f)
    }

    val borderColor = if (isSelected) {
        accentColor.copy(alpha = 0.72f)
    } else {
        MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onSelected),
        shape = RoundedCornerShape(14.dp),
        color = containerColor,
        border = androidx.compose.foundation.BorderStroke(1.dp, borderColor)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.surface,
                border = androidx.compose.foundation.BorderStroke(
                    1.dp,
                    MaterialTheme.colorScheme.outline.copy(alpha = 0.24f)
                )
            ) {
                Text(
                    text = indexLabel,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp)
                )
            }

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                if (title.isNotEmpty()) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                if (subtitle.isNotEmpty()) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                if (description.isNotEmpty()) {
                    Text(
                        text = description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.88f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            SelectionIndicator(
                isSelected = isSelected,
                accentColor = accentColor
            )
        }
    }
}

@Composable
private fun SelectionIndicator(
    isSelected: Boolean,
    accentColor: Color
) {
    Box(
        modifier = Modifier
            .size(22.dp)
            .background(
                color = if (isSelected) accentColor else Color.Transparent,
                shape = CircleShape
            )
            .border(
                width = 1.5.dp,
                color = if (isSelected) accentColor else MaterialTheme.colorScheme.outline,
                shape = CircleShape
            ),
        contentAlignment = Alignment.Center
    ) {
        if (isSelected) {
            Icon(
                imageVector = Icons.Default.Check,
                contentDescription = "Selected",
                tint = Color.White,
                modifier = Modifier.size(13.dp)
            )
        }
    }
}

@Composable
private fun EmptyState() {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outline.copy(alpha = 0.22f)
        )
    ) {
        Text(
            text = stringResource(R.string.player_dialog_no_tracks_available),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 16.dp)
        )
    }
}

private data class TrackDisplayInfo(
    val title: String,
    val subtitle: String,
    val description: String = ""
)

private data class VrProjectionOption(
    val id: String,
    val label: String
)

private data class StreamingQualityOption(
    val id: String,
    val label: String,
    val description: String
)

private data class AudioTranscodingModeOption(
    val id: String,
    val mode: AudioTranscodeMode,
    val label: String,
    val description: String,
    val channelSummary: String
)

