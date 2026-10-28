package com.vela.data.peach

import com.vela.data.model.BaseItemDto
import com.vela.data.model.BaseItemPerson
import com.vela.data.model.MediaSourceInfo
import com.vela.data.model.NameGuidPair
import com.vela.data.model.UserItemDataDto
import kotlin.math.roundToLong

/**
 * Peach 作品、媒体库与实体到 Vela `BaseItemDto` 的映射。
 *
 * - 标题取 `catalog_title`，没有则用文件名；番号进 `originalTitle` 之外的 `providerIds`；
 * - `duration`（秒）换算为 ticks，续播位置用 `leave_ratio × duration`；
 * - 已看 = `feedback == "seen"`，收藏 = 稍后看；
 * - 评分 0–100 折算为 0–10 的 `communityRating`。
 */
object PeachMapper {
    const val TICKS_PER_SECOND = 10_000_000L
    const val PROVIDER_CODE = "PeachCode"

    /** 离开比例高于此值视为看完，不再给续播位置。 */
    private const val RESUME_UPPER_RATIO = 0.95
    private const val RESUME_LOWER_RATIO = 0.01

    fun toBaseItem(asset: PeachAsset, serverId: String? = null): BaseItemDto {
        val durationTicks = asset.durationSeconds?.let { (it * TICKS_PER_SECOND).roundToLong() }
        val ratio = asset.leaveRatio?.takeIf { it in RESUME_LOWER_RATIO..RESUME_UPPER_RATIO }
        val resumeTicks = if (ratio != null && durationTicks != null) (durationTicks * ratio).roundToLong() else null
        val year = asset.releaseDate?.take(4)?.toIntOrNull()
        val people = asset.entityRefs
            .filter { it.kind == "performer" || it.kind == "creator" }
            .map { ref ->
                BaseItemPerson(
                    name = ref.name,
                    id = ref.id?.let { PeachIds.entity(ref.kind, it) },
                    role = if (ref.kind == "creator") "Creator" else null,
                    type = if (ref.kind == "creator") "Director" else "Actor",
                    primaryImageTag = if (ref.hasImage) ENTITY_IMAGE_TAG else null
                )
            }
            .ifEmpty {
                asset.performers.map { name -> BaseItemPerson(name = name, type = "Actor") }
            }
        val studios = asset.entityRefs.filter { it.kind == "studio" }
            .map { NameGuidPair(name = it.name, id = it.id?.let { id -> PeachIds.entity("studio", id) }) }
            .ifEmpty { listOfNotNull(asset.studio?.let { NameGuidPair(name = it) }) }
        val primaryTag = PeachImageUrls.primaryTag(asset)
        return BaseItemDto(
            name = asset.displayTitle,
            originalTitle = asset.originalTitle ?: asset.code,
            serverId = serverId,
            id = asset.id.toString(),
            dateCreated = asset.firstSeen?.let(::epochSecondsToIso),
            container = asset.container,
            sortName = asset.code ?: asset.displayTitle,
            premiereDate = asset.releaseDate?.takeIf { it.length >= 10 }?.let { "${it.take(10)}T00:00:00.0000000Z" },
            path = asset.name,
            overview = overview(asset),
            genres = asset.tags,
            tags = asset.tags,
            communityRating = asset.rating?.takeIf { it > 0 }?.let { it / 10f },
            runTimeTicks = durationTicks,
            productionYear = year,
            providerIds = buildMap {
                put("Peach", asset.id.toString())
                asset.code?.let { put(PROVIDER_CODE, it) }
            },
            isFolder = false,
            type = "Movie",
            mediaType = "Video",
            people = people,
            studios = studios,
            userData = UserItemDataDto(
                rating = asset.rating?.toDouble(),
                playedPercentage = asset.leaveRatio?.times(100),
                playbackPositionTicks = resumeTicks ?: 0L,
                playCount = asset.playCount ?: 0,
                isFavorite = asset.watchLater,
                likes = asset.liked,
                lastPlayedDate = asset.lastPlayed?.let(::epochSecondsToIso),
                played = asset.isSeen,
                key = asset.id.toString(),
                itemId = asset.id.toString()
            ),
            imageTags = mapOf("Primary" to primaryTag, "Thumb" to primaryTag),
            backdropImageTags = listOf(primaryTag),
            primaryImageAspectRatio = if (asset.coverKey != null) COVER_ASPECT_RATIO else null,
            width = asset.width,
            height = asset.height,
            partCount = asset.partGroup?.count?.takeIf { it > 1 },
            mediaSourceCount = asset.editionGroup?.count?.takeIf { it > 1 },
            mediaSources = listOf(
                MediaSourceInfo(
                    id = asset.id.toString(),
                    name = asset.editionLabel ?: asset.partLabel ?: asset.name,
                    container = asset.container,
                    size = asset.size,
                    runTimeTicks = durationTicks,
                    isRemote = asset.location == "online" || asset.isStrm
                )
            ),
            locationType = "FileSystem",
            canDownload = !asset.isStrm && asset.location != "online"
        )
    }

    /** 关注时间线的帖子：可在 Vela 的详情页与播放器里打开，播放走 `/follow-stream`。 */
    fun followToBaseItem(item: PeachFollowItem, media: Int? = null): BaseItemDto {
        val selectedMedia = media?.let { index -> item.media.firstOrNull { it.index == index } }
        val thumb = selectedMedia?.thumbUrl ?: item.thumbUrl
        val tag = thumb?.let(PeachImageUrls::pathTag)
        val durationTicks = item.durationSeconds?.let { (it * TICKS_PER_SECOND).roundToLong() }
        return BaseItemDto(
            name = selectedMedia?.name?.takeIf { media != null } ?: item.displayTitle,
            id = PeachIds.follow(item.id, media),
            overview = listOfNotNull(item.summary, item.url).joinToString("\n\n").takeIf { it.isNotBlank() },
            premiereDate = item.publishedAt,
            productionYear = item.publishedAt?.take(4)?.toIntOrNull(),
            runTimeTicks = durationTicks,
            genres = item.tags,
            tags = item.tags,
            type = "Movie",
            mediaType = "Video",
            isFolder = false,
            people = listOfNotNull(item.author?.let { BaseItemPerson(name = it, type = "Actor") }),
            studios = listOfNotNull((item.sourceLabel ?: item.providerLabel)?.let { NameGuidPair(name = it) }),
            userData = UserItemDataDto(
                played = item.status == "seen",
                isFavorite = item.isSaved,
                playbackPositionTicks = 0L,
                key = PeachIds.follow(item.id, media)
            ),
            imageTags = tag?.let { mapOf("Primary" to it, "Thumb" to it) },
            backdropImageTags = listOfNotNull(tag),
            width = selectedMedia?.width,
            height = selectedMedia?.height,
            canDownload = false,
            locationType = "Remote"
        )
    }

    fun libraryToView(library: PeachLibrary): BaseItemDto = BaseItemDto(
        name = library.name,
        id = PeachIds.library(library.id),
        type = "CollectionFolder",
        collectionType = "movies",
        isFolder = true,
        childCount = library.folders
    )

    /**
     * Peach 网页侧栏的固定列表与「Peach 浏览」入口，作为虚拟媒体库附在真实媒体库之后。
     * 名称沿用 Peach 自己的中文界面。
     */
    fun virtualViews(): List<BaseItemDto> = listOf(
        BaseItemDto(name = "稍后看", id = PeachIds.view("later"), type = "CollectionFolder", collectionType = "movies", isFolder = true),
        BaseItemDto(name = "没看过", id = PeachIds.view("unseen"), type = "CollectionFolder", collectionType = "movies", isFolder = true),
        BaseItemDto(name = "已标记", id = PeachIds.view("flagged"), type = "CollectionFolder", collectionType = "movies", isFolder = true),
        BaseItemDto(name = "JAV", id = PeachIds.view("jav"), type = "CollectionFolder", collectionType = "movies", isFolder = true),
        BaseItemDto(name = "Peach 浏览", id = PeachIds.hub("browse"), type = "CollectionFolder", isFolder = true)
    )

    fun entityToPerson(kind: String, id: Long, name: String, hasImage: Boolean, overview: String? = null): BaseItemDto =
        BaseItemDto(
            name = name,
            id = PeachIds.entity(kind, id),
            type = when (kind) {
                "studio", "agency" -> "Studio"
                "tag" -> "Genre"
                "series" -> "BoxSet"
                else -> "Person"
            },
            overview = overview,
            imageTags = if (hasImage) mapOf("Primary" to ENTITY_IMAGE_TAG) else null
        )

    private fun overview(asset: PeachAsset): String? {
        val lines = buildList {
            asset.code?.let { add("番号：$it") }
            asset.creator?.let { add("创作者：$it") }
            asset.studio?.let { add("厂牌：$it") }
            asset.regionLabel?.let { add("产地：$it") }
        }
        return lines.joinToString("\n").takeIf { it.isNotBlank() }
    }

    const val ENTITY_IMAGE_TAG = "e"
    private const val COVER_ASPECT_RATIO = 1.5

    /** Peach 的时间戳是 Unix 秒（带小数），转成 Jellyfin 风格的 ISO 8601 UTC。 */
    fun epochSecondsToIso(seconds: Double): String? {
        if (seconds <= 0) return null
        val totalSeconds = seconds.toLong()
        val days = totalSeconds.floorDiv(86_400L)
        val secondOfDay = totalSeconds.mod(86_400L)
        val (year, month, day) = civilFromDays(days)
        val hour = secondOfDay / 3600
        val minute = (secondOfDay % 3600) / 60
        val second = secondOfDay % 60
        return buildString {
            append(year.toString().padStart(4, '0')).append('-')
            append(month.toString().padStart(2, '0')).append('-')
            append(day.toString().padStart(2, '0')).append('T')
            append(hour.toString().padStart(2, '0')).append(':')
            append(minute.toString().padStart(2, '0')).append(':')
            append(second.toString().padStart(2, '0')).append(".0000000Z")
        }
    }

    /** Howard Hinnant 的 civil_from_days：自 1970-01-01 起的天数转公历年月日。 */
    private fun civilFromDays(daysSinceEpoch: Long): Triple<Long, Int, Int> {
        val z = daysSinceEpoch + 719_468
        val era = (if (z >= 0) z else z - 146_096) / 146_097
        val doe = z - era * 146_097
        val yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365
        val y = yoe + era * 400
        val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
        val mp = (5 * doy + 2) / 153
        val d = (doy - (153 * mp + 2) / 5 + 1).toInt()
        val m = (if (mp < 10) mp + 3 else mp - 9).toInt()
        return Triple(if (m <= 2) y + 1 else y, m, d)
    }
}

/**
 * Peach 的图片路由。Vela 的界面按 Jellyfin 的 `Items/{id}/Images/{type}` 取图，
 * Peach 服务器时由 MediaRepository 改为这里生成的地址；鉴权头由图片加载器统一附加。
 */
object PeachImageUrls {
    private const val COVER_TAG_PREFIX = "c."
    private const val PATH_TAG_PREFIX = "u."
    private const val POSTER_TAG = "p"

    /** 关注帖子等由服务端给出相对图片地址的条目，把地址放进图片标签里随条目传递。 */
    fun pathTag(path: String): String = "$PATH_TAG_PREFIX$path"

    fun primaryTag(asset: PeachAsset): String =
        asset.coverKey?.let { "$COVER_TAG_PREFIX$it" } ?: POSTER_TAG

    fun coverKeyFromTag(tag: String?): String? =
        tag?.takeIf { it.startsWith(COVER_TAG_PREFIX) }?.removePrefix(COVER_TAG_PREFIX)?.takeIf { it.isNotBlank() }

    /**
     * @param coverKey 已知的封面键；为空时用 [imageTag] 里携带的键，再没有就退到预览帧拼图。
     * @param large 详情页背景等大图取 1600 档，卡片取 800 档。
     */
    fun imageUrl(
        baseUrl: String,
        itemId: String,
        imageTag: String? = null,
        coverKey: String? = null,
        large: Boolean = false
    ): String? {
        val base = baseUrl.trimEnd('/')
        imageTag?.takeIf { it.startsWith(PATH_TAG_PREFIX) }?.removePrefix(PATH_TAG_PREFIX)?.let { path ->
            return if (path.startsWith("http://") || path.startsWith("https://")) path else "$base/${path.trimStart('/')}"
        }
        PeachIds.followRef(itemId)?.let { (id, media) ->
            return "$base/follow-cover?id=$id" + (media?.let { "&media=$it" } ?: "")
        }
        PeachIds.javPreviewCode(itemId)?.let { code ->
            return "$base/cover-image?code=${encodeQueryValue(code)}"
        }
        PeachIds.entity(itemId)?.let { (kind, id) ->
            return if (kind == "tag") null else "$base/entity-image?kind=$kind&id=$id&thumb=${if (large) 0 else 1}"
        }
        val assetId = PeachIds.assetId(itemId) ?: return null
        val key = coverKey ?: coverKeyFromTag(imageTag)
        return if (key != null) {
            "$base/cover?code=${encodeQueryValue(key)}&thumb=${if (large) 1 else 2}"
        } else {
            "$base/poster?id=$assetId&c=4"
        }
    }

    fun encodeQueryValue(value: String): String = buildString {
        value.encodeToByteArray().forEach { byte ->
            val c = byte.toInt().toChar()
            if (c.isLetterOrDigit() && byte >= 0 || c in "-_.~") {
                append(c)
            } else {
                append('%')
                append(((byte.toInt() shr 4) and 0xF).toString(16).uppercase())
                append((byte.toInt() and 0xF).toString(16).uppercase())
            }
        }
    }
}
