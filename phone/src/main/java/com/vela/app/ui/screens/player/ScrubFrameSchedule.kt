package com.vela.app.ui.screens.player

import kotlin.math.abs

/** 同一缓存格的时间宽度，单位毫秒。格内重复拖动直接复用已抽出的关键帧。 */
internal const val SCRUB_FRAME_BUCKET_MS = 1_000L

/** 先拿来顶上的邻近缓存最大距离，单位毫秒。更远的旧帧不再当作当前预览。 */
internal const val SCRUB_FRAME_NEAR_MS = 8_000L

/**
 * 内存里保留的预览帧数量（按关键帧去重后计）。超出后先丢最久未用的现场抽帧，见 [ScrubFrameCache]。
 * 320px 宽的 RGB_565 帧约 115 KB，满额约 15 MB；需大于 [SCRUB_SWEEP_LEVELS] 的最细一档，
 * 网格帧固定保留后仍留出余量给拖动现场抽出的帧。
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
 * 在已缓存的关键帧里找离目标最近、且不超过 [maxDistanceMs] 的一张。
 *
 * @param keyframesMs 已缓存帧对应的关键帧时间，单位毫秒
 * @param positionMs 拖动位置，单位毫秒
 * @param maxDistanceMs 可接受的最大距离，单位毫秒；见 [scrubNearDistanceMs]
 * @return 可先显示的关键帧时间；没有足够近的缓存时为 null
 */
internal fun nearestScrubFrameKey(
    keyframesMs: Collection<Long>,
    positionMs: Long,
    maxDistanceMs: Long = SCRUB_FRAME_NEAR_MS
): Long? {
    val nearest = keyframesMs.minByOrNull { abs(it - positionMs) } ?: return null
    return nearest.takeIf { abs(it - positionMs) <= maxDistanceMs }
}

/**
 * 预览帧缓存：帧按解出的关键帧时间存放，请求位置所在的秒格只记一个指向关键帧的别名。
 *
 * 关键帧间隔通常 2～10 秒，按请求秒格直接存图时，同一张关键帧会被不同秒格各存一份，
 * 既浪费内存又把网格帧挤出缓存；按关键帧去重后每张图只存一次。
 * 后台预生成的网格帧（[put] 的 `grid`）固定保留，淘汰时先淘汰最久未用的现场抽帧，
 * 保证来回拖动之后全片仍有均匀的邻近帧可用。
 *
 * 不是线程安全的，由调用方加锁。
 *
 * @param capacity 最多保留的帧数，可在换片时调整
 * @param onEvict 帧被淘汰或因重复而丢弃时调用，由调用方决定是否回收
 */
internal class ScrubFrameCache<T : Any>(
    var capacity: Int,
    private val onEvict: (T) -> Unit
) {
    /** 关键帧时间（毫秒）→ 帧。访问顺序即淘汰顺序，最近用过的在后面。 */
    private val frames = LinkedHashMap<Long, T>(capacity, 0.75f, true)
    /** 请求秒格 → 关键帧时间（毫秒）。只记录仍在 [frames] 里的关键帧。 */
    private val aliases = HashMap<Long, Long>()
    /** 网格帧的关键帧时间，淘汰时最后考虑。 */
    private val gridKeys = HashSet<Long>()

    val size: Int get() = frames.size

    /** 请求位置所在秒格已经确定对应的帧；没有时为 null。 */
    fun exact(positionMs: Long): T? = aliases[scrubFrameBucket(positionMs)]?.let { frames[it] }

    /** 离 [positionMs] 最近、距离不超过 [maxDistanceMs] 的帧；没有时为 null。 */
    fun nearest(positionMs: Long, maxDistanceMs: Long): T? =
        nearestScrubFrameKey(frames.keys, positionMs, maxDistanceMs)?.let { frames[it] }

    /** 这张关键帧是否已缓存。抽帧器据此跳过重复解码。 */
    fun containsKeyframe(keyframeMs: Long): Boolean = frames.containsKey(keyframeMs)

    /**
     * 记下请求位置对应的关键帧，并在需要时存入新帧。
     *
     * @param positionMs 请求位置，单位毫秒
     * @param keyframeMs 实际解出的关键帧时间，单位毫秒
     * @param frame 新解出的帧；为 null 表示抽帧器发现该关键帧已缓存、没有解码
     * @param grid 是否为后台预生成的网格帧
     * @return 该关键帧当前对应的帧；关键帧未缓存且没有新帧时为 null
     */
    fun put(positionMs: Long, keyframeMs: Long, frame: T?, grid: Boolean): T? {
        val existing = frames[keyframeMs]
        val stored = when {
            existing != null -> {
                if (frame != null && frame !== existing) onEvict(frame)
                existing
            }
            frame != null -> frame.also { frames[keyframeMs] = it }
            else -> return null
        }
        aliases[scrubFrameBucket(positionMs)] = keyframeMs
        if (grid) gridKeys += keyframeMs
        trim()
        return frames[keyframeMs]
    }

    /** 清空并对所有帧调用 [onEvict]。 */
    fun clear() {
        frames.values.forEach(onEvict)
        frames.clear()
        aliases.clear()
        gridKeys.clear()
    }

    /** 超出容量时先淘汰最久未用的非网格帧；全是网格帧时才淘汰最久未用的网格帧。 */
    private fun trim() {
        while (frames.size > capacity) {
            val victim = frames.keys.firstOrNull { it !in gridKeys } ?: frames.keys.first()
            frames.remove(victim)?.let(onEvict)
            gridKeys -= victim
            aliases.values.removeAll { it == victim }
        }
    }
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

/** 直链预生成要求主播放器至少有这么多前向缓冲，单位毫秒；不足时先让主播放下载。 */
internal const val SCRUB_SWEEP_MIN_BUFFER_MS = 20_000L

/** 接近片尾时从剩余时长里扣掉的余量，单位毫秒；demuxer 报告的缓冲通常到不了片尾最后一点。 */
private const val SCRUB_SWEEP_END_MARGIN_MS = 2_000L

/**
 * 主播放器前向缓冲不足、预生成应先暂停时为 true。
 *
 * @param bufferAheadMs 播放位置之后已缓冲的时长，单位毫秒
 * @param remainingMs 播放位置到片尾的时长，单位毫秒；片长未知（≤ 0）时不让位
 */
internal fun scrubSweepShouldYieldToBuffer(bufferAheadMs: Long, remainingMs: Long): Boolean {
    if (remainingMs <= 0L) return false
    val required = minOf(SCRUB_SWEEP_MIN_BUFFER_MS, remainingMs - SCRUB_SWEEP_END_MARGIN_MS)
    return bufferAheadMs < required
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
