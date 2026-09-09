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

    fun artworkUrl(item: BaseItemDto, width: Int = 512): String? {
        val id = if (item.imageTags?.containsKey("Primary") == true) item.id else item.albumId ?: item.id
        return id?.let {
            url("Items/$it/Images/Primary", listOf("maxWidth" to width.toString(), "quality" to "90",
                serverType.tokenQueryParameter to accessToken))
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

    /** 书籍只缓存到应用私有目录；失败/取消不会留下可被当作完整书籍的文件。 */
    suspend fun cacheBook(item: BaseItemDto, cacheDir: File, onProgress: (Long, Long?) -> Unit): File = withContext(Dispatchers.IO) {
        require(item.isBookItem()) { "该条目不是书籍" }
        val itemId = requireNotNull(item.id) { "书籍缺少编号" }
        val format = item.bookFormat()
        require(format in setOf("epub", "pdf", "cbz", "zip", "txt")) { "暂不支持 ${format.ifBlank { "未知" }} 格式，请使用 EPUB、PDF、CBZ 或 TXT" }
        val directory = File(cacheDir, "books/${libraryCacheKey(accountKey)}").apply { mkdirs() }
        val target = File(directory, "${libraryCacheKey(itemId + ":" + item.etag.orEmpty() + ":" + item.dateCreated.orEmpty())}.$format")
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
