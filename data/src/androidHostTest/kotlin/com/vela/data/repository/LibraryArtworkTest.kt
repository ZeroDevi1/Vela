package com.vela.data.repository

import com.vela.data.api.MediaServerApiClient
import com.vela.data.api.MediaServerApi
import com.vela.data.model.BaseItemDto
import com.vela.data.model.MediaLibraryQuery
import com.vela.data.model.QueryResult
import com.vela.data.network.ApiResponse
import com.vela.data.network.HttpStatusException
import com.vela.data.network.ServerType
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.http.Url
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertFailsWith
import kotlinx.coroutines.test.runTest
import java.lang.reflect.Proxy

/** 验证封面所有者、缓存版本和账户鉴权，不访问真实服务器。 */
class LibraryArtworkTest {
    /** 文件夹限定查询子内容，失败可重试，成功封面在同一会话内复用。 */
    @Test
    fun folderArtworkIsScopedAndFailedRequestsAreNotCached() = runTest {
        var calls = 0
        var responseItems = listOf(BaseItemDto(id = "no-image"),
            BaseItemDto(id = "book", imageTags = mapOf("Primary" to "cover")))
        // 只实现本用例允许调用的 API，任何额外查询都直接失败。
        val api = Proxy.newProxyInstance(MediaServerApi::class.java.classLoader,
            arrayOf(MediaServerApi::class.java)) { _, method, args ->
            check(method.name == "getMediaLibraryItems")
            val query = args[1] as MediaLibraryQuery
            assertEquals("folder", query.parentId)
            assertEquals(true, query.recursive)
            assertEquals(50, query.limit)
            calls++
            if (calls == 1) ApiResponse<QueryResult<BaseItemDto>>(null, 503)
            else ApiResponse(QueryResult(items = responseItems), 200)
        } as MediaServerApi
        val session = LibraryMediaSession("account", "https://nas.example", "user",
            ServerType.JELLYFIN, emptyMap(), "token", "device", api)
        val folder = BaseItemDto(id = "folder", isFolder = true)
        // 首次失败不能变成永久无图；第二次取有图子项，第三次命中会话缓存。
        assertFailsWith<HttpStatusException> { session.resolveArtworkItem(folder) }
        val resolved = session.resolveArtworkItem(folder)
        assertEquals("/Items/book/Images/Primary", Url(requireNotNull(session.artworkUrl(resolved))).encodedPath)
        assertEquals(resolved, session.resolveArtworkItem(folder))
        assertEquals(2, calls)
        // 新账户不能复用旧候选；服务器没有任何封面时仍交出可渲染的 PDF。
        responseItems = listOf(BaseItemDto(id = "pdf", type = "Book", path = "/books/first.pdf"))
        val other = LibraryMediaSession("other", "https://nas.example", "user",
            ServerType.JELLYFIN, emptyMap(), "other-token", "device", api)
        assertEquals("pdf", other.resolveArtworkItem(folder).id)
        assertEquals(3, calls)
    }

    /** 自身图片优先于专辑，继承图与缩略图使用各自所有者；无图不构造必然失败的请求。 */
    @Test
    fun selectsArtworkOwnerAndVersion() {
        // 禁止隐式网络探测，URL 构造必须是纯本地操作。
        val client = HttpClient(MockEngine { error("封面选择不应发起请求") })
        try {
            val session = LibraryMediaSession("account", "https://nas.example/media", "user",
                ServerType.JELLYFIN, emptyMap(), "token+/=", "device",
                MediaServerApiClient(client, "https://nas.example/media", ServerType.JELLYFIN))
            val cases = listOf(
                Triple(BaseItemDto(id = "song", imageTags = mapOf("Primary" to "own"), albumId = "album", albumPrimaryImageTag = "album-tag"), "song/Images/Primary", "own"),
                Triple(BaseItemDto(id = "song", albumId = "album", albumPrimaryImageTag = "album-tag"), "album/Images/Primary", "album-tag"),
                Triple(BaseItemDto(id = "book", parentPrimaryImageItemId = "parent", parentPrimaryImageTag = "parent-tag"), "parent/Images/Primary", "parent-tag"),
                Triple(BaseItemDto(id = "folder", imageTags = mapOf("Thumb" to "thumb-tag")), "folder/Images/Thumb", "thumb-tag"),
                Triple(BaseItemDto(id = "folder", parentThumbItemId = "parent", parentThumbImageTag = "thumb-tag"), "parent/Images/Thumb", "thumb-tag"),
                Triple(BaseItemDto(id = "album", backdropImageTags = listOf("backdrop-tag")), "album/Images/Backdrop", "backdrop-tag")
            )
            // 验证反向代理前缀、图片版本和特殊字符鉴权值均保留。
            for ((item, path, tag) in cases) {
                val url = Url(requireNotNull(session.artworkUrl(item)))
                assertEquals("/media/Items/$path", url.encodedPath)
                assertEquals(tag, url.parameters["tag"])
                assertEquals("token+/=", url.parameters["ApiKey"])
            }
            assertNull(session.artworkUrl(BaseItemDto(id = "empty-folder", isFolder = true)))
        } finally {
            // 即使断言失败也释放测试客户端。
            client.close()
        }
    }
}
