package com.vela.data.peach

import android.content.Context
import com.vela.data.repository.AuthRepositoryProvider
import com.vela.data.repository.MediaRepositoryProvider
import com.vela.data.security.SecureSessionStore
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

    /** 只允许本机的部署会回 403（[PeachApiException.Kind.LOCAL_ONLY]），调用方据此整页降级为只读说明。 */
    suspend fun configuration(): PeachConfigurationSummary = PeachConfigurationSummary.parse(client().get("/api/configuration"))

    suspend fun serverVersion(): PeachServerVersion = PeachServerVersion.parse(client().get("/api/client-updates"))

    suspend fun emptyTrash(): PeachTrashResult = PeachTrashResult.parse(client().post("/api/trash/empty"))

    suspend fun trashSummary(): Pair<Int, Long> {
        val json = client().get("/api/items", listOf("state" to "trash", "limit" to 1))
        return (json.int("total") ?: 0) to (json.long("bytes") ?: 0L)
    }

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
