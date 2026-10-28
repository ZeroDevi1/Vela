package com.vela.data.peach

import android.content.Context
import com.vela.data.repository.AuthRepositoryProvider
import com.vela.data.repository.MediaRepositoryProvider
import com.vela.data.security.SecureSessionStore
import com.vela.data.model.BaseItemDto
import kotlinx.serialization.json.JsonObject

/**
 * Peach 专属能力：互动、找资源与云下载、任务中心、订阅与关注、想要清单、来源与凭据、
 * 内容偏好与服务器配置。都走当前活动 Peach 服务器的会话；当前不是 Peach 时抛 [IllegalStateException]。
 *
 * 失败统一抛 [PeachApiException]，界面按 [PeachApiException.kind] 区分「仅本机」「账本只读」等。
 * 切换类接口（已看、不喜欢、稍后看、回收站）以服务端返回的状态为准，不在本地自行翻转。
 */
class PeachRepository(private val context: Context) {
    private val mediaRepository = MediaRepositoryProvider.getInstance(context)

    private suspend fun client(): PeachClient =
        mediaRepository.peachApi()?.peach ?: throw IllegalStateException("当前服务器不是 Peach")

    // region 作品互动

    suspend fun item(assetId: Long): PeachAsset =
        PeachAsset.parse(client().get("/api/item", listOf("id" to assetId)))
            ?: throw PeachApiException(404, PeachApiException.Kind.NOT_FOUND, "作品不存在")

    /** 五星对应 20 的倍数；0 表示撤销评分，服务端写 NULL。 */
    suspend fun rate(assetId: Long, stars: Int): PeachFeedbackState =
        PeachFeedbackState.parse(
            client().post("/api/feedback", peachBody("id" to assetId, "kind" to "rate", "value" to stars.coerceIn(0, 5) * 20))
        )

    /** `seen` / `dislike` / `dispose` 是切换语义；`o` / `o-undo` 是计数加减。 */
    suspend fun feedback(assetId: Long, kind: String): PeachFeedbackState =
        PeachFeedbackState.parse(client().post("/api/feedback", peachBody("id" to assetId, "kind" to kind)))

    suspend fun toggleWatchLater(assetId: Long): Boolean =
        client().post("/api/watch-later", peachBody("id" to assetId)).bool("watch_later") == true

    suspend fun setLiked(assetId: Long, liked: Boolean) {
        client().post("/api/preference", peachBody("id" to assetId, "liked" to liked))
    }

    // endregion

    /** 同番号的其他版次（有码、中字、无码等），不含自身。 */
    suspend fun editions(assetId: Long): List<PeachAsset> = runCatching {
        client().get("/api/editions", listOf("id" to assetId)).objects("items").mapNotNull(PeachAsset::parse)
    }.getOrDefault(emptyList()).filterNot { it.id == assetId }

    /** 作品当前的产地（jp / kr / cn / west / other），由服务端推断或用户设定。 */
    suspend fun region(assetId: Long): String? = client().get("/api/item", listOf("id" to assetId)).string("region")

    // region 搜索补全与历史（与 Peach 网页共享）

    suspend fun suggestions(query: String, limit: Int = 6): List<PeachSuggestion> {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return emptyList()
        return PeachSuggestion.parseList(client().get("/api/suggest", listOf("q" to trimmed.take(64), "limit" to limit)))
    }

    suspend fun searchHistory(limit: Int = 20): List<String> =
        client().get("/api/search-history", listOf("limit" to limit.coerceIn(1, 50))).strings("items")

    suspend fun rememberSearch(query: String) {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return
        client().post("/api/search-history", peachBody("operation" to "remember", "query" to trimmed.take(200)))
    }

    suspend fun forgetSearch(query: String) {
        client().post("/api/search-history", peachBody("operation" to "remove", "query" to query.take(200)))
    }

    // endregion

    /**
     * 批量操作（每次 1–200 个作品）：like / seen / later / dispose / restore / delete /
     * dismiss-junk / reconsider-junk / region。`delete` 只对回收站或已消失的作品生效，会删除文件。
     */
    suspend fun batch(assetIds: List<Long>, operation: String, disposalReason: String? = null, region: String? = null): JsonObject {
        require(assetIds.size in 1..200) { "一次最多处理 200 个作品" }
        return client().post(
            "/api/batch",
            peachBody("ids" to assetIds, "operation" to operation, "disposal_reason" to disposalReason, "region" to region)
        )
    }

    // region 浏览

    /** 海报墙：[query] 为 `/api/items` 的筛选参数（library、folder、tag、performer、state、year、sort…）。 */
    suspend fun items(query: List<Pair<String, Any?>>, offset: Int, limit: Int = 60): Pair<List<BaseItemDto>, Boolean> {
        val page = PeachItemPage.parse(client().get("/api/items", query + listOf("offset" to offset, "limit" to limit, "count" to 0)))
        val items = page.items.onEach(PeachArtwork::remember).map(PeachMapper::toBaseItem)
        return items to (page.hasMore == true)
    }

    suspend fun facets(query: List<Pair<String, Any?>>): PeachFacets = PeachFacets.parse(client().get("/api/facets", query))

    /** 名册：`performers`、`creators`、`studios`、`agencies`、`series`、`tags`。 */
    suspend fun roster(kind: String, query: String?, offset: Int, limit: Int = 120): Pair<List<PeachIndexEntry>, Boolean> {
        val json = client().get("/api/index", listOf("kind" to kind, "q" to query?.takeIf { it.isNotBlank() }, "limit" to limit, "offset" to offset))
        return PeachIndexEntry.parseList(json) to (json.bool("has_more") == true)
    }

    suspend fun tops(jav: Boolean = false, n: Int = 28): PeachTops =
        PeachTops.parse(client().get("/api/tops", listOf("n" to n, "jav" to if (jav) 1 else null)))

    suspend fun folders(folderId: String?, query: String? = null): PeachFolders =
        PeachFolders.parse(client().get("/api/folders", listOf("folder" to folderId, "q" to query?.takeIf { it.isNotBlank() })))

    suspend fun shortcuts(): PeachShortcuts = PeachShortcuts.parse(client().get("/api/library-shortcuts"))

    suspend fun setFavoriteEntity(entityId: Long, favorite: Boolean): PeachShortcuts =
        PeachShortcuts.parse(client().post("/api/library-shortcuts", peachBody("action" to "favorite", "entity_id" to entityId, "on" to favorite)))

    suspend fun isFavoriteEntity(kind: String, entityId: Long): Boolean = runCatching {
        client().get("/api/entity", listOf("kind" to kind, "id" to entityId)).bool("favorite") == true
    }.getOrDefault(false)

    // endregion

    // region 播放列表

    suspend fun playlists(): List<PeachPlaylistSummary> = PeachPlaylistSummary.parseList(client().get("/api/playlists"))

    suspend fun playlistItems(playlistId: Long): List<BaseItemDto> =
        client().get("/api/playlist", listOf("id" to playlistId)).objects("items")
            .mapNotNull(PeachAsset::parse).map(PeachMapper::toBaseItem)

    suspend fun createPlaylist(name: String, assetIds: List<Long> = emptyList()) {
        client().post("/api/playlist", peachBody("action" to "create", "name" to name.take(80), "asset_ids" to assetIds))
    }

    suspend fun renamePlaylist(playlistId: Long, name: String) {
        client().post("/api/playlist", peachBody("action" to "rename", "id" to playlistId, "name" to name.take(80)))
    }

    suspend fun deletePlaylist(playlistId: Long) {
        client().post("/api/playlist", peachBody("action" to "delete", "id" to playlistId))
    }

    suspend fun removeFromPlaylist(playlistId: Long, assetId: Long) {
        client().post("/api/playlist", peachBody("action" to "remove", "id" to playlistId, "asset_id" to assetId))
    }

    suspend fun addToPlaylist(playlistId: Long, assetIds: List<Long>) {
        client().post("/api/playlist", peachBody("action" to "add", "id" to playlistId, "asset_ids" to assetIds))
    }

    // endregion

    // region 关注时间线

    suspend fun followTimeline(
        status: String = "new,seen,saved",
        sort: String = "new",
        offset: Int = 0,
        author: String? = null,
        tag: String? = null,
        source: Long? = null,
        limit: Int = 40
    ): PeachFollowPage = PeachFollowPage.parse(
        client().get(
            "/api/follow",
            listOf(
                "status" to status, "sort" to sort, "offset" to offset, "limit" to limit,
                "author" to author, "tag" to tag, "source" to source
            )
        )
    )

    /** `to`：new / seen / ignored。保存进馆藏用 [saveFollowItem]。 */
    suspend fun setFollowStatus(itemIds: List<Long>, to: String) {
        if (itemIds.isEmpty()) return
        client().post("/api/follow/status", peachBody("items" to itemIds, "to" to to))
    }

    suspend fun saveFollowItem(itemId: Long): Long? =
        client().post("/api/follow/save", peachBody("item" to itemId)).long("asset_id")

    suspend fun hideFollowMedia(itemId: Long, media: Int, hidden: Boolean = true) {
        client().post("/api/follow/media/hide", peachBody("item" to itemId, "media" to media, "hidden" to hidden))
    }

    suspend fun followItem(itemId: Long): PeachFollowItem? = api().followItem(itemId)

    // endregion

    // region JAV 探索

    /** [scope]：`external` 看库外新作，`library` 看馆藏里少看的作品。 */
    suspend fun javDiscoveries(scope: String, sort: String = "taste", ascending: Boolean = false): List<PeachJavDiscovery> =
        PeachJavDiscovery.parseList(
            client().get(
                "/api/jav/discoveries",
                listOf("scope" to scope, "sort" to sort, "direction" to if (ascending) "asc" else "desc", "limit" to 100)
            )
        )

    suspend fun javDetail(code: String, refresh: Boolean = false): PeachJavDetail =
        PeachJavDetail.parse(client().get("/api/jav/detail", listOf("code" to code, "refresh" to if (refresh) 1 else null)))
            ?: throw PeachApiException(404, PeachApiException.Kind.NOT_FOUND, "没有这部作品的资料")

    suspend fun javReviews(code: String, page: Int = 1): Pair<List<PeachJavReview>, Boolean> {
        val json = client().get("/api/jav/reviews", listOf("code" to code, "page" to page))
        return PeachJavReview.parseList(json) to (json.bool("more") == true)
    }

    /** 馆藏里这个番号的作品（库外发现没有资产 ID，按番号查）。 */
    suspend fun libraryItemsForCode(code: String): List<BaseItemDto> = items(listOf("q" to code), 0, 12).first
        .filter { it.providerIds?.get(PeachMapper.PROVIDER_CODE).equals(code, ignoreCase = true) }

    // endregion

    // region 图集

    suspend fun photos(kind: String, entityId: Long, offset: Int = 0): PeachPhotoPage =
        PeachPhotoPage.parse(client().get("/api/photos", listOf("kind" to kind, "id" to entityId, "offset" to offset, "limit" to 120)))

    suspend fun photoSet(anchorId: Long, offset: Int = 0): PeachPhotoPage =
        PeachPhotoPage.parse(client().get("/api/photo-set", listOf("id" to anchorId, "offset" to offset, "limit" to 120)))

    suspend fun baseUrl(): String = client().baseUrl.trimEnd('/')

    // endregion

    // region 在线字幕

    private suspend fun api(): PeachMediaServerApi =
        mediaRepository.peachApi() ?: throw IllegalStateException("当前服务器不是 Peach")

    suspend fun searchOnlineSubtitles(query: String, offset: Int = 0) = api().searchOnlineSubtitles(query, offset)

    suspend fun onlineSubtitleFiles(subtitleId: String) = api().onlineSubtitleFiles(subtitleId)

    suspend fun saveOnlineSubtitle(assetId: Long, subtitleId: String, file: PeachOnlineSubtitleFile) =
        api().saveOnlineSubtitle(assetId, subtitleId, file)

    // endregion

    // region 找资源与云下载

    suspend fun downloads(beforeId: Long? = null, limit: Int = 50): PeachDownloads =
        PeachDownloads.parse(client().get("/api/downloads", listOf("limit" to limit, "before_id" to beforeId)))

    /** 番号资源：先查番号资源页，没有结果时再走多源资源搜索。 */
    suspend fun findResources(code: String, refresh: Boolean = false): PeachResourceResult {
        val client = client()
        val javResult = runCatching {
            PeachResourceResult.parse(
                client.get("/api/jav/resources", listOf("code" to code, "refresh" to if (refresh) 1 else null))
            )
        }.getOrNull()
        if (javResult != null && javResult.items.isNotEmpty()) return javResult
        return runCatching {
            PeachResourceResult.parse(client.get("/api/resources/search", listOf("code" to code)))
        }.getOrElse { error ->
            javResult ?: PeachResourceResult("error", emptyList(), (error as? PeachApiException)?.message ?: error.message, emptyList())
        }
    }

    /** 提交磁力到云下载。`origin` 固定传 `vela`，在 Peach 任务列表里区分来源。 */
    suspend fun submitMagnet(magnet: String, provider: String, code: String?, title: String?): PeachDownloadTask? {
        val json = client().post(
            "/api/downloads",
            peachBody("magnet" to magnet, "provider" to provider, "code" to code, "title" to title, "origin" to ORIGIN)
        )
        return json.obj("task")?.let(PeachDownloadTask::parse)
    }

    suspend fun cancelDownload(taskId: Long): PeachDownloadTask? =
        client().post("/api/downloads/cancel", peachBody("id" to taskId)).obj("task")?.let(PeachDownloadTask::parse)

    suspend fun retryPostprocess(taskId: Long): PeachDownloadTask? =
        client().post("/api/downloads/postprocess", peachBody("id" to taskId)).obj("task")?.let(PeachDownloadTask::parse)

    // endregion

    // region 任务中心

    suspend fun tasks(): PeachTasks = PeachTasks.parse(client().get("/api/tasks", listOf("limit" to 30)))

    suspend fun libraryProcessing(): JsonObject = client().get("/api/library-processing")

    /** 只处理新入库的文件；`scope=new` 不与 `asset_ids` 混用。 */
    suspend fun startLibraryProcessing(): JsonObject =
        client().post("/api/library-processing", peachBody("stage" to "all", "scope" to "new"))

    suspend fun controlLibraryProcessing(jobId: String, action: String): JsonObject =
        client().post("/api/library-processing", peachBody("action" to action, "job_id" to jobId))

    // endregion

    // region 订阅源

    suspend fun feeds(): PeachFeeds = PeachFeeds.parse(client().get("/api/feeds"))

    suspend fun setFeedEnabled(sourceId: Long, enabled: Boolean) {
        client().post("/api/feeds/source", peachBody("action" to "enabled", "id" to sourceId, "enabled" to enabled))
    }

    suspend fun removeFeed(sourceId: Long) {
        client().post("/api/feeds/source", peachBody("action" to "remove", "id" to sourceId))
    }

    /** 人物页「订阅新作」：服务端按实体自行拼来源地址，客户端只传实体 ID。 */
    suspend fun followPerformer(entityId: Long, following: Boolean): Boolean =
        client().post("/api/feeds/source", peachBody("action" to "follow", "entity_id" to entityId, "enabled" to following))
            .bool("following") == true

    suspend fun isFollowingPerformer(entityId: Long): Boolean = runCatching {
        client().get("/api/entity", listOf("kind" to "performer", "id" to entityId)).obj("feed")?.bool("following") == true
    }.getOrDefault(false)

    suspend fun discoveries(state: String = "active", limit: Int = 50): List<PeachDiscovery> =
        PeachDiscovery.parseList(client().get("/api/feeds/discoveries", listOf("state" to state, "limit" to limit)))

    /** `action`：read / unread / ignore / unignore / want / unwant。 */
    suspend fun markDiscoveries(ids: List<Long>, action: String) {
        if (ids.isEmpty()) return
        client().post("/api/feeds/discovery", peachBody("action" to action, "ids" to ids))
    }

    suspend fun checkFeeds() {
        client().post("/api/feeds/check", peachBody("all" to true, "background" to true))
    }

    suspend fun feedSchedule(): PeachSchedule = PeachSchedule.parse(client().get("/api/feeds/schedule"))

    // endregion

    // region 关注

    suspend fun followSources(): List<PeachFollowSource> =
        PeachFollowSource.parseList(client().get("/api/follow", listOf("summary" to 1)))

    /** 粘贴来源链接即登记；首次检查失败显示在该行，不算添加失败。 */
    suspend fun addFollowSource(url: String): String? {
        val json = client().post("/api/follow/source", peachBody("action" to "add", "url" to url.trim(), "defer_check" to true))
        return json.obj("checked")?.string("message") ?: json.obj("checked")?.string("error")
    }

    suspend fun setFollowSourceEnabled(sourceId: Long, enabled: Boolean) {
        client().post("/api/follow/source", peachBody("action" to "enabled", "id" to sourceId, "enabled" to enabled))
    }

    suspend fun removeFollowSource(sourceId: Long) {
        client().post("/api/follow/source", peachBody("action" to "remove", "id" to sourceId))
    }

    suspend fun checkFollow() {
        client().post("/api/follow/check", peachBody("background" to true))
    }

    suspend fun followSchedule(): PeachSchedule = PeachSchedule.parse(client().get("/api/follow/schedule"))

    suspend fun setFollowSchedule(enabled: Boolean, intervalMinutes: Int): PeachSchedule =
        PeachSchedule.parse(
            client().post(
                "/api/follow/schedule",
                peachBody("enabled" to enabled, "interval_minutes" to intervalMinutes.coerceIn(15, 10_080))
            )
        )

    // endregion

    // region 想要清单

    suspend fun wants(): List<PeachWant> = PeachWant.parseList(client().get("/api/wants"))

    suspend fun addWant(code: String): PeachWant? =
        client().post("/api/wants", peachBody("action" to "add", "code" to code.trim())).obj("want")?.let(PeachWant::parse)

    suspend fun removeWants(ids: List<Long>) {
        client().post("/api/wants", peachBody("action" to "remove", "ids" to ids))
    }

    // endregion

    // region 来源与凭据

    suspend fun scrapingSources(): List<PeachScrapingSource> = PeachScrapingSource.parseList(client().get("/api/scraping"))

    suspend fun checkScrapingSource(source: String): List<PeachScrapingCheck> =
        PeachScrapingCheck.parseList(client().post("/api/scraping/check", peachBody("source" to source)))

    suspend fun credentials(): List<PeachCredentialStatus> =
        PeachCredentialStatus.parseList(client().get("/api/follow/credentials"))

    /**
     * 凭据写到运行 Peach 的电脑上，只接受服务端声明过的字段。调用方提交后应立即清空输入，
     * 这里不缓存、不记录 [values]。
     */
    suspend fun saveCredential(provider: String, values: Map<String, String>) {
        client().post("/api/follow/credential", peachBody("provider" to provider, "values" to values))
    }

    // endregion

    // region 内容偏好与服务器配置

    suspend fun preferences(): PeachPreferences = PeachPreferences.parse(client().get("/api/settings"))

    /** 写入是合并：只提交本次改动的键。 */
    suspend fun updatePreference(key: String, value: Any): PeachPreferences =
        PeachPreferences.parse(client().post("/api/settings", peachBody(key to value)))

    suspend fun serverVersion(): PeachServerVersion = PeachServerVersion.parse(client().get("/api/client-updates"))

    suspend fun emptyTrash(): PeachTrashResult = PeachTrashResult.parse(client().post("/api/trash/empty"))

    suspend fun trashSummary(): Pair<Int, Long> {
        val json = client().get("/api/items", listOf("state" to "trash", "limit" to 1))
        return (json.int("total") ?: 0) to (json.long("bytes") ?: 0L)
    }

    // endregion

    // region 馆藏维护：复核、重复文件、高清目标、垃圾文件、数据清理

    /** 人工复核全量数据（分区、三个队列、计数）；界面自行分页。 */
    suspend fun review(): JsonObject = client().get("/api/review")

    /**
     * 复核决定。`status`：approved / rejected / skipped / restore。
     * 元数据候选需要 [candidateKey]；作品已被改动时服务端回 409（[PeachApiException.Kind.CONFLICT]）。
     */
    suspend fun reviewDecision(
        category: String,
        itemKey: String,
        status: String,
        candidateKey: String? = null,
        note: String? = null,
        expectedRevision: Int? = null
    ): JsonObject = client().post(
        "/api/review/decision",
        peachBody(
            "category" to category, "item_key" to itemKey, "status" to status,
            "candidate_key" to candidateKey, "note" to note?.take(2000), "expected_revision" to expectedRevision
        )
    )

    /** 把来源的类别名映射到本地标签；[tag] 为空表示「不是内容标签」。 */
    suspend fun reviewGenre(genre: String, tag: String?): JsonObject =
        client().post("/api/review/genre", peachBody("genre" to genre, "tag" to (tag ?: "")))

    suspend fun duplicates(offset: Int = 0, limit: Int = 60): JsonObject =
        client().get("/api/duplicates", listOf("offset" to offset, "limit" to limit))

    suspend fun qualityGoals(offset: Int = 0, limit: Int = 60): JsonObject =
        client().get("/api/quality-goals", listOf("offset" to offset, "limit" to limit))

    /** 想要高清版：`wanted=false` 删除目标。返回最新状态。 */
    suspend fun setQualityGoal(assetId: Long, wanted: Boolean, reason: String? = null): Boolean =
        client().post("/api/quality-goal", peachBody("id" to assetId, "wanted" to wanted, "reason" to reason?.take(500)))
            .bool("better_version") == true

    suspend fun qualityGoal(assetId: Long): Pair<Boolean, String?> {
        val json = client().get("/api/item", listOf("id" to assetId))
        return (json.bool("better_version") == true) to json.string("better_version_reason")
    }

    /** 垃圾文件（广告、无关附件）复核队列。`status`：pending / dismissed。 */
    suspend fun junkFiles(kind: String?, status: String, offset: Int = 0, limit: Int = 100): JsonObject =
        client().get("/api/ads", listOf("kind" to kind, "status" to status, "offset" to offset, "limit" to limit))

    suspend fun orphanRecords(): JsonObject = client().get("/api/orphan-records")

    /** 把已消失文件的观看记录、评分等挂到 [target] 作品上。 */
    suspend fun attachOrphan(orphanId: Long, target: Long): JsonObject =
        client().post("/api/orphan-records/attach", peachBody("id" to orphanId, "target" to target))

    /** 按某个作品所在目录对账：不在盘上的移入回收站或标记消失。立即生效，可用 restore 撤回。 */
    suspend fun purgeMissing(anchorAssetId: Long): JsonObject =
        client().post("/api/purge-missing", peachBody("id" to anchorAssetId))

    /** 资源同步：后台扫描全部来源，完成后凭 `scan_id` 应用。 */
    suspend fun resourceSyncScan(restart: Boolean = false): JsonObject =
        client().post("/api/resource-sync/scan", peachBody("background" to true, "restart" to restart))

    suspend fun resourceSyncStatus(): JsonObject =
        client().post("/api/resource-sync/scan", peachBody("background" to true, "status_only" to true))

    suspend fun resourceSyncApply(scanId: String, cleanCache: Boolean = true): JsonObject =
        client().post(
            "/api/resource-sync/apply",
            peachBody("confirm" to true, "scan_id" to scanId, "background" to true, "clean_cache" to cleanCache)
        )

    suspend fun resourceSyncApplyStatus(): JsonObject = client().get("/api/resource-sync/apply")

    suspend fun links(): JsonObject = client().get("/api/links")

    suspend fun checkLinks(restart: Boolean = false): JsonObject =
        client().post("/api/links/check", peachBody("restart" to if (restart) true else null))

    suspend fun linkCheckStatus(): JsonObject = client().post("/api/links/check", peachBody("status_only" to true))

    suspend fun pruneLinks(checkId: String, ids: List<Long>? = null): JsonObject =
        client().post("/api/links/prune", peachBody("confirm" to true, "check_id" to checkId, "ids" to ids))

    suspend fun mediaRepair(): JsonObject = client().get("/api/media-repair")

    suspend fun startMediaRepair(libraryId: String, restart: Boolean = false): JsonObject =
        client().post("/api/media-repair", peachBody("library" to libraryId, "restart" to if (restart) true else null))

    suspend fun stopMediaRepair(): JsonObject = client().post("/api/media-repair", peachBody("stop" to true))

    suspend fun libraries(): List<PeachLibrary> = PeachLibrary.parseList(client().get("/api/libraries"))

    suspend fun libraryProcessingIssues(jobId: String, offset: Int = 0, revision: String? = null): JsonObject =
        client().get(
            "/api/library-processing/issues",
            listOf("job_id" to jobId, "offset" to offset, "limit" to 50, "revision" to revision)
        )

    suspend fun backgroundIssues(): List<JsonObject> = client().get("/api/background-issues").objects("issues")

    suspend fun retryBackgroundIssue(path: String): JsonObject = client().post(path)

    // endregion

    // region 整理、标签与元数据

    /** 文件整理状态、模板与可整理的来源。 */
    suspend fun organize(): JsonObject = client().get("/api/organize")

    suspend fun organizeExample(fileTemplate: String, dirTemplate: String): JsonObject =
        client().get("/api/organize/example", listOf("file_template" to fileTemplate, "dir_template" to dirTemplate))

    suspend fun organizePreview(location: String, fileTemplate: String?, dirTemplate: String?, path: String?): JsonObject =
        client().post(
            "/api/organize/preview",
            peachBody("location" to location, "file_template" to fileTemplate, "dir_template" to dirTemplate, "path" to path)
        )

    /** 按预览的同一组参数执行整理。必须先经用户确认。 */
    suspend fun organizeApply(location: String, fileTemplate: String?, dirTemplate: String?, path: String?): JsonObject =
        client().post(
            "/api/organize/apply",
            peachBody(
                "confirm" to true, "location" to location, "file_template" to fileTemplate,
                "dir_template" to dirTemplate, "path" to path
            )
        )

    /** 回滚最近一批整理。 */
    suspend fun organizeRollback(): JsonObject = client().post("/api/organize/rollback", peachBody("confirm" to true))

    /** 标签管理分页列表。`scope`：local / online；`state`：active / hidden / blocked。 */
    suspend fun tagManagement(scope: String, state: String, query: String?, offset: Int, limit: Int = 100): JsonObject =
        client().get(
            "/api/tag-management",
            listOf("scope" to scope, "state" to state, "q" to query?.takeIf { it.isNotBlank() }, "offset" to offset, "limit" to limit)
        )

    /** 预览改名、合并、隐藏、恢复（在线标签另有屏蔽）；返回受影响范围与执行用的 `token`。 */
    suspend fun tagPreview(scope: String, operation: String, ids: List<Any>, name: String? = null, targetId: Any? = null): JsonObject =
        client().post(
            "/api/tag-management/preview",
            peachBody("scope" to scope, "operation" to operation, "ids" to ids, "name" to name, "target_id" to targetId)
        )

    suspend fun tagApply(scope: String, operation: String, ids: List<Any>, token: String, name: String? = null, targetId: Any? = null): JsonObject =
        client().post(
            "/api/tag-management",
            peachBody(
                "scope" to scope, "operation" to operation, "ids" to ids,
                "name" to name, "target_id" to targetId, "token" to token
            )
        )

    /** 给作品加标签（须是已有词表中的标签）或隐藏标签。返回作品现在的标签。 */
    suspend fun editItemTag(assetId: Long, tag: String, add: Boolean): List<String> =
        client().post("/api/item-tag", peachBody("id" to assetId, "operation" to if (add) "add" else "remove", "tag" to tag.trim()))
            .strings("tags")

    /** 实体改用某个别名作为规范名。 */
    suspend fun renameEntity(kind: String, currentName: String, canonical: String): JsonObject =
        client().post("/api/entity-name", peachBody("kind" to kind, "name" to currentName, "canonical" to canonical))

    suspend fun editEntityAlias(kind: String, name: String, alias: String, remove: Boolean): JsonObject =
        client().post("/api/entity-alias", peachBody("kind" to kind, "name" to name, "alias" to alias, "remove" to if (remove) true else null))

    suspend fun entity(kind: String, entityId: Long): JsonObject = api().fetchEntity(kind, entityId)

    /** 自定义元数据来源（返回 JSON 的刮削接口）。 */
    suspend fun customMetadataSources(): List<JsonObject> = client().get("/api/custom-metadata").objects("items")

    suspend fun saveCustomMetadataSource(source: Map<String, Any?>): JsonObject =
        client().post("/api/custom-metadata", peachBody(*source.toList().toTypedArray()))

    suspend fun deleteCustomMetadataSource(id: String): JsonObject =
        client().post("/api/custom-metadata", peachBody("action" to "delete", "id" to id))

    suspend fun previewCustomMetadataSource(code: String, source: Map<String, Any?>): JsonObject =
        client().post("/api/custom-metadata", peachBody(*(source + mapOf("action" to "preview", "code" to code)).toList().toTypedArray()))

    // endregion

    // region 统计与口味

    suspend fun stats(): JsonObject = client().get("/api/stats")

    suspend fun taste(window: String = "all"): JsonObject = client().get("/api/taste", listOf("window" to window))

    // endregion

    // region 关注与订阅：作者、别名、标签、批量添加

    suspend fun followAuthors(query: String?, offset: Int = 0): JsonObject =
        client().get("/api/follow/authors", listOf("q" to query?.takeIf { it.isNotBlank() }, "offset" to offset, "limit" to 120))

    suspend fun addAuthorAlias(canonical: String, alias: String): JsonObject =
        client().post("/api/follow/author-alias", peachBody("action" to "add", "canonical" to canonical, "alias" to alias))

    suspend fun removeAuthorAlias(alias: String): JsonObject =
        client().post("/api/follow/author-alias", peachBody("action" to "remove", "alias" to alias))

    suspend fun followTags(query: String?, offset: Int = 0): JsonObject =
        client().get("/api/follow/tags", listOf("q" to query?.takeIf { it.isNotBlank() }, "types" to "all", "offset" to offset, "limit" to 180))

    /** 帖子标签：`add` / `remove` / `restore`。 */
    suspend fun editFollowTag(itemId: Long, tag: String, operation: String): JsonObject =
        client().post("/api/follow/tag", peachBody("id" to itemId, "operation" to operation, "tag" to tag))

    /** 批量解析链接或名字，得到可关注的来源候选。 */
    suspend fun resolveFollowLines(lines: List<String>): JsonObject =
        client().post("/api/follow/resolve", peachBody("lines" to lines.take(40)))

    /** 按名字查 JavDB 演员，用于「按名字订阅」。 */
    suspend fun lookupFeed(name: String): JsonObject = client().get("/api/feeds/lookup", listOf("q" to name))

    suspend fun followFeedByName(name: String, ids: List<String>): JsonObject =
        client().post("/api/feeds/source", peachBody("action" to "follow-name", "name" to name, "ids" to ids))

    // endregion

    // region 服务器配置（仅本机或 Docker 私有网络可写）

    suspend fun rawConfiguration(): JsonObject = client().get("/api/configuration")

    /** 保存媒体库与网络设置；带 GET 时的 `revision`，配置已被改动时回 409。 */
    suspend fun saveMediaSources(revision: String, mediaSources: List<Map<String, Any?>>): JsonObject =
        client().post("/api/configuration", peachBody("revision" to revision, "media_sources" to mediaSources))

    suspend fun testSmb(path: String, username: String?, password: String?, encrypt: Boolean, rootId: String? = null): JsonObject =
        client().post(
            "/api/configuration/smb/test",
            peachBody("path" to path, "smb_username" to username, "smb_password" to password, "smb_encrypt" to encrypt, "root_id" to rootId)
        )

    suspend fun saveDownloadSettings(body: Map<String, Any?>): JsonObject =
        client().post("/api/configuration/downloads", peachBody(*body.toList().toTypedArray()))

    suspend fun checkDownloadSettings(): JsonObject = client().get("/api/configuration/downloads/check")

    suspend fun createDownloadFolder(path: String): JsonObject =
        client().post("/api/configuration/downloads/folder", peachBody("path" to path))

    suspend fun pikpakLogin(username: String, password: String, remember: Boolean): JsonObject =
        client().post("/api/configuration/downloads/pikpak/login", peachBody("username" to username, "password" to password, "remember" to remember))

    suspend fun pikpakLogout(): JsonObject = client().post("/api/configuration/downloads/pikpak/logout")

    suspend fun indexers(): JsonObject = client().get("/api/configuration/indexers")

    suspend fun saveIndexers(indexers: List<Map<String, Any?>>): JsonObject =
        client().post("/api/configuration/indexers", peachBody("indexers" to indexers))

    suspend fun cloudProxies(): JsonObject = client().get("/api/configuration/cloud-proxies")

    suspend fun saveCloudProxy(accountId: String, revision: String, api: Map<String, Any?>?, data: Map<String, Any?>?): JsonObject =
        client().post("/api/configuration/cloud-proxies", peachBody("id" to accountId, "revision" to revision, "api" to api, "data" to data))

    suspend fun setAutomaticUpdates(mode: String, intervalHours: Int): JsonObject =
        client().post("/api/configuration/automatic-updates", peachBody("mode" to mode, "interval_hours" to intervalHours))

    suspend fun checkUpdates(): JsonObject = client().get("/api/configuration/updates")

    suspend fun updateStatus(): JsonObject = client().get("/api/configuration/update-status")

    /** 下载并安装更新；[restart] 时装好后重启 Peach，重启期间请求会失败。 */
    suspend fun installUpdate(restart: Boolean): JsonObject =
        client().post(if (restart) "/api/configuration/update-restart" else "/api/configuration/update")

    suspend fun measureStorage(): JsonObject = client().post("/api/configuration/storage/measure")

    suspend fun setRemoteReporting(enabled: Boolean): JsonObject =
        client().post("/api/configuration/remote-reporting", peachBody("usage" to enabled, "errors" to enabled))

    suspend fun librarySharingPreview(): JsonObject = client().get("/api/configuration/library-sharing/preview")

    suspend fun setLibrarySharing(enabled: Boolean, previewToken: String?, schema: Any?): JsonObject =
        client().post(
            "/api/configuration/library-sharing",
            peachBody("enabled" to enabled, "preview_token" to previewToken, "consent" to true, "schema" to schema)
        )

    suspend fun setPeachProxy(mode: String, proxy: String?): JsonObject =
        client().post("/api/configuration/peach-proxy", peachBody("mode" to mode, "proxy" to proxy))

    suspend fun setEntryLinks(sites: Map<String, String>): JsonObject =
        client().post("/api/configuration/entry-links", peachBody("sites" to sites.mapValues { (_, host) -> mapOf("host" to host) }))

    suspend fun networkStatus(check: Boolean = false): JsonObject =
        if (check) client().post("/api/network-status/check") else client().get("/api/network-status")

    suspend fun mediaProcessing(): JsonObject = client().get("/api/media-processing")

    suspend fun setMediaProcessing(values: Map<String, Any?>): JsonObject =
        client().post("/api/media-processing", peachBody(*values.toList().toTypedArray()))

    suspend fun thumbnailJobs(): JsonObject = client().get("/api/thumbnail-jobs")

    suspend fun setThumbnailMode(mode: String): JsonObject = client().post("/api/thumbnail-jobs", peachBody("mode" to mode))

    suspend fun diagnostics(): JsonObject = client().get("/api/diagnostics")

    // endregion

    // region 网页入口

    /**
     * Peach 网页的地址与登录方式。口令登录时用 `?t=` 建立 Cookie 会话（服务端随即重定向去掉口令），
     * 密码登录时直接复用会话 Cookie。
     */
    suspend fun webEntry(path: String = "/"): PeachWebEntry {
        val client = client()
        val snapshot = AuthRepositoryProvider.getInstance(context).getActiveSessionSnapshot()
        val credential = PeachCredential.decode(SecureSessionStore(context).getToken(snapshot.activeServerId))
            ?: PeachCredential.Open
        return PeachWebEntry(baseUrl = client.baseUrl.trimEnd('/'), path = path, credential = credential)
    }

    // endregion

    companion object {
        const val ORIGIN = "vela"

        @Volatile
        private var instance: PeachRepository? = null

        fun getInstance(context: Context): PeachRepository =
            instance ?: synchronized(this) {
                instance ?: PeachRepository(context.applicationContext).also { instance = it }
            }
    }
}
