package com.vela.app.ui.screens.player

import android.media.MediaDataSource
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import java.io.IOException

/**
 * 让 MediaExtractor 通过播放器的 Media3 数据源读取直链。
 *
 * 系统自带的 HTTP 抽取路径每次 seek 都会断开重连，并在后台一路预读到十几 MB，
 * 与主播放器抢带宽。这里改成按需读取：只拉解复用器真正要的字节，已缓冲、已播过的
 * 区间直接命中播放器的磁盘缓存，不再走网络。
 *
 * 只在抽帧线程上使用，不做线程同步。
 *
 * @param factory 读穿播放器缓存的数据源工厂
 * @param uri 直链地址
 * @param cacheKey 主播放器使用的缓存键；为 null 时按地址缓存
 */
@UnstableApi
internal class ScrubMediaDataSource(
    factory: DataSource.Factory,
    private val uri: Uri,
    private val cacheKey: String?
) : MediaDataSource() {
    private val source: DataSource = factory.createDataSource()
    /** 当前是否有已打开的顺序读取流。 */
    private var opened = false
    /** 顺序流下一次读取对应的文件偏移，单位字节。 */
    private var streamPosition = 0L
    /** 文件总长度，单位字节。第一次打开后确定；服务端没有给出时为 -1。 */
    private var totalSize = SIZE_UNKNOWN
    /** 是否已经尝试过解析文件长度。 */
    private var sizeResolved = false

    override fun getSize(): Long {
        if (!sizeResolved) openAt(0L)
        return totalSize
    }

    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
        if (size <= 0) return 0
        if (position < 0L) throw IOException("negative read position $position")
        if (sizeResolved && totalSize >= 0L && position >= totalSize) return -1
        if (!opened || streamPosition != position) {
            openAt(position)
        }
        var read = 0
        while (read < size) {
            val count = source.read(buffer, offset + read, size - read)
            if (count == C.RESULT_END_OF_INPUT) break
            read += count
        }
        streamPosition += read
        return if (read == 0) -1 else read
    }

    override fun close() {
        closeStream()
    }

    /**
     * 从指定偏移重新打开顺序流。
     *
     * 打开失败时保持关闭状态并抛出，调用方把它当作这一帧解不出来。
     *
     * @param position 起始偏移，单位字节
     */
    private fun openAt(position: Long) {
        closeStream()
        val spec = DataSpec.Builder()
            .setUri(uri)
            .setPosition(position)
            .apply { cacheKey?.let { setKey(it) } }
            .build()
        val remaining = try {
            source.open(spec)
        } catch (error: IOException) {
            runCatching { source.close() }
            throw error
        }
        opened = true
        streamPosition = position
        if (!sizeResolved) {
            sizeResolved = true
            totalSize = if (remaining == C.LENGTH_UNSET.toLong()) SIZE_UNKNOWN else position + remaining
        }
    }

    /** 关闭当前顺序流。已关闭时什么都不做。 */
    private fun closeStream() {
        if (!opened) return
        opened = false
        runCatching { source.close() }
    }

    private companion object {
        /** MediaDataSource 约定的长度未知值。 */
        private const val SIZE_UNKNOWN = -1L
    }
}
