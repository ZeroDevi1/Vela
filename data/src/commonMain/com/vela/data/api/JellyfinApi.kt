package com.vela.data.api

import com.vela.data.model.ActivityLogResult
import com.vela.data.model.AuthenticationRequest
import com.vela.data.model.AuthenticationResult
import com.vela.data.model.BaseItemDto
import com.vela.data.model.PlaybackInfoResponse
import com.vela.data.model.PlaybackInfoRequest
import com.vela.data.model.PlaybackProgressRequest
import com.vela.data.model.PlaybackStartRequest
import com.vela.data.model.PlaybackStoppedRequest
import com.vela.data.model.RecommendationDto
import com.vela.data.model.QuickConnectDto
import com.vela.data.model.QuickConnectResult
import com.vela.data.model.QueryResult
import com.vela.data.model.AdminSessionInfo
import com.vela.data.model.ItemIdResult
import com.vela.data.model.ServerInfo
import com.vela.data.model.SystemInfoFull
import com.vela.data.model.UserDto
import com.vela.data.model.UserConfiguration
import com.vela.data.model.DisplayPreferencesDto
import com.vela.data.network.ApiResponse

interface MediaServerApi {

    suspend fun getMediaLibraryItems(userId: String, query: com.vela.data.model.MediaLibraryQuery): ApiResponse<QueryResult<BaseItemDto>>

    suspend fun getMediaLibraryPeople(userId: String, parentId: String?, personTypes: String, searchTerm: String?, startIndex: Int, limit: Int): ApiResponse<QueryResult<BaseItemDto>>

    suspend fun getPlaylistItems(userId: String, playlistId: String, startIndex: Int, limit: Int): ApiResponse<QueryResult<BaseItemDto>>

    suspend fun getLyrics(itemId: String): ApiResponse<com.vela.data.model.LyricsDto>


    suspend fun getPublicSystemInfo(): ApiResponse<ServerInfo>

    suspend fun authenticateByName(request: AuthenticationRequest): ApiResponse<AuthenticationResult>

    suspend fun initiateQuickConnect(): ApiResponse<QuickConnectResult>

    suspend fun authenticateWithQuickConnect(
        request: QuickConnectDto
    ): ApiResponse<AuthenticationResult>

    suspend fun getLatestItems(
        userId: String,
        parentId: String? = null,
        includeItemTypes: String? = null,
        limit: Int? = null,
        fields: String? = null
    ): ApiResponse<List<BaseItemDto>>

    suspend fun getUserItems(
        userId: String,
        parentId: String? = null,
        personIds: String? = null,
        genres: String? = null,
        genreIds: String? = null,
        includeItemTypes: String? = null,
        recursive: Boolean? = null,
        sortBy: String? = null,
        sortOrder: String? = null,
        limit: Int? = null,
        startIndex: Int? = null,
        filters: String? = null,
        anyProviderIdEquals: String? = null,
        tags: String? = null,
        fields: String? = null
    ): ApiResponse<QueryResult<BaseItemDto>>

    suspend fun getSuggestions(
        endpoint: String,
        userId: String? = null,
        mediaType: String? = null,
        type: String? = null,
        includeItemTypes: String? = null,
        limit: Int? = null,
        fields: String? = null
    ): ApiResponse<QueryResult<BaseItemDto>>

    suspend fun getUserViews(
        userId: String,
        includeHidden: Boolean? = null
    ): ApiResponse<QueryResult<BaseItemDto>>

    suspend fun getMovieRecommendations(
        userId: String,
        parentId: String? = null,
        categoryLimit: Int? = null,
        itemLimit: Int? = null,
        fields: String? = null
    ): ApiResponse<List<RecommendationDto>>

    suspend fun getResumeItems(
        userId: String,
        parentId: String? = null,
        includeItemTypes: String? = null,
        limit: Int? = null,
        startIndex: Int? = null,
        recursive: Boolean = true,
        sortBy: String = "DatePlayed",
        sortOrder: String = "Descending",
        fields: String? = null
    ): ApiResponse<QueryResult<BaseItemDto>>

    suspend fun getNextUp(
        userId: String,
        seriesId: String? = null,
        parentId: String? = null,
        limit: Int? = null,
        startIndex: Int? = null,
        legacyNextUp: Boolean? = null,
        fields: String? = null,
        enableUserData: Boolean? = null,
        enableImages: Boolean? = null,
        imageTypeLimit: Int? = null,
        enableImageTypes: String? = null
    ): ApiResponse<QueryResult<BaseItemDto>>

    suspend fun getUserById(userId: String): ApiResponse<UserDto>

    suspend fun updateUserConfiguration(
        userId: String,
        configuration: UserConfiguration
    ): ApiResponse<Unit>

    suspend fun getDisplayPreferences(
        displayPreferencesId: String,
        userId: String,
        client: String
    ): ApiResponse<DisplayPreferencesDto>

    suspend fun updateDisplayPreferences(
        displayPreferencesId: String,
        userId: String,
        client: String,
        preferences: DisplayPreferencesDto
    ): ApiResponse<Unit>

    suspend fun getItemById(
        userId: String,
        itemId: String,
        fields: String? = "People,Studios,Genres,Overview,ChildCount,RecursiveItemCount,EpisodeCount,SeriesName,SeriesId,UserData,Chapters"
    ): ApiResponse<BaseItemDto>

    suspend fun getSimilarItems(
        itemId: String,
        userId: String,
        limit: Int? = null,
        fields: String? = "Overview,Genres,CommunityRating,ProductionYear,OfficialRating,SeriesName,SeriesId,UserData"
    ): ApiResponse<QueryResult<BaseItemDto>>

    suspend fun getAdditionalParts(
        itemId: String,
        userId: String? = null
    ): ApiResponse<QueryResult<BaseItemDto>>

    suspend fun markAsFavorite(
        userId: String,
        itemId: String
    ): ApiResponse<Unit>

    suspend fun unmarkAsFavorite(
        userId: String,
        itemId: String
    ): ApiResponse<Unit>

    suspend fun markAsPlayed(
        userId: String,
        itemId: String
    ): ApiResponse<Unit>

    suspend fun unmarkAsPlayed(
        userId: String,
        itemId: String
    ): ApiResponse<Unit>

    suspend fun getGenres(
        userId: String,
        parentId: String? = null,
        includeItemTypes: String? = null,
        recursive: Boolean? = null,
        sortBy: String? = null,
        sortOrder: String? = null,
        enableTotalRecordCount: Boolean? = null,
        enableImages: Boolean? = null,
        startIndex: Int? = null,
        limit: Int? = null
    ): ApiResponse<QueryResult<BaseItemDto>>

    suspend fun getItemsByGenre(
        userId: String,
        genreIds: String,
        includeItemTypes: String? = null,
        recursive: Boolean? = true,
        limit: Int? = null,
        sortBy: String? = null,
        sortOrder: String? = null,
        fields: String? = null
    ): ApiResponse<QueryResult<BaseItemDto>>

    suspend fun getSeasons(
        seriesId: String,
        userId: String,
        fields: String? = "ChildCount,RecursiveItemCount,EpisodeCount"
    ): ApiResponse<QueryResult<BaseItemDto>>

    suspend fun getEpisodes(
        seriesId: String,
        userId: String,
        seasonId: String? = null,
        fields: String? = "Overview,MediaStreams,SeriesName,SeriesId,SeasonName,SeasonId",
        limit: Int? = null,
        startIndex: Int? = null,
        adjacentTo: String? = null
    ): ApiResponse<QueryResult<BaseItemDto>>

    suspend fun getPlaybackInfoGet(
        itemId: String,
        userId: String,
        maxStreamingBitrate: Int? = null,
        audioStreamIndex: Int? = null,
        subtitleStreamIndex: Int? = null,
        enableDirectPlay: Boolean? = null,
        enableDirectStream: Boolean? = null,
        enableTranscoding: Boolean? = null
    ): ApiResponse<PlaybackInfoResponse>

    suspend fun getPlaybackInfoPost(
        itemId: String,
        request: PlaybackInfoRequest
    ): ApiResponse<PlaybackInfoResponse>

    suspend fun getVideoStreamUrl(
        itemId: String,
        static: Boolean = true,
        mediaSourceId: String? = null,
        deviceId: String? = null,
        apiKey: String? = null
    ): String

    suspend fun searchItems(
        userId: String,
        searchTerm: String,
        includeItemTypes: String? = "Movie,Series",
        recursive: Boolean = true,
        limit: Int? = 50,
        startIndex: Int? = null,
        sortBy: String? = null,
        sortOrder: String? = null,
        genres: String? = null,
        fields: String? = "ChildCount,RecursiveItemCount,EpisodeCount,SeriesName,SeriesId,Genres,CommunityRating,ProductionYear,Overview"
    ): ApiResponse<QueryResult<BaseItemDto>>

    suspend fun searchItemsByName(
        userId: String,
        nameStartsWith: String,
        includeItemTypes: String? = "Movie,Series",
        recursive: Boolean = true,
        limit: Int? = 50,
        fields: String? = "ChildCount,RecursiveItemCount,EpisodeCount,SeriesName,SeriesId,Genres,CommunityRating,ProductionYear,Overview"
    ): ApiResponse<QueryResult<BaseItemDto>>

    suspend fun reportPlaybackStart(request: PlaybackStartRequest): ApiResponse<Unit>

    suspend fun reportPlaybackProgress(request: PlaybackProgressRequest): ApiResponse<Unit>

    suspend fun reportPlaybackStopped(request: PlaybackStoppedRequest): ApiResponse<Unit>

    suspend fun getSystemInfo(): ApiResponse<SystemInfoFull>

    suspend fun getActiveSessions(): ApiResponse<List<AdminSessionInfo>>

    suspend fun getActivityLog(
        startIndex: Int? = null,
        limit: Int? = null
    ): ApiResponse<ActivityLogResult>

    suspend fun refreshItem(
        itemId: String,
        recursive: Boolean = true,
        metadataRefreshMode: String = "Default",
        imageRefreshMode: String = "Default",
        replaceAllMetadata: Boolean = false
    ): ApiResponse<Unit>

    suspend fun deleteItem(itemId: String): ApiResponse<Unit>

    /** 识别：按 [searchType]（Movie/Series 等，见 remoteSearchTypeFor）搜索候选。需要管理员权限。 */
    suspend fun remoteSearch(
        searchType: String,
        query: com.vela.data.model.RemoteSearchQuery
    ): ApiResponse<List<com.vela.data.model.RemoteSearchResult>>

    /** 应用识别结果并同步刷新元数据；[replaceAllImages] 为 true 时同时替换全部图片。 */
    suspend fun applyRemoteSearchResult(
        itemId: String,
        result: com.vela.data.model.RemoteSearchResult,
        replaceAllImages: Boolean
    ): ApiResponse<Unit>

    /** 列出某类图片的远程候选；[providerName] 为 null 时返回全部提供方。 */
    suspend fun getRemoteImages(
        itemId: String,
        imageType: String,
        providerName: String? = null,
        includeAllLanguages: Boolean = true
    ): ApiResponse<com.vela.data.model.RemoteImageResult>

    suspend fun getExternalIdInfos(itemId: String): ApiResponse<List<com.vela.data.model.ExternalIdInfo>>

    /** 条目当前的全部图片（含多张背景图的索引）。 */
    suspend fun getItemImages(itemId: String): ApiResponse<List<com.vela.data.model.ItemImageInfo>>

    suspend fun deleteItemImage(itemId: String, imageType: String, imageIndex: Int?): ApiResponse<Unit>

    /**
     * 上传本地图片。Emby 与 Jellyfin 都要求请求体是 Base64 文本、Content-Type 为图片的 MIME 类型。
     * 背景图会追加为新的一张，其余类型替换现有图片。
     */
    suspend fun uploadItemImage(
        itemId: String,
        imageType: String,
        base64Data: String,
        mimeType: String
    ): ApiResponse<Unit>

    /**
     * 以原始 JSON 读取条目。编辑元数据必须整份回写，用原始 JSON 才不会丢掉模型里未声明的字段。
     */
    suspend fun getItemJson(userId: String, itemId: String): ApiResponse<kotlinx.serialization.json.JsonObject>

    suspend fun updateItemJson(itemId: String, item: kotlinx.serialization.json.JsonObject): ApiResponse<Unit>

    suspend fun searchRemoteSubtitles(
        itemId: String,
        language: String,
        mediaSourceId: String?
    ): ApiResponse<List<com.vela.data.model.RemoteSubtitleInfo>>

    suspend fun downloadRemoteSubtitle(
        itemId: String,
        subtitleId: String,
        mediaSourceId: String?
    ): ApiResponse<Unit>

    /** 删除外挂字幕。Jellyfin 与 Emby 路径不同，由实现按服务端类型区分。 */
    suspend fun deleteSubtitle(itemId: String, mediaSourceId: String?, streamIndex: Int): ApiResponse<Unit>

    suspend fun getCultures(): ApiResponse<List<com.vela.data.model.CultureInfo>>

    /** 让服务端下载远程图片并设为该条目的 [imageType] 图片。 */
    suspend fun downloadRemoteImage(
        itemId: String,
        imageType: String,
        imageUrl: String,
        providerName: String?
    ): ApiResponse<Unit>

    suspend fun updateItem(itemId: String, item: BaseItemDto): ApiResponse<Unit>

    suspend fun createPlaylist(
        name: String,
        ids: String,
        userId: String
    ): ApiResponse<ItemIdResult>

    suspend fun addToPlaylist(
        playlistId: String,
        ids: String,
        userId: String
    ): ApiResponse<Unit>

    suspend fun createCollection(
        name: String,
        ids: String
    ): ApiResponse<ItemIdResult>

    suspend fun addToCollection(
        collectionId: String,
        ids: String
    ): ApiResponse<Unit>
}