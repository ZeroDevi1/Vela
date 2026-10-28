package com.vela.data.peach

import kotlinx.serialization.json.JsonObject

/** 实体引用（出镜者、创作者、厂牌、系列）。`hasImage` 决定能否取 `/entity-image`。 */
data class PeachEntityRef(
    val kind: String,
    val id: Long?,
    val name: String,
    val hasImage: Boolean = false
)

/** 同番号版次组或分卷组的卡片摘要。 */
data class PeachGroup(
    val title: String,
    val count: Int,
    val seedId: Long?,
    val itemIds: List<Long>
)

/** `/api/items`、`/api/item` 与推荐列表共用的作品投影。 */
data class PeachAsset(
    val id: Long,
    val name: String?,
    val catalogTitle: String?,
    val originalTitle: String?,
    val code: String?,
    val releaseDate: String?,
    val durationSeconds: Double?,
    val width: Int?,
    val height: Int?,
    val size: Long?,
    val playCount: Int?,
    val lastPlayed: Double?,
    val firstSeen: Double?,
    val leaveRatio: Double?,
    val feedback: String?,
    val disposal: String?,
    val rating: Int?,
    val oCount: Int?,
    val watchLater: Boolean,
    val liked: Boolean,
    val location: String?,
    val isStrm: Boolean,
    val performers: List<String>,
    val tags: List<String>,
    val creator: String?,
    val studio: String?,
    val coverKey: String?,
    val hasThumb: Boolean,
    val regionLabel: String?,
    val entityRefs: List<PeachEntityRef>,
    val editionGroup: PeachGroup?,
    val partGroup: PeachGroup?,
    val partLabel: String?,
    val editionLabel: String?
) {
    val displayTitle: String
        get() = catalogTitle?.takeIf { it.isNotBlank() }
            ?: name?.takeIf { it.isNotBlank() }
            ?: code
            ?: id.toString()

    val isSeen: Boolean get() = feedback == "seen"
    val isDisliked: Boolean get() = feedback == "dislike"
    val inTrash: Boolean get() = disposal == "trash"

    /** 文件扩展名，用作容器名与版本标签。 */
    val container: String?
        get() = name?.substringAfterLast('.', missingDelimiterValue = "")
            ?.lowercase()
            ?.takeIf { it.isNotBlank() && it.length <= 5 }

    companion object {
        fun parse(json: JsonObject): PeachAsset? {
            val id = json.long("id") ?: return null
            val refs = buildList {
                val entityRefs = json.obj("entity_refs")
                if (entityRefs != null) {
                    for (kind in ENTITY_REF_KINDS) {
                        entityRefs.objects(kind).forEach { ref -> parseRef(kind, ref)?.let(::add) }
                    }
                } else {
                    json.objects("performer_entities").forEach { ref -> parseRef("performer", ref)?.let(::add) }
                    json.obj("creator_entity")?.let { ref -> parseRef("creator", ref)?.let(::add) }
                }
            }
            return PeachAsset(
                id = id,
                name = json.string("name"),
                catalogTitle = json.string("catalog_title"),
                originalTitle = json.string("original_title"),
                code = json.string("code"),
                releaseDate = json.string("release_date"),
                durationSeconds = json.double("duration")?.takeIf { it > 0 },
                width = json.int("width"),
                height = json.int("height"),
                size = json.long("size"),
                playCount = json.int("play_count"),
                lastPlayed = json.double("last_played"),
                firstSeen = json.double("first_seen"),
                leaveRatio = json.double("leave_ratio"),
                feedback = json.string("feedback"),
                disposal = json.string("disposal"),
                rating = json.int("rating"),
                oCount = json.int("o_count"),
                watchLater = json.bool("watch_later") == true,
                liked = json.bool("liked") == true,
                location = json.string("location"),
                isStrm = json.bool("is_strm") == true,
                performers = json.strings("performers"),
                tags = json.strings("tags", objectKey = "k"),
                creator = json.string("creator"),
                studio = json.string("studio"),
                coverKey = json.string("cover_key")?.takeIf { json.bool("has_cover") != false },
                hasThumb = json.bool("has_thumb") == true,
                regionLabel = json.string("region_label"),
                entityRefs = refs,
                editionGroup = json.obj("edition_group")?.let(::parseGroup),
                partGroup = json.obj("part_group")?.let(::parseGroup),
                partLabel = json.string("part_label"),
                editionLabel = json.string("edition_label")
            )
        }

        private val ENTITY_REF_KINDS = listOf("performer", "creator", "studio", "series")

        private fun parseRef(kind: String, json: JsonObject): PeachEntityRef? {
            val name = json.string("name") ?: return null
            return PeachEntityRef(
                kind = kind,
                id = json.long("id"),
                name = name,
                hasImage = json.bool("has_image") == true
            )
        }

        private fun parseGroup(json: JsonObject): PeachGroup = PeachGroup(
            title = json.string("title").orEmpty(),
            count = json.int("count") ?: 0,
            seedId = json.long("seed_id"),
            itemIds = json.array("item_ids")?.mapNotNull { element ->
                element.displayText()?.toLongOrNull()
            }.orEmpty()
        )
    }
}

data class PeachItemPage(
    val items: List<PeachAsset>,
    val total: Int?,
    val hasMore: Boolean?
) {
    companion object {
        fun parse(json: JsonObject): PeachItemPage = PeachItemPage(
            items = json.objects("items").mapNotNull(PeachAsset::parse),
            total = json.int("total"),
            hasMore = json.bool("has_more")
        )
    }
}

data class PeachLibrary(
    val id: String,
    val name: String,
    val icon: String?,
    val color: String?,
    val folders: Int,
    val metered: Boolean,
    val rootIds: List<String>
) {
    companion object {
        fun parseList(json: JsonObject): List<PeachLibrary> = json.objects("libraries").mapNotNull { row ->
            val id = row.string("id") ?: return@mapNotNull null
            PeachLibrary(
                id = id,
                name = row.string("name") ?: id,
                icon = row.string("icon"),
                color = row.string("color"),
                folders = row.int("folders") ?: 0,
                metered = row.bool("metered") == true,
                rootIds = row.objects("roots").mapNotNull { it.string("root_id") }
            )
        }
    }
}

/** `/api/stream-plan` 的结果：`range` 直接播放 `src`，`hls` 播放 m3u8。 */
data class PeachStreamPlan(
    val protocol: String,
    val src: String,
    val mimeType: String?,
    val durationSeconds: Double?,
    val reason: String?
) {
    val isHls: Boolean get() = protocol.equals("hls", ignoreCase = true)

    companion object {
        fun parse(json: JsonObject): PeachStreamPlan? {
            val src = json.string("src") ?: return null
            return PeachStreamPlan(
                protocol = json.string("protocol") ?: "range",
                src = src,
                mimeType = json.string("mime_type"),
                durationSeconds = json.double("duration"),
                reason = json.string("reason")
            )
        }
    }
}

data class PeachSubtitleTrack(
    val id: String,
    val index: Int,
    val name: String?,
    val label: String?,
    val language: String?,
    val format: String?,
    val playable: Boolean,
    val note: String?,
    val src: String?
) {
    companion object {
        fun parseList(json: JsonObject): List<PeachSubtitleTrack> =
            json.objects("subtitles").mapIndexedNotNull { position, row ->
                PeachSubtitleTrack(
                    id = row.string("id") ?: "sidecar:$position",
                    index = row.int("index") ?: position,
                    name = row.string("name"),
                    label = row.string("label"),
                    language = row.string("language"),
                    format = row.string("format"),
                    playable = row.bool("playable") != false,
                    note = row.string("note"),
                    src = row.string("src")
                )
            }
    }
}

/** `/api/feedback` 返回的最新状态。切换类接口以它为准，不在本地自行翻转。 */
data class PeachFeedbackState(
    val feedback: String?,
    val disposal: String?,
    val rating: Int?,
    val oCount: Int?
) {
    companion object {
        fun parse(json: JsonObject) = PeachFeedbackState(
            feedback = json.string("feedback"),
            disposal = json.string("disposal"),
            rating = json.int("rating"),
            oCount = json.int("o_count")
        )
    }
}

/** `/api/client-updates` 里与客户端相关的版本信息。 */
data class PeachServerVersion(
    val version: String?,
    val buildId: String?
) {
    companion object {
        fun parse(json: JsonObject): PeachServerVersion {
            val runtime = json.obj("runtime")
            return PeachServerVersion(
                version = runtime?.string("version"),
                buildId = runtime?.string("build_id")
            )
        }
    }
}

/** `/api/index` 的一行：标签、出镜者、创作者、厂牌等名册。 */
data class PeachIndexEntry(
    val id: Long?,
    val kind: String?,
    val name: String,
    val count: Int?,
    val hasImage: Boolean
) {
    companion object {
        fun parseList(json: JsonObject): List<PeachIndexEntry> = json.objects("items").mapNotNull { row ->
            val name = row.string("k") ?: row.string("name") ?: return@mapNotNull null
            PeachIndexEntry(
                id = row.long("entity_id") ?: row.long("id"),
                kind = row.string("entity_kind"),
                name = name,
                count = row.int("n"),
                hasImage = row.bool("has_image") == true
            )
        }
    }
}

/** `/api/entity` 资料页的摘要。合并过的实体以响应里的 id 为准。 */
data class PeachEntity(
    val id: Long,
    val kind: String,
    val name: String,
    val hasImage: Boolean,
    val assetCount: Int?,
    val aliases: List<String>,
    val following: Boolean
) {
    val summary: String?
        get() = buildList {
            assetCount?.let { add("作品 $it 部") }
            if (aliases.isNotEmpty()) add("别名：" + aliases.take(6).joinToString("、"))
        }.joinToString("\n").takeIf { it.isNotBlank() }

    companion object {
        fun parse(json: JsonObject): PeachEntity? {
            val id = json.long("id") ?: return null
            return PeachEntity(
                id = id,
                kind = json.string("kind").orEmpty(),
                name = json.string("canonical_name") ?: json.string("name") ?: return null,
                hasImage = json.bool("has_image") == true,
                assetCount = json.int("asset_count"),
                aliases = json.strings("display_aliases").ifEmpty { json.strings("aliases") },
                following = json.obj("feed")?.bool("following") == true
            )
        }
    }
}
