package com.vela.app.ui.screens.player

import android.content.res.Configuration
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeDown
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.rounded.FastForward
import androidx.compose.material.icons.rounded.FastRewind
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.vela.player.core.PlayerConstants.GESTURE_INDICATOR_PADDING_DP
import com.vela.shared.ui.theme.velaMotion

enum class SeekSide {
    LEFT, CENTER, RIGHT
}

@Composable
fun GestureIndicators(
    modifier: Modifier = Modifier,
    volumeLevel: Float? = null,
    brightnessLevel: Float? = null,
    seekPosition: String? = null,
    seekSide: SeekSide = SeekSide.CENTER,
    swipeSeekPositionMs: Long? = null,
    swipeSeekDurationMs: Long = 0L,
    holdSpeedLabel: String? = null,
    controlsVisible: Boolean = false
) {
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val motion = MaterialTheme.velaMotion
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        AnimatedVisibility(
            visible = volumeLevel != null,
            enter = fadeIn(motion.fastEffectsSpec()) + slideInHorizontally(motion.defaultSpatialSpec()) { it / 2 },
            exit = fadeOut(motion.fastEffectsSpec()) + slideOutHorizontally { it / 2 },
            modifier = Modifier.align(Alignment.CenterEnd)
        ) {
            volumeLevel?.let { level ->
                VolumeIndicator(
                    level = level,
                    modifier = Modifier.padding(end = GESTURE_INDICATOR_PADDING_DP.dp)
                )
            }
        }

        AnimatedVisibility(
            visible = brightnessLevel != null,
            enter = fadeIn(motion.fastEffectsSpec()) + slideInHorizontally(motion.defaultSpatialSpec()) { -it / 2 },
            exit = fadeOut(motion.fastEffectsSpec()) + slideOutHorizontally { -it / 2 },
            modifier = Modifier.align(Alignment.CenterStart)
        ) {
            brightnessLevel?.let { level ->
                BrightnessIndicator(
                    level = level,
                    modifier = Modifier.padding(start = GESTURE_INDICATOR_PADDING_DP.dp)
                )
            }
        }

        // ±秒提示的中心与前进/后退键中心对齐：控制层隐藏时就落在按键位置，
        // 控制层可见时下移到按键正下方，避免与按键重叠。
        val seekOffsetX = when (seekSide) {
            SeekSide.LEFT -> -transportSeekCenterOffset(landscape)
            SeekSide.CENTER -> 0.dp
            SeekSide.RIGHT -> transportSeekCenterOffset(landscape)
        }
        val seekOffsetY = if (controlsVisible) TransportSeekButtonSize / 2 + 32.dp else 0.dp
        AnimatedVisibility(
            visible = seekPosition != null && swipeSeekPositionMs == null,
            enter = fadeIn(motion.fastEffectsSpec()) + scaleIn(motion.fastSpatialSpec(), initialScale = 0.85f),
            exit = fadeOut(motion.fastEffectsSpec()),
            modifier = Modifier
                .align(Alignment.Center)
                .offset(x = seekOffsetX, y = seekOffsetY)
        ) {
            seekPosition?.let { position ->
                SeekIndicator(
                    position = position,
                    forward = seekSide != SeekSide.LEFT
                )
            }
        }

        AnimatedVisibility(
            visible = swipeSeekPositionMs != null && swipeSeekDurationMs > 0L,
            enter = fadeIn(motion.fastEffectsSpec()),
            exit = fadeOut(motion.fastEffectsSpec()),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(top = 12.dp)
        ) {
            val position = swipeSeekPositionMs ?: 0L
            SwipeSeekHud(
                positionMs = position,
                durationMs = swipeSeekDurationMs
            )
        }

        AnimatedVisibility(
            visible = holdSpeedLabel != null,
            enter = fadeIn(motion.fastEffectsSpec()) + scaleIn(motion.fastSpatialSpec(), initialScale = 0.9f),
            exit = fadeOut(motion.fastEffectsSpec()),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = maxHeight * 0.18f)
        ) {
            holdSpeedLabel?.let { label ->
                GesturePill {
                    Icon(
                        imageVector = Icons.Rounded.FastForward,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(20.dp)
                    )
                    Text(
                        text = label,
                        style = MaterialTheme.typography.titleSmall.merge(TabularNumbers),
                        color = Color.White
                    )
                }
            }
        }
    }
}

/** 手势提示统一的半透明胶囊；不使用模糊，避免每帧离屏渲染。 */
@Composable
private fun GesturePill(
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit
) {
    Surface(
        color = GestureFill,
        contentColor = Color.White,
        shape = CircleShape,
        modifier = modifier
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            content = content
        )
    }
}

private val GestureFill = Color.Black.copy(alpha = 0.55f)
private val GestureTrackHeight = 168.dp
private val TabularNumbers = TextStyle(fontFeatureSettings = "tnum")

@Composable
private fun SeekTimeHud(
    positionMs: Long,
    durationMs: Long,
    modifier: Modifier = Modifier
) {
    GesturePill(modifier = modifier) {
        Text(
            text = formatPlaybackTime(positionMs),
            style = MaterialTheme.typography.titleLarge.merge(TabularNumbers),
            fontWeight = FontWeight.SemiBold,
            color = Color.White
        )
        Text(
            text = "/ " + formatPlaybackTime(durationMs),
            style = MaterialTheme.typography.titleMedium.merge(TabularNumbers),
            color = Color.White.copy(alpha = 0.64f)
        )
    }
}

@Composable
private fun SwipeSeekHud(
    positionMs: Long,
    durationMs: Long,
    modifier: Modifier = Modifier
) {
    val progress = if (durationMs > 0L) {
        (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
    } else {
        0f
    }
    Column(
        modifier = modifier
            .widthIn(max = 360.dp)
            .fillMaxWidth()
            .padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        SeekTimeHud(positionMs = positionMs, durationMs = durationMs)
        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp),
            color = Color.White,
            trackColor = Color.White.copy(alpha = 0.28f),
            strokeCap = StrokeCap.Round,
            drawStopIndicator = {}
        )
    }
}

internal fun formatPlaybackTime(timeMs: Long): String {
    val totalSeconds = timeMs.coerceAtLeast(0L) / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        String.format(java.util.Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(java.util.Locale.US, "%02d:%02d", minutes, seconds)
    }
}

@Composable
private fun VolumeIndicator(
    level: Float,
    modifier: Modifier = Modifier
) {
    val volumeIcon = when {
        level <= 0f -> Icons.AutoMirrored.Filled.VolumeOff
        level <= 0.5f -> Icons.AutoMirrored.Filled.VolumeDown
        else -> Icons.AutoMirrored.Filled.VolumeUp
    }

    GestureIndicatorCard(
        icon = volumeIcon,
        value = "${(level * 100).toInt()}%",
        progress = level,
        modifier = modifier
    )
}

@Composable
private fun BrightnessIndicator(
    level: Float,
    modifier: Modifier = Modifier
) {
    val brightnessIcon = when {
        level <= 0.3f -> Icons.Filled.BrightnessLow
        level <= 0.7f -> Icons.Filled.BrightnessMedium
        else -> Icons.Filled.BrightnessHigh
    }

    GestureIndicatorCard(
        icon = brightnessIcon,
        value = "${(level * 100).toInt()}%",
        progress = level,
        modifier = modifier
    )
}

@Composable
private fun SeekIndicator(
    position: String,
    forward: Boolean,
    modifier: Modifier = Modifier
) {
    GesturePill(modifier = modifier) {
        Icon(
            imageVector = if (forward) Icons.Rounded.FastForward else Icons.Rounded.FastRewind,
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(20.dp)
        )
        Text(
            text = position,
            style = MaterialTheme.typography.titleSmall.merge(TabularNumbers),
            color = Color.White
        )
    }
}

/**
 * 音量/亮度提示：竖向胶囊自下而上填充，图标固定在底部，颜色随是否被填充覆盖切换以保持对比度。
 */
@Composable
private fun GestureIndicatorCard(
    icon: ImageVector,
    value: String,
    progress: Float,
    modifier: Modifier = Modifier
) {
    val level = progress.coerceIn(0f, 1f)
    val motion = MaterialTheme.velaMotion
    val animatedLevel by animateFloatAsState(
        targetValue = level,
        animationSpec = motion.fastSpatialSpec(),
        label = "gestureLevel"
    )
    Column(
        modifier = modifier.padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            text = value,
            style = MaterialTheme.typography.labelLarge.merge(TabularNumbers),
            color = Color.White
        )
        Box(
            modifier = Modifier
                .width(52.dp)
                .height(GestureTrackHeight)
                .clip(RoundedCornerShape(26.dp))
                .background(GestureFill),
            contentAlignment = Alignment.BottomCenter
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(animatedLevel)
                    .background(Color.White)
            )
            Icon(
                imageVector = icon,
                contentDescription = null,
                // 图标中心距底部 26dp（14dp 边距 + 半个 24dp 图标），填充越过中心后图标落在白底上，改用深色。
                tint = if (animatedLevel * GestureTrackHeight.value > 26f) Color.Black else Color.White,
                modifier = Modifier
                    .padding(bottom = 14.dp)
                    .size(24.dp)
            )
        }
    }
}
