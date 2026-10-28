package com.vela.data.peach

import java.util.concurrent.ConcurrentHashMap

/**
 * 作品 ID → 官方封面键。界面取图时多数只给条目 ID，不带图片标签；
 * 列表与详情映射时记下封面键，生成图片地址时据此选 `/cover` 而不是预览拼图。
 */
object PeachArtwork {
    private const val MAX_ENTRIES = 4_096
    private val coverKeys = ConcurrentHashMap<Long, String>()

    fun remember(asset: PeachAsset) {
        val key = asset.coverKey ?: return
        if (coverKeys.size >= MAX_ENTRIES) coverKeys.clear()
        coverKeys[asset.id] = key
    }

    fun coverKey(itemId: String): String? = PeachIds.assetId(itemId)?.let(coverKeys::get)

    fun clear() = coverKeys.clear()
}
