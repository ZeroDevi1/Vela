package com.vela.data.repository

import java.io.IOException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.*
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class BookRangeUnsupportedException : IOException("服务器不支持分段读取，正在下载完整书籍")
data class BookRange(val bytes: ByteArray, val start: Long, val total: Long)

/** 每个阅读会话独立持有文件版本，防止目录和页面来自不同版本。 */
class BookRangeSource internal constructor(private val client: OkHttpClient, private val request: Request) {
    private var validator: String? = null
    private var total: Long? = null

    suspend fun read(start: Long?, count: Int): BookRange {
        require(count in 1..(33 * 1024 * 1024) && (start == null || start >= 0))
        val range = if (start == null) "bytes=-$count" else "bytes=$start-${start + count - 1}"
        val call = client.newCall(request.newBuilder().header("Range", range).header("Accept-Encoding", "identity")
            .apply { validator?.let { header("If-Range", it) } }.build())
        return suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(e)
                }
                override fun onResponse(call: Call, response: Response) {
                    try {
                        val result = response.use {
                            if (it.code == 200 && total == null) throw BookRangeUnsupportedException()
                            if (it.code != 206) throw IOException("漫画分段读取失败（HTTP ${it.code}），请重新打开书籍")
                            val match = Regex("bytes (\\d+)-(\\d+)/(\\d+)").matchEntire(it.header("Content-Range").orEmpty())
                                ?: throw IOException("服务器返回了无效的分段信息")
                            val (first, last, size) = match.destructured.toList().map(String::toLong)
                            val expectedStart = start ?: (size - count).coerceAtLeast(0)
                            val expectedEnd = if (start == null) size - 1 else start + count - 1
                            if (size <= 0 || first != expectedStart || last != expectedEnd || last >= size ||
                                (total != null && total != size)) throw IOException("漫画文件长度或分段范围发生变化")
                            val version = it.header("ETag")?.takeUnless { tag -> tag.startsWith("W/") } ?: it.header("Last-Modified")
                            if (validator != null && version != validator) throw IOException("漫画文件已更新，请重新打开")
                            val body = it.body
                            val expected = (last - first + 1).toInt()
                            val bytes = ByteArray(expected)
                            body.byteStream().use { input ->
                                var offset = 0
                                while (offset < expected) {
                                    if (!continuation.isActive) throw java.io.InterruptedIOException()
                                    val n = input.read(bytes, offset, expected - offset)
                                    if (n < 0) throw IOException("漫画分段下载不完整")
                                    offset += n
                                }
                                if (input.read() != -1) throw IOException("漫画分段超过请求范围")
                            }
                            total = size
                            validator = version
                            BookRange(bytes, first, size)
                        }
                        if (continuation.isActive) continuation.resume(result)
                    } catch (e: Exception) {
                        if (continuation.isActive) continuation.resumeWithException(e)
                    }
                }
            })
        }
    }
}
