package com.vela.app.ui.screens.player

import kotlin.math.abs

/** 同一缓存格的时间宽度，单位毫秒。格内重复拖动直接复用已抽出的关键帧。 */
internal const val SCRUB_FRAME_BUCKET_MS = 1_000L

/** 先拿来顶上的邻近缓存最大距离，单位毫秒。更远的旧帧不再当作当前预览。 */
internal const val SCRUB_FRAME_NEAR_MS = 8_000L

/**
 * 内存里保留的预览帧数量。超出后丢掉最久未用的一帧。
 * 320px 宽的 RGB_565 帧约 115 KB，满额约 15 MB；需大于 [SCRUB_SWEEP_LEVELS] 的最细一档。
 */
internal const val SCRUB_FRAME_CACHE_CAPACITY = 128

/** 低内存设备的缓存上限；这类设备也不做后台预生成。 */
internal const val SCRUB_FRAME_CACHE_CAPACITY_LOW_RAM = 32

/**
 * 后台预生成缩略图网格的分档：先全片均分 12 张，再逐档加密到 96 张。
 * 由粗到细保证预生成刚开始不久，任意位置就已有一张不太远的预览。
 */
internal val SCRUB_SWEEP_LEVELS = listOf(12, 24, 48, 96)

/** 开播后延迟预热抽帧器，避开播放器自己打开片源的那一下。单位毫秒。 */
internal const val SCRUB_FRAME_WARM_DELAY_MS = 150L

/**
 * 把播放位置收成缓存格。
 *
 * @param positionMs 目标位置，单位毫秒
 * @return 从 0 开始的秒级格号
 */
internal fun scrubFrameBucket(positionMs: Long): Long {
    return positionMs.coerceAtLeast(0L) / SCRUB_FRAME_BUCKET_MS
}

/**
 * 在已缓存的格子里找离目标最近、且不超过 [maxDistanceMs] 的一格。
 *
 * @param buckets 已有帧的格号
 * @param positionMs 拖动位置，单位毫秒
 * @param maxDistanceMs 可接受的最大距离，单位毫秒；见 [scrubNearDistanceMs]
 * @return 可先显示的格号；没有足够近的缓存时为 null
 */
internal fun nearestScrubFrameBucket(
    buckets: Set<Long>,
    positionMs: Long,
    maxDistanceMs: Long = SCRUB_FRAME_NEAR_MS
): Long? {
    if (buckets.isEmpty()) return null
    val target = scrubFrameBucket(positionMs)
    val nearest = buckets.minBy { bucket -> abs(bucket - target) }
    val distanceMs = abs(nearest - target) * SCRUB_FRAME_BUCKET_MS
    return nearest.takeIf { distanceMs <= maxDistanceMs }
}

/**
 * 邻近帧可接受的最大距离：至少 [SCRUB_FRAME_NEAR_MS]，长片放宽到网格最细一档的间距，
 * 让预生成的网格帧在拖到任意位置时都能立即顶上。
 *
 * @param durationMs 片长，单位毫秒；未知时传 0
 */
internal fun scrubNearDistanceMs(durationMs: Long): Long {
    val gridGap = if (durationMs > 0L) durationMs / SCRUB_SWEEP_LEVELS.last() else 0L
    return maxOf(SCRUB_FRAME_NEAR_MS, gridGap)
}

/**
 * 后台预生成的取帧位置，按 [SCRUB_SWEEP_LEVELS] 由粗到细排列，同一秒格只出现一次。
 *
 * @param durationMs 片长，单位毫秒
 * @return 取帧位置，单位毫秒；片长未知时为空
 */
internal fun scrubSweepPositions(durationMs: Long): List<Long> {
    if (durationMs <= 0L) return emptyList()
    // 各档共用最细一档的格子：粗档按步长从中抽取，总帧数不超过最细档，不会重复取帧。
    val finest = SCRUB_SWEEP_LEVELS.last()
    val pickedCells = LinkedHashSet<Int>()
    for (count in SCRUB_SWEEP_LEVELS) {
        val stride = finest / count
        for (index in 0 until count) pickedCells += index * stride + stride / 2
    }
    val seen = HashSet<Long>()
    return pickedCells.mapNotNull { cell ->
        // 取格子中点，避开片头黑场和片尾字幕的边界。
        val position = (durationMs * (2L * cell + 1L)) / (2L * finest)
        position.takeIf { seen.add(scrubFrameBucket(it)) }
    }
}

/**
 * 判断这次定位是不是还停在上一张关键帧上。
 *
 * @param lastSyncUs 上一张同步样本时间，单位微秒；没有时为负
 * @param syncUs 这次 seek 到的同步样本时间，单位微秒
 * @return 可以跳过解码时为 true
 */
internal fun shouldReuseScrubSync(lastSyncUs: Long, syncUs: Long): Boolean {
    return syncUs >= 0L && syncUs == lastSyncUs
}

/**
 * 按长边上限算出预览尺寸，保持画面比例并考虑容器里的旋转。
 *
 * @param width 画面宽度，单位像素
 * @param height 画面高度，单位像素
 * @param rotationDegrees 顺时针旋转，90 和 270 会交换宽高
 * @param maxEdgePx 长边上限，单位像素
 * @return 预览宽高；输入非法时为 null
 */
internal fun scrubPreviewSize(width: Int, height: Int, rotationDegrees: Int, maxEdgePx: Int): Pair<Int, Int>? {
    if (width <= 0 || height <= 0 || maxEdgePx <= 0) return null
    val quarterTurn = rotationDegrees == 90 || rotationDegrees == 270
    val orientedW = if (quarterTurn) height else width
    val orientedH = if (quarterTurn) width else height
    val scale = minOf(maxEdgePx.toFloat() / orientedW, maxEdgePx.toFloat() / orientedH)
    return (orientedW * scale).toInt().coerceAtLeast(1) to (orientedH * scale).toInt().coerceAtLeast(1)
}
