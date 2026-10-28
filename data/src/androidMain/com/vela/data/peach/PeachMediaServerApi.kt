package com.vela.data.peach

import com.vela.data.api.MediaServerApi
import com.vela.data.model.ActivityLogResult
import com.vela.data.model.AdminSessionInfo
import com.vela.data.model.AuthenticationRequest
import com.vela.data.model.AuthenticationResult
import com.vela.data.model.BaseItemDto
import com.vela.data.model.CultureInfo
import com.vela.data.model.DisplayPreferencesDto
import com.vela.data.model.ExternalIdInfo
import com.vela.data.model.ItemIdResult
import com.vela.data.model.ItemImageInfo
import com.vela.data.model.LyricsDto
import com.vela.data.model.MediaLibraryQuery
import com.vela.data.model.MediaSource
import com.vela.data.model.MediaStream
import com.vela.data.model.PlaybackInfoRequest
import com.vela.data.model.PlaybackInfoResponse
import com.vela.data.model.PlaybackProgressRequest
import com.vela.data.model.PlaybackStartRequest
import com.vela.data.model.PlaybackStoppedRequest
import com.vela.data.model.QueryResult
import com.vela.data.model.QuickConnectDto
import com.vela.data.model.QuickConnectResult
import com.vela.data.model.RecommendationDto
import com.vela.data.model.RemoteImageResult
import com.vela.data.model.RemoteSearchQuery
import com.vela.data.model.RemoteSearchResult
import com.vela.data.model.RemoteSubtitleInfo
import com.vela.data.model.ServerInfo
import com.vela.data.model.SystemInfoFull
import com.vela.data.model.UserConfiguration
import com.vela.data.model.UserDto
import com.vela.data.model.UserPolicy
import com.vela.data.network.ApiResponse
import kotlinx.serialization.json.JsonObject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * 用 Peach 的 `/api/…` 实现 Vela 的媒体服务器接口，让首页、媒体库、详情、搜索、
 * 收藏与播放上报不必逐处区分服务器类型。Peach 没有的能力（元数据编辑、服务器管理、
 * 剧集结构等）返回 501，调用方按失败处理。
 */
class PeachMediaServerApi(
    val peach: PeachClient
) : MediaServerApi {

    /** 实体 ID → 规范名。`/api/items` 的实体筛选按名字查，不按 ID。 */
    private val entityNames = ConcurrentHashMap<String, String>()

    /** 每个播放会话的进度累计，用来算 `/api/activity` 要的 `delta` 与 `seeks`。 */
    private val playbackSessions = ConcurrentHashMap<String, PlaybackTracker>()

    private data class PlaybackTracker(
        val assetId: Long,
        val durationSeconds: Double,
        var lastPositionSeconds: Double,
        var lastReportedAtMs: Long,
        var pendingSeeks: Int = 0
    )

    // region 浏览

    override suspend fun getUserViews(userId: String, includeHidden: Boolean?): ApiResponse<QueryResult<BaseItemDto>> =
        call {
            val views = PeachLibrary.parseList(peach.get("/api/libraries")).map(PeachMapper::libraryToView)
            QueryResult(items = views, totalRecordCount = views.size, startIndex = 0)
        }

    override suspend fun getMediaLibraryItems(userId: String, query: MediaLibraryQuery): ApiResponse<QueryResult<BaseItemDto>> =
        call {
            queryItems(
                parentId = query.parentId,
                includeItemTypes = query.includeItemTypes,
                searchTerm = query.searchTerm,
                startIndex = query.startIndex,
                limit = query.limit,
                sortBy = query.sortBy,
                sortOrder = query.sortOrder,
                filters = query.filters,
                personIds = query.personIds,
                genreIds = query.genreIds
            )
        }

    override suspend fun getUserItems(
        userId: String,
        parentId: String?,
        personIds: String?,
        genres: String?,
        genreIds: String?,
        includeItemTypes: String?,
        recursive: Boolean?,
        sortBy: String?,
        sortOrder: String?,
        limit: Int?,
        startIndex: Int?,
        filters: String?,
        anyProviderIdEquals: String?,
        tags: String?,
        fields: String?
    ): ApiResponse<QueryResult<BaseItemDto>> = call {
        val playlistId = PeachIds.playlistId(parentId)
        if (playlistId != null) {
            return@call playlistItems(playlistId, startIndex ?: 0, limit ?: DEFAULT_LIMIT)
        }
        queryItems(
            parentId = parentId,
            includeItemTypes = includeItemTypes,
            searchTerm = null,
            startIndex = startIndex ?: 0,
            limit = limit ?: DEFAULT_LIMIT,
            sortBy = sortBy,
            sortOrder = sortOrder,
            filters = filters,
            personIds = personIds,
            genreIds = listOfNotNull(genreIds, genres, tags).joinToString(",").takeIf { it.isNotBlank() },
            providerCode = anyProviderIdEquals
                ?.split(',')
                ?.firstNotNullOfOrNull { entry ->
                    entry.substringAfter('.', "").takeIf { entry.startsWith("${PeachMapper.PROVIDER_CODE}.", ignoreCase = true) }
                }
        )
    }

    override suspend fun getLatestItems(
        userId: String,
        parentId: String?,
        includeItemTypes: String?,
        limit: Int?,
        fields: String?
    ): ApiResponse<List<BaseItemDto>> = call {
        queryItems(
            parentId = parentId,
            includeItemTypes = includeItemTypes,
            searchTerm = null,
            startIndex = 0,
            limit = limit ?: 20,
            sortBy = "DateCreated",
            sortOrder = "Descending",
            filters = null,
            personIds = null,
            genreIds = null,
            includeTotal = false
        ).items.orEmpty()
    }

    override suspend fun getResumeItems(
        userId: String,
        parentId: String?,
        includeItemTypes: String?,
        limit: Int?,
        startIndex: Int?,
        recursive: Boolean,
        sortBy: String,
        sortOrder: String,
        fields: String?
    ): ApiResponse<QueryResult<BaseItemDto>> = call {
        queryItems(
            parentId = parentId,
            includeItemTypes = includeItemTypes,
            searchTerm = null,
            startIndex = startIndex ?: 0,
            limit = limit ?: 20,
            sortBy = "DatePlayed",
            sortOrder = "Descending",
            filters = "IsResumable",
            personIds = null,
            genreIds = null,
            includeTotal = false
        )
    }

    override suspend fun getNextUp(
        userId: String,
        seriesId: String?,
        parentId: String?,
        limit: Int?,
        startIndex: Int?,
        legacyNextUp: Boolean?,
        fields: String?,
        enableUserData: Boolean?,
        enableImages: Boolean?,
        imageTypeLimit: Int?,
        enableImageTypes: String?
    ): ApiResponse<QueryResult<BaseItemDto>> = success(emptyQueryResult())

    override suspend fun getSuggestions(
        endpoint: String,
        userId: String?,
        mediaType: String?,
        type: String?,
        includeItemTypes: String?,
        limit: Int?,
        fields: String?
    ): ApiResponse<QueryResult<BaseItemDto>> = call {
        // 「每日轮换」是 Peach 首页的默认排序：同一天顺序固定，隔天换一批。
        val page = PeachItemPage.parse(
            peach.get("/api/items", listOf("sort" to "daily", "limit" to (limit ?: 20), "count" to 0))
        )
        page.toQueryResult(0)
    }

    override suspend fun getMovieRecommendations(
        userId: String,
        parentId: String?,
        categoryLimit: Int?,
        itemLimit: Int?,
        fields: String?
    ): ApiResponse<List<RecommendationDto>> = success(emptyList())

    override suspend fun getItemById(userId: String, itemId: String, fields: String?): ApiResponse<BaseItemDto> = call {
        resolveItem(itemId)
    }

    override suspend fun getSimilarItems(
        itemId: String,
        userId: String,
        limit: Int?,
        fields: String?
    ): ApiResponse<QueryResult<BaseItemDto>> = call {
        val assetId = PeachIds.assetId(itemId) ?: return@call emptyQueryResult()
        val json = peach.get("/api/related", listOf("id" to assetId, "limit" to min(limit ?: 24, 60)))
        val items = json.objects("items").mapNotNull(PeachAsset::parse).map(::mapAsset)
        QueryResult(items = items, totalRecordCount = items.size, startIndex = 0)
    }

    override suspend fun getAdditionalParts(itemId: String, userId: String?): ApiResponse<QueryResult<BaseItemDto>> = call {
        val assetId = PeachIds.assetId(itemId) ?: return@call emptyQueryResult()
        val parts = runCatching { peach.get("/api/parts", listOf("id" to assetId)) }
            .getOrNull()
            ?.objects("items")
            ?.mapNotNull(PeachAsset::parse)
            .orEmpty()
            .filterNot { it.id == assetId }
            .map(::mapAsset)
        QueryResult(items = parts, totalRecordCount = parts.size, startIndex = 0)
    }

    override suspend fun getMediaLibraryPeople(
        userId: String,
        parentId: String?,
        personTypes: String,
        searchTerm: String?,
        startIndex: Int,
        limit: Int
    ): ApiResponse<QueryResult<BaseItemDto>> = call {
        val kind = if (personTypes.contains("Director", ignoreCase = true)) "creators" else "performers"
        val json = peach.get(
            "/api/index",
            listOf("kind" to kind, "q" to searchTerm, "limit" to limit.coerceIn(1, 600), "offset" to startIndex)
        )
        val entityKind = kind.removeSuffix("s")
        val people = PeachIndexEntry.parseList(json).map { entry ->
            entry.id?.let { entityNames["$entityKind:$it"] = entry.name }
            PeachMapper.entityToPerson(entityKind, entry.id ?: 0L, entry.name, entry.hasImage).copy(
                childCount = entry.count
            )
        }
        val total = json.int("total") ?: (startIndex + people.size + if (json.bool("has_more") == true) 1 else 0)
        QueryResult(items = people, totalRecordCount = total, startIndex = startIndex)
    }

    override suspend fun getGenres(
        userId: String,
        parentId: String?,
        includeItemTypes: String?,
        recursive: Boolean?,
        sortBy: String?,
        sortOrder: String?,
        enableTotalRecordCount: Boolean?,
        enableImages: Boolean?,
        startIndex: Int?,
        limit: Int?
    ): ApiResponse<QueryResult<BaseItemDto>> = call {
        val json = peach.get(
            "/api/index",
            listOf("kind" to "tags", "limit" to (limit ?: 180).coerceIn(1, 600), "offset" to (startIndex ?: 0))
        )
        val genres = PeachIndexEntry.parseList(json).map { entry ->
            BaseItemDto(
                name = entry.name,
                id = tagGenreId(entry.name),
                type = "Genre",
                childCount = entry.count
            )
        }
        QueryResult(items = genres, totalRecordCount = json.int("total") ?: genres.size, startIndex = startIndex ?: 0)
    }

    override suspend fun getItemsByGenre(
        userId: String,
        genreIds: String,
        includeItemTypes: String?,
        recursive: Boolean?,
        limit: Int?,
        sortBy: String?,
        sortOrder: String?,
        fields: String?
    ): ApiResponse<QueryResult<BaseItemDto>> = call {
        queryItems(
            parentId = null,
            includeItemTypes = includeItemTypes,
            searchTerm = null,
            startIndex = 0,
            limit = limit ?: DEFAULT_LIMIT,
            sortBy = sortBy,
            sortOrder = sortOrder,
            filters = null,
            personIds = null,
            genreIds = genreIds
        )
    }

    override suspend fun getSeasons(seriesId: String, userId: String, fields: String?): ApiResponse<QueryResult<BaseItemDto>> =
        success(emptyQueryResult())

    override suspend fun getEpisodes(
        seriesId: String,
        userId: String,
        seasonId: String?,
        fields: String?,
        limit: Int?,
        startIndex: Int?,
        adjacentTo: String?
    ): ApiResponse<QueryResult<BaseItemDto>> = success(emptyQueryResult())

    override suspend fun searchItems(
        userId: String,
        searchTerm: String,
        includeItemTypes: String?,
        recursive: Boolean,
        limit: Int?,
        startIndex: Int?,
        sortBy: String?,
        sortOrder: String?,
        genres: String?,
        fields: String?
    ): ApiResponse<QueryResult<BaseItemDto>> = call {
        queryItems(
            parentId = null,
            includeItemTypes = includeItemTypes,
            searchTerm = searchTerm,
            startIndex = startIndex ?: 0,
            limit = limit ?: 50,
            sortBy = sortBy,
            sortOrder = sortOrder,
            filters = null,
            personIds = null,
            genreIds = genres
        )
    }

    override suspend fun searchItemsByName(
        userId: String,
        nameStartsWith: String,
        includeItemTypes: String?,
        recursive: Boolean,
        limit: Int?,
        fields: String?
    ): ApiResponse<QueryResult<BaseItemDto>> =
        searchItems(userId, nameStartsWith, includeItemTypes, recursive, limit, null, null, null, null, fields)

    override suspend fun getPlaylistItems(
        userId: String,
        playlistId: String,
        startIndex: Int,
        limit: Int
    ): ApiResponse<QueryResult<BaseItemDto>> = call {
        playlistItems(PeachIds.playlistId(playlistId) ?: playlistId, startIndex, limit)
    }

    // endregion

    // region 互动

    /** 收藏映射为「稍后看」。服务端是切换语义，先读当前状态，已经一致就不再切。 */
    override suspend fun markAsFavorite(userId: String, itemId: String): ApiResponse<Unit> =
        setWatchLater(itemId, true)

    override suspend fun unmarkAsFavorite(userId: String, itemId: String): ApiResponse<Unit> =
        setWatchLater(itemId, false)

    override suspend fun markAsPlayed(userId: String, itemId: String): ApiResponse<Unit> =
        setFeedback(itemId, "seen", true)

    override suspend fun unmarkAsPlayed(userId: String, itemId: String): ApiResponse<Unit> =
        setFeedback(itemId, "seen", false)

    private suspend fun setWatchLater(itemId: String, queued: Boolean): ApiResponse<Unit> = call {
        val assetId = requireAsset(itemId)
        val current = PeachAsset.parse(peach.get("/api/item", listOf("id" to assetId)))?.watchLater
        if (current != queued) {
            peach.post("/api/watch-later", peachBody("id" to assetId))
        }
    }

    private suspend fun setFeedback(itemId: String, kind: String, enabled: Boolean): ApiResponse<Unit> = call {
        val assetId = requireAsset(itemId)
        val current = PeachAsset.parse(peach.get("/api/item", listOf("id" to assetId)))?.feedback
        if ((current == kind) != enabled) {
            peach.post("/api/feedback", peachBody("id" to assetId, "kind" to kind))
        }
    }

    // endregion

    // region 播放

    override suspend fun getPlaybackInfoPost(itemId: String, request: PlaybackInfoRequest): ApiResponse<PlaybackInfoResponse> =
        call { playbackInfo(itemId) }

    override suspend fun getPlaybackInfoGet(
        itemId: String,
        userId: String,
        maxStreamingBitrate: Int?,
        audioStreamIndex: Int?,
        subtitleStreamIndex: Int?,
        enableDirectPlay: Boolean?,
        enableDirectStream: Boolean?,
        enableTranscoding: Boolean?
    ): ApiResponse<PlaybackInfoResponse> = call { playbackInfo(itemId) }

    /**
     * Peach 没有 PlaybackInfo：用作品详情和字幕列表拼一个媒体源。真正的播放地址由
     * [streamPlan] 按会话取，会话 ID 就是这里的 `playSessionId`，停止时用它释放服务端转码。
     */
    private suspend fun playbackInfo(itemId: String): PlaybackInfoResponse {
        val assetId = requireAsset(itemId)
        val asset = PeachAsset.parse(peach.get("/api/item", listOf("id" to assetId)))
            ?: throw PeachApiException(404, PeachApiException.Kind.NOT_FOUND, "作品不存在")
        val subtitles = runCatching {
            PeachSubtitleTrack.parseList(peach.get("/api/assets/$assetId/subtitles"))
        }.getOrDefault(emptyList())
        val memory = subtitleMemory(assetId)
        val durationTicks = asset.durationSeconds?.let { (it * PeachMapper.TICKS_PER_SECOND).toLong() }
        val streams = buildList {
            add(
                MediaStream(
                    type = "Video",
                    index = 0,
                    width = asset.width,
                    height = asset.height,
                    isDefault = true
                )
            )
            // 字幕轨的 `comment` 记 Peach 的轨道键，选择变化时据此写回字幕记忆。
            var position = 0
            subtitles.filter { it.playable && it.src != null }.forEach { track ->
                add(
                    subtitleStream(
                        index = SUBTITLE_INDEX_BASE + position++,
                        key = track.legacyId ?: track.id,
                        title = track.label ?: track.name,
                        language = track.language,
                        url = peach.absoluteUrl(track.src!!)
                    )
                )
            }
            memory?.tracks?.forEach { track ->
                add(
                    subtitleStream(
                        index = SUBTITLE_INDEX_BASE + position++,
                        key = track.key,
                        title = track.name,
                        language = null,
                        url = peach.absoluteUrl(track.src)
                    )
                )
            }
        }
        val rememberedSubtitle = memory?.selected?.let { key ->
            streams.firstOrNull { it.type == "Subtitle" && it.comment == key }?.index
        }
        return PlaybackInfoResponse(
            mediaSources = listOf(
                MediaSource(
                    id = assetId.toString(),
                    name = asset.editionLabel ?: asset.name,
                    container = asset.container,
                    size = asset.size,
                    runTimeTicks = durationTicks,
                    isRemote = asset.location == "online" || asset.isStrm,
                    supportsDirectPlay = true,
                    supportsDirectStream = true,
                    supportsTranscoding = false,
                    mediaStreams = streams,
                    defaultSubtitleStreamIndex = rememberedSubtitle
                )
            ),
            playSessionId = UUID.randomUUID().toString()
        )
    }

    private fun subtitleStream(index: Int, key: String, title: String?, language: String?, url: String) = MediaStream(
        type = "Subtitle",
        index = index,
        codec = "webvtt",
        language = language,
        title = title,
        displayTitle = title ?: language,
        comment = key,
        isExternal = true,
        isTextSubtitleStream = true,
        supportsExternalStream = true,
        deliveryMethod = "External",
        deliveryUrl = url
    )

    // region 字幕与播放记忆、在线字幕

    suspend fun subtitleMemory(assetId: Long): PeachSubtitleMemory? = runCatching {
        PeachSubtitleMemory.parse(
            peach.get("/api/subtitles/memory", listOf("video" to PeachSubtitleMemory.videoKey(assetId)))
        )
    }.getOrNull()

    suspend fun playerRate(assetId: Long): Double? = runCatching {
        PeachSubtitleMemory.parse(
            peach.get("/api/player/memory", listOf("video" to PeachSubtitleMemory.videoKey(assetId)))
        ).rate
    }.getOrNull()

    /** 倍速写回播放记忆；旋转与字幕外观仍保留本机偏好，不改。 */
    suspend fun savePlayerRate(assetId: Long, rate: Double) {
        peach.put(
            "/api/player/memory",
            peachBody("rate" to rate.coerceIn(0.25, 3.0)),
            listOf("video" to PeachSubtitleMemory.videoKey(assetId))
        )
    }

    /** 记住字幕选择。[key] 为 null 表示关闭字幕；Peach 不接受的键（按文件发现的配对字幕）不写。 */
    suspend fun saveSubtitleSelection(assetId: Long, key: String?) {
        if (key != null && !isRememberableKey(key)) return
        peach.put(
            "/api/subtitles/memory",
            kotlinx.serialization.json.buildJsonObject {
                put("selected", key?.let { kotlinx.serialization.json.JsonPrimitive(it) } ?: kotlinx.serialization.json.JsonNull)
            },
            listOf("video" to PeachSubtitleMemory.videoKey(assetId))
        )
    }

    private fun isRememberableKey(key: String): Boolean =
        key.startsWith("native:") || Regex("sidecar:[1-9][0-9]*").matches(key) || !key.startsWith("sidecar:")

    suspend fun searchOnlineSubtitles(query: String, offset: Int = 0): Pair<List<PeachOnlineSubtitle>, Boolean> {
        val json = peach.get("/api/subtitles/search", listOf("q" to query.trim().take(200), "offset" to offset))
        return PeachOnlineSubtitle.parseList(json) to (json.bool("more") == true)
    }

    suspend fun onlineSubtitleFiles(subtitleId: String): List<PeachOnlineSubtitleFile> =
        PeachOnlineSubtitleFile.parseList(peach.get("/api/subtitles/subtitlecat/${subtitleId.encodePathSegment()}"))

    /**
     * 与网页相同：取已转好的 WebVTT，连同选择一起存进字幕记忆，下次播放（含网页端）默认使用。
     */
    suspend fun saveOnlineSubtitle(assetId: Long, subtitleId: String, file: PeachOnlineSubtitleFile) {
        val text = peach.getText("/api/subtitles/subtitlecat/${subtitleId.encodePathSegment()}/${file.index}")
        val key = "subtitlecat:$subtitleId:${file.index}"
        peach.put(
            "/api/subtitles/memory",
            peachBody(
                "selected" to key,
                "track" to mapOf("key" to key, "name" to file.name, "text" to text)
            ),
            listOf("video" to PeachSubtitleMemory.videoKey(assetId))
        )
    }

    private fun String.encodePathSegment(): String = java.net.URLEncoder.encode(this, "UTF-8").replace("+", "%20")

    // endregion

    /** `GET /api/stream-plan`：410 表示会话已被取消，415 表示 ISO 原盘只能外部播放。 */
    suspend fun streamPlan(assetId: Long, session: String, mode: String? = null): PeachStreamPlan {
        val json = peach.get("/api/stream-plan", listOf("id" to assetId, "session" to session, "mode" to mode))
        return PeachStreamPlan.parse(json)
            ?: throw PeachApiException(500, PeachApiException.Kind.OTHER, "Peach 没有返回播放地址")
    }

    suspend fun cancelStream(session: String) {
        runCatching { peach.post("/api/stream-cancel", query = listOf("session" to session)) }
    }

    override suspend fun getVideoStreamUrl(
        itemId: String,
        static: Boolean,
        mediaSourceId: String?,
        deviceId: String?,
        apiKey: String?
    ): String = peach.absoluteUrl("/stream?id=${PeachIds.assetId(itemId) ?: itemId}")

    override suspend fun reportPlaybackStart(request: PlaybackStartRequest): ApiResponse<Unit> = call {
        val assetId = PeachIds.assetId(request.itemId) ?: return@call
        val asset = PeachAsset.parse(peach.get("/api/item", listOf("id" to assetId)))
        peach.post("/api/play", peachBody("id" to assetId))
        val position = (request.positionTicks ?: 0L).toSeconds()
        playbackSessions[sessionKey(request.playSessionId, request.itemId)] = PlaybackTracker(
            assetId = assetId,
            durationSeconds = asset?.durationSeconds ?: 0.0,
            lastPositionSeconds = position,
            lastReportedAtMs = System.currentTimeMillis()
        )
    }

    override suspend fun reportPlaybackProgress(request: PlaybackProgressRequest): ApiResponse<Unit> = call {
        val tracker = playbackSessions[sessionKey(request.playSessionId, request.itemId)] ?: return@call
        reportActivity(tracker, request.positionTicks.toSeconds(), paused = request.isPaused, ended = false)
    }

    override suspend fun reportPlaybackStopped(request: PlaybackStoppedRequest): ApiResponse<Unit> = call {
        val key = sessionKey(request.playSessionId, request.itemId)
        val tracker = playbackSessions.remove(key)
        request.playSessionId?.let { cancelStream(it) }
        if (tracker == null) return@call
        val position = request.positionTicks?.toSeconds() ?: tracker.lastPositionSeconds
        val ended = tracker.durationSeconds > 0 && position >= tracker.durationSeconds * ENDED_RATIO
        reportActivity(tracker, position, paused = false, ended = ended)
    }

    /**
     * `delta` 是两次上报之间真实看过的秒数：取播放位置的增量与墙钟时间的较小者，
     * 位置跳变超过墙钟时间较多时记一次拖动，增量不计入观看时长。
     */
    private suspend fun reportActivity(tracker: PlaybackTracker, position: Double, paused: Boolean, ended: Boolean) {
        val now = System.currentTimeMillis()
        val wallSeconds = max(0.0, (now - tracker.lastReportedAtMs) / 1000.0)
        val moved = position - tracker.lastPositionSeconds
        val seeked = abs(moved - wallSeconds) > SEEK_TOLERANCE_SECONDS && !paused
        val delta = when {
            paused -> 0.0
            seeked -> min(wallSeconds, max(0.0, moved)).coerceAtMost(wallSeconds)
            else -> max(0.0, min(moved, wallSeconds))
        }
        if (seeked) tracker.pendingSeeks += 1
        peach.post(
            "/api/activity",
            peachBody(
                "id" to tracker.assetId,
                "position" to position,
                "duration" to tracker.durationSeconds,
                "delta" to delta,
                "ended" to ended,
                "seeks" to tracker.pendingSeeks
            )
        )
        tracker.pendingSeeks = 0
        tracker.lastPositionSeconds = position
        tracker.lastReportedAtMs = now
    }

    private fun sessionKey(playSessionId: String?, itemId: String): String = "${playSessionId.orEmpty()}|$itemId"

    private fun Long.toSeconds(): Double = this / PeachMapper.TICKS_PER_SECOND.toDouble()

    // endregion

    // region 账户与服务器

    override suspend fun getPublicSystemInfo(): ApiResponse<ServerInfo> = call {
        val version = PeachServerVersion.parse(peach.get("/api/client-updates"))
        ServerInfo(serverName = "Peach", productName = PRODUCT_NAME, version = version.version)
    }

    override suspend fun getSystemInfo(): ApiResponse<SystemInfoFull> = call {
        val version = PeachServerVersion.parse(peach.get("/api/client-updates"))
        SystemInfoFull(serverName = "Peach", version = version.version)
    }

    override suspend fun getUserById(userId: String): ApiResponse<UserDto> = success(peachUser())

    override suspend fun updateUserConfiguration(userId: String, configuration: UserConfiguration): ApiResponse<Unit> =
        success(Unit)

    override suspend fun getDisplayPreferences(
        displayPreferencesId: String,
        userId: String,
        client: String
    ): ApiResponse<DisplayPreferencesDto> =
        success(DisplayPreferencesDto(id = displayPreferencesId, sortBy = "DateCreated", sortOrder = "Descending", client = client))

    override suspend fun updateDisplayPreferences(
        displayPreferencesId: String,
        userId: String,
        client: String,
        preferences: DisplayPreferencesDto
    ): ApiResponse<Unit> = success(Unit)

    override suspend fun authenticateByName(request: AuthenticationRequest): ApiResponse<AuthenticationResult> = unsupported()

    override suspend fun initiateQuickConnect(): ApiResponse<QuickConnectResult> = unsupported()

    override suspend fun authenticateWithQuickConnect(request: QuickConnectDto): ApiResponse<AuthenticationResult> = unsupported()

    override suspend fun getActiveSessions(): ApiResponse<List<AdminSessionInfo>> = success(emptyList())

    override suspend fun getActivityLog(startIndex: Int?, limit: Int?): ApiResponse<ActivityLogResult> = unsupported()

    // endregion

    // region 播放列表

    override suspend fun createPlaylist(name: String, ids: String, userId: String): ApiResponse<ItemIdResult> = call {
        val created = peach.post(
            "/api/playlist",
            peachBody("action" to "create", "name" to name.take(80), "asset_ids" to assetIds(ids))
        )
        val playlistId = created.obj("playlist")?.string("id") ?: created.string("id")
            ?: throw PeachApiException(500, PeachApiException.Kind.OTHER, "Peach 没有返回播放列表 ID")
        ItemIdResult(id = PeachIds.playlist(playlistId))
    }

    override suspend fun addToPlaylist(playlistId: String, ids: String, userId: String): ApiResponse<Unit> = call {
        val id = PeachIds.playlistId(playlistId) ?: playlistId
        peach.post("/api/playlist", peachBody("action" to "add", "id" to (id.toLongOrNull() ?: id), "asset_ids" to assetIds(ids)))
    }

    private suspend fun playlistItems(playlistId: String, startIndex: Int, limit: Int): QueryResult<BaseItemDto> {
        val json = peach.get("/api/playlist", listOf("id" to playlistId))
        val all = json.objects("items").mapNotNull(PeachAsset::parse).map(::mapAsset)
        val page = all.drop(startIndex).take(limit)
        return QueryResult(items = page, totalRecordCount = all.size, startIndex = startIndex)
    }

    private fun assetIds(ids: String): List<Long> = ids.split(',').mapNotNull { PeachIds.assetId(it) }

    // endregion

    // region 不支持的能力

    override suspend fun getLyrics(itemId: String): ApiResponse<LyricsDto> = unsupported()
    override suspend fun refreshItem(
        itemId: String,
        recursive: Boolean,
        metadataRefreshMode: String,
        imageRefreshMode: String,
        replaceAllMetadata: Boolean
    ): ApiResponse<Unit> = unsupported()

    /** 删除映射为移入回收站（`feedback kind=dispose`），可在 Peach 回收站里恢复。 */
    override suspend fun deleteItem(itemId: String): ApiResponse<Unit> = call {
        val assetId = requireAsset(itemId)
        val current = PeachAsset.parse(peach.get("/api/item", listOf("id" to assetId)))
        if (current?.inTrash != true) {
            peach.post("/api/feedback", peachBody("id" to assetId, "kind" to "dispose"))
        }
    }

    override suspend fun remoteSearch(searchType: String, query: RemoteSearchQuery): ApiResponse<List<RemoteSearchResult>> = unsupported()
    override suspend fun applyRemoteSearchResult(itemId: String, result: RemoteSearchResult, replaceAllImages: Boolean): ApiResponse<Unit> = unsupported()
    override suspend fun getRemoteImages(itemId: String, imageType: String, providerName: String?, includeAllLanguages: Boolean): ApiResponse<RemoteImageResult> = unsupported()
    override suspend fun getExternalIdInfos(itemId: String): ApiResponse<List<ExternalIdInfo>> = success(emptyList())
    override suspend fun getItemImages(itemId: String): ApiResponse<List<ItemImageInfo>> = success(emptyList())
    override suspend fun deleteItemImage(itemId: String, imageType: String, imageIndex: Int?): ApiResponse<Unit> = unsupported()
    override suspend fun uploadItemImage(itemId: String, imageType: String, base64Data: String, mimeType: String): ApiResponse<Unit> = unsupported()
    override suspend fun getItemJson(userId: String, itemId: String): ApiResponse<JsonObject> = unsupported()
    override suspend fun updateItemJson(itemId: String, item: JsonObject): ApiResponse<Unit> = unsupported()
    override suspend fun searchRemoteSubtitles(itemId: String, language: String, mediaSourceId: String?): ApiResponse<List<RemoteSubtitleInfo>> = success(emptyList())
    override suspend fun downloadRemoteSubtitle(itemId: String, subtitleId: String, mediaSourceId: String?): ApiResponse<Unit> = unsupported()
    override suspend fun deleteSubtitle(itemId: String, mediaSourceId: String?, streamIndex: Int): ApiResponse<Unit> = unsupported()
    override suspend fun getCultures(): ApiResponse<List<CultureInfo>> = success(emptyList())
    override suspend fun downloadRemoteImage(itemId: String, imageType: String, imageUrl: String, providerName: String?): ApiResponse<Unit> = unsupported()
    override suspend fun updateItem(itemId: String, item: BaseItemDto): ApiResponse<Unit> = unsupported()
    override suspend fun createCollection(name: String, ids: String): ApiResponse<ItemIdResult> = unsupported()
    override suspend fun addToCollection(collectionId: String, ids: String): ApiResponse<Unit> = unsupported()

    // endregion

    // region 查询拼装

    private suspend fun queryItems(
        parentId: String?,
        includeItemTypes: String?,
        searchTerm: String?,
        startIndex: Int,
        limit: Int,
        sortBy: String?,
        sortOrder: String?,
        filters: String?,
        personIds: String?,
        genreIds: String?,
        providerCode: String? = null,
        includeTotal: Boolean = true
    ): QueryResult<BaseItemDto> {
        if (!includesVideos(includeItemTypes)) return emptyQueryResult()
        val filterSet = filters.orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        val resumable = "IsResumable" in filterSet
        val query = mutableListOf<Pair<String, Any?>>()
        PeachIds.libraryId(parentId)?.let { query += "library" to it }
        PeachIds.entity(parentId)?.let { (kind, id) -> addEntityFilter(query, kind, id) }
        personIds?.split(',')?.firstNotNullOfOrNull { PeachIds.entity(it.trim()) }?.let { (kind, id) ->
            addEntityFilter(query, kind, id)
        }
        val tagNames = genreIds?.split(',')?.mapNotNull { tagName(it.trim()) }.orEmpty()
        if (tagNames.isNotEmpty()) query += "tag" to tagNames.joinToString(",")
        val text = searchTerm?.trim()?.takeIf { it.isNotEmpty() } ?: providerCode
        text?.let { query += "q" to it }
        when {
            "IsFavorite" in filterSet -> query += "state" to "later"
            "IsUnplayed" in filterSet -> query += "state" to "fresh"
            "IsPlayed" in filterSet || resumable -> query += "state" to "played"
        }
        val (sort, direction) = if (resumable) "played" to "desc" else sortParameters(sortBy, sortOrder)
        query += "sort" to sort
        direction?.let { query += "dir" to it }
        query += "limit" to limit.coerceIn(1, MAX_PAGE)
        query += "offset" to startIndex.coerceAtLeast(0)
        if (!includeTotal || resumable) query += "count" to 0
        val page = PeachItemPage.parse(peach.get("/api/items", query))
        val result = page.toQueryResult(startIndex)
        if (!resumable) return result
        val resumableItems = result.items.orEmpty().filter { (it.userData?.playbackPositionTicks ?: 0L) > 0L }
        return QueryResult(items = resumableItems, totalRecordCount = resumableItems.size, startIndex = startIndex)
    }

    private suspend fun addEntityFilter(query: MutableList<Pair<String, Any?>>, kind: String, id: Long) {
        val name = entityName(kind, id) ?: return
        if (kind == "tag") query += "tag" to name else query += kind to name
    }

    private suspend fun entityName(kind: String, id: Long): String? {
        val key = "$kind:$id"
        entityNames[key]?.let { return it }
        val json = runCatching { fetchEntity(kind, id) }.getOrNull() ?: return null
        val name = json.string("canonical_name") ?: json.string("name") ?: return null
        entityNames[key] = name
        return name
    }

    /** 合并过的实体回 `{redirect:{id, kind}}`，跟一次到最终身份。 */
    suspend fun fetchEntity(kind: String, id: Long): JsonObject {
        val json = peach.get("/api/entity", listOf("kind" to kind, "id" to id))
        val redirect = json.obj("redirect") ?: return json
        val targetId = redirect.long("id") ?: return json
        return peach.get("/api/entity", listOf("kind" to (redirect.string("kind") ?: kind), "id" to targetId))
    }

    private suspend fun resolveItem(itemId: String): BaseItemDto {
        PeachIds.assetId(itemId)?.let { assetId ->
            val asset = PeachAsset.parse(peach.get("/api/item", listOf("id" to assetId)))
                ?: throw PeachApiException(404, PeachApiException.Kind.NOT_FOUND, "作品不存在")
            return mapAsset(asset)
        }
        PeachIds.libraryId(itemId)?.let { libraryId ->
            val library = PeachLibrary.parseList(peach.get("/api/libraries")).firstOrNull { it.id == libraryId }
                ?: throw PeachApiException(404, PeachApiException.Kind.NOT_FOUND, "媒体库不存在")
            return PeachMapper.libraryToView(library)
        }
        PeachIds.entity(itemId)?.let { (kind, id) ->
            val entity = PeachEntity.parse(fetchEntity(kind, id))
                ?: throw PeachApiException(404, PeachApiException.Kind.NOT_FOUND, "资料不存在")
            entityNames["$kind:${entity.id}"] = entity.name
            return PeachMapper.entityToPerson(kind, entity.id, entity.name, entity.hasImage, entity.summary)
        }
        PeachIds.playlistId(itemId)?.let { playlistId ->
            val json = peach.get("/api/playlist", listOf("id" to playlistId))
            return BaseItemDto(
                id = itemId,
                name = json.string("name") ?: json.obj("playlist")?.string("name") ?: "播放列表",
                type = "Playlist",
                isFolder = true,
                childCount = json.objects("items").size
            )
        }
        throw PeachApiException(404, PeachApiException.Kind.NOT_FOUND, "条目不存在")
    }

    private fun mapAsset(asset: PeachAsset): BaseItemDto {
        asset.entityRefs.forEach { ref -> ref.id?.let { entityNames["${ref.kind}:$it"] = ref.name } }
        PeachArtwork.remember(asset)
        return PeachMapper.toBaseItem(asset)
    }

    private fun PeachItemPage.toQueryResult(startIndex: Int): QueryResult<BaseItemDto> {
        val mapped = items.map(::mapAsset)
        val totalCount = total ?: if (hasMore == true) startIndex + mapped.size + 1 else startIndex + mapped.size
        return QueryResult(items = mapped, totalRecordCount = totalCount, startIndex = startIndex)
    }

    private fun requireAsset(itemId: String): Long = PeachIds.assetId(itemId)
        ?: throw PeachApiException(400, PeachApiException.Kind.OTHER, "不是 Peach 作品")

    private fun includesVideos(includeItemTypes: String?): Boolean {
        val types = includeItemTypes?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
        return types.isEmpty() || types.any { it.equals("Movie", true) || it.equals("Video", true) }
    }

    private fun sortParameters(sortBy: String?, sortOrder: String?): Pair<String, String?> {
        val primary = sortBy?.split(',')?.firstOrNull()?.trim().orEmpty()
        val sort = when {
            primary.equals("SortName", true) || primary.equals("Name", true) -> "name"
            primary.equals("PremiereDate", true) || primary.equals("ProductionYear", true) -> "release"
            primary.equals("Runtime", true) -> "dur"
            primary.equals("DatePlayed", true) -> "played"
            primary.equals("CommunityRating", true) || primary.equals("CriticRating", true) -> "rating"
            primary.equals("PlayCount", true) -> "plays"
            primary.equals("Random", true) -> return "rand" to null
            else -> "new"
        }
        val direction = if (sortOrder?.startsWith("Asc", ignoreCase = true) == true) "asc" else "desc"
        return sort to direction
    }

    private fun tagName(genreId: String): String? = when {
        genreId.isBlank() -> null
        genreId.startsWith(TAG_GENRE_PREFIX) -> genreId.removePrefix(TAG_GENRE_PREFIX)
        PeachIds.entity(genreId)?.first == "tag" -> entityNames[genreId]
        else -> genreId
    }

    private fun tagGenreId(name: String): String = "$TAG_GENRE_PREFIX$name"

    private fun peachUser() = UserDto(
        name = "Peach",
        id = PeachIds.USER_ID,
        serverName = "Peach",
        hasPassword = false,
        policy = UserPolicy(isAdministrator = false, enableMediaPlayback = true)
    )

    private fun <T> emptyQueryResult() = QueryResult<T>(items = emptyList(), totalRecordCount = 0, startIndex = 0)

    private fun <T> success(value: T): ApiResponse<T> = ApiResponse(value, 200, "OK")

    private fun <T> unsupported(): ApiResponse<T> =
        ApiResponse(null, 501, "Not Implemented", errorBodyValue = "Peach 不支持此操作")

    private suspend inline fun <T> call(crossinline block: suspend () -> T): ApiResponse<T> {
        return try {
            ApiResponse(block(), 200, "OK")
        } catch (error: PeachApiException) {
            ApiResponse(null, error.status, error.message, errorBodyValue = error.message)
        }
    }

    // endregion

    companion object {
        const val PRODUCT_NAME = "Peach"
        private const val DEFAULT_LIMIT = 60
        private const val MAX_PAGE = 200
        private const val SUBTITLE_INDEX_BASE = 100
        private const val SEEK_TOLERANCE_SECONDS = 10.0
        private const val ENDED_RATIO = 0.95
        private const val TAG_GENRE_PREFIX = "tagname:"
    }
}
