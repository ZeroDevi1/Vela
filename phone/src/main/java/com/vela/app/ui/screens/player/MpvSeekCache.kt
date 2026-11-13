package com.vela.app.ui.screens.player

import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelFileDescriptor
import android.os.ProxyFileDescriptorCallback
import android.os.storage.StorageManager
import android.system.ErrnoException
import android.system.OsConstants
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import com.vela.player.core.PlayerUtils
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * MPV 与目标位置的 MediaExtractor 共用播放器磁盘缓存。代理描述符给 MPV 提供可定位的
 * 字节流，后台抽取器只解复用以定位目标和确认缓冲时长。
 */
@UnstableApi
internal class MpvSeekCache private constructor(
    val descriptor: ParcelFileDescriptor,
    private val source: ScrubPreviewSource,
    private val size: Long,
    private val preloadFactory: DataSource.Factory
) {
    suspend fun preload(
        positionMs: Long,
        endMs: Long,
        audioTrackOrdinal: Int?,
        hasPlaybackReserve: () -> Boolean
    ): Boolean {
        val owner = currentCoroutineContext()
        return runInterruptible(Dispatchers.IO) {
            val reader = ScrubMediaDataSource(preloadFactory, source.uri, source.cacheKey, size, PRELOAD_RANGE_BYTES) {
                while (!hasPlaybackReserve()) {
                    owner.ensureActive()
                    Thread.sleep(100)
                }
                owner.ensureActive()
            }
            val extractor = MediaExtractor()
            try {
                extractor.setDataSource(reader)
                val tracks = (0 until extractor.trackCount).map { index ->
                    index to extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME).orEmpty()
                }
                val video = tracks.firstOrNull { (_, mime) -> mime.startsWith("video/") }?.first
                val audioTracks = tracks.filter { (_, mime) -> mime.startsWith("audio/") }
                val audio = audioTrackOrdinal?.let { audioTracks.getOrNull(it)?.first }
                val selected = listOfNotNull(video, audio)
                if (selected.isEmpty()) return@runInterruptible false
                selected.forEach(extractor::selectTrack)
                extractor.seekTo(positionMs * 1000L, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                val endUs = endMs * 1000L
                val lastSampleUs = selected.associateWith { -1L }.toMutableMap()
                var latestUs = -1L
                while (true) {
                    owner.ensureActive()
                    val sampleUs = extractor.sampleTime
                    if (sampleUs < 0L) break
                    latestUs = maxOf(latestUs, sampleUs)
                    val track = extractor.sampleTrackIndex
                    lastSampleUs[track]?.let { previous -> lastSampleUs[track] = maxOf(previous, sampleUs) }
                    if (lastSampleUs.values.all { it >= endUs }) return@runInterruptible true
                    if (!extractor.advance()) break
                }
                // 最后一帧的时间戳略早于容器报告的时长。
                latestUs >= (endMs - 250L).coerceAtLeast(positionMs) * 1000L
            } finally {
                extractor.release()
                reader.close()
            }
        }
    }

    companion object {
        private const val PRELOAD_RANGE_BYTES = 2L * 1024 * 1024
        private val rangeProbe = OkHttpClient.Builder()
            .callTimeout(3, TimeUnit.SECONDS)
            .build()
        private val contentRange = Regex("bytes 0-0/([1-9][0-9]*)", RegexOption.IGNORE_CASE)

        /** 确认服务端接受 Range，并读取未压缩的文件总长度。 */
        suspend fun probeSize(source: ScrubPreviewSource): Long? = runInterruptible(Dispatchers.IO) {
            val request = Request.Builder().url(source.uri.toString()).apply {
                source.requestHeaders.forEach { (key, value) -> header(key, value) }
                header("Accept-Encoding", "identity")
                header("Range", "bytes=0-0")
            }.build()
            rangeProbe.newCall(request).execute().use { response ->
                val encoding = response.header("Content-Encoding")
                if (response.code != 206 || (encoding != null && !encoding.equals("identity", true))) return@use null
                contentRange.matchEntire(response.header("Content-Range").orEmpty())
                    ?.groupValues?.get(1)?.toLongOrNull()
            }
        }

        fun open(source: ScrubPreviewSource, size: Long): MpvSeekCache {
            // MPV 有自己的前向缓存。主播放读共享缓存但不写，避免把刚预读的目标区间淘汰。
            val playbackFactory = PlayerUtils.createCachedDataSourceFactory(
                source.context, source.requestHeaders, writeToCache = false, timeoutMs = 15_000
            )
            val preloadFactory = PlayerUtils.createCachedDataSourceFactory(
                source.context, source.requestHeaders, timeoutMs = 5_000
            )
            val reader = ScrubMediaDataSource(playbackFactory, source.uri, source.cacheKey, size)
            val thread = HandlerThread("mpv-cache-reader").apply { start() }
            val callback = object : ProxyFileDescriptorCallback() {
                override fun onGetSize(): Long = size

                override fun onRead(offset: Long, size: Int, data: ByteArray): Int = try {
                    reader.readAt(offset, data, 0, size).coerceAtLeast(0)
                } catch (_: Exception) {
                    throw ErrnoException("mpv-cache-read", OsConstants.EIO)
                }

                override fun onRelease() {
                    reader.close()
                    thread.quitSafely()
                }
            }
            try {
                val storage = checkNotNull(source.context.getSystemService(StorageManager::class.java))
                val descriptor = storage.openProxyFileDescriptor(
                    ParcelFileDescriptor.MODE_READ_ONLY, callback, Handler(thread.looper)
                )
                return MpvSeekCache(descriptor, source, size, preloadFactory)
            } catch (error: Exception) {
                reader.close()
                thread.quitSafely()
                throw error
            }
        }
    }
}
