package com.vela.app.ui.screens.player

import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelFileDescriptor
import android.os.ProxyFileDescriptorCallback
import android.os.storage.StorageManager
import android.system.ErrnoException
import android.system.OsConstants
import androidx.media3.common.util.UnstableApi
import com.vela.player.core.PlayerUtils
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * 给 MPV 提供可定位的文件描述符，把顺序播放和预读到的字节写入共享磁盘缓存。
 * MPV 的解复用缓存淘汰旧区间后，回跳仍可从磁盘读取尚未淘汰的数据。
 */
@UnstableApi
internal object MpvPlaybackCache {
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

    fun open(source: ScrubPreviewSource, size: Long): ParcelFileDescriptor {
        val playbackFactory = PlayerUtils.createCachedDataSourceFactory(
            source.context, source.requestHeaders, timeoutMs = 15_000
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
            return storage.openProxyFileDescriptor(
                ParcelFileDescriptor.MODE_READ_ONLY, callback, Handler(thread.looper)
            )
        } catch (error: Exception) {
            reader.close()
            thread.quitSafely()
            throw error
        }
    }
}
