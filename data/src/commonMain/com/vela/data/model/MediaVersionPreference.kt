package com.vela.data.model

/**
 * 「视频首选版本」：同一条目有多个版本（例如 4K 与 1080p）且未手动选择时起播哪一个。与 iOS 版规则一致。
 *
 * [id] 为持久化值；未知值按 [DEFAULT] 处理。
 */
enum class MediaVersionPreference(val id: String, val targetWidth: Int? = null) {
    /** 服务器返回的第一个版本。 */
    DEFAULT("default"),
    /** 分辨率最高，其次码率最高。 */
    BEST_RESOLUTION("best_resolution"),
    /** 码率最高，其次分辨率最高。 */
    BEST_BITRATE("best_bitrate"),
    /** 动态范围最好（杜比视界 > HDR10+ > HDR10 / HLG > SDR），其次分辨率、码率。 */
    BEST_DYNAMIC_RANGE("best_dynamic_range"),
    /** 不超过目标分辨率中最清晰的版本；都超过时取最小的一个。 */
    UHD_4K("4k", targetWidth = 3840),
    FULL_HD_1080("1080p", targetWidth = 1920),
    HD_720("720p", targetWidth = 1280);

    companion object {
        fun fromId(id: String?): MediaVersionPreference = entries.firstOrNull { it.id == id } ?: DEFAULT
    }
}

/** 按「视频首选版本」挑选版本；只有一个或为空时原样返回第一个。 */
fun List<MediaSource>.preferredVersion(preference: MediaVersionPreference): MediaSource? {
    if (size <= 1) return firstOrNull()
    fun video(source: MediaSource) = source.mediaStreams?.firstOrNull { it.type.equals("Video", ignoreCase = true) }
    fun pixels(source: MediaSource): Long = (video(source)?.width ?: 0).toLong() * (video(source)?.height ?: 0)
    fun bitrate(source: MediaSource): Long = (source.bitrate ?: 0).toLong()
    val byQuality = compareBy<MediaSource>({ pixels(it) }, { bitrate(it) })
    return when (preference) {
        MediaVersionPreference.DEFAULT -> first()
        MediaVersionPreference.BEST_RESOLUTION -> maxWithOrNull(byQuality)
        MediaVersionPreference.BEST_BITRATE -> maxWithOrNull(compareBy<MediaSource>({ bitrate(it) }, { pixels(it) }))
        MediaVersionPreference.BEST_DYNAMIC_RANGE -> maxWithOrNull(
            compareBy<MediaSource>({ dynamicRangeRank(video(it)?.videoRangeType) }, { pixels(it) }, { bitrate(it) })
        )
        MediaVersionPreference.UHD_4K, MediaVersionPreference.FULL_HD_1080, MediaVersionPreference.HD_720 -> {
            // 宽度留 10% 余量：2.39:1 电影的 1080p 版本可能编码为 1998 / 2048 宽。
            val limit = (preference.targetWidth ?: 0) * 1.1
            filter { (video(it)?.width ?: 0) <= limit }.maxWithOrNull(byQuality) ?: minWithOrNull(byQuality)
        }
    }
}

/** 服务器 `VideoRangeType` 的优先级：杜比视界 > HDR10+ > HDR10 / HLG > SDR / 未知。 */
internal fun dynamicRangeRank(range: String?): Int = when {
    range == null -> 0
    range.startsWith("DOVI") -> 3
    range == "HDR10Plus" -> 2
    range == "HDR10" || range == "HLG" -> 1
    else -> 0
}
