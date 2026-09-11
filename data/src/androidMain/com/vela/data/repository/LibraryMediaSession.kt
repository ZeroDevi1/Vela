package com.vela.data.repository

import com.vela.data.api.MediaServerApi
import com.vela.data.model.*
import com.vela.data.network.ApiResponse
import com.vela.data.network.HttpStatusException
import com.vela.data.network.ServerType
import com.vela.data.network.tokenQueryParameter
import io.ktor.http.URLBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.ConcurrentHashMap
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** 固定到打开页面时的账户；后台播放和阅读不能随着全局选服把请求发给另一账户。 */
class LibraryMediaSession internal constructor(
    val accountKey: String,
    val baseUrl: String,
    val userId: String,
    val serverType: ServerType?,
    val requestHeaders: Map<String, String>,
    private val accessToken: String,
    private val deviceId: String,
    private val api: MediaServerApi
) {
    /** 当前账户会话内的文件夹封面候选；空列表表示没有可用子项，随会话释放。 */
    private val folderArtwork = ConcurrentHashMap<String, List<BaseItemDto>>()
    /** 限制书架滚动时的补图请求，最多四个并发，避免挤占主列表查询。 */
    private val artworkRequests = Semaphore(4)

    fun matchesToken(token: String?): Boolean = token == accessToken

    suspend fun items(query: MediaLibraryQuery): QueryResult<BaseItemDto> =
        api.getMediaLibraryItems(userId, query).requiredBody()

    suspend fun item(id: String): BaseItemDto = api.getItemById(
        userId, id, "Overview,People,Genres,Path,MediaSources,Chapters,ChildCount"
    ).requiredBody()

    suspend fun people(parentId: String?, personTypes: String, search: String?, start: Int, limit: Int): QueryResult<BaseItemDto> =
        api.getMediaLibraryPeople(userId, parentId, personTypes, search, start, limit).requiredBody()

    suspend fun playlistItems(id: String, start: Int = 0, limit: Int = 60): QueryResult<BaseItemDto> =
        api.getPlaylistItems(userId, id, start, limit).requiredBody()

    suspend fun genres(parentId: String?, types: String, start: Int, limit: Int): QueryResult<BaseItemDto> =
        api.getGenres(userId, parentId, types, true, "SortName", "Ascending", true, true, start, limit).requiredBody()

    suspend fun favorite(itemId: String, favorite: Boolean) {
        (if (favorite) api.markAsFavorite(userId, itemId) else api.unmarkAsFavorite(userId, itemId)).requireSuccess()
    }

    suspend fun createPlaylist(name: String, ids: List<String>): String {
        require(name.isNotBlank()) { "请输入歌单名称" }
        return api.createPlaylist(name.trim(), ids.joinToString(","), userId).requiredBody().id
            ?: throw IOException("服务器未返回歌单编号")
    }

    suspend fun lyrics(id: String): List<LyricLine> {
        val response = api.getLyrics(id)
        if (response.code() == 404) return emptyList()
        return response.requiredBody().lyrics
    }

    /**
     * 按服务器图片元数据选择封面，使用当前固定账户鉴权；不发起网络请求。
     * @param item 书籍、音频或文件夹，允许没有图片元数据。
     * @param width 请求图片的最大宽度，单位像素，必须大于零。
     * @return 自身、专辑或继承封面的 URL；没有已知图片时返回 null，由界面显示占位。
     * @throws IllegalArgumentException 宽度非正数时抛出。
     */
    fun artworkUrl(item: BaseItemDto, width: Int = 512): String? {
        require(width > 0) { "封面宽度必须大于零" }
        // 图片编号与 tag 必须来自同一个所有者，不能把歌曲 tag 用到专辑上。
        val source = when {
            !item.id.isNullOrBlank() && !item.imageTags?.get("Primary").isNullOrBlank() ->
                Triple(item.id, "Primary", item.imageTags?.get("Primary"))
            !item.albumId.isNullOrBlank() && !item.albumPrimaryImageTag.isNullOrBlank() ->
                Triple(item.albumId, "Primary", item.albumPrimaryImageTag)
            !item.parentPrimaryImageItemId.isNullOrBlank() && !item.parentPrimaryImageTag.isNullOrBlank() ->
                Triple(item.parentPrimaryImageItemId, "Primary", item.parentPrimaryImageTag)
            !item.id.isNullOrBlank() && !item.imageTags?.get("Thumb").isNullOrBlank() ->
                Triple(item.id, "Thumb", item.imageTags?.get("Thumb"))
            !item.parentThumbItemId.isNullOrBlank() && !item.parentThumbImageTag.isNullOrBlank() ->
                Triple(item.parentThumbItemId, "Thumb", item.parentThumbImageTag)
            !item.id.isNullOrBlank() && !item.backdropImageTags.isNullOrEmpty() ->
                Triple(item.id, "Backdrop", item.backdropImageTags.first())
            else -> return null
        }
        // tag 随服务器封面更新变化，避免旧图长期命中磁盘缓存。
        return url("Items/${source.first}/Images/${source.second}", listOf(
            "maxWidth" to width.toString(), "quality" to "90", "tag" to source.third.orEmpty(),
            serverType.tokenQueryParameter to accessToken))
    }

    /**
     * 选择自身或文件夹中的封面来源，供界面加载服务器图片或生成书籍首页缩略图。
     * @param item 原始媒体条目；只有自身无图的文件夹会查询子项。
     * @return 前 50 项中优先有服务器封面的子项，否则首个可生成首页的书籍；无候选返回原条目。网络失败向上传播。
     */
    suspend fun resolveArtworkItem(item: BaseItemDto): BaseItemDto {
        if (artworkUrl(item) != null) return item
        val id = item.id?.takeIf { it.isNotBlank() && item.isFolder == true } ?: return item
        // 会话限定缓存防止跨账户复用授权 URL；进入并发许可后再次检查以减少重复查询。
        return artworkRequests.withPermit {
            folderArtwork[id]?.let { return@withPermit it.firstOrNull() ?: item }
            val children = items(MediaLibraryQuery(parentId = id, recursive = true,
                includeItemTypes = "Book,Audio,MusicAlbum,AudioBook", limit = 50, sortBy = "SortName"))
            val selected = children.items.orEmpty().firstOrNull { artworkUrl(it) != null }
                ?: children.items.orEmpty().firstOrNull { it.isBookItem() && it.bookFormat() in setOf("pdf", "cbz", "zip") }
            folderArtwork[id] = listOfNotNull(selected)
            selected ?: item
        }
    }

    fun audioSource(item: BaseItemDto, transcode: Boolean = false): LibraryAudioSource {
        require(item.isAudioItem()) { "该条目不是音频" }
        val id = requireNotNull(item.id) { "音频缺少编号" }
        val sourceId = item.mediaSources?.firstOrNull()?.id
        val playSessionId = UUID.randomUUID().toString().replace("-", "")
        val parameters = mutableListOf("static" to (!transcode).toString(), "DeviceId" to deviceId,
            "PlaySessionId" to playSessionId)
        sourceId?.let { parameters += "MediaSourceId" to it }
        if (transcode) {
            parameters += listOf("AudioCodec" to "mp3", "AudioBitRate" to "320000", "MaxAudioChannels" to "2")
        }
        return LibraryAudioSource(id, url("Audio/$id/stream${if (transcode) ".mp3" else ""}", parameters),
            sourceId, playSessionId, if (transcode) "Transcode" else "DirectPlay")
    }

    suspend fun reportStart(source: LibraryAudioSource, positionMs: Long, paused: Boolean) {
        api.reportPlaybackStart(PlaybackStartRequest(itemId = source.itemId, mediaSourceId = source.mediaSourceId,
            playSessionId = source.playSessionId, positionTicks = positionMs.coerceAtLeast(0) * 10_000,
            isPaused = paused, playMethod = source.playMethod, canSeek = true)).requireSuccess()
    }

    suspend fun reportProgress(source: LibraryAudioSource, positionMs: Long, paused: Boolean) {
        api.reportPlaybackProgress(PlaybackProgressRequest(itemId = source.itemId, mediaSourceId = source.mediaSourceId,
            playSessionId = source.playSessionId, positionTicks = positionMs.coerceAtLeast(0) * 10_000,
            isPaused = paused, playMethod = source.playMethod)).requireSuccess()
    }

    suspend fun reportStop(source: LibraryAudioSource, positionMs: Long, failed: Boolean = false) {
        api.reportPlaybackStopped(PlaybackStoppedRequest(itemId = source.itemId, mediaSourceId = source.mediaSourceId,
            playSessionId = source.playSessionId, positionTicks = positionMs.coerceAtLeast(0) * 10_000,
            failed = failed)).requireSuccess()
    }

    /** 复用先前完整下载的账户私有缓存，允许已缓存书籍离线打开。 */
    fun cachedBook(item: BaseItemDto, cacheDir: File): File? = bookCacheFile(item, cacheDir)
        .takeIf { it.isFile && it.length() > 0 }

    private fun bookCacheFile(item: BaseItemDto, cacheDir: File): File {
        val id = requireNotNull(item.id) { "书籍缺少编号" }
        val directory = File(cacheDir, "books/${libraryCacheKey(accountKey)}")
        return File(directory, "${libraryCacheKey(id + ":" + item.etag.orEmpty() + ":" + item.dateCreated.orEmpty())}.${item.bookFormat()}")
    }

    /**
     * 创建固定账户的按需书籍数据源，不在此处下载文件。
     * @param item 带编号的 PDF、CBZ 或 ZIP 书籍；其他类型抛 IllegalArgumentException。
     * @return 校验响应范围和文件版本的数据源；不支持 Range 的服务器在读取时明确报错。
     */
    fun bookRangeSource(item: BaseItemDto): BookRangeSource {
        require(item.isBookItem() && item.bookFormat() in setOf("cbz", "zip", "pdf"))
        val id = requireNotNull(item.id)
        return BookRangeSource(bookClient, Request.Builder().url(url("Items/$id/File"))
            .apply { requestHeaders.forEach { (name, value) -> header(name, value) } }.build())
    }

    /** 书籍只缓存到应用私有目录；失败/取消不会留下可被当作完整书籍的文件。 */
    suspend fun cacheBook(item: BaseItemDto, cacheDir: File, onProgress: (Long, Long?) -> Unit): File = withContext(Dispatchers.IO) {
        require(item.isBookItem()) { "该条目不是书籍" }
        val itemId = requireNotNull(item.id) { "书籍缺少编号" }
        val format = item.bookFormat()
        require(format in setOf("epub", "pdf", "cbz", "zip", "txt")) { "暂不支持 ${format.ifBlank { "未知" }} 格式，请使用 EPUB、PDF、CBZ 或 TXT" }
        val directory = File(cacheDir, "books/${libraryCacheKey(accountKey)}").apply { mkdirs() }
        val target = bookCacheFile(item, cacheDir)
        if (target.isFile && target.length() > 0) return@withContext target
        // 清理旧缓存，不影响当前返回给阅读器的书籍。
        directory.listFiles()?.filter { it.isFile && it != target }?.sortedBy { it.lastModified() }
            ?.let { files ->
                var size = files.sumOf { it.length() }
                for (file in files) {
                    if (size <= 512L * 1024 * 1024) break
                    val length = file.length()
                    if (file.delete()) size -= length
                }
            }
        val temporary = File.createTempFile("reading-", ".part", directory)
        val call = bookClient.newCall(Request.Builder().url(url("Items/$itemId/File"))
            .apply { requestHeaders.forEach { (name, value) -> header(name, value) } }.build())
        suspendCancellableCoroutine<File> { continuation ->
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : okhttp3.Callback {
                override fun onFailure(call: okhttp3.Call, error: IOException) {
                    temporary.delete()
                    if (continuation.isActive) continuation.resumeWithException(error)
                }

                override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                    try {
                        response.use {
                            if (!it.isSuccessful) throw HttpStatusException(it.code, "读取书籍失败（HTTP ${it.code}）")
                            val body = it.body ?: throw IOException("服务器返回了空文件")
                            val length = body.contentLength().takeIf { size -> size >= 0 }
                            if (length != null && length > MAX_BOOK_BYTES) throw IOException("书籍超过 512 MB，无法在应用内打开")
                            var received = 0L
                            onProgress(received, length)
                            body.byteStream().use { input -> temporary.outputStream().use { output ->
                                val buffer = ByteArray(64 * 1024)
                                while (true) {
                                    if (!continuation.isActive) throw CancellationException("阅读已取消")
                                    val count = input.read(buffer)
                                    if (count < 0) break
                                    received += count
                                    if (received > MAX_BOOK_BYTES) throw IOException("书籍超过 512 MB，无法在应用内打开")
                                    output.write(buffer, 0, count)
                                    onProgress(received, length)
                                }
                            } }
                            if (received == 0L || (length != null && received != length)) throw IOException("书籍下载不完整，请重试")
                        }
                        if (!continuation.isActive) throw CancellationException("阅读已取消")
                        if (!temporary.renameTo(target)) throw IOException("无法保存书籍缓存")
                        continuation.resume(target)
                    } catch (error: Exception) {
                        if (continuation.isActive) continuation.resumeWithException(error)
                    } finally {
                        temporary.delete()
                    }
                }
            })
        }
    }

    private fun url(path: String, queryParameters: List<Pair<String, String>> = emptyList()): String =
        URLBuilder("${baseUrl.trimEnd('/')}/$path").apply {
            queryParameters.forEach { (name, value) -> parameters.append(name, value) }
        }.buildString()

    private fun ApiResponse<*>.requireSuccess() {
        if (!isSuccessful) throw HttpStatusException(code(), "媒体服务器请求失败（HTTP ${code()}）")
    }

    private fun <T> ApiResponse<T>.requiredBody(): T {
        requireSuccess()
        return body() ?: throw IOException("媒体服务器返回了空响应")
    }

    companion object {
        private const val MAX_BOOK_BYTES = 512L * 1024 * 1024
        private val bookClient = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS).build()
    }
}

data class LibraryAudioSource(val itemId: String, val url: String, val mediaSourceId: String?, val playSessionId: String, val playMethod: String)

fun libraryCacheKey(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
    .joinToString("") { "%02x".format(it) }
