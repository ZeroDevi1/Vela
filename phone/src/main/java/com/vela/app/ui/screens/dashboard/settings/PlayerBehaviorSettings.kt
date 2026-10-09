package com.vela.app.ui.screens.dashboard.settings

import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.vela.app.ui.components.common.SelectionOption
import com.vela.player.preferences.OsdOptions
import com.vela.player.preferences.PlayerBehaviorPreferences
import com.vela.player.preferences.PlayerButtons
import com.vela.shared.R

// 与 iOS「播放设置 / 播放手势 / 界面偏好」中 Android 原来缺少的项（见 iOS 仓库 docs/PLAN.md 第 4.3 节对照表）。
// 设置直接读写 PlayerBehaviorPreferences，播放器在下次读取时生效（多数在下一次进入播放器时）。

private val behaviorColor = Color(0xFF6366F1)

@Composable
private fun rememberBehaviorPreferences(): PlayerBehaviorPreferences {
    val context = LocalContext.current
    return remember { PlayerBehaviorPreferences(context) }
}

/** 布尔设置项：本地状态与偏好同步写入。 */
@Composable
private fun BehaviorSwitch(
    icon: ImageVector,
    title: String,
    subtitle: String = "",
    initial: Boolean,
    enabled: Boolean = true,
    onChange: (Boolean) -> Unit
) {
    var checked by remember { mutableStateOf(initial) }
    SwitchSettingsItem(
        icon = icon,
        title = title,
        subtitle = subtitle,
        checked = checked,
        onCheckedChange = { checked = it; onChange(it) },
        enabled = enabled,
        accentColor = behaviorColor
    )
}

@Composable
private fun tapActionLabel(action: String): String = stringResource(
    when (action) {
        PlayerBehaviorPreferences.TAP_TOGGLE_CONTROLS -> R.string.pb_tap_toggle_controls
        PlayerBehaviorPreferences.TAP_PLAY_PAUSE -> R.string.pb_tap_play_pause
        PlayerBehaviorPreferences.TAP_SEEK -> R.string.pb_tap_seek
        PlayerBehaviorPreferences.TAP_SPEED -> R.string.pb_tap_speed
        else -> R.string.pb_tap_none
    }
)

@Composable
private fun TapActionItem(title: String, initial: String, default: String, onChange: (String) -> Unit) {
    var value by remember { mutableStateOf(initial) }
    SelectionDialogSettingsItem(
        icon = Icons.Rounded.TouchApp,
        title = title,
        subtitle = tapActionLabel(value),
        selectedValue = value,
        options = PlayerBehaviorPreferences.TAP_ACTIONS.map { action ->
            SelectionOption(action, tapActionLabel(action), "", isDefault = action == default)
        },
        onOptionSelected = { value = it; onChange(it) },
        accentColor = behaviorColor
    )
}

/** 「播放设置」页：续播、自动连播、精准定位、自动跳过片头。 */
internal fun LazyListScope.playbackBehaviorItems() {
    item { PlayerSettingsSectionLabel(stringResource(R.string.pb_section_playback)) }
    item {
        val prefs = rememberBehaviorPreferences()
        var autoSkip by remember { mutableStateOf(prefs.autoSkipIntro) }
        SettingsSection {
            BehaviorSwitch(Icons.Rounded.History, stringResource(R.string.pb_resume_playback),
                stringResource(R.string.pb_resume_playback_summary), prefs.resumePlayback) { prefs.resumePlayback = it }
            SettingsDivider()
            BehaviorSwitch(Icons.Rounded.SkipNext, stringResource(R.string.pb_auto_play_next),
                initial = prefs.autoPlayNext) { prefs.autoPlayNext = it }
            SettingsDivider()
            BehaviorSwitch(Icons.Rounded.GpsFixed, stringResource(R.string.pb_precise_seek),
                stringResource(R.string.pb_precise_seek_summary), prefs.preciseSeek) { prefs.preciseSeek = it }
            SettingsDivider()
            BehaviorSwitch(Icons.Rounded.FastForward, stringResource(R.string.pb_auto_skip_intro),
                initial = autoSkip) { autoSkip = it; prefs.autoSkipIntro = it }
        }
    }
}

/** 「播放手势」页：单击 / 双击动作与震动反馈。 */
internal fun LazyListScope.gestureBehaviorItems() {
    item { PlayerSettingsSectionLabel(stringResource(R.string.pb_section_taps)) }
    item {
        val prefs = rememberBehaviorPreferences()
        SettingsSection {
            TapActionItem(stringResource(R.string.pb_single_tap_center), prefs.singleTapCenter,
                PlayerBehaviorPreferences.TAP_TOGGLE_CONTROLS) { prefs.singleTapCenter = it }
            SettingsDivider()
            TapActionItem(stringResource(R.string.pb_single_tap_sides), prefs.singleTapSides,
                PlayerBehaviorPreferences.TAP_TOGGLE_CONTROLS) { prefs.singleTapSides = it }
            SettingsDivider()
            TapActionItem(stringResource(R.string.pb_double_tap_center), prefs.doubleTapCenter,
                PlayerBehaviorPreferences.TAP_PLAY_PAUSE) { prefs.doubleTapCenter = it }
            SettingsDivider()
            TapActionItem(stringResource(R.string.pb_double_tap_sides), prefs.doubleTapSides,
                PlayerBehaviorPreferences.TAP_SEEK) { prefs.doubleTapSides = it }
        }
    }
    item { PlayerSettingsSectionLabel(stringResource(R.string.pb_section_haptics)) }
    item {
        val prefs = rememberBehaviorPreferences()
        SettingsSection {
            BehaviorSwitch(Icons.Rounded.Vibration, stringResource(R.string.pb_haptic_long_press),
                initial = prefs.hapticLongPress) { prefs.hapticLongPress = it }
            SettingsDivider()
            BehaviorSwitch(Icons.Rounded.Vibration, stringResource(R.string.pb_haptic_swipe_seek),
                initial = prefs.hapticSwipeSeek) { prefs.hapticSwipeSeek = it }
            SettingsDivider()
            BehaviorSwitch(Icons.Rounded.Vibration, stringResource(R.string.pb_haptic_scrub_bar),
                initial = prefs.hapticScrubBar) { prefs.hapticScrubBar = it }
        }
    }
}

/** 「音频设置」页：记住音轨。 */
internal fun LazyListScope.audioTrackBehaviorItems() {
    item { PlayerSettingsSectionLabel(stringResource(R.string.pb_section_tracks)) }
    item {
        val prefs = rememberBehaviorPreferences()
        SettingsSection {
            BehaviorSwitch(Icons.Rounded.Audiotrack, stringResource(R.string.pb_remember_audio_track),
                stringResource(R.string.pb_remember_audio_track_summary), prefs.rememberAudioTrack) { prefs.rememberAudioTrack = it }
        }
    }
}

/** 「字幕设置」页：记住字幕。 */
internal fun LazyListScope.subtitleTrackBehaviorItems() {
    item { PlayerSettingsSectionLabel(stringResource(R.string.pb_section_tracks)) }
    item {
        val prefs = rememberBehaviorPreferences()
        SettingsSection {
            BehaviorSwitch(Icons.Rounded.Subtitles, stringResource(R.string.pb_remember_subtitle_track),
                stringResource(R.string.pb_remember_subtitle_track_summary), prefs.rememberSubtitleTrack) { prefs.rememberSubtitleTrack = it }
        }
    }
}

/**
 * 「界面偏好」页：控制栏与提示信息；OSD 信息、自定义播放器按钮各自是子页面（[osdBehaviorItems]、[buttonBehaviorItems]），
 * 这里只放入口。
 */
internal fun LazyListScope.interfaceBehaviorItems(onOpenPage: (PlayerSettingsPage) -> Unit) {
    item { PlayerSettingsSectionLabel(stringResource(R.string.pb_section_controls)) }
    item {
        val prefs = rememberBehaviorPreferences()
        var remaining by remember { mutableStateOf(prefs.showRemainingTime) }
        var autoHide by remember { mutableStateOf(prefs.controlsAutoHideSeconds) }
        val totalLabel = stringResource(R.string.pb_time_total)
        val remainingLabel = stringResource(R.string.pb_time_remaining)
        SettingsSection {
            SelectionDialogSettingsItem(
                icon = Icons.Rounded.Timer,
                title = stringResource(R.string.pb_progress_time_mode),
                subtitle = if (remaining) remainingLabel else totalLabel,
                selectedValue = remaining.toString(),
                options = listOf(
                    SelectionOption("false", totalLabel, "", isDefault = true),
                    SelectionOption("true", remainingLabel, "")
                ),
                onOptionSelected = { remaining = it == "true"; prefs.showRemainingTime = remaining },
                accentColor = behaviorColor
            )
            SettingsDivider()
            val autoHideLabel: @Composable (Int) -> String = { seconds ->
                if (seconds == 0) stringResource(R.string.pb_auto_hide_never) else stringResource(R.string.pb_auto_hide_seconds, seconds)
            }
            SelectionDialogSettingsItem(
                icon = Icons.Rounded.VisibilityOff,
                title = stringResource(R.string.pb_auto_hide),
                subtitle = autoHideLabel(autoHide),
                selectedValue = autoHide.toString(),
                options = PlayerBehaviorPreferences.AUTO_HIDE_OPTIONS.map { seconds ->
                    SelectionOption(seconds.toString(), autoHideLabel(seconds), "", isDefault = seconds == 3)
                },
                onOptionSelected = { autoHide = it.toInt(); prefs.controlsAutoHideSeconds = autoHide },
                accentColor = behaviorColor
            )
            SettingsDivider()
            BehaviorSwitch(Icons.Rounded.NotificationsActive, stringResource(R.string.pb_hint_before_skip),
                initial = prefs.hintBeforeSkip) { prefs.hintBeforeSkip = it }
        }
    }
    item { PlayerSettingsSectionLabel(stringResource(R.string.pb_section_overlay)) }
    item {
        SettingsSection {
            ClickableSettingsItem(
                icon = Icons.Rounded.Info,
                title = stringResource(R.string.pb_osd),
                subtitle = stringResource(R.string.pb_osd_entry_summary),
                onClick = { onOpenPage(PlayerSettingsPage.OSD) },
                accentColor = behaviorColor
            )
            SettingsDivider()
            ClickableSettingsItem(
                icon = Icons.Rounded.SmartButton,
                title = stringResource(R.string.pb_buttons),
                subtitle = stringResource(R.string.pb_buttons_entry_summary),
                onClick = { onOpenPage(PlayerSettingsPage.BUTTONS) },
                accentColor = behaviorColor
            )
        }
    }
}

/** 「OSD 信息」子页面（从界面偏好进入）。 */
internal fun LazyListScope.osdBehaviorItems() {
    item {
        val prefs = rememberBehaviorPreferences()
        var osd by remember { mutableStateOf(prefs.osd) }
        fun update(transform: (OsdOptions) -> OsdOptions) {
            osd = transform(osd)
            prefs.osd = osd
        }
        val sizeLabels = mapOf(
            PlayerBehaviorPreferences.OSD_TEXT_SMALL to stringResource(R.string.pb_osd_small),
            PlayerBehaviorPreferences.OSD_TEXT_STANDARD to stringResource(R.string.pb_osd_standard),
            PlayerBehaviorPreferences.OSD_TEXT_LARGE to stringResource(R.string.pb_osd_large)
        )
        SettingsSection {
            BehaviorSwitch(Icons.Rounded.Schedule, stringResource(R.string.pb_osd_clock), initial = osd.clock) { v -> update { it.copy(clock = v) } }
            SettingsDivider()
            BehaviorSwitch(Icons.Rounded.Speed, stringResource(R.string.pb_osd_frame_rate), initial = osd.frameRate) { v -> update { it.copy(frameRate = v) } }
            SettingsDivider()
            BehaviorSwitch(Icons.Rounded.NetworkCheck, stringResource(R.string.pb_osd_network_speed), initial = osd.networkSpeed) { v -> update { it.copy(networkSpeed = v) } }
            SettingsDivider()
            BehaviorSwitch(Icons.Rounded.SlowMotionVideo, stringResource(R.string.pb_osd_playback_speed), initial = osd.playbackSpeed) { v -> update { it.copy(playbackSpeed = v) } }
            SettingsDivider()
            BehaviorSwitch(Icons.Rounded.Timer, stringResource(R.string.pb_osd_playback_time), initial = osd.playbackTime) { v -> update { it.copy(playbackTime = v) } }
            SettingsDivider()
            BehaviorSwitch(Icons.Rounded.Memory, stringResource(R.string.pb_osd_cpu), initial = osd.cpuUsage) { v -> update { it.copy(cpuUsage = v) } }
            SettingsDivider()
            BehaviorSwitch(Icons.Rounded.BatteryStd, stringResource(R.string.pb_osd_battery), initial = osd.battery) { v -> update { it.copy(battery = v) } }
            SettingsDivider()
            BehaviorSwitch(Icons.Rounded.FormatColorFill, stringResource(R.string.pb_osd_text_background), initial = osd.textBackground) { v -> update { it.copy(textBackground = v) } }
            SettingsDivider()
            SelectionDialogSettingsItem(
                icon = Icons.Rounded.FormatSize,
                title = stringResource(R.string.pb_osd_text_size),
                subtitle = sizeLabels[osd.textSize].orEmpty(),
                selectedValue = osd.textSize,
                options = PlayerBehaviorPreferences.OSD_TEXT_SIZES.map {
                    SelectionOption(it, sizeLabels[it].orEmpty(), "", isDefault = it == PlayerBehaviorPreferences.OSD_TEXT_STANDARD)
                },
                onOptionSelected = { size -> update { it.copy(textSize = size) } },
                accentColor = behaviorColor
            )
            SettingsDivider()
            BehaviorSwitch(Icons.Rounded.SwapHoriz, stringResource(R.string.pb_osd_hug_edges), initial = osd.hugEdges) { v -> update { it.copy(hugEdges = v) } }
            SettingsDivider()
            BehaviorSwitch(Icons.Rounded.ScreenRotation, stringResource(R.string.pb_osd_landscape_only),
                stringResource(R.string.pb_osd_summary), osd.landscapeOnly) { v -> update { it.copy(landscapeOnly = v) } }
        }
    }
}

/** 「自定义播放器按钮」子页面（从界面偏好进入）。 */
internal fun LazyListScope.buttonBehaviorItems() {
    item {
        val prefs = rememberBehaviorPreferences()
        var buttons by remember { mutableStateOf(prefs.buttons) }
        fun update(transform: (PlayerButtons) -> PlayerButtons) {
            buttons = transform(buttons)
            prefs.buttons = buttons
        }
        SettingsSection {
            BehaviorSwitch(Icons.Rounded.FastRewind, stringResource(R.string.pb_button_seek), initial = buttons.seek) { v -> update { it.copy(seek = v) } }
            SettingsDivider()
            BehaviorSwitch(Icons.Rounded.SkipNext, stringResource(R.string.pb_button_switch_media), initial = buttons.switchMedia) { v -> update { it.copy(switchMedia = v) } }
            SettingsDivider()
            BehaviorSwitch(Icons.Rounded.PlayArrow, stringResource(R.string.pb_button_play_pause), initial = buttons.playPause) { v -> update { it.copy(playPause = v) } }
            SettingsDivider()
            BehaviorSwitch(Icons.Rounded.CropFree, stringResource(R.string.pb_button_zoom), initial = buttons.zoom) { v -> update { it.copy(zoom = v) } }
            SettingsDivider()
            BehaviorSwitch(Icons.Rounded.ScreenRotation, stringResource(R.string.pb_button_rotate), initial = buttons.rotate) { v -> update { it.copy(rotate = v) } }
            SettingsDivider()
            BehaviorSwitch(Icons.Rounded.PictureInPictureAlt, stringResource(R.string.pb_button_pip), initial = buttons.pictureInPicture) { v -> update { it.copy(pictureInPicture = v) } }
            SettingsDivider()
            BehaviorSwitch(Icons.Rounded.Bookmarks, stringResource(R.string.pb_button_chapters), initial = buttons.chapters) { v -> update { it.copy(chapters = v) } }
            SettingsDivider()
            BehaviorSwitch(Icons.Rounded.Speed, stringResource(R.string.pb_button_speed), initial = buttons.speed) { v -> update { it.copy(speed = v) } }
            SettingsDivider()
            BehaviorSwitch(Icons.Rounded.PhotoCamera, stringResource(R.string.pb_button_screenshot), initial = buttons.screenshot) { v -> update { it.copy(screenshot = v) } }
        }
    }
}
