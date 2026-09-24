package com.vela.app.ui.screens.player

import kotlin.math.abs

/** 同一缓存格的时间宽度，单位毫秒。格内重复拖动直接复用已抽出的关键帧。 */
internal const val SCRUB_FRAME_BUCKET_MS = 1_000L

/** 先拿来顶上的邻近缓存最大距离，单位毫秒。更远的旧帧不再当作当前预览。 */
internal const val SCRUB_FRAME_NEAR_MS = 8_000L

/** 内存里保留的预览帧数量。超出后丢掉最久未用的一帧。 */
internal const val SCRUB_FRAME_CACHE_CAPACITY = 32

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
 * 在已缓存的格子里找离目标最近、且不超过 [SCRUB_FRAME_NEAR_MS] 的一格。
 *
 * @param buckets 已有帧的格号
 * @param positionMs 拖动位置，单位毫秒
 * @return 可先显示的格号；没有足够近的缓存时为 null
 */
internal fun nearestScrubFrameBucket(buckets: Set<Long>, positionMs: Long): Long? {
    if (buckets.isEmpty()) return null
    val target = scrubFrameBucket(positionMs)
    val nearest = buckets.minBy { bucket -> abs(bucket - target) }
    val distanceMs = abs(nearest - target) * SCRUB_FRAME_BUCKET_MS
    return nearest.takeIf { distanceMs <= SCRUB_FRAME_NEAR_MS }
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
