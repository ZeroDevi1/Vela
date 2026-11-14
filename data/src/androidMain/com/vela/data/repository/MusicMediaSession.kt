package com.vela.data.repository

import com.vela.data.model.BaseItemDto
import com.vela.data.model.LyricLine
import com.vela.data.model.MediaLibraryQuery
import com.vela.data.model.QueryResult

/** 播放队列始终绑定创建该队列的账户。 */
interface MusicMediaSession {
    val accountKey: String
    val baseUrl: String
    val requestHeaders: Map<String, String>

    fun audioSource(item: BaseItemDto, transcode: Boolean = false): LibraryAudioSource
    suspend fun lyrics(item: BaseItemDto): List<LyricLine>
    suspend fun favorite(item: BaseItemDto, favorite: Boolean)
    suspend fun reportStart(source: LibraryAudioSource, positionMs: Long, paused: Boolean)
    suspend fun reportProgress(source: LibraryAudioSource, positionMs: Long, paused: Boolean)
    suspend fun reportStop(source: LibraryAudioSource, positionMs: Long, failed: Boolean = false)
}

interface BrowsableMediaSession : MusicMediaSession {
    suspend fun items(query: MediaLibraryQuery): QueryResult<BaseItemDto>
    suspend fun item(id: String): BaseItemDto
    suspend fun people(parentId: String?, personTypes: String, search: String?, start: Int, limit: Int): QueryResult<BaseItemDto>
    suspend fun playlistItems(id: String, start: Int, limit: Int): QueryResult<BaseItemDto>
    suspend fun genres(parentId: String?, types: String, start: Int, limit: Int): QueryResult<BaseItemDto>
    suspend fun createPlaylist(name: String, ids: List<String>)
    fun canFavorite(item: BaseItemDto): Boolean = true
}
