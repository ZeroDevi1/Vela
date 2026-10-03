package com.vela.app.ui.screens.player

import android.content.Context
import android.content.res.Resources
import android.media.AudioManager
import android.os.SystemClock
import android.view.GestureDetector
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import androidx.media3.common.Player
import kotlin.math.abs

import com.vela.player.core.PlayerConstants.GESTURE_EXCLUSION_AREA_HORIZONTAL
import com.vela.player.core.PlayerConstants.GESTURE_EXCLUSION_AREA_VERTICAL
import com.vela.player.core.PlayerConstants.FULL_SWIPE_RANGE_SCREEN_RATIO
import com.vela.player.core.PlayerConstants.ZOOM_SCALE_BASE
import com.vela.player.core.PlayerConstants.ZOOM_SCALE_THRESHOLD
import com.vela.player.preferences.PlayerPreferences

/** VR 转平面时保留滑动调进度的底部区域占视图高度的比例。 */
private const val VR_SEEK_BAND_FRACTION = 0.22f

class GestureHelper(
    private val context: Context,
    private val touchView: View,
    private val audioManager: AudioManager,
    private val onShowControls: () -> Unit,
    private val onSeek: (Long) -> Unit,
    private val onVolumeChange: (Float) -> Unit,
    private val onBrightnessChange: (Float) -> Unit,
    private val getCurrentVolumeLevel: () -> Float,
    private val getCurrentBrightnessLevel: () -> Float,
    private val onZoomChange: (Boolean) -> Unit,
    private val onTogglePlayPause: () -> Unit = {},
    private val getPlayer: () -> Player? = { null },
    private val getPlaybackPosition: () -> Long = { 0L },
    private val getPlaybackDuration: () -> Long = { 0L },
    private val onSeekPreview: (Long?) -> Unit = {},
    private val onHoldSpeed: (Boolean) -> Unit = {},
    private val isVrLookAround: () -> Boolean = { false },
    private val onLookAround: (Float, Float) -> Unit = { _, _ -> },
    private val onFovScale: (Float) -> Unit = {},
    private val getVrSurface: () -> View? = { null }
) {
    private val playerPreferences = PlayerPreferences(context)
    // Gesture state tracking
    private var swipeGestureValueTrackerVolume = -1f
    private var swipeGestureValueTrackerBrightness = -1f
    private var swipeGestureValueTrackerProgress = 0L
    private var swipeGestureVolumeOpen = false
    private var swipeGestureBrightnessOpen = false
    private var swipeGestureProgressOpen = false
    private var swipeGestureLookOpen = false
    private var vrSurfaceLookStarted = false
    private var lastScaleEvent: Long = 0
    private var currentNumberOfPointers: Int = 0
    private var isZoomEnabled = false
    private var screenWidth = 0
    private var screenHeight = 0
    private var swipeSeekStartPosition = 0L
    private var speedHoldActive = false

    // Constants

    private fun seekBackwardDeltaMs(): Long {
        return playerPreferences.getSeekBackwardIntervalSeconds() * 1000L
    }

    private fun seekForwardDeltaMs(): Long {
        return playerPreferences.getSeekForwardIntervalSeconds() * 1000L
    }

    // Single tap and double tap detector
    private val tapGestureDetector = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true

            // 中央区域没有双击跳转，单击立即显隐控制层，不必等双击超时（约 300ms）。
            override fun onSingleTapUp(e: MotionEvent): Boolean {
                if (tapZone(e.x) == 0) onShowControls()
                return true
            }

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                // 两侧区域要等确认不是双击跳转后才显隐控制层；中央已在 onSingleTapUp 处理。
                if (tapZone(e.x) != 0) onShowControls()
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                val zone = tapZone(e.x)
                if (zone == 0) {
                    // 中央双击：播放/暂停（第一次单击已唤出控制层，可直接看到状态变化）。
                    onTogglePlayPause()
                    touchView.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                    return true
                }
                if (!playerPreferences.arePlayerGesturesEnabled() ||
                    !playerPreferences.isProgressSeekGestureEnabled()
                ) {
                    onShowControls()
                    return true
                }
                onSeek(if (zone < 0) -seekBackwardDeltaMs() else seekForwardDeltaMs())
                touchView.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                return true
            }

            override fun onLongPress(e: MotionEvent) {
                if (!playerPreferences.arePlayerGesturesEnabled()) return
                // VR 转平面时“按住再拖”是调整视角的自然动作，长按倍速会误触成快进并锁住视角拖动。
                if (isVrLookAround()) return
                if (swipeGestureProgressOpen || swipeGestureVolumeOpen || swipeGestureBrightnessOpen) return
                speedHoldActive = true
                touchView.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                onHoldSpeed(true)
            }
        }
    )

    private fun tapZone(x: Float): Int {
        val viewWidth = touchView.measuredWidth
        if (viewWidth <= 0) return 0
        val landscape = viewWidth > touchView.measuredHeight
        val sideFraction = if (landscape) 0.22f else 0.4f
        val leftEnd = viewWidth * sideFraction
        val rightStart = viewWidth * (1f - sideFraction)
        return when {
            x < leftEnd -> -1
            x >= rightStart -> 1
            else -> 0
        }
    }

    private val seekGestureDetector = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onScroll(
                firstEvent: MotionEvent?,
                currentEvent: MotionEvent,
                distanceX: Float,
                distanceY: Float,
            ): Boolean {
                if (firstEvent == null) return false
                if (!playerPreferences.arePlayerGesturesEnabled()) {
                    return false
                }
                if (inExclusionArea(firstEvent)) return false

                if (isVrLookAround() && !inVrSeekBand(firstEvent)) {
                    if (speedHoldActive || (SystemClock.elapsedRealtime() - lastScaleEvent) <= 200) {
                        return false
                    }
                    if (inVolumeBrightnessEdge(firstEvent) && abs(distanceY) >= abs(distanceX)) {
                        return false
                    }
                    swipeGestureLookOpen = true
                    val surface = getVrSurface()
                    if (surface != null) {
                        dispatchVrSurfaceLook(surface, firstEvent, currentEvent)
                        return true
                    }
                    // 上报手指位移（以视图高度为单位，右/下为正），角度换算依赖当前 FOV，交给 ViewModel。
                    val height = touchView.measuredHeight.coerceAtLeast(1).toFloat()
                    onLookAround(-distanceX / height, -distanceY / height)
                    return true
                }

                // Check if swipe is horizontal
                if (abs(distanceX) > abs(distanceY)) {
                    return if ((abs(currentEvent.x - firstEvent.x) > 50 || swipeGestureProgressOpen) &&
                        !swipeGestureBrightnessOpen && !swipeGestureVolumeOpen &&
                        !speedHoldActive &&
                        (SystemClock.elapsedRealtime() - lastScaleEvent) > 200
                    ) {
                        if (!swipeGestureProgressOpen) {
                            swipeSeekStartPosition = getPlaybackPosition()
                        }
                        val duration = getPlaybackDuration()
                        val width = touchView.measuredWidth.coerceAtLeast(1)
                        val rangeMs = if (duration > 0L) duration / 5L else 10L * 60L * 1000L
                        val difference = ((currentEvent.x - firstEvent.x) / width.toFloat() * rangeMs).toLong()
                        swipeGestureValueTrackerProgress = difference
                        swipeGestureProgressOpen = true
                        val preview = if (duration > 0L) {
                            (swipeSeekStartPosition + difference).coerceIn(0L, duration)
                        } else {
                            (swipeSeekStartPosition + difference).coerceAtLeast(0L)
                        }
                        onSeekPreview(preview)
                        true
                    } else {
                        false
                    }
                }
                return true
            }
        }
    )

    // Volume and brightness gesture detector
    private val vbGestureDetector = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onScroll(
                firstEvent: MotionEvent?,
                currentEvent: MotionEvent,
                distanceX: Float,
                distanceY: Float,
            ): Boolean {
                if (firstEvent == null) return false
                if (inExclusionArea(firstEvent)) {
                    return false
                }
                if (isVrLookAround() && !inVolumeBrightnessEdge(firstEvent)) {
                    return false
                }
                if (!playerPreferences.arePlayerGesturesEnabled() ||
                    !playerPreferences.isVolumeBrightnessGesturesEnabled()
                ) {
                    return false
                }

                if (abs(distanceY) < abs(distanceX)) {
                    return false
                }
                if (swipeGestureProgressOpen || swipeGestureLookOpen) {
                    return false
                }

                val viewCenterX = touchView.measuredWidth / 2
                val distanceFull = touchView.measuredHeight * FULL_SWIPE_RANGE_SCREEN_RATIO
                val ratioChange = distanceY / distanceFull

                if (firstEvent.x.toInt() > viewCenterX) {
                    if (swipeGestureValueTrackerVolume == -1f) {
                        val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                        swipeGestureValueTrackerVolume = getCurrentVolumeLevel() * maxVolume
                    }
                    val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                    val change = ratioChange * maxVolume
                    swipeGestureValueTrackerVolume = (swipeGestureValueTrackerVolume + change)
                        .coerceIn(0f, maxVolume.toFloat())

                    val volumePercent = (swipeGestureValueTrackerVolume / maxVolume.toFloat())
                    onVolumeChange(volumePercent)
                    swipeGestureVolumeOpen = true
                } else {
                    if (swipeGestureValueTrackerBrightness == -1f) {
                        swipeGestureValueTrackerBrightness = getCurrentBrightnessLevel()
                    }
                    
                    val newBrightness = (swipeGestureValueTrackerBrightness + ratioChange)
                        .coerceIn(0.01f, 1f)
                    swipeGestureValueTrackerBrightness = newBrightness

                    onBrightnessChange(ratioChange)
                    swipeGestureBrightnessOpen = true
                }
                return true
            }
        }
    )

    // Zoom gesture detector
    private val zoomGestureDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.OnScaleGestureListener {
            override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                return playerPreferences.arePlayerGesturesEnabled() &&
                    (isVrLookAround() || playerPreferences.isZoomGestureEnabled())
            }

            override fun onScale(detector: ScaleGestureDetector): Boolean {
                if (!playerPreferences.arePlayerGesturesEnabled()) {
                    return false
                }
                lastScaleEvent = SystemClock.elapsedRealtime()
                val scaleFactor = detector.scaleFactor
                if (isVrLookAround()) {
                    onFovScale(scaleFactor)
                    return true
                }
                if (!playerPreferences.isZoomGestureEnabled()) {
                    return false
                }
                
                if (abs(scaleFactor - ZOOM_SCALE_BASE) > ZOOM_SCALE_THRESHOLD) {
                    val enableZoom = scaleFactor > 1
                    updateZoomMode(enableZoom)
                }
                return true
            }

            override fun onScaleEnd(detector: ScaleGestureDetector) = Unit
        }
    ).apply {
        isQuickScaleEnabled = false
    }

    private fun updateZoomMode(enabled: Boolean) {
        isZoomEnabled = enabled
        onZoomChange(enabled)
    }

    private fun releaseAction(event: MotionEvent) {
        if (event.action == MotionEvent.ACTION_UP || event.action == MotionEvent.ACTION_CANCEL) {
            if (swipeGestureVolumeOpen) {
                swipeGestureVolumeOpen = false
                swipeGestureValueTrackerVolume = -1f
            }

            if (swipeGestureBrightnessOpen) {
                swipeGestureBrightnessOpen = false
                swipeGestureValueTrackerBrightness = -1f
            }

            if (swipeGestureProgressOpen) {
                if (swipeGestureValueTrackerProgress != 0L) {
                    onSeek(swipeGestureValueTrackerProgress)
                }
                swipeGestureProgressOpen = false
                swipeGestureValueTrackerProgress = 0L
                onSeekPreview(null)
            }

            if (swipeGestureLookOpen) {
                finishVrSurfaceLook(event)
                swipeGestureLookOpen = false
            }

            if (speedHoldActive) {
                speedHoldActive = false
                onHoldSpeed(false)
            }

            currentNumberOfPointers = 0
        }
    }

    private fun inExclusionArea(firstEvent: MotionEvent): Boolean {
        val exclusionVertical = GESTURE_EXCLUSION_AREA_VERTICAL * Resources.getSystem().displayMetrics.density
        val exclusionHorizontal = GESTURE_EXCLUSION_AREA_HORIZONTAL * Resources.getSystem().displayMetrics.density

        val inExclusion = firstEvent.y < exclusionVertical ||
            firstEvent.y > screenHeight - exclusionVertical ||
            firstEvent.x < exclusionHorizontal ||
            firstEvent.x > screenWidth - exclusionHorizontal

        return inExclusion
    }

    private fun dispatchVrSurfaceLook(
        surface: View,
        firstEvent: MotionEvent,
        currentEvent: MotionEvent
    ) {
        if (!vrSurfaceLookStarted) {
            dispatchToVrSurface(surface, firstEvent)
            vrSurfaceLookStarted = true
        }
        dispatchToVrSurface(surface, currentEvent)
    }

    private fun finishVrSurfaceLook(event: MotionEvent) {
        val surface = getVrSurface()
        if (vrSurfaceLookStarted && surface != null) {
            dispatchToVrSurface(surface, event)
        }
        vrSurfaceLookStarted = false
    }

    private fun dispatchToVrSurface(surface: View, event: MotionEvent): Boolean {
        val viewLoc = IntArray(2)
        val surfaceLoc = IntArray(2)
        touchView.getLocationOnScreen(viewLoc)
        surface.getLocationOnScreen(surfaceLoc)
        val transformed = MotionEvent.obtain(event)
        transformed.offsetLocation(
            (viewLoc[0] - surfaceLoc[0]).toFloat(),
            (viewLoc[1] - surfaceLoc[1]).toFloat()
        )
        return try {
            surface.dispatchTouchEvent(transformed)
        } finally {
            transformed.recycle()
        }
    }

    /**
     * VR 转平面时单指拖动用于转视角；从底部这条区域起手的横向拖动仍是滑动调进度，两种手势按起点区分、互不冲突。
     */
    private fun inVrSeekBand(firstEvent: MotionEvent): Boolean {
        val height = touchView.measuredHeight
        if (height <= 0) return false
        return firstEvent.y > height * (1f - VR_SEEK_BAND_FRACTION)
    }

    private fun inVolumeBrightnessEdge(firstEvent: MotionEvent): Boolean {
        val width = touchView.measuredWidth
        if (width <= 0) return false
        val edge = width * 0.18f
        return firstEvent.x < edge || firstEvent.x > width - edge
    }

    fun handleTouchEvent(event: MotionEvent): Boolean {
        currentNumberOfPointers = event.pointerCount

        screenWidth = touchView.width
        screenHeight = touchView.height

        when (event.pointerCount) {
            1 -> {
                tapGestureDetector.onTouchEvent(event)
                if (playerPreferences.arePlayerGesturesEnabled() && !speedHoldActive) {
                    vbGestureDetector.onTouchEvent(event)
                    seekGestureDetector.onTouchEvent(event)
                }
            }
            2 -> {
                if (playerPreferences.arePlayerGesturesEnabled() &&
                    (isVrLookAround() || playerPreferences.isZoomGestureEnabled())
                ) {
                    zoomGestureDetector.onTouchEvent(event)
                }
            }
        }

        releaseAction(event)
        return true
    }
}
