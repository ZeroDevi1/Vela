package com.vela.app.ui.screens.books

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelFileDescriptor
import android.os.ProxyFileDescriptorCallback
import android.os.storage.StorageManager
import android.system.ErrnoException
import android.system.OsConstants
import com.vela.data.model.BaseItemDto
import com.vela.data.model.bookFormat
import com.vela.data.model.isBookItem
import com.vela.data.repository.BookRangeSource
import com.vela.data.repository.LibraryMediaSession
import com.vela.data.repository.libraryCacheKey
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/** 无服务器封面时按需生成首页缩略图，文件与请求均按账户隔离。 */
internal object BookArtwork {
    /** 同时只解码两本书，限制内存和服务器 Range 请求压力。 */
    private val decoding = Semaphore(2)

    /**
     * 解析服务器封面或生成 PDF/漫画首页；只处理可见条目，不下载整本书。
     * @param context 用于私有缓存和 PDF 可随机访问文件服务的应用上下文。
     * @param session 固定账户的媒体会话。
     * @param item 书籍、音乐或文件夹；文件夹先选取一个封面来源。
     * @return Coil 可加载的 URL 或缓存 File；不支持生成且无图片时为 null。网络/解码失败向上传播。
     */
    suspend fun resolve(context: Context, session: LibraryMediaSession, item: BaseItemDto): Any? {
        val source = session.resolveArtworkItem(item)
        session.artworkUrl(source)?.let { return it }
        if (!source.isBookItem() || source.bookFormat() !in setOf("pdf", "cbz", "zip")) return null
        return withContext(Dispatchers.IO) {
            // 版本和账户参与缓存键，线路切换不复用其他账户的书籍。
            val directory = File(context.cacheDir, "book-artwork/${libraryCacheKey(session.accountKey)}")
            val target = File(directory, "${libraryCacheKey(source.id + ":" + source.etag.orEmpty() + ":" + source.dateCreated.orEmpty())}.png")
            decoding.withPermit {
                if (target.isFile && target.length() > 0) return@withPermit target
                if (!directory.isDirectory && !directory.mkdirs()) throw IOException("无法创建封面缓存目录")
                val local = session.cachedBook(source, context.cacheDir)
                val bitmap = if (source.bookFormat() == "pdf") {
                    if (local != null) ParcelFileDescriptor.open(local, ParcelFileDescriptor.MODE_READ_ONLY).use(::renderPdf)
                    else remotePdf(context, session.bookRangeSource(source))
                } else {
                    val bytes = if (local != null) BookArchive(local).let { it.bytes(it.comicPages().first().path) }
                        else RemoteComicArchive.open(session.bookRangeSource(source)::read).bytes(0)
                    decodeCover(bytes)
                }
                // 临时文件完整写入后才发布；取消或失败不留下半张封面。
                var temporary: File? = null
                try {
                    currentCoroutineContext().ensureActive()
                    val pending = File.createTempFile("cover-", ".tmp", directory)
                    temporary = pending
                    pending.outputStream().use { if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) throw IOException("封面编码失败") }
                    if (!pending.renameTo(target)) throw IOException("封面缓存写入失败")
                    target
                } finally {
                    bitmap.recycle()
                    temporary?.delete()
                }
            }
        }
    }

    /**
     * 将漫画首页降采样为缩略图，避免按原图尺寸分配内存。
     * @param bytes 已通过归档大小及 CRC 校验的图片字节。
     * @return 调用方负责回收的位图；格式无效时抛 IOException。
     */
    private fun decodeCover(bytes: ByteArray): Bitmap {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        if (options.outWidth <= 0 || options.outHeight <= 0) throw IOException("漫画封面不是有效图片")
        // 最大边不超过 1024，保留列表与详情页所需清晰度。
        options.inSampleSize = 1
        while (maxOf(options.outWidth, options.outHeight) / options.inSampleSize > 1024) options.inSampleSize *= 2
        options.inJustDecodeBounds = false
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: throw IOException("漫画封面解码失败")
    }

    /**
     * 渲染 PDF 第一页；不能在主线程调用，PdfRenderer 接管并关闭文件描述符。
     * @param descriptor 可定位的只读 PDF 文件描述符。
     * @return 最大 512x1024 的位图，调用方负责回收；损坏、加密或空 PDF 明确失败。
     */
    private fun renderPdf(descriptor: ParcelFileDescriptor): Bitmap = PdfRenderer(descriptor).use { renderer ->
        if (renderer.pageCount == 0) throw IOException("PDF 没有页面")
        renderer.openPage(0).use { page ->
            val scale = minOf(512f / page.width, 1024f / page.height)
            val bitmap = Bitmap.createBitmap((page.width * scale).toInt().coerceAtLeast(1),
                (page.height * scale).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
            // PDF 透明背景在深色书架上不可读，使用纸张白底。
            try {
                bitmap.eraseColor(Color.WHITE)
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                bitmap
            } catch (e: Exception) {
                bitmap.recycle()
                throw e
            }
        }
    }

    /**
     * 使用系统可定位代理文件为 PDF 提供 HTTP Range 数据，避免为封面下载整本 PDF。
     * @param context 提供 StorageManager 系统服务的上下文。
     * @param source 带固定账户鉴权和文件版本校验的数据源。
     * @return 第一页位图；不支持 Range、网络或渲染错误向上传播，退出时关闭描述符和回调线程。
     */
    private suspend fun remotePdf(context: Context, source: BookRangeSource): Bitmap {
        val length = source.read(0, 1).total
        val owner = currentCoroutineContext()
        val failure = AtomicReference<Throwable?>()
        val thread = HandlerThread("book-cover-range").apply { start() }
        // 回调必须使用另一线程，否则 PdfRenderer 的同步读取会与当前线程互相等待。
        val callback = object : ProxyFileDescriptorCallback() {
            /** 64 KB 分块的短期缓存，上限 8 MB，仅当前 PDF 渲染期间有效。 */
            private val chunks = LinkedHashMap<Long, ByteArray>(128, .75f, true)
            /** 累计下载字节，上限 32 MB，拒绝为缩略图无限下载整个大文件。 */
            private var downloaded = 0L

            /** 返回服务器已验证的文件总字节数，不发网络请求。 */
            override fun onGetSize(): Long = length

            /**
             * 满足 PDF 的随机读取；失败转为 POSIX EIO 并保留原始异常供上层展示。
             * @param offset 从文件起点计算的字节偏移。
             * @param size 本次读取上限，单位字节。
             * @param data 系统提供的目标缓冲区。
             * @return 已复制的字节数，到文件末尾返回零。
             */
            override fun onRead(offset: Long, size: Int, data: ByteArray): Int {
                try {
                    owner.ensureActive()
                    var copied = 0
                    val count = minOf(size.toLong(), (length - offset).coerceAtLeast(0)).toInt()
                    // 对齐分块复用 PDF 索引和页面对象的小范围读取。
                    while (copied < count) {
                        val position = offset + copied
                        val start = position / 65536 * 65536
                        val chunk = chunks[start] ?: run {
                            val bytes = minOf(65536L, length - start).toInt()
                            if (downloaded + bytes > 32L * 1024 * 1024) throw IOException("PDF 封面读取超过 32 MB 限制")
                            runBlocking(owner) { source.read(start, bytes).bytes }.also {
                                downloaded += it.size
                                chunks[start] = it
                                if (chunks.size > 128) chunks.remove(chunks.keys.first())
                            }
                        }
                        val within = (position - start).toInt()
                        val amount = minOf(count - copied, chunk.size - within)
                        chunk.copyInto(data, copied, within, within + amount)
                        copied += amount
                    }
                    return copied
                } catch (e: Exception) {
                    failure.set(e)
                    throw ErrnoException("book-cover-read", OsConstants.EIO)
                }
            }

            /** 系统不再使用代理文件时释放分块缓存并结束回调线程。 */
            override fun onRelease() { chunks.clear(); thread.quitSafely() }
        }
        try {
            val storage = context.getSystemService(StorageManager::class.java)
            return storage.openProxyFileDescriptor(ParcelFileDescriptor.MODE_READ_ONLY, callback, Handler(thread.looper))
                .use { descriptor ->
                    try { renderPdf(descriptor) }
                    catch (e: Exception) { throw failure.get() ?: e }
                }
        } finally {
            // 创建代理或渲染失败时也必须结束线程，不能依赖系统一定回调 onRelease。
            thread.quitSafely()
        }
    }
}
