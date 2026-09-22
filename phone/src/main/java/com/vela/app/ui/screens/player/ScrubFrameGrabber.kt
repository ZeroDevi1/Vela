package com.vela.app.ui.screens.player

import android.graphics.Bitmap
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.Image
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.min

/**
 * 为进度预览保持一条独立的硬解抽帧管线。
 *
 * 抽帧器打开一次后就留着。拖动落在同一张关键帧上时只做一次定位并复用上一张图；
 * 跨关键帧才硬解这一帧，并直接缩到预览尺寸。不再走 MediaMetadataRetriever，
 * 避免每次拖动都重新解析片源。
 */
internal class ScrubFrameGrabber {
    private val alive = AtomicBoolean(true)
    private val thread = HandlerThread("vela-scrub-frame").apply { start() }
    private val handler = Handler(thread.looper)

    /** 当前已打开的片源标识。换片时清空。 */
    private var sourceKey: String? = null
    private var extractor: MediaExtractor? = null
    /** 已选中的视频轨序号。没有选中时为 -1。 */
    private var videoTrack = -1
    private var codec: MediaCodec? = null
    /** 解码器对应的 MIME。换轨时重建解码器。 */
    private var codecMime: String? = null
    /** 容器里的画面旋转，单位度。0、90、180、270。 */
    private var rotationDegrees = 0
    /** 上一张已经解出的同步样本时间，单位微秒。未解出时为负。 */
    private var lastSyncUs = NO_SYNC
    private var lastBitmap: Bitmap? = null
    /** 硬解建不起来时，这一路片源不再重复尝试。 */
    private var hardwareUnavailable = false

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
     * 丢掉当前片源的解复用器和解码器，线程保留给下一部片子。
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
        handler.post { closeSource() }
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
        val codec = ensureCodec(extractor) ?: return null
        val bitmap = decodeKeyframe(extractor, codec, maxEdgePx) ?: run {
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
     * @param source 播放地址和请求头
     * @return 片源可用时为 true
     */
    private fun ensureSource(source: ScrubPreviewSource): Boolean {
        val key = source.uri.toString()
        if (key == sourceKey && extractor != null) return true
        closeSource()
        val opened = MediaExtractor()
        val headers = source.requestHeaders.takeIf { it.isNotEmpty() }
        try {
            opened.setDataSource(source.context, source.uri, headers)
        } catch (error: Exception) {
            Log.w(TAG, "scrub extractor open failed: ${error.javaClass.simpleName}")
            opened.release()
            return false
        }
        val track = selectVideoTrack(opened)
        if (track < 0) {
            opened.release()
            return false
        }
        opened.selectTrack(track)
        extractor = opened
        videoTrack = track
        sourceKey = key
        hardwareUnavailable = false
        return true
    }

    /**
     * 按当前视频轨建立硬解码器。
     *
     * @param extractor 已经选中视频轨的解复用器
     * @return 可复用的解码器；这一轨没有硬解时为 null
     */
    private fun ensureCodec(extractor: MediaExtractor): MediaCodec? {
        val format = extractor.getTrackFormat(videoTrack.takeIf { it >= 0 } ?: return null)
        val mime = format.getString(MediaFormat.KEY_MIME) ?: return null
        codec?.takeIf { codecMime == mime }?.let { return it }
        releaseCodec()
        val created = createHardwareDecoder(mime) ?: run {
            hardwareUnavailable = true
            return null
        }
        val decodeFormat = MediaFormat.createVideoFormat(
            mime,
            format.getInteger(MediaFormat.KEY_WIDTH),
            format.getInteger(MediaFormat.KEY_HEIGHT)
        )
        copyCodecConfig(format, decodeFormat)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            decodeFormat.setInteger(MediaFormat.KEY_PRIORITY, 0)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            decodeFormat.setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
        }
        return try {
            created.configure(decodeFormat, null, null, 0)
            created.start()
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
     * 把当前同步样本送进解码器，并缩成预览图。
     *
     * 只送这一张关键帧，再送一个结束标记迫使解码器立刻出图。
     *
     * @param extractor 已经 seek 到同步样本
     * @param codec 已启动的解码器
     * @param maxEdgePx 预览长边上限，单位像素
     * @return 预览位图；超时或输出不是 YUV 时为 null
     */
    private fun decodeKeyframe(
        extractor: MediaExtractor,
        codec: MediaCodec,
        maxEdgePx: Int
    ): Bitmap? {
        codec.flush()
        if (!queueSample(extractor, codec, endOfStream = false)) return null
        if (!queueSample(extractor, codec, endOfStream = true)) return null
        val deadline = System.nanoTime() + DECODE_BUDGET_NS
        val info = MediaCodec.BufferInfo()
        while (System.nanoTime() < deadline) {
            val output = codec.dequeueOutputBuffer(info, OUTPUT_WAIT_US)
            when {
                output == MediaCodec.INFO_TRY_AGAIN_LATER -> continue
                output == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> continue
                output < 0 -> continue
                else -> {
                    val image = codec.getOutputImage(output)
                    val bitmap = image?.let { frame ->
                        try {
                            frame.toPreviewBitmap(maxEdgePx, rotationDegrees)
                        } finally {
                            frame.close()
                        }
                    }
                    codec.releaseOutputBuffer(output, false)
                    if (bitmap != null) return bitmap
                }
            }
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

    /** 释放当前片源占用的解复用器、解码器和上一张关键帧。 */
    private fun closeSource() {
        releaseCodec()
        extractor?.release()
        extractor = null
        videoTrack = -1
        sourceKey = null
        lastSyncUs = NO_SYNC
        lastBitmap?.takeIf { !it.isRecycled }?.recycle()
        lastBitmap = null
        hardwareUnavailable = false
        rotationDegrees = 0
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
        /** 单次硬解出图的时间预算。单位纳秒。留给定时和出图，不再等到秒级。 */
        private const val DECODE_BUDGET_NS = 400_000_000L
        /** 等输入缓冲区的时间。单位微秒。 */
        private const val INPUT_WAIT_US = 8_000L
        /** 等一帧输出的时间。单位微秒。 */
        private const val OUTPUT_WAIT_US = 8_000L

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
         * 找一个硬解码器。
         *
         * @param mime 视频 MIME
         * @return 硬解码器；系统只有软解时为 null
         */
        private fun createHardwareDecoder(mime: String): MediaCodec? {
            val candidates = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
            for (info in candidates) {
                if (info.isEncoder || !info.supports(mime) || !info.isHardwareDecoder()) continue
                return runCatching { MediaCodec.createByCodecName(info.name) }.getOrNull()
            }
            return null
        }

        /**
         * 把码流配置从源格式抄到解码格式。
         *
         * @param source 容器里的轨道格式
         * @param target 准备 configure 的格式
         */
        private fun copyCodecConfig(source: MediaFormat, target: MediaFormat) {
            if (source.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
                target.setInteger(
                    MediaFormat.KEY_MAX_INPUT_SIZE,
                    source.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE)
                )
            }
            listOf("csd-0", "csd-1", "csd-2").forEach { key ->
                if (source.containsKey(key)) {
                    target.setByteBuffer(key, source.getByteBuffer(key))
                }
            }
        }

        /** 这个解码器是不是硬解。API 29 以下用名字排除系统软解。 */
        private fun MediaCodecInfo.isHardwareDecoder(): Boolean {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) return isHardwareAccelerated
            val normalized = name.lowercase()
            return !normalized.startsWith("omx.google.") && !normalized.startsWith("c2.android.")
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

/**
 * 把 YUV 画面按长边限制缩成预览位图。
 *
 * 只采样目标尺寸上的点，不生成全分辨率中间图。
 *
 * @param maxEdgePx 长边上限，单位像素
 * @param rotationDegrees 顺时针旋转，只处理 0、90、180、270
 * @return 预览位图
 */
private fun Image.toPreviewBitmap(maxEdgePx: Int, rotationDegrees: Int): Bitmap? {
    if (planes.size < 3 || maxEdgePx <= 0) return null
    val crop = cropRect
    val srcW = crop.width().takeIf { it > 0 } ?: width
    val srcH = crop.height().takeIf { it > 0 } ?: height
    val quarterTurn = rotationDegrees == 90 || rotationDegrees == 270
    val orientedW = if (quarterTurn) srcH else srcW
    val orientedH = if (quarterTurn) srcW else srcH
    val scale = min(maxEdgePx.toFloat() / orientedW, maxEdgePx.toFloat() / orientedH)
    val dstW = (orientedW * scale).toInt().coerceAtLeast(1)
    val dstH = (orientedH * scale).toInt().coerceAtLeast(1)
    val yPlane = planes[0]
    val uPlane = planes[1]
    val vPlane = planes[2]
    val pixels = IntArray(dstW * dstH)
    for (dy in 0 until dstH) {
        val syNorm = dy.toFloat() / dstH
        for (dx in 0 until dstW) {
            val sxNorm = dx.toFloat() / dstW
            val (sx, sy) = sourceSample(sxNorm, syNorm, srcW, srcH, rotationDegrees)
            val y = planeByte(yPlane, crop.top + sy, crop.left + sx)
            val u = planeByte(uPlane, crop.top + sy / 2, crop.left + sx / 2) - 128
            val v = planeByte(vPlane, crop.top + sy / 2, crop.left + sx / 2) - 128
            val c = y - 16
            val r = (298 * c + 409 * v + 128) shr 8
            val g = (298 * c - 100 * u - 208 * v + 128) shr 8
            val b = (298 * c + 516 * u + 128) shr 8
            pixels[dy * dstW + dx] =
                (0xFF shl 24) or (r.coerceIn(0, 255) shl 16) or (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255)
        }
    }
    return Bitmap.createBitmap(pixels, dstW, dstH, Bitmap.Config.ARGB_8888)
}

/**
 * 按显示旋转把预览图上的点映射回原始画面。
 *
 * @param sxNorm 预览图横向位置，0 到 1
 * @param syNorm 预览图纵向位置，0 到 1
 * @param srcW 原始宽度，单位像素
 * @param srcH 原始高度，单位像素
 * @param rotationDegrees 顺时针旋转
 * @return 原始画面上的采样坐标，已限制在画面内
 */
private fun sourceSample(
    sxNorm: Float,
    syNorm: Float,
    srcW: Int,
    srcH: Int,
    rotationDegrees: Int
): Pair<Int, Int> {
    val (xNorm, yNorm) = when (rotationDegrees) {
        90 -> 1f - syNorm to sxNorm
        180 -> 1f - sxNorm to 1f - syNorm
        270 -> syNorm to 1f - sxNorm
        else -> sxNorm to syNorm
    }
    val x = (xNorm * srcW).toInt().coerceIn(0, srcW - 1)
    val y = (yNorm * srcH).toInt().coerceIn(0, srcH - 1)
    return x to y
}

/**
 * 读取 YUV 平面上一个样本。
 *
 * @param plane 图像平面
 * @param row 行，单位像素
 * @param column 列，单位像素
 * @return 0 到 255 的样本值
 */
private fun planeByte(plane: Image.Plane, row: Int, column: Int): Int {
    val buffer = plane.buffer
    val index = buffer.position() + row * plane.rowStride + column * plane.pixelStride
    if (index < 0 || index >= buffer.limit()) return 0
    return buffer.get(index).toInt() and 0xFF
}

/** 复制一张互不影响回收的预览图。原图已回收时为 null。 */
private fun Bitmap.safeCopy(): Bitmap? {
    if (isRecycled) return null
    return copy(Bitmap.Config.ARGB_8888, false)
}
