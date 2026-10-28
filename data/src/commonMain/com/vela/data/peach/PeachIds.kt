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
}
