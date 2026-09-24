package com.vela.app.ui.screens.player

import android.graphics.Bitmap
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaDataSource
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import androidx.media3.common.util.UnstableApi
import java.util.Locale
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 为进度预览保持一条独立的硬解抽帧管线。
 *
 * 抽帧器打开一次后就留着。拖动落在同一张关键帧上时只做一次定位并复用上一张图；
 * 跨关键帧才硬解这一帧。解码器输出直接渲染到 [ScrubFrameRenderer] 的 Surface，
 * 在 GPU 上缩到预览尺寸，不把整帧映射回 CPU。直链通过播放器的缓存数据源读取，
 * 已缓冲的区间不再走网络。
 */
@UnstableApi
internal class ScrubFrameGrabber {
    private val alive = AtomicBoolean(true)
    private val thread = HandlerThread("vela-scrub-frame").apply { start() }
    private val handler = Handler(thread.looper)

    /** 当前已打开的片源标识。换片时清空。 */
    private var sourceKey: String? = null
    private var extractor: MediaExtractor? = null
    /** 直链场景下交给解复用器的数据源。本地文件时为 null。 */
    private var dataSource: MediaDataSource? = null
    /** 已选中的视频轨序号。没有选中时为 -1。 */
    private var videoTrack = -1
    private var codec: MediaCodec? = null
    /** 解码器对应的 MIME。换轨时重建解码器。 */
    private var codecMime: String? = null
    /** 解码器输出画面的宽高，单位像素。来自轨道格式。 */
    private var frameWidth = 0
    private var frameHeight = 0
    /** 容器里的画面旋转，单位度。0、90、180、270。Surface 输出时只用来算预览宽高。 */
    private var rotationDegrees = 0
    /** 上一张已经解出的同步样本时间，单位微秒。未解出时为负。 */
    private var lastSyncUs = NO_SYNC
    private var lastBitmap: Bitmap? = null
    /** 硬解建不起来时，这一路片源不再重复尝试。 */
    private var hardwareUnavailable = false
    /** GPU 缩图器。首次解码时建立，跨片源复用，只在 [release] 时销毁。 */
    private var renderer: ScrubFrameRenderer? = null
    /** EGL 建不起来时不再重试。 */
    private var rendererUnavailable = false

    /**
     * 取目标时间附近的关键帧预览。
     *
     * 同一张关键帧会复制一张小图返回，调用方可以自己缓存或回收。
     *
     * @param source 播放地址和请求头
     * @param positionMs 目标位置，单位毫秒
     * @param maxEdgePx 预览长边上限，单位像素
     * @return 预览位图；片源打不开或这一帧解不出来时为 null
     */
    fun frameAt(source: ScrubPreviewSource, positionMs: Long, maxEdgePx: Int): Bitmap? {
        if (!alive.get()) return null
        val task = FutureTask { grab(source, positionMs, maxEdgePx) }
        if (!handler.post(task)) return null
        return runCatching { task.get(GRAB_WAIT_MS, TimeUnit.MILLISECONDS) }.getOrNull()
    }

    /**
     * 丢掉当前片源的解复用器和解码器，线程和 GPU 缩图器保留给下一部片子。
     */
    fun reset() {
        if (!alive.get()) return
        val task = FutureTask { closeSource() }
        if (!handler.post(task)) return
        runCatching { task.get(GRAB_WAIT_MS, TimeUnit.MILLISECONDS) }
    }

    /**
     * 关闭抽帧线程。调用后不能再取帧。
     */
    fun release() {
        if (!alive.compareAndSet(true, false)) return
        handler.post {
            closeSource()
            renderer?.release()
            renderer = null
        }
        thread.quitSafely()
    }

    private fun grab(source: ScrubPreviewSource, positionMs: Long, maxEdgePx: Int): Bitmap? {
        if (!alive.get()) return null
        if (!ensureSource(source)) return null
        val extractor = extractor ?: return null
        val timeUs = positionMs.coerceAtLeast(0L) * 1_000L
        extractor.seekTo(timeUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
        val syncUs = extractor.sampleTime
        if (shouldReuseScrubSync(lastSyncUs, syncUs)) {
            return lastBitmap?.takeIf { !it.isRecycled }?.safeCopy()
        }
        if (hardwareUnavailable) return null
        val renderer = ensureRenderer() ?: return null
        val codec = ensureCodec(extractor, renderer) ?: return null
        val bitmap = decodeKeyframe(extractor, codec, renderer, maxEdgePx) ?: run {
            releaseCodec()
            return null
        }
        lastSyncUs = syncUs
        lastBitmap?.takeIf { !it.isRecycled }?.recycle()
        lastBitmap = bitmap
        return bitmap.safeCopy()
    }

    /**
     * 打开片源并选中第一条视频轨。已经打开同一地址时什么都不做。
     *
     * 直链优先走播放器缓存数据源；本地文件直接交给系统。
     *
     * @param source 播放地址和请求头
     * @return 片源可用时为 true
     */
    private fun ensureSource(source: ScrubPreviewSource): Boolean {
        val key = source.uri.toString()
        if (key == sourceKey && extractor != null) return true
        closeSource()
        val opened = MediaExtractor()
        val cached = source.dataSourceFactory
            ?.takeIf { source.uri.scheme?.lowercase(Locale.ROOT) in REMOTE_SCHEMES }
            ?.let { ScrubMediaDataSource(it, source.uri, source.cacheKey) }
        try {
            if (cached != null) {
                opened.setDataSource(cached)
            } else {
                val headers = source.requestHeaders.takeIf { it.isNotEmpty() }
                opened.setDataSource(source.context, source.uri, headers)
            }
        } catch (error: Exception) {
            Log.w(TAG, "scrub extractor open failed: ${error.javaClass.simpleName}")
            opened.release()
            cached?.runCatching { close() }
            return false
        }
        val track = selectVideoTrack(opened)
        if (track < 0) {
            opened.release()
            cached?.runCatching { close() }
            return false
        }
        opened.selectTrack(track)
        extractor = opened
        dataSource = cached
        videoTrack = track
        sourceKey = key
        hardwareUnavailable = false
        return true
    }

    /**
     * 建立 GPU 缩图器。失败一次后不再尝试。
     *
     * @return 可用的缩图器；EGL 不可用时为 null
     */
    private fun ensureRenderer(): ScrubFrameRenderer? {
        renderer?.let { return it }
        if (rendererUnavailable) return null
        return try {
            ScrubFrameRenderer().also { renderer = it }
        } catch (error: Exception) {
            Log.w(TAG, "scrub renderer init failed: ${error.message}")
            rendererUnavailable = true
            null
        }
    }

    /**
     * 按当前视频轨建立硬解码器，输出到缩图器的 Surface。
     *
     * 直接沿用轨道格式 configure，保留色彩标准、范围和 HDR 元数据，
     * 让 Surface 上的画面按正确的色彩空间采样。
     *
     * @param extractor 已经选中视频轨的解复用器
     * @param renderer 提供输出 Surface 的缩图器
     * @return 可复用的解码器；这一轨没有硬解时为 null
     */
    private fun ensureCodec(extractor: MediaExtractor, renderer: ScrubFrameRenderer): MediaCodec? {
        val format = extractor.getTrackFormat(videoTrack.takeIf { it >= 0 } ?: return null)
        val mime = format.getString(MediaFormat.KEY_MIME) ?: return null
        codec?.takeIf { codecMime == mime }?.let { return it }
        releaseCodec()
        val created = createHardwareDecoder(mime) ?: run {
            hardwareUnavailable = true
            return null
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            format.setInteger(MediaFormat.KEY_PRIORITY, 0)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            format.setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
        }
        return try {
            created.configure(format, renderer.surface, null, 0)
            created.start()
            frameWidth = format.getInteger(MediaFormat.KEY_WIDTH)
            frameHeight = format.getInteger(MediaFormat.KEY_HEIGHT)
            rotationDegrees = format.rotationDegrees()
            codec = created
            codecMime = mime
            created
        } catch (error: Exception) {
            Log.w(TAG, "scrub decoder configure failed: ${error.javaClass.simpleName}")
            created.release()
            hardwareUnavailable = true
            null
        }
    }

    /**
     * 把当前同步样本送进解码器，渲染到 Surface 后缩成预览图。
     *
     * 只送这一张关键帧，再送一个结束标记迫使解码器立刻出图。
     *
     * @param extractor 已经 seek 到同步样本
     * @param codec 已启动的解码器
     * @param renderer 接收解码输出的缩图器
     * @param maxEdgePx 预览长边上限，单位像素
     * @return 预览位图；超时时为 null
     */
    private fun decodeKeyframe(
        extractor: MediaExtractor,
        codec: MediaCodec,
        renderer: ScrubFrameRenderer,
        maxEdgePx: Int
    ): Bitmap? {
        val (dstW, dstH) = scrubPreviewSize(frameWidth, frameHeight, rotationDegrees, maxEdgePx) ?: return null
        codec.flush()
        if (!queueSample(extractor, codec, endOfStream = false)) return null
        if (!queueSample(extractor, codec, endOfStream = true)) return null
        val deadline = System.nanoTime() + DECODE_BUDGET_NS
        val info = MediaCodec.BufferInfo()
        while (System.nanoTime() < deadline) {
            val output = codec.dequeueOutputBuffer(info, OUTPUT_WAIT_US)
            if (output < 0) continue
            if (info.size <= 0) {
                // 结束标记单独占一个空缓冲，直接归还。
                codec.releaseOutputBuffer(output, false)
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                continue
            }
            renderer.beginFrame()
            codec.releaseOutputBuffer(output, true)
            return renderer.render(info.presentationTimeUs, deadline, dstW, dstH)
        }
        return null
    }

    /**
     * 把当前样本或结束标记送进解码器。
     *
     * @param extractor 样本来源。结束标记不读取它
     * @param codec 解码器
     * @param endOfStream 为 true 时送空的结束标记
     * @return 成功排队时为 true
     */
    private fun queueSample(
        extractor: MediaExtractor,
        codec: MediaCodec,
        endOfStream: Boolean
    ): Boolean {
        val index = codec.dequeueInputBuffer(INPUT_WAIT_US)
        if (index < 0) return false
        if (endOfStream) {
            codec.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            return true
        }
        val buffer = codec.getInputBuffer(index) ?: return false
        buffer.clear()
        val size = extractor.readSampleData(buffer, 0)
        if (size < 0) return false
        codec.queueInputBuffer(
            index,
            0,
            size,
            extractor.sampleTime,
            MediaCodec.BUFFER_FLAG_KEY_FRAME
        )
        return true
    }

    /** 释放当前片源占用的解复用器、数据源、解码器和上一张关键帧。 */
    private fun closeSource() {
        releaseCodec()
        extractor?.release()
        extractor = null
        dataSource?.runCatching { close() }
        dataSource = null
        videoTrack = -1
        sourceKey = null
        lastSyncUs = NO_SYNC
        lastBitmap?.takeIf { !it.isRecycled }?.recycle()
        lastBitmap = null
        hardwareUnavailable = false
        rotationDegrees = 0
        frameWidth = 0
        frameHeight = 0
    }

    /** 停止并释放解码器。 */
    private fun releaseCodec() {
        codec?.runCatching {
            stop()
            release()
        }
        codec = null
        codecMime = null
    }

    private companion object {
        private const val TAG = "ScrubFrameGrabber"
        /** 尚未解出过同步样本。 */
        private const val NO_SYNC = -1L
        /** 等抽帧线程返回的上限，避免解码器卡死时拖死预览。单位毫秒。 */
        private const val GRAB_WAIT_MS = 1_500L
        /** 单次硬解出图的时间预算，包含等 Surface 收到帧。单位纳秒。 */
        private const val DECODE_BUDGET_NS = 400_000_000L
        /** 等输入缓冲区的时间。单位微秒。 */
        private const val INPUT_WAIT_US = 8_000L
        /** 等一帧输出的时间。单位微秒。 */
        private const val OUTPUT_WAIT_US = 8_000L
        /** 走播放器缓存数据源的地址协议。 */
        private val REMOTE_SCHEMES = setOf("http", "https")

        /**
         * 选择第一条视频轨。
         *
         * @param extractor 已打开的解复用器
         * @return 轨序号；没有视频轨时为 -1
         */
        private fun selectVideoTrack(extractor: MediaExtractor): Int {
            for (index in 0 until extractor.trackCount) {
                val mime = extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME).orEmpty()
                if (mime.startsWith("video/")) return index
            }
            return -1
        }

        /**
         * 找一个非安全的硬解码器。
         *
         * @param mime 视频 MIME
         * @return 硬解码器；系统只有软解时为 null
         */
        private fun createHardwareDecoder(mime: String): MediaCodec? {
            val candidates = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
            for (info in candidates) {
                if (info.isEncoder || info.isSecureDecoder() || !info.supports(mime) || !info.isHardwareDecoder()) {
                    continue
                }
                return runCatching { MediaCodec.createByCodecName(info.name) }.getOrNull()
            }
            return null
        }

        /** 这个解码器是不是硬解。API 29 以下用名字排除系统软解。 */
        private fun MediaCodecInfo.isHardwareDecoder(): Boolean {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) return isHardwareAccelerated
            val normalized = name.lowercase()
            return !normalized.startsWith("omx.google.") && !normalized.startsWith("c2.android.")
        }

        /** 安全解码器只服务 DRM 内容，明文片源用它会失败。 */
        private fun MediaCodecInfo.isSecureDecoder(): Boolean {
            return name.lowercase().endsWith(".secure")
        }

        /** 解码器是否支持这种 MIME。 */
        private fun MediaCodecInfo.supports(mime: String): Boolean {
            return supportedTypes.any { it.equals(mime, ignoreCase = true) }
        }

        /** 读取画面旋转。缺失时为 0 度。 */
        private fun MediaFormat.rotationDegrees(): Int {
            if (!containsKey(MediaFormat.KEY_ROTATION)) return 0
            return getInteger(MediaFormat.KEY_ROTATION)
        }
    }
}

/** 复制一张互不影响回收的预览图。原图已回收时为 null。 */
private fun Bitmap.safeCopy(): Bitmap? {
    if (isRecycled) return null
    return copy(Bitmap.Config.ARGB_8888, false)
}
