package com.vela.app.player.mpv

internal object MpvPlaybackTuning {
    private const val NORMAL_SPEED = 1.0
    private const val HIGH_SPEED_EPSILON = 0.01
    private const val SKIP_LOOP_FILTER_SPEED = 2.0
    private const val MAX_SURFACE_FRAME_RATE = 240.0

    /** 高码率文件耗尽缓存后攒够一段再恢复，避免默认 1 秒缓存导致几秒一次碎片卡顿。 */
    fun cachePauseWaitSeconds(bitrate: Int?, cacheTimeSeconds: Int): Int {
        val requested = if ((bitrate ?: 0) >= 40_000_000) 8 else 1
        return requested.coerceAtMost(cacheTimeSeconds.coerceAtLeast(1))
    }

    fun seekFlags(exact: Boolean, preferFastSeek: Boolean): String {
        // absolute 默认也是精确跳转；快速路径必须显式指定 keyframes。
        return if (exact && !preferFastSeek) "absolute+exact" else "absolute+keyframes"
    }

    fun isHighSpeed(speed: Double): Boolean {
        return speed > NORMAL_SPEED + HIGH_SPEED_EPSILON
    }

    fun interpolation(smoothMotion: Boolean, speed: Double): String {
        return if (smoothMotion && !isHighSpeed(speed)) "yes" else "no"
    }

    fun videoSync(smoothMotion: Boolean, speed: Double): String {
        return if (smoothMotion && !isHighSpeed(speed)) "display-resample" else "audio"
    }

    /**
     * 倍速时也只在 vo 端丢帧：decoder 丢帧按解码进度随机丢 B 帧，配合硬解时出帧间隔忽长忽短，
     * 2x 下画面明显一顿一顿；vo 端只丢真正迟到的帧，节奏更均匀。
     */
    const val FRAMEDROP = "vo"

    /**
     * 软解高倍速时跳过非关键帧的环路滤波，省下约 20~30% CPU，画质损失在快进中不明显。
     * 不再跳过非参考帧（`vd-lavc-skipframe=nonref`）：b-pyramid 片源约一半帧是非参考 B 帧，
     * 跳掉后出帧间隔不均，2x 下比轻微掉帧更不连贯。硬解不受这两个选项影响。
     */
    fun skipLoopFilter(speed: Double): Boolean {
        return speed >= SKIP_LOOP_FILTER_SPEED
    }

    /**
     * 向系统声明的帧率取实际上屏帧率（片源帧率 × 倍速）。
     *
     * 只按片源帧率声明时，60fps 片源 2x 需要 120fps 上屏，屏幕却被锁在 60Hz，mpv 只能每隔一两帧
     * 丢一帧，画面卡顿不连贯；24/30fps 片源同理会落到不能整除的刷新率上。未知或异常时返回 null。
     */
    fun surfaceFrameRate(videoFps: Double, speed: Double): Float? {
        if (videoFps !in 1.0..MAX_SURFACE_FRAME_RATE) return null
        val effective = videoFps * speed.coerceAtLeast(NORMAL_SPEED)
        return effective.coerceAtMost(MAX_SURFACE_FRAME_RATE).toFloat()
    }
}
