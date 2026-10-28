package com.vela.data.peach

import kotlinx.serialization.json.JsonObject

/*
 * 浏览页用到的接口投影：关注时间线、原目录、播放列表、JAV 探索、图集、热门实体与收藏。
 * 图片地址都是 Peach 的相对路由，由调用方拼上服务器地址。
 */

/** 关注时间线里帖子的一个媒体。`index` 是原始序号，取流时用作 `media=`。 */
data class PeachFollowMedia(
    val index: Int,
    val name: String?,
    val kind: String?,
    val mediaType: String?,
    val thumbUrl: String?,
    val width: Int?,
    val height: Int?
) {
    val isVideo: Boolean get() = kind == "video" || mediaType?.startsWith("video/") == true
}

/** 关注时间线的一条帖子。 */
data class PeachFollowItem(
    val id: Long,
    val title: String?,
    val author: String?,
    val providerLabel: String?,
    val sourceLabel: String?,
    val summary: String?,
    val url: String?,
    val thumbUrl: String?,
    val publishedAt: String?,
    val durationSeconds: Double?,
    val status: String,
    val assetId: Long?,
    val playable: Boolean,
    val mediaKind: String?,
    val mediaNeedsCredential: Boolean,
    val mediaError: String?,
    val tags: List<String>,
    val media: List<PeachFollowMedia>
) {
    val displayTitle: String get() = title?.takeIf { it.isNotBlank() } ?: author ?: "#$id"
    val isSaved: Boolean get() = status == "saved" || assetId != null

    companion object {
        fun parse(json: JsonObject): PeachFollowItem? {
            val id = json.long("id") ?: return null
            return PeachFollowItem(
                id = id,
                title = json.string("title"),
                author = json.string("author"),
                providerLabel = json.string("provider_label"),
                sourceLabel = json.string("source_label"),
                summary = json.string("summary"),
                url = json.string("url"),
                thumbUrl = json.string("thumb_url"),
                publishedAt = json.string("published_at"),
                durationSeconds = json.double("duration")?.takeIf { it > 0 },
                status = json.string("status") ?: "new",
                assetId = json.long("asset_id"),
                playable = json.bool("playable") == true,
                mediaKind = json.string("media_kind"),
                mediaNeedsCredential = json.bool("media_needs_credential") == true,
                mediaError = json.string("media_error"),
                tags = json.strings("tags"),
                media = json.objects("media_items").mapNotNull { row ->
                    PeachFollowMedia(
                        index = row.int("index") ?: return@mapNotNull null,
                        name = row.string("name"),
                        kind = row.string("media_kind"),
                        mediaType = row.string("media_type"),
                        thumbUrl = row.string("thumb_url"),
                        width = row.int("width"),
                        height = row.int("height")
                    )
                }
            )
        }
    }
}

data class PeachFollowPage(
    val items: List<PeachFollowItem>,
    val hasMore: Boolean,
    val counts: Map<String, Int>,
    val authors: List<String>,
    val tags: List<String>
) {
    companion object {
        /** 每个分组取主帖；版本与重复项折叠在主帖下，时间线只列一次。 */
        fun parse(json: JsonObject): PeachFollowPage {
            val counts = json.obj("counts")?.let { counts -> counts.keys.associateWith { counts.int(it) ?: 0 } }.orEmpty()
            val facets = json.obj("facets")
            return PeachFollowPage(
                items = json.objects("groups").mapNotNull { group -> group.obj("primary")?.let(PeachFollowItem::parse) },
                hasMore = json.bool("has_more") == true,
                counts = counts,
                authors = facets?.strings("authors").orEmpty(),
                tags = facets?.array("tags")?.mapNotNull { entry -> entry.asArray()?.firstOrNull().displayText() }.orEmpty()
            )
        }

        /** `/api/follow?item=` 只返回这一条所在的分组，在主帖、版本与重复项里按 ID 找。 */
        fun findItem(json: JsonObject, itemId: Long): PeachFollowItem? =
            json.objects("groups").asSequence()
                .flatMap { group ->
                    listOfNotNull(group.obj("primary")) + group.objects("variants") + group.objects("duplicates")
                }
                .mapNotNull(PeachFollowItem::parse)
                .firstOrNull { it.id == itemId }
    }
}

/** 原目录浏览的一个文件夹。`id` 是不透明的目录标识，作 `/api/items?folder=` 使用。 */
data class PeachFolder(
    val id: String,
    val name: String,
    val path: String?,
    val location: String?,
    val videos: Int,
    val files: Int,
    val hasChildren: Boolean
)

data class PeachFolders(
    val roots: List<PeachFolder>,
    val children: List<PeachFolder>,
    val ancestors: List<PeachFolder>,
    val selected: PeachFolder?,
    val matches: List<PeachFolder>
) {
    companion object {
        private fun folder(json: JsonObject): PeachFolder? = PeachFolder(
            id = json.string("id") ?: return null,
            name = json.string("name") ?: json.string("path") ?: "",
            path = json.string("path"),
            location = json.string("location"),
            videos = json.int("videos") ?: 0,
            files = json.int("files") ?: 0,
            hasChildren = json.bool("has_children") == true
        )

        fun parse(json: JsonObject) = PeachFolders(
            roots = json.objects("roots").mapNotNull(::folder),
            children = json.objects("children").mapNotNull(::folder),
            ancestors = json.objects("ancestors").mapNotNull(::folder),
            selected = json.obj("selected")?.let(::folder),
            matches = json.objects("matches").mapNotNull(::folder)
        )
    }
}

data class PeachPlaylistSummary(
    val id: Long,
    val name: String,
    val itemCount: Int,
    val previewAssetId: Long?,
    val isMix: Boolean
) {
    companion object {
        fun parseList(json: JsonObject): List<PeachPlaylistSummary> = json.objects("items").mapNotNull { row ->
            PeachPlaylistSummary(
                id = row.long("id") ?: return@mapNotNull null,
                name = row.string("name").orEmpty(),
                itemCount = row.int("item_count") ?: 0,
                previewAssetId = row.long("preview_asset_id"),
                isMix = row.string("source_kind") == "mix"
            )
        }
    }
}

/** JAV 探索的一条：库外发现或馆藏里少看的作品。 */
data class PeachJavDiscovery(
    val code: String,
    val title: String?,
    val releaseDate: String?,
    val studio: String?,
    val performers: String?,
    val tags: List<String>,
    val assetId: Long?,
    val hasCover: Boolean,
    val wanted: Boolean,
    val reasons: List<String>,
    val score: Double?
) {
    companion object {
        fun parseList(json: JsonObject): List<PeachJavDiscovery> = json.objects("items").mapNotNull { row ->
            PeachJavDiscovery(
                code = row.string("code") ?: return@mapNotNull null,
                title = row.string("title"),
                releaseDate = row.string("release_date"),
                studio = row.string("studio"),
                performers = row.string("performers"),
                tags = row.strings("tags"),
                assetId = row.long("asset_id"),
                hasCover = row.bool("has_cover") == true,
                wanted = row.bool("wanted") == true,
                reasons = row.strings("reasons"),
                score = row.double("taste_score") ?: row.double("score")
            )
        }
    }
}

data class PeachJavDetail(
    val code: String,
    val title: String?,
    val releaseDate: String?,
    val studio: String?,
    val director: String?,
    val durationMinutes: Int?,
    val score: String?,
    val summary: String?,
    val tags: List<String>,
    val performers: List<PeachEntityRef>,
    val hasCover: Boolean,
    val previewCount: Int,
    val previewVideoUrl: String?,
    val commentsCount: Int,
    val wanted: Boolean,
    val wantId: Long?,
    val sourceUrl: String?,
    val message: String?
) {
    companion object {
        fun parse(json: JsonObject): PeachJavDetail? = PeachJavDetail(
            code = json.string("code") ?: return null,
            title = json.string("title"),
            releaseDate = json.string("release_date"),
            studio = json.string("studio"),
            director = json.string("director"),
            durationMinutes = json.int("duration"),
            score = json["score"].displayText(),
            summary = json.string("summary"),
            tags = json.strings("tags"),
            performers = json.objects("performer_refs").mapNotNull { ref ->
                PeachEntityRef("performer", ref.long("id"), ref.string("name") ?: return@mapNotNull null, ref.bool("has_image") == true)
            },
            hasCover = json.bool("has_cover") == true,
            previewCount = json.int("preview_count") ?: 0,
            previewVideoUrl = json.string("preview_video_url"),
            commentsCount = json.int("comments_count") ?: 0,
            wanted = json.bool("wanted") == true,
            wantId = json.long("want_id"),
            sourceUrl = json.string("source_url"),
            message = json.string("message")
        )
    }
}

data class PeachJavReview(val username: String?, val content: String, val score: String?, val createdAt: String?) {
    companion object {
        fun parseList(json: JsonObject): List<PeachJavReview> = json.objects("items").mapNotNull { row ->
            PeachJavReview(
                username = row.string("username"),
                content = row.string("content") ?: return@mapNotNull null,
                score = row["score"].displayText(),
                createdAt = row.string("created_at")
            )
        }
    }
}

/** 实体图集：按目录或番号分组的图片集合，加上散图。 */
data class PeachPhotoPage(
    val sets: List<PhotoSet>,
    val photos: List<Long>,
    val total: Int,
    val hasMore: Boolean
) {
    data class PhotoSet(val id: String, val kind: String, val title: String, val count: Int, val code: String?)

    companion object {
        fun parse(json: JsonObject) = PeachPhotoPage(
            sets = json.objects("sets").mapNotNull { row ->
                PhotoSet(
                    id = row["id"].displayText() ?: return@mapNotNull null,
                    kind = row.string("kind").orEmpty(),
                    title = row.string("title") ?: row.string("name") ?: row.string("code").orEmpty(),
                    count = row.int("n") ?: 0,
                    code = row.string("code")
                )
            },
            photos = json.objects("items").mapNotNull { it.long("id") },
            total = json.int("total") ?: 0,
            hasMore = json.bool("has_more") == true
        )
    }
}

/** `/api/tops` 的热门出镜者与厂牌。 */
data class PeachTops(val performers: List<PeachIndexEntry>, val studios: List<PeachIndexEntry>) {
    companion object {
        private fun rows(json: JsonObject, key: String, kind: String) = json.objects(key).mapNotNull { row ->
            PeachIndexEntry(
                id = row.long("id"),
                kind = kind,
                name = row.string("k") ?: return@mapNotNull null,
                count = row.int("n"),
                hasImage = row.bool("has_image") == true || row.bool("has_logo") == true
            )
        }

        fun parse(json: JsonObject) = PeachTops(rows(json, "performers", "performer"), rows(json, "studios", "studio"))
    }
}

/** 收藏的实体与保存的筛选（Peach 网页侧栏的「收藏」）。 */
data class PeachShortcuts(val favorites: List<Favorite>, val filters: List<SavedFilter>) {
    data class Favorite(val entityId: Long?, val kind: String, val name: String)
    data class SavedFilter(val id: String, val name: String, val href: String)

    companion object {
        fun parse(json: JsonObject) = PeachShortcuts(
            favorites = json.objects("favorites").mapNotNull { row ->
                Favorite(row.long("entity_id"), row.string("kind").orEmpty(), row.string("name") ?: return@mapNotNull null)
            },
            filters = json.objects("filters").mapNotNull { row ->
                SavedFilter(row.string("id") ?: return@mapNotNull null, row.string("name").orEmpty(), row.string("href").orEmpty())
            }
        )
    }
}

/** `/api/facets`：海报墙筛选面板的候选值与计数。 */
data class PeachFacets(
    val years: List<Facet>,
    val tags: List<Facet>,
    val regions: List<Facet>,
    val orientations: List<Facet>,
    val locations: List<Facet>,
    val total: Int?
) {
    data class Facet(val key: String, val label: String, val count: Int)

    companion object {
        private fun facets(json: JsonObject, key: String) = json.objects(key).mapNotNull { row ->
            val value = row["k"].displayText() ?: return@mapNotNull null
            Facet(value, row.string("label") ?: value, row.int("n") ?: 0)
        }

        fun parse(json: JsonObject) = PeachFacets(
            years = facets(json, "years"),
            tags = facets(json, "tags"),
            regions = facets(json, "regions"),
            orientations = facets(json, "orientations"),
            locations = facets(json, "locations"),
            total = json.obj("stats")?.int("total")
        )
    }
}
