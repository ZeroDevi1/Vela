package com.vela.data.repository

import android.content.Context
import com.vela.data.api.MediaServerApi
import com.vela.data.model.BaseItemDto
import com.vela.data.model.SearchMediaType
import com.vela.data.network.NetworkModule
import com.vela.data.network.tokenQueryParameter
import com.vela.data.network.ServerType
import com.vela.data.network.trimTrailingSlash
import com.vela.data.preferences.NetworkPreferences
import com.vela.data.security.SecureSessionStore
import java.net.URLEncoder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

data class FederatedServer(
    val id: String,
    val name: String
)

data class FederatedMediaItem(
    val item: BaseItemDto,
    val serverId: String,
    val serverName: String,
    val imageUrl: String?,
    val lineName: String? = null
)

data class FederatedServerFailure(
    val serverId: String,
    val serverName: String,
    val message: String
)

data class FederatedMediaResponse(
    val items: List<FederatedMediaItem>,
    val failures: List<FederatedServerFailure>
)

enum class FederatedContentSection {
    CONTINUE_WATCHING,
    FAVORITES,
    RECENT,
    LIBRARIES
}

/**
 * 使用每个已保存账户自己的 URL、token 和 userId 读取聚合内容，避免通过切换活动会话制造全局状态竞争。
 */
class FederatedMediaRepository(context: Context) {
    private val appContext = context.applicationContext
    private val authRepository = AuthRepositoryProvider.getInstance(appContext)
    private val secureSessionStore = SecureSessionStore(appContext)
    private val networkPreferences = NetworkPreferences(appContext)
    private val catalogLookupGate = Semaphore(MAX_CONCURRENT_SERVERS)

    fun availableServers(
        savedServers: List<AuthRepository.SavedServer> = authRepository.getActiveSessionSnapshot().savedServers
    ): List<FederatedServer> =
        authenticatedServers(savedServers).map { server ->
            FederatedServer(
                id = server.id,
                name = server.displayName()
            )
        }

    suspend fun search(
        query: String,
        selectedTypes: Set<SearchMediaType>,
        limitPerServer: Int = 60,
        selectedServerIds: Set<String>? = null
    ): FederatedMediaResponse = coroutineScope {
        val trimmedQuery = query.trim()
        if (trimmedQuery.isEmpty() || selectedTypes.isEmpty()) {
            return@coroutineScope FederatedMediaResponse(emptyList(), emptyList())
        }

        val servers = authenticatedServers().filter { selectedServerIds == null || it.id in selectedServerIds }
        val includeItemTypes = selectedTypes.joinToString(",") { type ->
            when (type) {
                SearchMediaType.MOVIE -> "Movie"
                SearchMediaType.SERIES -> "Series"
                SearchMediaType.EPISODE -> "Episode"
            }
        }
        // 手机端可能保存 4 台以上服务器；限制并发可避免瞬间占满连接池和图片请求队列。
        val semaphore = Semaphore(MAX_CONCURRENT_SERVERS)
        val outcomes = servers.map { server ->
            async {
                semaphore.withPermit {
                    searchServer(
                        server = server,
                        query = trimmedQuery,
                        includeItemTypes = includeItemTypes,
                        limit = limitPerServer
                    )
                }
            }
        }.awaitAll()

        FederatedMediaResponse(
            items = outcomes.flatMap { it.items },
            failures = outcomes.mapNotNull { it.failure }
        )
    }

    suspend fun loadContent(
        section: FederatedContentSection,
        excludedServerIds: Set<String> = emptySet()
    ): FederatedMediaResponse = coroutineScope {
        val servers = authenticatedServers().filter { server ->
            server.id !in excludedServerIds
        }
        val semaphore = Semaphore(MAX_CONCURRENT_SERVERS)
        val outcomes = servers.map { server ->
            async {
                semaphore.withPermit { loadServerContent(server, section) }
            }
        }.awaitAll()

        FederatedMediaResponse(
            items = outcomes.flatMap { it.items },
            failures = outcomes.mapNotNull { it.failure }
        )
    }

    /** Provider identity is mandatory; title similarity never grants playback. */
    suspend fun matchCatalog(type: String, tmdbId: Int, imdbId: String? = null): FederatedMediaResponse = coroutineScope {
        require(type == "movie" || type == "tv")
        require(tmdbId > 0)
        val gate = catalogLookupGate
        val outcomes = authenticatedServers().map { server -> async {
            gate.withPermit {
                try {
                    val token = secureSessionStore.getToken(server.id) ?: error("Missing access token")
                    val baseUrl = server.activeLine()?.url ?: server.serverUrl
                    val api = createApi(baseUrl, token, runCatching { ServerType.valueOf(server.serverTypeRaw) }.getOrNull())
                    val providerQuery = listOfNotNull("Tmdb.$tmdbId", imdbId?.takeIf { it.isNotBlank() }?.let { "Imdb.$it" }).joinToString(",")
                    val response = api.getUserItems(userId = server.userId, recursive = true,
                        includeItemTypes = if (type == "tv") "Series" else "Movie",
                        anyProviderIdEquals = providerQuery, limit = 100,
                        fields = "ProviderIds,MediaSources,MediaStreams,Chapters,UserData,Overview,RunTimeTicks,EpisodeCount")
                    check(response.isSuccessful) { "HTTP ${response.code()}" }
                    val items = response.body()?.items ?: error("Missing response")
                    val identity = com.vela.data.model.CatalogIdentity(type, tmdbId, imdbId)
                    // 再校验身份与媒体类型，兼容忽略请求过滤条件的服务器。
                    ServerOutcome(items.filter(identity::matches).map { item ->
                        FederatedMediaItem(item, server.id, server.displayName(), item.id?.let { buildImageUrl(baseUrl, it, token, "Primary", server.serverTypeRaw) },
                            server.activeLine()?.name?.takeIf { it.isNotBlank() })
                    })
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { ServerOutcome.failure(server, e.message ?: "Resource lookup failed") }
            }
        } }.awaitAll()
        val active = authRepository.getActiveSessionSnapshot().activeServerId
        FederatedMediaResponse(outcomes.flatMap { it.items }.sortedBy { it.serverId != active }, outcomes.mapNotNull { it.failure })
    }

    /** Read episodes using the matched server credentials without switching the active session. */
    suspend fun subscriptionEpisodes(match: FederatedMediaItem, season: Int): List<FederatedMediaItem> = catalogLookupGate.withPermit {
        require(season >= 0)
        val server = authenticatedServers().firstOrNull { it.id == match.serverId } ?: error("Server signed out")
        val token = secureSessionStore.getToken(server.id) ?: error("Missing access token")
        val baseUrl = server.activeLine()?.url ?: server.serverUrl
        val api = createApi(baseUrl, token, runCatching { ServerType.valueOf(server.serverTypeRaw) }.getOrNull())
        val items = mutableListOf<BaseItemDto>()
        var start = 0
        // Bound malformed pagination; an incomplete result must not look like a complete season.
        while (true) {
            check(start < 10000) { "Episode list exceeds supported size" }
            val response = api.getEpisodes(requireNotNull(match.item.id), server.userId,
                fields = "UserData,SeriesName,SeriesId,IndexNumber,ParentIndexNumber,RunTimeTicks,ProviderIds",
                limit = 200, startIndex = start)
            check(response.isSuccessful) { "HTTP ${response.code()}" }
            val page = response.body() ?: error("Missing episode response")
            val batch = page.items.orEmpty()
            val knownIds = items.mapNotNull { it.id }.toSet()
            check(batch.none { it.id != null && it.id in knownIds }) { "Episode pagination did not advance; refresh the library" }
            check(batch.isNotEmpty() || (page.totalRecordCount ?: start) <= start) { "Incomplete episode response" }
            items.addAll(batch)
            start += batch.size
            if (batch.isEmpty() || (page.totalRecordCount?.let { start >= it } ?: (batch.size < 200))) break
        }
        items.filter { it.type == "Episode" && it.parentIndexNumber == season && !it.id.isNullOrBlank() }
            .distinctBy { it.id }.map { match.copy(item = it) }
    }

    private fun authenticatedServers(
        savedServers: List<AuthRepository.SavedServer> = authRepository.getActiveSessionSnapshot().savedServers
    ): List<AuthRepository.SavedServer> =
        savedServers
            .filter { server ->
                !server.isPrivate && server.userId.isNotBlank() && secureSessionStore.hasToken(server.id)
            }
            .distinctBy { it.id }

    private suspend fun searchServer(
        server: AuthRepository.SavedServer,
        query: String,
        includeItemTypes: String,
        limit: Int
    ): ServerOutcome {
        val token = secureSessionStore.getToken(server.id)
            ?: return ServerOutcome.failure(server, "Missing access token")
        val serverType = runCatching { ServerType.valueOf(server.serverTypeRaw) }.getOrNull()
        // 搜索遵循该账户当前选中的线路，但不会写回或切换全局活动服务器。
        val baseUrl = server.activeLine()?.url ?: server.serverUrl

        return try {
            val api = createApi(baseUrl, token, serverType)
            var response = api.searchItems(
                userId = server.userId,
                searchTerm = query,
                includeItemTypes = includeItemTypes,
                recursive = true,
                limit = limit,
                fields = SEARCH_FIELDS
            )
            // 部分 Emby 版本对拉丁文本的 searchTerm 返回空，保留现有搜索使用的前缀兼容路径。
            if (
                query.any { it in 'A'..'Z' || it in 'a'..'z' } &&
                (!response.isSuccessful || response.body()?.items.isNullOrEmpty())
            ) {
                response = api.searchItemsByName(
                    userId = server.userId,
                    nameStartsWith = query,
                    includeItemTypes = includeItemTypes,
                    recursive = true,
                    limit = limit,
                    fields = SEARCH_FIELDS
                )
            }

            if (!response.isSuccessful || response.body() == null) {
                return ServerOutcome.failure(
                    server,
                    "HTTP ${response.code()} ${response.message()}".trim()
                )
            }

            val items = response.body()?.items.orEmpty()
                .filter { item -> !item.id.isNullOrBlank() }
                .distinctBy { item -> item.id }
                .map { item ->
                    FederatedMediaItem(
                        item = item,
                        serverId = server.id,
                        serverName = server.displayName(),
                        imageUrl = item.id?.let { itemId ->
                            buildImageUrl(baseUrl, itemId, token, "Primary", server.serverTypeRaw)
                        }
                    )
                }
            ServerOutcome(items = items)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            ServerOutcome.failure(
                server,
                error.message ?: error::class.java.simpleName
            )
        }
    }

    private suspend fun loadServerContent(
        server: AuthRepository.SavedServer,
        section: FederatedContentSection
    ): ServerOutcome {
        val token = secureSessionStore.getToken(server.id)
            ?: return ServerOutcome.failure(server, "Missing access token")
        val serverType = runCatching { ServerType.valueOf(server.serverTypeRaw) }.getOrNull()
        val baseUrl = server.activeLine()?.url ?: server.serverUrl

        return try {
            val api = createApi(baseUrl, token, serverType)
            val itemsResult = fetchServerContent(api, server.userId, section)
            val items = itemsResult.getOrElse { error ->
                return ServerOutcome.failure(
                    server,
                    error.message ?: error::class.java.simpleName
                )
            }
            ServerOutcome(
                items = items
                    .filter { item -> !item.id.isNullOrBlank() }
                    .distinctBy { item -> item.id }
                    .map { item ->
                        val (imageItemId, imageType) = imageSpec(item, section)
                        FederatedMediaItem(
                            item = item,
                            serverId = server.id,
                            serverName = server.displayName(),
                            imageUrl = imageItemId?.let { itemId ->
                                buildImageUrl(baseUrl, itemId, token, imageType, server.serverTypeRaw)
                            }
                        )
                    }
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            ServerOutcome.failure(server, error.message ?: error::class.java.simpleName)
        }
    }

    private suspend fun fetchServerContent(
        api: MediaServerApi,
        userId: String,
        section: FederatedContentSection
    ): Result<List<BaseItemDto>> {
        val response = when (section) {
            FederatedContentSection.CONTINUE_WATCHING -> api.getResumeItems(
                userId = userId,
                includeItemTypes = "Movie,Series,Episode",
                limit = CONTENT_LIMIT_PER_SERVER,
                recursive = true,
                sortBy = "DatePlayed",
                sortOrder = "Descending",
                fields = CONTENT_FIELDS
            )
            FederatedContentSection.RECENT -> api.getUserItems(
                userId = userId, includeItemTypes = "Movie,Episode", recursive = true,
                sortBy = "DateCreated", sortOrder = "Descending", limit = CONTENT_LIMIT_PER_SERVER,
                fields = "$CONTENT_FIELDS,DateCreated"
            )
            FederatedContentSection.FAVORITES -> api.getUserItems(
                userId = userId,
                includeItemTypes = "Movie,Series,Episode",
                recursive = true,
                sortBy = "DateCreated",
                sortOrder = "Descending",
                limit = CONTENT_LIMIT_PER_SERVER,
                filters = "IsFavorite",
                fields = CONTENT_FIELDS
            )
            FederatedContentSection.LIBRARIES -> api.getUserViews(userId)
        }
        if (!response.isSuccessful || response.body() == null) {
            return Result.failure(
                IllegalStateException("HTTP ${response.code()} ${response.message()}".trim())
            )
        }

        val items = response.body()?.items.orEmpty().let { loadedItems ->
            if (section == FederatedContentSection.LIBRARIES) {
                loadedItems.filter { item ->
                    item.collectionType !in setOf("boxsets", "playlists", "folders") &&
                        (item.type == "CollectionFolder" || item.type == "Folder")
                }
            } else {
                loadedItems
            }
        }
        return Result.success(items)
    }

    private fun createApi(
        baseUrl: String,
        token: String,
        serverType: ServerType?
    ): MediaServerApi = NetworkModule.createMediaServerApi(
        baseUrl = baseUrl,
        accessToken = token,
        serverType = serverType,
        // 多账户不能同时让独立 OkHttp Cache 占用同一目录；API 客户端本身仍按凭据缓存。
        storageDir = null,
        timeoutConfig = networkPreferences.getTimeoutConfig()
    )

    private fun imageSpec(
        item: BaseItemDto,
        section: FederatedContentSection
    ): Pair<String?, String> = when (section) {
        FederatedContentSection.CONTINUE_WATCHING -> when {
            !item.parentThumbItemId.isNullOrBlank() -> item.parentThumbItemId to "Thumb"
            item.imageTags?.containsKey("Thumb") == true -> item.id to "Thumb"
            !item.backdropImageTags.isNullOrEmpty() -> item.id to "Backdrop"
            !item.seriesId.isNullOrBlank() && !item.seriesThumbImageTag.isNullOrBlank() ->
                item.seriesId to "Thumb"
            else -> (item.parentPrimaryImageItemId ?: item.id) to "Primary"
        }
        FederatedContentSection.FAVORITES, FederatedContentSection.RECENT ->
            (if (item.type == "Episode") item.seriesId ?: item.id else item.id) to "Primary"
        FederatedContentSection.LIBRARIES -> item.id to "Primary"
    }

    private fun buildImageUrl(
        baseUrl: String,
        itemId: String,
        token: String,
        imageType: String,
        serverTypeRaw: String
    ): String {
        val tokenParameter = runCatching { ServerType.valueOf(serverTypeRaw) }.getOrNull().tokenQueryParameter
        val encodedToken = URLEncoder.encode(token, Charsets.UTF_8.name())
        return "${trimTrailingSlash(baseUrl)}/Items/$itemId/Images/$imageType" +
            "?maxWidth=640&quality=85&$tokenParameter=$encodedToken"
    }

    private data class ServerOutcome(
        val items: List<FederatedMediaItem> = emptyList(),
        val failure: FederatedServerFailure? = null
    ) {
        companion object {
            fun failure(server: AuthRepository.SavedServer, message: String) = ServerOutcome(
                failure = FederatedServerFailure(
                    serverId = server.id,
                    serverName = server.displayName(),
                    message = message
                )
            )
        }
    }

    private companion object {
        const val MAX_CONCURRENT_SERVERS = 3
        const val CONTENT_LIMIT_PER_SERVER = 30
        const val SEARCH_FIELDS =
            "ChildCount,RecursiveItemCount,EpisodeCount,SeriesName,SeriesId,Genres," +
                "CommunityRating,ProductionYear,Overview,IndexNumber,ParentIndexNumber"
        const val CONTENT_FIELDS =
            "ChildCount,RecursiveItemCount,EpisodeCount,SeriesName,SeriesId,Genres," +
                "CommunityRating,ProductionYear,Overview,IndexNumber,ParentIndexNumber," +
                "RunTimeTicks,UserData,ParentPrimaryImageItemId,CollectionType,ImageTags," +
                "BackdropImageTags,ParentThumbItemId,ParentThumbImageTag,SeriesThumbImageTag"
    }
}
