package com.vela.app.player.mpv

import com.vela.data.model.MediaStream
import kotlin.math.roundToInt

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

    /** 高吞吐片源的像素率下限：3840x2160 48fps，单位像素/秒。 */
    private const val HIGH_THROUGHPUT_PIXEL_RATE = 3840L * 2160L * 48L

    /** `operating-rate` 相对片源帧率的倍数，对应长按倍速和最常用的 2x。 */
    private const val OPERATING_RATE_SPEED_HEADROOM = 2f

    /** 倍速后上屏帧率超过该值（帧/秒）时改用轻量渲染；取所有屏幕都支持的 60Hz。 */
    private const val FAST_RENDER_OUTPUT_FPS = 60.0

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

    /**
     * 是否改用轻量渲染（bilinear 缩放、不去色带）：超高像素片源，或倍速后上屏帧率超过 60fps。
     *
     * 后一种情况下帧数已超过多数屏幕的刷新率，渲染线程每帧的时间预算不足 16ms；高阶缩放和去色带
     * 一旦让某帧超时，后续帧会被连续判为迟到而丢弃。实测 4K 60fps 片源 2x（屏幕 90Hz）用
     * lanczos/hermite + deband 时上屏在 36~86fps 间波动，轻量渲染稳定在 88fps。
     *
     * @param videoFps 片源帧率，未知时传 0，此时只看 [heavySource]
     */
    fun needsFastRender(heavySource: Boolean, videoFps: Double, speed: Double): Boolean {
        if (heavySource) return true
        return MpvPlaybackTuning.isHighSpeed(speed) && videoFps * speed > FAST_RENDER_OUTPUT_FPS
    }

    /**
     * 解码负载接近硬解上限的片源：像素率（宽 × 高 × 帧率）达到 4K 48fps，即 4K 50/60fps 及以上。
     *
     * 这类片源 2x 时要解 4K 120fps，已是旗舰 SoC 硬解的规格上限（实测 SM8475 上
     * `mediacodec-copy` 约 118fps，够不到 120）。视频一旦落后音频超过一帧，mpv 的 vo 丢帧会把
     * 所有帧判为迟到，上屏退化到约 10fps，因此要走零拷贝并声明 [decoderOperatingRate]。
     * 分辨率或帧率未知时返回 false。
     */
    fun isHighThroughputSource(mediaStreams: List<MediaStream>?): Boolean {
        val video = videoStream(mediaStreams) ?: return false
        val pixels = (video.width?.toLong() ?: return false) * (video.height?.toLong() ?: return false)
        val fps = frameRate(video) ?: return false
        return pixels * fps >= HIGH_THROUGHPUT_PIXEL_RATE
    }

    /**
     * 高吞吐片源声明给 MediaCodec 的 `operating-rate`（帧/秒），取片源帧率的 2 倍；其余片源为 null。
     *
     * 不声明时高通解码器按片源帧率定时钟，提速后要过几秒才升频，这段时间视频落后于音频。
     * 只能在解码器配置时指定，所以按常用的 2x 预留；代价是这类片源 1x 播放时解码器也跑在高频档，
     * 因此不对普通片源声明。
     */
    fun decoderOperatingRate(mediaStreams: List<MediaStream>?): Int? {
        if (!isHighThroughputSource(mediaStreams)) return null
        val fps = videoStream(mediaStreams)?.let(::frameRate) ?: return null
        return (fps * OPERATING_RATE_SPEED_HEADROOM).roundToInt()
    }

    private fun videoStream(mediaStreams: List<MediaStream>?): MediaStream? {
        return mediaStreams.orEmpty().firstOrNull { it.type.equals("Video", ignoreCase = true) }
    }

    private fun frameRate(video: MediaStream): Float? {
        return (video.realFrameRate ?: video.averageFrameRate)?.takeIf { it > 0f }
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
