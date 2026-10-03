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
 * 区间直接命中播放器的磁盘缓存。
 *
 * 解复用器（尤其是 MKV）会在同一区域内来回做大量小读取；mpv 播放时主播放器不写
 * Media3 缓存，这些小读取原本每次都要重开一次 Range 请求。因此按 [BLOCK_SIZE]
 * 对齐整块读取并在内存里保留最近 [MAX_BLOCKS] 块，目标块就在当前流前方不远处时
 * 顺读过去而不是重开连接。
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
    /** 顺序流下一次读取对应的文件偏移，单位字节；始终落在块边界上。 */
    private var streamPosition = 0L
    /** 文件总长度，单位字节。第一次打开后确定；服务端没有给出时为 -1。 */
    private var totalSize = SIZE_UNKNOWN
    /** 是否已经尝试过解析文件长度。 */
    private var sizeResolved = false
    /** 最近读过的块，键是块序号；按访问顺序淘汰。最后一块可能短于 [BLOCK_SIZE]。 */
    private val blocks = object : LinkedHashMap<Long, ByteArray>(MAX_BLOCKS, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, ByteArray>): Boolean {
            return size > MAX_BLOCKS
        }
    }

    override fun getSize(): Long {
        if (!sizeResolved) openAt(0L)
        return totalSize
    }

    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
        if (size <= 0) return 0
        if (position < 0L) throw IOException("negative read position $position")
        var copied = 0
        while (copied < size) {
            val pos = position + copied
            if (sizeResolved && totalSize >= 0L && pos >= totalSize) break
            val blockIndex = pos / BLOCK_SIZE
            val block = blocks[blockIndex] ?: loadBlock(blockIndex)
            val inBlock = (pos - blockIndex * BLOCK_SIZE).toInt()
            if (inBlock >= block.size) break
            val count = minOf(size - copied, block.size - inBlock)
            System.arraycopy(block, inBlock, buffer, offset + copied, count)
            copied += count
            // 短块意味着已经读到文件末尾。
            if (block.size < BLOCK_SIZE && inBlock + count >= block.size) break
        }
        return if (copied == 0) -1 else copied
    }

    override fun close() {
        closeStream()
        blocks.clear()
    }

    /**
     * 读出指定块并放进缓存。
     *
     * 目标块在当前流前方 [FORWARD_SKIP_LIMIT] 以内时顺读过去（途经的块一起缓存），
     * 否则从块起点重新打开流。
     *
     * @param blockIndex 块序号
     * @return 块内容；读到文件末尾时可能短于 [BLOCK_SIZE] 甚至为空
     */
    private fun loadBlock(blockIndex: Long): ByteArray {
        val start = blockIndex * BLOCK_SIZE
        val canReadForward = opened && streamPosition <= start && start - streamPosition <= FORWARD_SKIP_LIMIT
        if (!canReadForward) openAt(start)
        while (true) {
            val index = streamPosition / BLOCK_SIZE
            val block = readNextBlock()
            blocks[index] = block
            if (index == blockIndex || block.size < BLOCK_SIZE) return block
        }
    }

    /** 从当前流位置读满一块；遇到文件末尾时返回实际读到的部分。 */
    private fun readNextBlock(): ByteArray {
        val data = ByteArray(BLOCK_SIZE)
        var read = 0
        while (read < BLOCK_SIZE) {
            val count = source.read(data, read, BLOCK_SIZE - read)
            if (count == C.RESULT_END_OF_INPUT) break
            read += count
        }
        streamPosition += read
        return if (read == BLOCK_SIZE) data else data.copyOf(read)
    }

    /**
     * 从指定偏移重新打开顺序流。
     *
     * 打开失败时保持关闭状态并抛出，调用方把它当作这一帧解不出来。
     *
     * @param position 起始偏移，单位字节；必须是块边界
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
        /** 每次网络读取的对齐块大小，单位字节。 */
        private const val BLOCK_SIZE = 128 * 1024
        /** 内存里最多保留的块数，约 8 MB。 */
        private const val MAX_BLOCKS = 64
        /** 目标在当前流前方多远以内时顺读而不重连，单位字节。 */
        private const val FORWARD_SKIP_LIMIT = 1024L * 1024L
    }
}
