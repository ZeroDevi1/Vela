package com.vela.app.player.mpv

/**
 * 识别 ctts 写坏的片源：码流有 B 帧，但容器把 pts 写成「dts + 常数」（解码顺序）。
 *
 * 硬件解码器按显示顺序出帧、只回传送入时的 pts，于是每个 B 帧组都会出现一次时间戳倒退，
 * mpv 报 `Invalid video timestamp` 并丢帧；软解有重排后的 dts 可用，mpv 会自动改用 dts，不受影响。
 * 达到阈值后，由调用方根据帧率选择修复方式。
 *
 * 非线程安全；事件线程和加载线程通过同一把锁访问。每次换片调用 [reset]。
 */
internal class MpvBrokenPtsDetector(
    /** 窗口内需要累计的警告次数。正常片源只在不连续点偶发一次；坏片源约每 4 帧一次（30fps 下约 7 次/秒）。 */
    private val threshold: Int = 4,
    /** 统计窗口，单位毫秒。 */
    private val windowMs: Long = 2_000L
) {
    private val hits = ArrayDeque<Long>()

    /** 本片源是否已判定为 pts 损坏；判定后不再重复触发。 */
    var triggered = false
        private set

    fun reset() {
        hits.clear()
        triggered = false
    }

    /**
     * 处理一条 mpv 日志。
     *
     * @param nowMs 单调时钟毫秒数。
     * @return 本次刚达到阈值时为 true（每个片源最多一次）。
     */
    fun onLog(prefix: String, text: String, nowMs: Long): Boolean {
        if (triggered || !matches(prefix, text)) return false
        hits.addLast(nowMs)
        while (hits.isNotEmpty() && nowMs - hits.first() > windowMs) hits.removeFirst()
        if (hits.size < threshold) return false
        triggered = true
        hits.clear()
        return true
    }

    companion object {
        fun matches(prefix: String, text: String): Boolean =
            prefix == "cplayer" && text.startsWith("Invalid video timestamp")
    }
}
