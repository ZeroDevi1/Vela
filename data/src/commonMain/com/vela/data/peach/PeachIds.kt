package com.vela.data.peach

/**
 * Vela 的条目 ID 是字符串；Peach 的作品 ID 是整数，媒体库与实体各有自己的 ID 空间。
 * 作品直接用十进制数字，其余加前缀区分，`MediaServerApi` 的实现据此分派。
 */
object PeachIds {
    /** Peach 是单用户服务，Vela 侧的用户 ID 固定为 Peach 的默认档案 ID。 */
    const val USER_ID = "local-default"

    private const val LIBRARY_PREFIX = "lib:"
    private const val PLAYLIST_PREFIX = "playlist:"
    val ENTITY_KINDS = setOf("performer", "creator", "studio", "series", "agency", "tag")

    fun library(id: String): String = "$LIBRARY_PREFIX$id"

    fun libraryId(itemId: String?): String? =
        itemId?.takeIf { it.startsWith(LIBRARY_PREFIX) }?.removePrefix(LIBRARY_PREFIX)

    fun playlist(id: String): String = "$PLAYLIST_PREFIX$id"

    fun playlistId(itemId: String?): String? =
        itemId?.takeIf { it.startsWith(PLAYLIST_PREFIX) }?.removePrefix(PLAYLIST_PREFIX)

    fun entity(kind: String, id: Long): String = "$kind:$id"

    /** 解析 `performer:12` 形式的实体 ID；不是实体返回 null。 */
    fun entity(itemId: String?): Pair<String, Long>? {
        val value = itemId ?: return null
        val kind = value.substringBefore(':', missingDelimiterValue = "")
        if (kind !in ENTITY_KINDS) return null
        val id = value.substringAfter(':').toLongOrNull() ?: return null
        return kind to id
    }

    fun assetId(itemId: String?): Long? = itemId?.trim()?.toLongOrNull()

    // region 虚拟视图与非馆藏条目

    private const val VIEW_PREFIX = "view:"
    private const val HUB_PREFIX = "peach-hub:"
    private const val FOLLOW_PREFIX = "follow:"
    private const val JAV_PREVIEW_PREFIX = "javpreview:"

    /** Peach 网页侧栏里的固定列表，作为 Vela 的虚拟媒体库出现。值是 `/api/items` 的查询参数。 */
    val VIEWS: Map<String, Pair<String, String>> = linkedMapOf(
        "later" to ("state" to "later"),
        "unseen" to ("state" to "fresh"),
        "flagged" to ("state" to "flagged"),
        "jav" to ("jav" to "1")
    )

    fun view(key: String): String = "$VIEW_PREFIX$key"

    fun viewKey(itemId: String?): String? =
        itemId?.takeIf { it.startsWith(VIEW_PREFIX) }?.removePrefix(VIEW_PREFIX)?.takeIf { it in VIEWS }

    /** 「Peach 浏览」入口：点开后由界面跳到原生的 Peach 浏览页，而不是海报墙。 */
    fun hub(page: String): String = "$HUB_PREFIX$page"

    fun hubPage(itemId: String?): String? =
        itemId?.takeIf { it.startsWith(HUB_PREFIX) }?.removePrefix(HUB_PREFIX)

    /** 关注时间线的一条帖子；[media] 为帖子里的第几个媒体，null 表示主媒体。 */
    fun follow(itemId: Long, media: Int? = null): String =
        if (media == null) "$FOLLOW_PREFIX$itemId" else "$FOLLOW_PREFIX$itemId:$media"

    fun followRef(itemId: String?): Pair<Long, Int?>? {
        val value = itemId?.takeIf { it.startsWith(FOLLOW_PREFIX) }?.removePrefix(FOLLOW_PREFIX) ?: return null
        val id = value.substringBefore(':').toLongOrNull() ?: return null
        val media = value.substringAfter(':', "").takeIf { it.isNotEmpty() }?.toIntOrNull()
        return id to media
    }

    /** 库外作品（JAV 探索）的官方预告片。 */
    fun javPreview(code: String): String = "$JAV_PREVIEW_PREFIX$code"

    fun javPreviewCode(itemId: String?): String? =
        itemId?.takeIf { it.startsWith(JAV_PREVIEW_PREFIX) }?.removePrefix(JAV_PREVIEW_PREFIX)?.takeIf { it.isNotBlank() }

    // endregion
}
