package com.vela.data.peach

import kotlinx.serialization.json.JsonObject

/*
 * 「Peach 管理」各页用到的接口投影。字段名取自 Peach 源码里对应 handler 的返回体，
 * 解析一律宽松：缺字段给 null 或空集合，不因服务端多一个字段而失败。
 */

/** `/api/downloads` 的 `providers`：已配置的下载渠道，提交磁力时 `provider` 只能取这里的 `key`。 */
data class PeachDownloadChannel(
    val key: String,
    val label: String,
    val configured: Boolean,
    val canCancel: Boolean
)

data class PeachDownloadTask(
    val id: Long,
    val title: String,
    val code: String?,
    val providerLabel: String?,
    val state: String,
    val stateLabel: String,
    val failureLabel: String?,
    val failureDetail: String?,
    val progress: Double?,
    val postprocessLabel: String?,
    val postprocessRetryable: Boolean,
    val cancellable: Boolean,
    val assetId: Long?,
    val updatedAt: String?
) {
    val inProgress: Boolean get() = state in setOf("submitted", "remote_running", "remote_done", "landed")

    companion object {
        fun parse(json: JsonObject): PeachDownloadTask? {
            val id = json.long("id") ?: return null
            return PeachDownloadTask(
                id = id,
                title = json.string("display_title") ?: json.string("title") ?: json.string("display_name") ?: "#$id",
                code = json.string("display_code") ?: json.string("code"),
                providerLabel = json.string("provider_label"),
                state = json.string("state").orEmpty(),
                stateLabel = json.string("state_label") ?: json.string("state").orEmpty(),
                failureLabel = json.string("failure_label"),
                failureDetail = json.string("failure_detail"),
                progress = json.double("progress"),
                postprocessLabel = json.string("postprocess_label")
                    ?.takeUnless { json.string("postprocess_state") == "not_requested" },
                postprocessRetryable = json.bool("postprocess_retryable") == true,
                cancellable = json.bool("cancellable") == true,
                assetId = json.long("asset_id"),
                updatedAt = json.string("updated_at")
            )
        }
    }
}

data class PeachDownloads(
    val available: Boolean,
    val channels: List<PeachDownloadChannel>,
    val problems: List<String>,
    val tasks: List<PeachDownloadTask>,
    val hasMore: Boolean,
    val nextBeforeId: Long?
) {
    val configuredChannels: List<PeachDownloadChannel> get() = channels.filter { it.configured }

    companion object {
        fun parse(json: JsonObject) = PeachDownloads(
            available = json.bool("available") != false,
            channels = json.objects("providers").mapNotNull { row ->
                val key = row.string("key") ?: return@mapNotNull null
                PeachDownloadChannel(
                    key = key,
                    label = row.string("label") ?: key,
                    configured = row.bool("configured") == true,
                    canCancel = row.bool("can_cancel") == true
                )
            },
            problems = json.strings("problems"),
            tasks = json.objects("tasks").mapNotNull(PeachDownloadTask::parse),
            hasMore = json.bool("has_more") == true,
            nextBeforeId = json.long("next_before_id")
        )
    }
}

/** `/api/tasks` 的一次运行。 */
data class PeachTaskRun(
    val id: Long,
    val label: String,
    val status: String,
    val progressCurrent: Int?,
    val progressTotal: Int?,
    val progressLabel: String?,
    val startedAt: String?,
    val finishedAt: String?,
    val error: String?
) {
    val progressFraction: Float?
        get() {
            val total = progressTotal?.takeIf { it > 0 } ?: return null
            return ((progressCurrent ?: 0).toFloat() / total).coerceIn(0f, 1f)
        }

    companion object {
        fun parse(json: JsonObject): PeachTaskRun? {
            val id = json.long("id") ?: return null
            return PeachTaskRun(
                id = id,
                label = json.string("task_label") ?: json.string("task_key") ?: "#$id",
                status = json.string("status").orEmpty(),
                progressCurrent = json.int("progress_current"),
                progressTotal = json.int("progress_total"),
                progressLabel = json.string("progress_label"),
                startedAt = json.string("started_at"),
                finishedAt = json.string("finished_at"),
                error = json.string("error")
            )
        }
    }
}

data class PeachTasks(val running: List<PeachTaskRun>, val finished: List<PeachTaskRun>, val message: String?) {
    companion object {
        fun parse(json: JsonObject) = PeachTasks(
            running = json.objects("running").mapNotNull(PeachTaskRun::parse),
            finished = json.objects("finished").mapNotNull(PeachTaskRun::parse),
            message = json.string("message").takeIf { json.bool("available") == false }
        )
    }
}

/** 订阅源：按 JavDB 身份追女优新作。 */
data class PeachFeedSource(
    val id: Long,
    val name: String,
    val kindLabel: String?,
    val entityId: Long?,
    val enabled: Boolean,
    val lastError: String?,
    val lastNewCount: Int?,
    val nextFetchAt: String?
)

data class PeachFeeds(val sources: List<PeachFeedSource>, val unread: Int) {
    companion object {
        fun parse(json: JsonObject) = PeachFeeds(
            sources = json.objects("sources").mapNotNull { row ->
                val id = row.long("id") ?: return@mapNotNull null
                PeachFeedSource(
                    id = id,
                    name = row.string("entity_name") ?: row.string("name") ?: row.string("page_name") ?: "#$id",
                    kindLabel = row.string("kind_label"),
                    entityId = row.long("entity_id"),
                    enabled = row.bool("enabled") != false,
                    lastError = row.string("last_error"),
                    lastNewCount = row.int("last_new_count"),
                    nextFetchAt = row.string("next_fetch_at")
                )
            },
            unread = json.int("unread") ?: 0
        )
    }
}

/** 订阅源发现的库外新作。 */
data class PeachDiscovery(
    val id: Long,
    val code: String,
    val title: String?,
    val releaseDate: String?,
    val studio: String?,
    val performers: String?,
    val read: Boolean,
    val ignored: Boolean,
    val wanted: Boolean
) {
    companion object {
        fun parseList(json: JsonObject): List<PeachDiscovery> = json.objects("items").mapNotNull { row ->
            PeachDiscovery(
                id = row.long("id") ?: return@mapNotNull null,
                code = row.string("code") ?: return@mapNotNull null,
                title = row.string("title"),
                releaseDate = row.string("release_date"),
                studio = row.string("studio"),
                performers = row.string("performers"),
                read = row.bool("read") == true,
                ignored = row.bool("ignored") == true,
                wanted = row.bool("wanted") == true
            )
        }
    }
}

/** 关注来源：创作者站点的追更来源。 */
data class PeachFollowSource(
    val id: Long,
    val label: String,
    val providerLabel: String?,
    val url: String?,
    val enabled: Boolean,
    val lastError: String?,
    val lastCheckedAt: String?
) {
    companion object {
        fun parseList(json: JsonObject): List<PeachFollowSource> = json.objects("sources").mapNotNull { row ->
            val id = row.long("id") ?: return@mapNotNull null
            PeachFollowSource(
                id = id,
                label = row.string("label") ?: row.string("author_name") ?: row.string("ref") ?: "#$id",
                providerLabel = row.string("provider_label"),
                url = row.string("url"),
                enabled = row.bool("enabled") != false,
                lastError = row.string("last_error"),
                lastCheckedAt = row.string("last_checked_at")
            )
        }
    }
}

/** 订阅源与关注共用的自动检查计划状态。 */
data class PeachSchedule(
    val available: Boolean,
    val enabled: Boolean,
    val intervalMinutes: Int,
    val running: Boolean,
    val nextRunAt: String?,
    val lastError: String?
) {
    companion object {
        fun parse(json: JsonObject) = PeachSchedule(
            available = json.bool("available") != false,
            enabled = json.bool("enabled") == true,
            intervalMinutes = json.int("interval_minutes") ?: 60,
            running = json.bool("running") == true,
            nextRunAt = json.string("next_run_at"),
            lastError = json.string("last_error")
        )
    }
}

/** 想要清单：库外作品按番号登记，扫描入库时自动匹配。 */
data class PeachWant(
    val id: Long,
    val code: String?,
    val title: String?,
    val phase: String,
    val hasMagnets: Boolean?,
    val lastSearchOutcome: String?
) {
    companion object {
        fun parse(json: JsonObject): PeachWant? = PeachWant(
            id = json.long("id") ?: return null,
            code = json.string("code"),
            title = json.string("title"),
            phase = json.string("phase").orEmpty(),
            hasMagnets = json.bool("has_magnets"),
            lastSearchOutcome = json.string("last_search_outcome")
        )

        fun parseList(json: JsonObject): List<PeachWant> = json.objects("items").mapNotNull(::parse)
    }
}

/** 资源搜索、番号资源与想要清单磁力共用的候选。`size` 有的是字节数，有的是原文。 */
data class PeachMagnet(
    val name: String,
    val uri: String,
    val sizeText: String?,
    val seeders: Int?,
    val resolution: Int?,
    val chinese: Boolean,
    val uncensored: Boolean,
    val source: String?,
    val date: String?
) {
    companion object {
        fun parseList(json: JsonObject): List<PeachMagnet> = json.objects("items").mapNotNull { row ->
            val uri = row.string("uri") ?: return@mapNotNull null
            if (!uri.startsWith("magnet:", ignoreCase = true)) return@mapNotNull null
            PeachMagnet(
                name = row.string("name") ?: uri.take(60),
                uri = uri,
                sizeText = row.string("size_text") ?: row.string("size")?.takeIf { it.toLongOrNull() == null }
                    ?: row.long("size")?.let(::formatBytes),
                seeders = row.int("seeders"),
                resolution = row.int("resolution")?.takeIf { it > 0 },
                chinese = row.bool("chinese") == true,
                uncensored = row.bool("uncensored") == true,
                source = row.string("source"),
                date = row.string("date")
            )
        }
    }
}

data class PeachResourceResult(
    val state: String,
    val items: List<PeachMagnet>,
    val error: String?,
    val warnings: List<String>
) {
    companion object {
        fun parse(json: JsonObject) = PeachResourceResult(
            state = json.string("state") ?: "ready",
            items = PeachMagnet.parseList(json),
            error = json.string("error"),
            warnings = json.strings("warnings")
        )
    }
}

/** 刮削来源设置与可达性。 */
data class PeachScrapingSource(
    val source: String,
    val label: String,
    val network: String?,
    val acceptsCookie: Boolean,
    val cookieSaved: Boolean,
    val cooldownUntil: Double?,
    val needsBrowser: Boolean
) {
    companion object {
        fun parseList(json: JsonObject): List<PeachScrapingSource> = json.objects("sources").mapNotNull { row ->
            val source = row.string("source") ?: return@mapNotNull null
            PeachScrapingSource(
                source = source,
                label = row.string("label") ?: source,
                network = row.string("network"),
                acceptsCookie = row.bool("accepts_cookie") == true,
                cookieSaved = row.bool("cookie_saved") == true,
                cooldownUntil = row.double("cooldown_until"),
                needsBrowser = row.bool("browser") == true
            )
        }
    }
}

data class PeachScrapingCheck(val label: String, val ok: Boolean, val kind: String?, val reason: String?) {
    companion object {
        fun parseList(json: JsonObject): List<PeachScrapingCheck> = json.objects("results").map { row ->
            PeachScrapingCheck(
                label = row.string("label").orEmpty(),
                ok = row.bool("ok") == true,
                kind = row.string("kind"),
                reason = row.string("reason") ?: row.string("message")
            )
        }
    }
}

/** 关注来源所需凭据的状态。只有字段名与缺失项，从不返回凭据值。 */
data class PeachCredentialStatus(
    val provider: String,
    val label: String,
    val present: Boolean,
    val fields: List<String>,
    val missing: List<String>,
    val requirement: String?,
    val howto: String?,
    val where: String?
) {
    companion object {
        fun parseList(json: JsonObject): List<PeachCredentialStatus> = json.objects("providers").mapNotNull { row ->
            val provider = row.string("provider") ?: return@mapNotNull null
            PeachCredentialStatus(
                provider = provider,
                label = row.string("provider_label") ?: provider,
                present = row.bool("present") == true,
                fields = row.strings("needs").ifEmpty { row.strings("fields") },
                missing = row.strings("missing"),
                requirement = row.string("requirement"),
                howto = row.string("howto"),
                where = row.string("where")
            )
        }
    }
}

/** 内容偏好里 Vela 可以编辑的键，其余（如 Web 侧栏顺序）不展示。 */
data class PeachPreferences(
    val followInitialDays: Int?,
    val searchHistoryLimit: Int?,
    val feedHideGroupCompilations: Boolean,
    val feedHideSoloCompilations: Boolean,
    val feedHideExcerpts: Boolean
) {
    companion object {
        const val KEY_FOLLOW_INITIAL_DAYS = "followInitialDays"
        const val KEY_SEARCH_HISTORY_LIMIT = "searchHistoryLimit"
        const val KEY_HIDE_GROUP = "feedHideGroupCompilations"
        const val KEY_HIDE_SOLO = "feedHideSoloCompilations"
        const val KEY_HIDE_EXCERPTS = "feedHideExcerpts"
        val FOLLOW_INITIAL_DAY_OPTIONS = listOf(0, 7, 30, 90)

        fun parse(json: JsonObject) = PeachPreferences(
            followInitialDays = json.int(KEY_FOLLOW_INITIAL_DAYS),
            searchHistoryLimit = json.int(KEY_SEARCH_HISTORY_LIMIT),
            feedHideGroupCompilations = json.bool(KEY_HIDE_GROUP) == true,
            feedHideSoloCompilations = json.bool(KEY_HIDE_SOLO) == true,
            feedHideExcerpts = json.bool(KEY_HIDE_EXCERPTS) == true
        )
    }
}

/** 回收站清空结果。 */
data class PeachTrashResult(val purged: Int, val blocked: Int, val bytesReclaimed: Long?) {
    companion object {
        fun parse(json: JsonObject) = PeachTrashResult(
            purged = json.int("purged") ?: 0,
            blocked = json.array("blocked")?.size ?: 0,
            bytesReclaimed = json.long("bytes_reclaimed")
        )
    }
}

/** Peach 网页入口：地址与登录方式。 */
data class PeachWebEntry(val baseUrl: String, val path: String, val credential: PeachCredential) {
    /** 首次加载的地址：口令登录时附 `?t=`，服务端设置 Cookie 后重定向去掉它。 */
    fun initialUrl(): String {
        val target = baseUrl + (if (path.startsWith("/")) path else "/$path")
        val token = (credential as? PeachCredential.Token)?.value ?: return target
        val separator = if ('?' in target) '&' else '?'
        return "$target${separator}t=${PeachImageUrls.encodeQueryValue(token)}"
    }
}

fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val units = listOf("KB", "MB", "GB", "TB")
    var value = bytes.toDouble() / 1024
    var index = 0
    while (value >= 1024 && index < units.lastIndex) {
        value /= 1024
        index++
    }
    val rounded = (value * 10).toLong() / 10.0
    return "$rounded ${units[index]}"
}

/** `/api/suggest` 的一条补全：实体（出镜者、厂牌…）、标签或具体作品。 */
data class PeachSuggestion(
    val kind: String,
    val groupLabel: String?,
    val value: String,
    val entityId: Long?,
    val assetId: Long?,
    val count: Int?
) {
    /** 跳转用的 Vela 条目 ID：作品直接打开详情，可浏览的实体打开资料页；其余按文字搜索。 */
    val itemId: String?
        get() = when {
            kind == "asset" && assetId != null -> assetId.toString()
            entityId != null && kind in PeachIds.ENTITY_KINDS && kind != "tag" -> PeachIds.entity(kind, entityId)
            else -> null
        }

    companion object {
        fun parseList(json: JsonObject): List<PeachSuggestion> = json.objects("groups").flatMap { group ->
            val kind = group.string("kind").orEmpty()
            val label = group.string("label")
            group.objects("items").mapNotNull { row ->
                val value = row.string("value") ?: row.string("title") ?: row.string("code") ?: return@mapNotNull null
                PeachSuggestion(
                    kind = kind,
                    groupLabel = label,
                    value = value,
                    entityId = row.long("entity_id"),
                    assetId = if (kind == "asset") row.long("id") else null,
                    count = row.int("n")?.takeIf { it > 0 }
                )
            }
        }
    }
}
