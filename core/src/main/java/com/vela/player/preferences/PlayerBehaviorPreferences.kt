package com.vela.player.preferences

import android.content.Context
import android.content.SharedPreferences

/**
 * 与 iOS 版同步的播放行为设置（iOS `PlayerPreferences`：续播、自动连播、精准定位、点击动作、震动、OSD、
 * 控制栏按钮、进度条时间模式、自动隐藏、跳过提示）。与 [PlayerPreferences] 共用同一个 SharedPreferences 文件，
 * 只使用新键名，不改动已有键，旧版本升级后保持原有行为（默认值取 Android 原来的行为，与 iOS 不同处在注释中说明）。
 */
class PlayerBehaviorPreferences(context: Context) {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences("vela_player_prefs", Context.MODE_PRIVATE)

    /** 从上次进度播放；关闭后总是从头开始。 */
    var resumePlayback: Boolean
        get() = prefs.getBoolean(KEY_RESUME_PLAYBACK, true)
        set(value) = prefs.edit().putBoolean(KEY_RESUME_PLAYBACK, value).apply()

    /** 播放结束（或片尾倒计时结束）后自动播放下一集。 */
    var autoPlayNext: Boolean
        get() = prefs.getBoolean(KEY_AUTO_PLAY_NEXT, true)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_PLAY_NEXT, value).apply()

    /**
     * 精准定位：跳转到准确的帧（需要从关键帧解码到目标位置，较慢）；关闭时跳到最近的关键帧。
     * 默认开启，保持 Android 原有行为（iOS 默认关闭）。
     */
    var preciseSeek: Boolean
        get() = prefs.getBoolean(KEY_PRECISE_SEEK, true)
        set(value) = prefs.edit().putBoolean(KEY_PRECISE_SEEK, value).apply()

    /** 自动跳过片头（有片头片段时）；默认关闭，与 iOS 一致。 */
    var autoSkipIntro: Boolean
        get() = prefs.getBoolean(KEY_AUTO_SKIP_INTRO, false)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_SKIP_INTRO, value).apply()

    /** 自动跳过前先提示 3 秒（期间拖动进度即取消）。 */
    var hintBeforeSkip: Boolean
        get() = prefs.getBoolean(KEY_HINT_BEFORE_SKIP, true)
        set(value) = prefs.edit().putBoolean(KEY_HINT_BEFORE_SKIP, value).apply()

    var singleTapCenter: String
        get() = tapAction(KEY_SINGLE_TAP_CENTER, TAP_TOGGLE_CONTROLS)
        set(value) = prefs.edit().putString(KEY_SINGLE_TAP_CENTER, value).apply()
    var singleTapSides: String
        get() = tapAction(KEY_SINGLE_TAP_SIDES, TAP_TOGGLE_CONTROLS)
        set(value) = prefs.edit().putString(KEY_SINGLE_TAP_SIDES, value).apply()
    var doubleTapCenter: String
        get() = tapAction(KEY_DOUBLE_TAP_CENTER, TAP_PLAY_PAUSE)
        set(value) = prefs.edit().putString(KEY_DOUBLE_TAP_CENTER, value).apply()
    var doubleTapSides: String
        get() = tapAction(KEY_DOUBLE_TAP_SIDES, TAP_SEEK)
        set(value) = prefs.edit().putString(KEY_DOUBLE_TAP_SIDES, value).apply()

    var hapticLongPress: Boolean
        get() = prefs.getBoolean(KEY_HAPTIC_LONG_PRESS, true)
        set(value) = prefs.edit().putBoolean(KEY_HAPTIC_LONG_PRESS, value).apply()
    var hapticSwipeSeek: Boolean
        get() = prefs.getBoolean(KEY_HAPTIC_SWIPE_SEEK, true)
        set(value) = prefs.edit().putBoolean(KEY_HAPTIC_SWIPE_SEEK, value).apply()
    var hapticScrubBar: Boolean
        get() = prefs.getBoolean(KEY_HAPTIC_SCRUB_BAR, true)
        set(value) = prefs.edit().putBoolean(KEY_HAPTIC_SCRUB_BAR, value).apply()

    /** 进度条右侧显示剩余时长（否则总时长）；播放器里点击时间仍可临时切换。 */
    var showRemainingTime: Boolean
        get() = prefs.getString(KEY_PROGRESS_TIME_MODE, TIME_MODE_TOTAL) == TIME_MODE_REMAINING
        set(value) = prefs.edit().putString(KEY_PROGRESS_TIME_MODE, if (value) TIME_MODE_REMAINING else TIME_MODE_TOTAL).apply()

    /** 控制层自动隐藏秒数；0 为从不。默认 3 秒，保持 Android 原有行为（iOS 默认 5 秒）。 */
    var controlsAutoHideSeconds: Int
        get() = prefs.getInt(KEY_CONTROLS_AUTO_HIDE_SECONDS, 3).takeIf { it in AUTO_HIDE_OPTIONS } ?: 3
        set(value) = prefs.edit().putInt(KEY_CONTROLS_AUTO_HIDE_SECONDS, value).apply()

    var osd: OsdOptions
        get() = OsdOptions(
            clock = prefs.getBoolean("osd_clock", false),
            frameRate = prefs.getBoolean("osd_frame_rate", false),
            networkSpeed = prefs.getBoolean("osd_network_speed", false),
            playbackSpeed = prefs.getBoolean("osd_playback_speed", false),
            playbackTime = prefs.getBoolean("osd_playback_time", false),
            cpuUsage = prefs.getBoolean("osd_cpu_usage", false),
            battery = prefs.getBoolean("osd_battery", false),
            textBackground = prefs.getBoolean("osd_text_background", true),
            textSize = prefs.getString("osd_text_size", OSD_TEXT_STANDARD) ?: OSD_TEXT_STANDARD,
            hugEdges = prefs.getBoolean("osd_hug_edges", false),
            landscapeOnly = prefs.getBoolean("osd_landscape_only", false)
        )
        set(value) = prefs.edit()
            .putBoolean("osd_clock", value.clock)
            .putBoolean("osd_frame_rate", value.frameRate)
            .putBoolean("osd_network_speed", value.networkSpeed)
            .putBoolean("osd_playback_speed", value.playbackSpeed)
            .putBoolean("osd_playback_time", value.playbackTime)
            .putBoolean("osd_cpu_usage", value.cpuUsage)
            .putBoolean("osd_battery", value.battery)
            .putBoolean("osd_text_background", value.textBackground)
            .putString("osd_text_size", value.textSize)
            .putBoolean("osd_hug_edges", value.hugEdges)
            .putBoolean("osd_landscape_only", value.landscapeOnly)
            .apply()

    var buttons: PlayerButtons
        get() = PlayerButtons(
            seek = prefs.getBoolean("button_seek", true),
            switchMedia = prefs.getBoolean("button_switch_media", true),
            playPause = prefs.getBoolean("button_play_pause", true),
            zoom = prefs.getBoolean("button_zoom", true),
            rotate = prefs.getBoolean("button_rotate", true),
            pictureInPicture = prefs.getBoolean("button_pip", true),
            chapters = prefs.getBoolean("button_chapters", true),
            speed = prefs.getBoolean("button_speed", true),
            screenshot = prefs.getBoolean("button_screenshot", true)
        )
        set(value) = prefs.edit()
            .putBoolean("button_seek", value.seek)
            .putBoolean("button_switch_media", value.switchMedia)
            .putBoolean("button_play_pause", value.playPause)
            .putBoolean("button_zoom", value.zoom)
            .putBoolean("button_rotate", value.rotate)
            .putBoolean("button_pip", value.pictureInPicture)
            .putBoolean("button_chapters", value.chapters)
            .putBoolean("button_speed", value.speed)
            .putBoolean("button_screenshot", value.screenshot)
            .apply()

    private fun tapAction(key: String, fallback: String): String =
        prefs.getString(key, fallback)?.takeIf { it in TAP_ACTIONS } ?: fallback

    companion object {
        private const val KEY_RESUME_PLAYBACK = "resume_playback"
        private const val KEY_AUTO_PLAY_NEXT = "auto_play_next"
        private const val KEY_PRECISE_SEEK = "precise_seek"
        private const val KEY_AUTO_SKIP_INTRO = "auto_skip_intro"
        private const val KEY_HINT_BEFORE_SKIP = "hint_before_skip"
        private const val KEY_SINGLE_TAP_CENTER = "single_tap_center"
        private const val KEY_SINGLE_TAP_SIDES = "single_tap_sides"
        private const val KEY_DOUBLE_TAP_CENTER = "double_tap_center"
        private const val KEY_DOUBLE_TAP_SIDES = "double_tap_sides"
        private const val KEY_HAPTIC_LONG_PRESS = "haptic_long_press"
        private const val KEY_HAPTIC_SWIPE_SEEK = "haptic_swipe_seek"
        private const val KEY_HAPTIC_SCRUB_BAR = "haptic_scrub_bar"
        private const val KEY_PROGRESS_TIME_MODE = "progress_time_mode"
        private const val KEY_CONTROLS_AUTO_HIDE_SECONDS = "controls_auto_hide_seconds"
        private const val TIME_MODE_TOTAL = "total"
        private const val TIME_MODE_REMAINING = "remaining"

        // 取值与 iOS `PlayerTapAction` 的 rawValue 相同。
        const val TAP_TOGGLE_CONTROLS = "toggleControls"
        const val TAP_PLAY_PAUSE = "playPause"
        /** 两侧为快退 / 快进；中央没有方向，按播放 / 暂停处理（与 iOS 相同）。 */
        const val TAP_SEEK = "seek"
        /** 切换倍速（左侧 / 右侧使用长按倍速的速度），再次触发恢复原速。 */
        const val TAP_SPEED = "speed"
        const val TAP_NONE = "none"
        val TAP_ACTIONS = listOf(TAP_TOGGLE_CONTROLS, TAP_PLAY_PAUSE, TAP_SEEK, TAP_SPEED, TAP_NONE)

        /** 自动隐藏选项（秒），0 为从不；与 iOS `autoHideOptions` 相同。 */
        val AUTO_HIDE_OPTIONS = listOf(3, 5, 8, 10, 0)

        const val OSD_TEXT_SMALL = "small"
        const val OSD_TEXT_STANDARD = "standard"
        const val OSD_TEXT_LARGE = "large"
        val OSD_TEXT_SIZES = listOf(OSD_TEXT_SMALL, OSD_TEXT_STANDARD, OSD_TEXT_LARGE)
    }
}

/** 屏幕角落的 OSD 信息（与 iOS `OSDOptions` 对应）。 */
data class OsdOptions(
    val clock: Boolean = false,
    val frameRate: Boolean = false,
    val networkSpeed: Boolean = false,
    val playbackSpeed: Boolean = false,
    val playbackTime: Boolean = false,
    val cpuUsage: Boolean = false,
    val battery: Boolean = false,
    val textBackground: Boolean = true,
    val textSize: String = PlayerBehaviorPreferences.OSD_TEXT_STANDARD,
    /** 贴近屏幕左右边缘（忽略刘海等安全区）。 */
    val hugEdges: Boolean = false,
    val landscapeOnly: Boolean = false
) {
    val hasContent: Boolean get() = clock || frameRate || networkSpeed || playbackSpeed || playbackTime || cpuUsage || battery
}

/** 控制栏中可隐藏的按钮（与 iOS `PlayerButtons` 对应；Android 没有独立的投屏按钮）。 */
data class PlayerButtons(
    val seek: Boolean = true,
    val switchMedia: Boolean = true,
    val playPause: Boolean = true,
    val zoom: Boolean = true,
    val rotate: Boolean = true,
    val pictureInPicture: Boolean = true,
    val chapters: Boolean = true,
    val speed: Boolean = true,
    val screenshot: Boolean = true
)
