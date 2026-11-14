package com.vela.data.navidrome

import android.util.LruCache
import com.vela.data.model.BaseItemDto
import com.vela.data.model.LyricLine
import com.vela.data.model.MediaLibraryQuery
import com.vela.data.model.QueryResult
import com.vela.data.model.UserItemDataDto
import com.vela.data.model.isAudioItem
import com.vela.data.repository.BrowsableMediaSession
import com.vela.data.repository.LibraryAudioSource
import okhttp3.Request
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.IOException
import java.util.UUID

data class NavidromeFolder(val id: String, val name: String)

class NavidromeSession internal constructor(
    override val accountKey: String,
    override val baseUrl: String,
    val username: String,
    val note: String,
    private val client: NavidromeClient
) : BrowsableMediaSession {
    val displayName: String get() = note.ifBlank { "Navidrome" }
    override val requestHeaders: Map<String, String> = emptyMap()
    private val coverRequests = Semaphore(4)
    private val covers = object : LruCache<Pair<String, Int>, ByteArray>(8 * 1024 * 1024) {
        override fun sizeOf(key: Pair<String, Int>, value: ByteArray): Int = value.size
    }

    suspend fun folders(): List<NavidromeFolder> = client.request("getMusicFolders").obj("musicFolders")
        ?.rows("musicFolder").orEmpty().mapNotNull { row ->
            val id = row.string("id") ?: return@mapNotNull null
            NavidromeFolder(id, row.string("name") ?: id)
        }

    suspend fun randomTracks(limit: Int): List<BaseItemDto> =
        client.request("getRandomSongs", "size" to limit).obj("randomSongs")?.tracks().orEmpty()

    suspend fun similarTracks(item: BaseItemDto): List<BaseItemDto> =
        client.request("getSimilarSongs2", "id" to requireNotNull(item.id), "count" to 12)
            .obj("similarSongs2")?.tracks().orEmpty().filterNot { it.id == item.id }

    override suspend fun items(query: MediaLibraryQuery): QueryResult<BaseItemDto> {
        val folder = folderArgument(query.parentId)
        val term = query.searchTerm.orEmpty()
        if (query.filters == "IsFavorite") {
            val starred = client.request("getStarred2", *folder).obj("starred2")
                ?: return QueryResult(items = emptyList(), totalRecordCount = 0)
            val favorites = starred.tracks() + starred.rows("album").mapNotNull { it.toItem("MusicAlbum") } +
                starred.rows("artist").mapNotNull { it.toItem("MusicArtist") }
            return favorites.matching(term).page(query.startIndex, query.limit)
        }
        return when (query.includeItemTypes) {
            "Playlist" -> client.request("getPlaylists").obj("playlists")?.rows("playlist").orEmpty()
                .mapNotNull { it.toItem("Playlist") }.matching(term).page(query.startIndex, query.limit)
            "MusicAlbum" -> if (term.isNotEmpty()) {
                searchPage(term, "album", "MusicAlbum", query.startIndex, query.limit, folder)
            } else {
                val type = if (query.sortOrder == "Descending") "newest" else "alphabeticalByName"
                val rows = client.request("getAlbumList2", "type" to type, "size" to query.limit,
                    "offset" to query.startIndex, *folder).obj("albumList2")?.rows("album").orEmpty()
                QueryResult(items = rows.mapNotNull { it.toItem("MusicAlbum") })
            }
            else -> searchPage(term, "song", "Audio", query.startIndex, query.limit, folder)
        }
    }

    override suspend fun item(id: String): BaseItemDto = client.request("getSong", "id" to id)
        .obj("song")?.toItem("Audio") ?: throw IOException("Navidrome 未返回歌曲")

    override suspend fun people(parentId: String?, personTypes: String, search: String?, start: Int, limit: Int): QueryResult<BaseItemDto> {
        val folder = folderArgument(parentId)
        return searchPage(search.orEmpty(), "artist", "MusicArtist", start, limit, folder)
    }

    override suspend fun genres(parentId: String?, types: String, start: Int, limit: Int): QueryResult<BaseItemDto> =
        client.request("getGenres", *folderArgument(parentId)).obj("genres")?.rows("genre").orEmpty()
            .mapNotNull { row -> row.string("value")?.let { BaseItemDto(id = it, name = it, type = "MusicGenre", childCount = row.number("songCount")) } }
            .page(start, limit)

    override suspend fun playlistItems(id: String, start: Int, limit: Int): QueryResult<BaseItemDto> =
        client.request("getPlaylist", "id" to id).obj("playlist")?.tracks("entry").orEmpty().page(start, limit)

    suspend fun children(group: BaseItemDto, folderId: String?, search: String?, start: Int, limit: Int): QueryResult<BaseItemDto> {
        val id = requireNotNull(group.id)
        return when (group.type) {
            "MusicArtist" -> client.request("getArtist", "id" to id).obj("artist")?.rows("album").orEmpty()
                .mapNotNull { it.toItem("MusicAlbum") }.matching(search.orEmpty()).page(start, limit)
            "MusicAlbum" -> client.request("getAlbum", "id" to id).obj("album")?.tracks().orEmpty()
                .matching(search.orEmpty()).page(start, limit)
            "MusicGenre" -> {
                val rows = client.request("getSongsByGenre", "genre" to id, "count" to limit, "offset" to start,
                    *folderArgument(folderId)).obj("songsByGenre")?.tracks().orEmpty()
                QueryResult(items = rows)
            }
            "Playlist" -> playlistItems(id, start, limit)
            else -> QueryResult(items = emptyList())
        }
    }

    override fun canFavorite(item: BaseItemDto): Boolean = item.type in setOf("Audio", "MusicAlbum", "MusicArtist")

    override suspend fun favorite(item: BaseItemDto, favorite: Boolean) {
        val parameter = when (item.type) {
            "MusicAlbum" -> "albumId"
            "MusicArtist" -> "artistId"
            else -> "id"
        }
        client.request(if (favorite) "star" else "unstar", parameter to requireNotNull(item.id))
    }

    override suspend fun createPlaylist(name: String, ids: List<String>) {
        client.request("createPlaylist", "name" to name.trim(), *ids.map { "songId" to it }.toTypedArray())
    }

    override suspend fun lyrics(item: BaseItemDto): List<LyricLine> {
        val structured = try {
            client.request("getLyricsBySongId", "id" to requireNotNull(item.id)).obj("lyricsList")
                ?.rows("structuredLyrics").orEmpty()
        } catch (error: NavidromeApiException) {
            if (error.code != 70) throw error
            emptyList()
        } catch (error: NavidromeHttpException) {
            if (error.code !in setOf(404, 410, 501)) throw error
            emptyList()
        }
        val choices = structured.mapNotNull { lyric ->
            val offset = lyric.double("offset") ?: 0.0
            val lines = lyric.rows("line").mapNotNull lineChoice@{ line ->
                val value = line.string("value") ?: return@lineChoice null
                // 正偏移让歌词提前；Subsonic 使用毫秒，播放器数据模型使用 100 ns ticks。
                LyricLine(value, line.double("start")?.let { ((it - offset).coerceAtLeast(0.0) * 10_000).toLong() })
            }
            lines.takeIf { it.isNotEmpty() }
        }
        choices.firstOrNull { lines -> lines.any { it.start != null } }?.let { return it }
        choices.firstOrNull()?.let { return it }

        val artist = item.artists?.firstOrNull() ?: return emptyList()
        val title = item.name ?: return emptyList()
        return client.request("getLyrics", "artist" to artist, "title" to title).obj("lyrics")?.string("value")
            ?.takeIf { it.isNotBlank() }?.lineSequence()?.map { LyricLine(it) }?.toList().orEmpty()
    }

    suspend fun cover(item: BaseItemDto, width: Int = 512): ByteArray? {
        val id = item.imageTags?.get("Primary")?.takeIf { it.isNotBlank() } ?: return null
        val key = id to width
        covers.get(key)?.let { return it }
        return coverRequests.withPermit {
            covers.get(key) ?: client.cover(id, width).also { covers.put(key, it) }
        }
    }

    fun authorizeStreamRequest(request: Request): Request = client.streamRequest(request)

    override fun audioSource(item: BaseItemDto, transcode: Boolean): LibraryAudioSource {
        require(item.isAudioItem()) { "该条目不是音频" }
        val id = requireNotNull(item.id)
        return LibraryAudioSource(id, client.streamUrl(id, transcode), null, UUID.randomUUID().toString(),
            if (transcode) "Transcode" else "DirectPlay")
    }

    override suspend fun reportStart(source: LibraryAudioSource, positionMs: Long, paused: Boolean) {
        if (!paused) client.request("scrobble", "id" to source.itemId, "submission" to false)
    }

    override suspend fun reportProgress(source: LibraryAudioSource, positionMs: Long, paused: Boolean) = Unit
    override suspend fun reportStop(source: LibraryAudioSource, positionMs: Long, failed: Boolean) = Unit

    suspend fun scrobble(source: LibraryAudioSource) {
        client.request("scrobble", "id" to source.itemId, "submission" to true)
    }

    private suspend fun searchPage(term: String, key: String, type: String, start: Int, limit: Int,
                               folder: Array<Pair<String, Any>>): QueryResult<BaseItemDto> {
        val counts = listOf("song", "album", "artist").map { "${it}Count" to if (it == key) limit else 0 }
        val response = client.request("search3", "query" to term, "${key}Offset" to start, *counts.toTypedArray(), *folder)
        return QueryResult(items = response.obj("searchResult3")?.rows(key).orEmpty().mapNotNull { it.toItem(type) })
    }

    private fun folderArgument(id: String?): Array<Pair<String, Any>> =
        if (id == null) emptyArray() else arrayOf("musicFolderId" to id)

    private fun JsonObject.tracks(key: String = "song"): List<BaseItemDto> = rows(key).mapNotNull { it.toItem("Audio") }

    private fun JsonObject.toItem(type: String): BaseItemDto? {
        val id = string("id") ?: return null
        val artist = string("artist")
        return BaseItemDto(
            id = id, name = string(if (type == "Audio") "title" else "name"), type = type,
            album = string("album"), albumId = string("albumId"), albumArtist = artist,
            artists = artist?.let { listOf(it) }, childCount = number("songCount") ?: number("albumCount"),
            runTimeTicks = double("duration")?.let { (it * 10_000_000).toLong() },
            imageTags = string("coverArt")?.let { mapOf("Primary" to it) },
            userData = UserItemDataDto(isFavorite = !string("starred").isNullOrBlank())
        )
    }

    private fun JsonObject.double(key: String): Double? = (this[key] as? JsonPrimitive)?.doubleOrNull?.takeIf { it.isFinite() }

    private fun List<BaseItemDto>.matching(term: String): List<BaseItemDto> =
        if (term.isBlank()) this else filter { item ->
            listOfNotNull(item.name, item.albumArtist, item.album).any { it.contains(term, ignoreCase = true) }
        }

    private fun List<BaseItemDto>.page(start: Int, limit: Int): QueryResult<BaseItemDto> =
        QueryResult(items = drop(start).take(limit), totalRecordCount = size)
}
