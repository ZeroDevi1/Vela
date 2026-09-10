package com.vela.app.ui.screens.books

import com.vela.data.repository.BookRange
import java.io.IOException
import java.util.zip.CRC32
import java.util.zip.Inflater
import java.util.zip.InflaterInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** 只读取 ZIP 目录和需要的图片；页面缓存最多四张且不超过 48 MB。 */
internal class RemoteComicArchive private constructor(
    private val read: suspend (Long?, Int) -> BookRange,
    private val entries: List<Entry>,
    private val directoryOffset: Long
) {
    private data class Entry(val path: String, val flags: Int, val method: Int, val crc: Long,
        val compressed: Int, val size: Int, val offset: Long)
    val chapters = entries.map { BookChapter(it.path, it.path.substringAfterLast('/')) }
    private val mutex = Mutex()
    private val cache = LinkedHashMap<Int, ByteArray>(4, .75f, true)

    suspend fun bytes(page: Int): ByteArray = withContext(Dispatchers.IO) {
        mutex.withLock {
            cache[page]?.let { return@withLock it }
            val entry = entries[page]
            val header = read(entry.offset, 30).bytes
            if (header.u32(0) != 0x04034b50L || header.u16(6) != entry.flags || header.u16(8) != entry.method) {
                throw IOException("漫画页面文件头损坏")
            }
            val start = entry.offset + 30 + header.u16(26) + header.u16(28)
            if (start + entry.compressed > directoryOffset) throw IOException("漫画页面范围无效")
            val compressed = read(start, entry.compressed).bytes
            val inflater = Inflater(true)
            val result = try {
                val input = if (entry.method == 0) compressed.inputStream()
                    else InflaterInputStream(compressed.inputStream(), inflater)
                input.use {
                    val output = java.io.ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val n = it.read(buffer)
                        if (n < 0) break
                        if (output.size().toLong() + n > entry.size) throw IOException("漫画图片超过声明大小")
                        output.write(buffer, 0, n)
                    }
                    output.toByteArray()
                }
            } finally { inflater.end() }
            if (result.size != entry.size || CRC32().apply { update(result) }.value != entry.crc) {
                throw IOException("漫画页面校验失败，请重试")
            }
            cache[page] = result
            while (cache.size > 4 || cache.values.sumOf { it.size.toLong() } > 48L * 1024 * 1024) {
                cache.remove(cache.keys.first())
            }
            result
        }
    }

    companion object {
        suspend fun open(read: suspend (Long?, Int) -> BookRange): RemoteComicArchive = withContext(Dispatchers.IO) {
            val tail = read(null, 65557)
            val bytes = tail.bytes
            val end = (bytes.size - 22 downTo 0).firstOrNull {
                bytes.u32(it) == 0x06054b50L && it + 22 + bytes.u16(it + 20) == bytes.size
            } ?: throw IOException("漫画 ZIP 目录不存在")
            val count = bytes.u16(end + 10)
            val length = bytes.u32(end + 12)
            val offset = bytes.u32(end + 16)
            if (bytes.u16(end + 4) != 0 || bytes.u16(end + 6) != 0 || bytes.u16(end + 8) != count ||
                count == 65535 || length == 0xffffffffL || offset == 0xffffffffL) {
                throw IOException("暂不支持分卷或 ZIP64 漫画")
            }
            if (count !in 1..10000 || length !in 1..(8L * 1024 * 1024) || offset + length != tail.start + end) {
                throw IOException("漫画目录大小或位置无效")
            }
            val directory = if (offset >= tail.start) bytes.copyOfRange((offset - tail.start).toInt(), end)
                else read(offset, length.toInt()).bytes
            val entries = mutableListOf<Entry>()
            val names = mutableSetOf<String>()
            var cursor = 0
            repeat(count) {
                if (cursor + 46 > directory.size || directory.u32(cursor) != 0x02014b50L) throw IOException("漫画目录损坏")
                val flags = directory.u16(cursor + 8)
                val method = directory.u16(cursor + 10)
                val compressed = directory.u32(cursor + 20)
                val size = directory.u32(cursor + 24)
                val nameLength = directory.u16(cursor + 28)
                val next = cursor + 46 + nameLength + directory.u16(cursor + 30) + directory.u16(cursor + 32)
                if (next > directory.size || directory.u16(cursor + 34) != 0) throw IOException("漫画目录条目无效")
                val charset = if (flags and 0x800 != 0) Charsets.UTF_8 else charset("IBM437")
                val path = safeBookPath(String(directory, cursor + 46, nameLength, charset))
                if (!names.add(path)) throw IOException("漫画包含重复路径")
                if (!path.startsWith("__MACOSX/") && path.substringAfterLast('.').lowercase() in setOf("jpg", "jpeg", "png", "webp", "gif")) {
                    val localOffset = directory.u32(cursor + 42)
                    if (flags and 0x41 != 0 || method !in setOf(0, 8)) throw IOException("漫画图片使用了不支持的加密或压缩方式")
                    if (size !in 1..BookArchive.MAX_RESOURCE_BYTES.toLong() || compressed !in 1..(33L * 1024 * 1024) ||
                        localOffset + 30 > offset) throw IOException("漫画图片大小或位置无效")
                    entries += Entry(path, flags, method, directory.u32(cursor + 16), compressed.toInt(), size.toInt(), localOffset)
                }
                cursor = next
            }
            if (cursor != directory.size || entries.isEmpty()) throw IOException("漫画没有有效的图片目录")
            RemoteComicArchive(read, entries.sortedWith { a, b -> naturalBookCompare(a.path, b.path) }, offset)
        }
    }
}

private fun ByteArray.u16(offset: Int): Int = (this[offset].toInt() and 255) or ((this[offset + 1].toInt() and 255) shl 8)
private fun ByteArray.u32(offset: Int): Long = u16(offset).toLong() or (u16(offset + 2).toLong() shl 16)
