package com.vela.app.ui.screens.player

import android.content.Context
import android.content.res.Configuration
import android.net.TrafficStats
import android.os.BatteryManager
import android.os.Process
import android.os.SystemClock
import android.text.format.DateFormat
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vela.player.preferences.OsdOptions
import com.vela.player.preferences.PlayerBehaviorPreferences
import kotlinx.coroutines.delay
import java.util.Date
import java.util.Locale

/**
 * 屏幕左上角的 OSD 信息（与 iOS「界面偏好 → OSD 信息显示」相同的项目）：控制层隐藏时也常驻显示，每秒刷新一次。
 * 网速为设备总下行流量（含其他应用）；CPU 为本进程所有线程的占用之和，多核时可超过 100%。
 */
@Composable
internal fun BoxScope.PlayerOsdOverlay(
    playbackSpeed: Float,
    positionMs: () -> Long,
    durationMs: () -> Long,
    sampleFrameRate: () -> Double?
) {
    val context = LocalContext.current
    val options = remember { PlayerBehaviorPreferences(context).osd }
    val landscape = LocalConfiguration.current.orientation != Configuration.ORIENTATION_PORTRAIT
    if (!options.hasContent || (options.landscapeOnly && !landscape)) return

    var lines by remember { mutableStateOf(emptyList<String>()) }
    val currentSpeed by androidx.compose.runtime.rememberUpdatedState(playbackSpeed)
    LaunchedEffect(options) {
        val timeFormat = DateFormat.getTimeFormat(context)
        var lastRx = TrafficStats.getTotalRxBytes()
        var lastCpu = Process.getElapsedCpuTime()
        var lastTime = SystemClock.elapsedRealtime()
        while (true) {
            val now = SystemClock.elapsedRealtime()
            val elapsed = (now - lastTime).coerceAtLeast(1L)
            val rx = TrafficStats.getTotalRxBytes()
            val cpu = Process.getElapsedCpuTime()
            lines = osdLines(
                options = options,
                clock = timeFormat.format(Date()),
                frameRate = if (options.frameRate) sampleFrameRate() else null,
                networkBytesPerSecond = if (rx >= 0 && lastRx >= 0) (rx - lastRx).coerceAtLeast(0L) * 1000.0 / elapsed else null,
                playbackSpeed = currentSpeed,
                positionMs = positionMs(),
                durationMs = durationMs(),
                cpuPercent = (cpu - lastCpu).coerceAtLeast(0L) * 100.0 / elapsed,
                batteryPercent = readBattery(context)
            )
            lastRx = rx
            lastCpu = cpu
            lastTime = now
            delay(1_000)
        }
    }
    if (lines.isEmpty()) return
    val fontSize = when (options.textSize) {
        PlayerBehaviorPreferences.OSD_TEXT_SMALL -> 11.sp
        PlayerBehaviorPreferences.OSD_TEXT_LARGE -> 15.sp
        else -> 13.sp
    }
    Box(
        modifier = Modifier
            .align(Alignment.TopStart)
            .let { if (options.hugEdges) it else it.windowInsetsPadding(WindowInsets.displayCutout) }
            .padding(horizontal = if (options.hugEdges) 4.dp else 16.dp, vertical = 8.dp)
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            lines.forEach { line ->
                Text(
                    text = line,
                    color = Color.White,
                    fontSize = fontSize,
                    style = MaterialTheme.typography.labelMedium,
                    modifier = if (options.textBackground) {
                        Modifier
                            .background(Color.Black.copy(alpha = 0.45f), RoundedCornerShape(4.dp))
                            .padding(horizontal = 5.dp, vertical = 1.dp)
                    } else {
                        Modifier
                    }
                )
            }
        }
    }
}

/** OSD 文本，顺序与 iOS 相同；取不到的数据不显示。 */
internal fun osdLines(
    options: OsdOptions,
    clock: String,
    frameRate: Double?,
    networkBytesPerSecond: Double?,
    playbackSpeed: Float,
    positionMs: Long,
    durationMs: Long,
    cpuPercent: Double,
    batteryPercent: Int
): List<String> = buildList {
    if (options.clock) add(clock)
    if (options.frameRate && frameRate != null) add(String.format(Locale.US, "%.1f fps", frameRate))
    if (options.networkSpeed && networkBytesPerSecond != null) add(formatTransferRate(networkBytesPerSecond))
    if (options.playbackSpeed) add(String.format(Locale.US, "%.2f", playbackSpeed).trimEnd('0').trimEnd('.') + "x")
    if (options.playbackTime) add("${osdTime(positionMs)} / ${osdTime(durationMs)}")
    if (options.cpuUsage) add(String.format(Locale.US, "CPU %.0f%%", cpuPercent))
    if (options.battery && batteryPercent in 0..100) add("$batteryPercent%")
}

private fun osdTime(ms: Long): String {
    val total = (ms.coerceAtLeast(0L)) / 1000
    val hours = total / 3600
    return if (hours > 0) {
        String.format(Locale.US, "%d:%02d:%02d", hours, (total % 3600) / 60, total % 60)
    } else {
        String.format(Locale.US, "%02d:%02d", total / 60, total % 60)
    }
}

private fun readBattery(context: Context): Int {
    val manager = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
    return manager?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
}
