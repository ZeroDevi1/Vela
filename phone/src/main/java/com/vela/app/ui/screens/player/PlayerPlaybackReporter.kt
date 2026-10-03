package com.vela.app.ui.screens.player

import android.util.Log
import com.vela.data.repository.MediaRepository
import com.vela.shared.playback.UserDataRefreshSignals
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal class PlayerPlaybackReporter(
    private val mediaRepository: MediaRepository,
    private val scope: CoroutineScope,
    private val positionProvider: () -> Long,
    private val isPausedProvider: () -> Boolean,
    private val onScrobble: (String) -> Unit = {}
) {

    companion object {
        private const val TAG = "PlayerPlaybackReporter"
        private const val TICKS_PER_MILLISECOND = 10_000L
        private const val PROGRESS_REPORT_INTERVAL_MS = 15_000L

        /**
         * 停止上报必须在 ViewModel 清理之后仍能完成（退出播放页时 viewModelScope 随即取消），
         * 因此放在进程级作用域里执行。
         */
        private val stopReportScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    }

    private var session = PlaybackSessionContext()
    private var progressReportingJob: Job? = null
    private var hasReportedStart: Boolean = false
    private var scrobbleStarted = false
    private var scrobblePaused = false

    fun updateSession(newSession: PlaybackSessionContext) {
        session = newSession
    }

    fun hasReportedStart(): Boolean = hasReportedStart

    fun reset() {
        progressReportingJob?.cancel()
        progressReportingJob = null
        hasReportedStart = false
        scrobbleStarted = false
        session = PlaybackSessionContext()
    }

    fun reportPlaybackStatus() {
        val sessionSnapshot = session
        val mediaId = sessionSnapshot.mediaId ?: return
        if (sessionSnapshot.isOfflinePlayback) return
        if (!scrobbleStarted) {
            scrobbleStarted = true
            scrobblePaused = isPausedProvider()
            onScrobble(if (scrobblePaused) "pause" else "start")
        }
        if (hasReportedStart) return

        scope.launch {
            try {
                val result = mediaRepository.reportPlaybackStart(
                    itemId = mediaId,
                    playSessionId = sessionSnapshot.playSessionId,
                    mediaSourceId = sessionSnapshot.mediaSourceId,
                    positionTicks = positionProvider() * TICKS_PER_MILLISECOND,
                    playMethod = sessionSnapshot.playMethod.reportValue
                )
                if (result.isSuccess) {
                    hasReportedStart = true
                    startProgressReportingLoop()
                } else {
                    Log.e(
                        TAG,
                        "Failed to report playback start: ${result.exceptionOrNull()?.message}"
                    )
                }
            } catch (error: Exception) {
                Log.e(TAG, "Error reporting playback start", error)
            }
        }
    }

    fun reportPlaybackProgress() {
        if (!canReportProgress()) return
        scope.launch {
            reportPlaybackProgressNow()
        }
    }

    /**
     * 上报停止播放。
     *
     * @param refreshDelayMs 上报成功后延迟多久再广播刷新信号。退出播放页时传入转场时长，
     *   让详情页在返回动画结束后再重新拉取条目，避免动画中途整页重组掉帧
     */
    fun reportPlaybackStopped(failed: Boolean = false, refreshDelayMs: Long = 0L) {
        val sessionSnapshot = session
        val mediaId = sessionSnapshot.mediaId ?: return
        if (scrobbleStarted) {
            onScrobble(if (failed) "pause" else "stop")
            scrobbleStarted = false
        }
        if (sessionSnapshot.isOfflinePlayback || !hasReportedStart) return

        hasReportedStart = false
        progressReportingJob?.cancel()
        progressReportingJob = null

        val positionTicks = positionProvider() * TICKS_PER_MILLISECOND
        stopReportScope.launch {
            try {
                val result = mediaRepository.reportPlaybackStopped(
                    itemId = mediaId,
                    positionTicks = positionTicks,
                    playSessionId = sessionSnapshot.playSessionId,
                    mediaSourceId = sessionSnapshot.mediaSourceId,
                    failed = failed
                )
                if (result.isSuccess) {
                    if (refreshDelayMs > 0L) delay(refreshDelayMs)
                    UserDataRefreshSignals.notifyUserDataChanged(mediaId)
                } else {
                    Log.e(
                        TAG,
                        "Failed to report playback stopped: ${result.exceptionOrNull()?.message}"
                    )
                }
            } catch (error: Exception) {
                Log.e(TAG, "Error reporting playback stopped", error)
            }
        }
    }

    fun onPlaybackPauseStateChanged() {
        val paused = isPausedProvider()
        if (scrobbleStarted && paused != scrobblePaused) {
            scrobblePaused = paused
            onScrobble(if (paused) "pause" else "start")
        }
        if (hasReportedStart) {
            reportPlaybackProgress()
        }
    }

    fun onPlaybackPositionDiscontinuity() {
        if (hasReportedStart) {
            reportPlaybackProgress()
        }
    }

    private fun startProgressReportingLoop() {
        progressReportingJob?.cancel()
        progressReportingJob = scope.launch {
            while (hasReportedStart) {
                try {
                    delay(PROGRESS_REPORT_INTERVAL_MS)
                    reportPlaybackProgressNow()
                } catch (_: CancellationException) {
                    break
                } catch (error: Exception) {
                    Log.e(TAG, "Error in progress reporting loop", error)
                    break
                }
            }
        }
    }

    private fun canReportProgress(): Boolean {
        return hasReportedStart &&
            !session.isOfflinePlayback &&
            !session.mediaId.isNullOrBlank()
    }

    private suspend fun reportPlaybackProgressNow() {
        val sessionSnapshot = session
        val mediaId = sessionSnapshot.mediaId ?: return
        if (sessionSnapshot.isOfflinePlayback || !hasReportedStart) return

        try {
            val result = mediaRepository.reportPlaybackProgress(
                itemId = mediaId,
                positionTicks = positionProvider() * TICKS_PER_MILLISECOND,
                playSessionId = sessionSnapshot.playSessionId,
                mediaSourceId = sessionSnapshot.mediaSourceId,
                isPaused = isPausedProvider(),
                playMethod = sessionSnapshot.playMethod.reportValue
            )
            if (result.isFailure) {
                Log.e(
                    TAG,
                    "Failed to report playback progress: ${result.exceptionOrNull()?.message}"
                )
            }
        } catch (error: Exception) {
            Log.e(TAG, "Error reporting playback progress", error)
        }
    }
}