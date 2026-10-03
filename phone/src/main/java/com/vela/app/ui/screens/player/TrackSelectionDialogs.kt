package com.vela.app.ui.screens.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.FormatSize
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.vela.shared.R
import com.vela.shared.ui.theme.velaMotion
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
    onDismiss: () -> Unit,
    onAddLocalSubtitle: (() -> Unit)? = null,
    onShowSubtitleStyle: (() -> Unit)? = null,
    onShowSubtitleDelay: (() -> Unit)? = null
) {
    if (!isVisible) return

    // 字幕工具原先只藏在字幕按钮的长按菜单里，这里在面板顶部直接给出入口。
    val actions = listOfNotNull(
        onAddLocalSubtitle?.let {
            PanelAction(stringResource(R.string.player_subtitle_add_local), Icons.Rounded.Add, it)
        },
        onShowSubtitleStyle?.let {
            PanelAction(stringResource(R.string.player_subtitle_scale_position), Icons.Rounded.FormatSize, it)
        },
        onShowSubtitleDelay?.let {
            PanelAction(stringResource(R.string.player_subtitle_time_offset), Icons.Rounded.Schedule, it)
        }
    )
    TrackSelectionDialog(
        actions = actions,
        title = stringResource(R.string.player_dialog_subtitles_title),
        helperText = stringResource(R.string.player_dialog_subtitles_summary),
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

/**
 * 播放器内所有“单选列表”面板的统一外观：横屏从右侧滑入、竖屏从底部升起，视频保持可见。
 *
 * 选中态只用 secondaryContainer 底色 + 勾选图标表达，不再按类型区分强调色或显示序号。
 * [actions] 用于在列表上方放置与当前类型相关的快捷操作（如字幕样式），可为空。
 */
@Composable
private fun <T> TrackSelectionDialog(
    title: String,
    helperText: String,
    tracks: List<T>,
    currentTrack: T?,
    onTrackSelected: (String) -> Unit,
    onDismiss: () -> Unit,
    trackKey: (T) -> String,
    isTrackSelected: (T, T?) -> Boolean,
    trackDisplayInfo: (T) -> TrackDisplayInfo,
    actions: List<PanelAction> = emptyList()
) where T : Any {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
        HideSystemBarsForDialogWindow()

        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.48f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismiss
                )
        ) {
            val isLandscape = maxWidth > maxHeight
            val motion = MaterialTheme.velaMotion
            val visibleState = remember { MutableTransitionState(false) }.apply { targetState = true }
            val panelModifier = if (isLandscape) {
                Modifier
                    .align(Alignment.CenterEnd)
                    .fillMaxHeight()
                    .width(PanelWidth)
                    .padding(PanelInset)
            } else {
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .heightIn(max = maxHeight * 0.62f)
            }
            val panelShape = if (isLandscape) {
                RoundedCornerShape(28.dp)
            } else {
                RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
            }

            AnimatedVisibility(
                visibleState = visibleState,
                modifier = panelModifier,
                enter = fadeIn(motion.defaultEffectsSpec()) + if (isLandscape) {
                    slideInHorizontally(motion.defaultSpatialSpec()) { it / 3 }
                } else {
                    slideInVertically(motion.defaultSpatialSpec()) { it / 3 }
                }
            ) {
                Surface(
                    shape = panelShape,
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    modifier = Modifier
                        // 吞掉面板内部点击，避免穿透到背景触发关闭。
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = {}
                        )
                ) {
                    Column(
                        modifier = if (isLandscape) {
                            Modifier.fillMaxHeight()
                        } else {
                            Modifier.navigationBarsPadding()
                        }
                    ) {
                        PanelHeader(
                            title = title,
                            helperText = helperText,
                            onClose = onDismiss
                        )
                        if (actions.isNotEmpty()) {
                            PanelActions(actions = actions)
                        }
                        if (tracks.isEmpty()) {
                            EmptyState()
                        } else {
                            LazyColumn(
                                contentPadding = PaddingValues(
                                    start = 12.dp,
                                    end = 12.dp,
                                    bottom = 16.dp
                                ),
                                verticalArrangement = Arrangement.spacedBy(2.dp)
                            ) {
                                items(
                                    items = tracks,
                                    key = { track -> trackKey(track) }
                                ) { track ->
                                    val displayInfo = trackDisplayInfo(track)
                                    val trackId = trackKey(track)
                                    TrackRow(
                                        title = displayInfo.title,
                                        subtitle = displayInfo.subtitle,
                                        description = displayInfo.description,
                                        isSelected = isTrackSelected(track, currentTrack),
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

private val PanelWidth = 380.dp
private val PanelInset = 12.dp

/** 面板顶部的快捷操作，例如字幕面板里的“添加本地字幕”。 */
internal data class PanelAction(
    val label: String,
    val icon: ImageVector,
    val onClick: () -> Unit
)

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
private fun PanelHeader(
    title: String,
    helperText: String,
    onClose: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 24.dp, end = 12.dp, top = 16.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            if (helperText.isNotBlank()) {
                Text(
                    text = helperText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        IconButton(onClick = onClose) {
            Icon(
                imageVector = Icons.Rounded.Close,
                contentDescription = stringResource(R.string.settings_close),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun PanelActions(actions: List<PanelAction>) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.padding(bottom = 8.dp)
    ) {
        items(actions, key = { it.label }) { action ->
            AssistChip(
                onClick = action.onClick,
                label = { Text(action.label) },
                leadingIcon = {
                    Icon(
                        imageVector = action.icon,
                        contentDescription = null,
                        modifier = Modifier.size(AssistChipDefaults.IconSize)
                    )
                }
            )
        }
    }
}

@Composable
private fun TrackRow(
    title: String,
    subtitle: String,
    description: String,
    isSelected: Boolean,
    onSelected: () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    val motion = MaterialTheme.velaMotion
    val containerColor by animateColorAsState(
        targetValue = if (isSelected) colors.secondaryContainer else Color.Transparent,
        animationSpec = motion.defaultEffectsSpec(),
        label = "trackRowContainer"
    )
    val primaryText = if (isSelected) colors.onSecondaryContainer else colors.onSurface
    val secondaryText = if (isSelected) {
        colors.onSecondaryContainer.copy(alpha = 0.78f)
    } else {
        colors.onSurfaceVariant
    }
    val supporting = listOf(subtitle, description).filter { it.isNotBlank() }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(containerColor)
            .selectable(
                selected = isSelected,
                role = Role.RadioButton,
                onClick = onSelected
            )
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            if (title.isNotEmpty()) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                    color = primaryText,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            supporting.forEach { line ->
                Text(
                    text = line,
                    style = MaterialTheme.typography.bodySmall,
                    color = secondaryText,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        if (isSelected) {
            Icon(
                imageVector = Icons.Rounded.Check,
                contentDescription = null,
                tint = colors.onSecondaryContainer,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

@Composable
private fun EmptyState() {
    Text(
        text = stringResource(R.string.player_dialog_no_tracks_available),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 24.dp, vertical = 24.dp)
    )
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

