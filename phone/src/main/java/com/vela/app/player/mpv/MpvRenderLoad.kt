package com.vela.app.player.mpv

import com.vela.data.model.MediaStream

/**
 * 超高像素片源（5.7K / 8K VR 等）的渲染降级策略。
 *
 * 高阶缩放（lanczos / hermite）、deband、插帧会让 mpv 先把整帧画进 rgba16f 中间纹理：
 * 8192x4096 约 268MB/帧，手机 GPU 在 60fps 下撑不住；而缩到屏幕时已缩小 6 倍以上，
 * 高阶滤镜几乎看不出收益。此时全部改为 bilinear，让 vo=gpu 进入 "dumb mode" 直接采样上屏。
 * 普通片源（≤ DCI 4K 像素量）仍按用户设置。
 */
internal object MpvRenderLoad {
    /** 略高于 4096x2160，留出 4K 非标尺寸余量；5760x2880 起算超高像素。 */
    private const val HEAVY_SOURCE_PIXELS = 4096L * 2304L

    const val FAST_SCALER = "bilinear"

    /** 需要中间纹理（VR 转平面 hook）时使用；SDR 下 8bit 足够，带宽减半。 */
    const val HEAVY_FBO_FORMAT = "rgba8"
    const val DEFAULT_FBO_FORMAT = "auto"

    fun isHeavySource(mediaStreams: List<MediaStream>?): Boolean {
        val video = mediaStreams.orEmpty().firstOrNull { it.type.equals("Video", ignoreCase = true) }
            ?: return false
        val width = video.width?.toLong() ?: return false
        val height = video.height?.toLong() ?: return false
        return width * height > HEAVY_SOURCE_PIXELS
    }

    /** HDR 片源的中间纹理保留高精度，避免 8bit 色带。 */
    fun fboFormat(mediaStreams: List<MediaStream>?): String {
        if (!isHeavySource(mediaStreams)) return DEFAULT_FBO_FORMAT
        val video = mediaStreams.orEmpty().first { it.type.equals("Video", ignoreCase = true) }
        val range = "${video.videoRange} ${video.videoRangeType} ${video.colorTransfer}".lowercase()
        val hdr = listOf("hdr", "dovi", "hlg", "pq", "2084", "arib-std-b67").any { it in range }
        return if (hdr) DEFAULT_FBO_FORMAT else HEAVY_FBO_FORMAT
    }
}
