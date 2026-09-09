package com.vela.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** 列表查询保持库、分类、搜索和分页在同一个请求中，避免跨库结果混入。 */
data class MediaLibraryQuery(
    val parentId: String? = null,
    val includeItemTypes: String? = null,
    val recursive: Boolean = true,
    val searchTerm: String? = null,
    val startIndex: Int = 0,
    val limit: Int = 60,
    val sortBy: String = "SortName",
    val sortOrder: String = "Ascending",
    val filters: String? = null,
    val personIds: String? = null,
    val genreIds: String? = null
)

@Serializable
data class LyricsDto(@SerialName("Lyrics") val lyrics: List<LyricLine> = emptyList())

@Serializable
data class LyricLine(
    @SerialName("Text") val text: String = "",
    /** Jellyfin 的 Start 为 100ns ticks，不是毫秒。 */
    @SerialName("Start") val start: Long? = null
) {
    val startMs: Long? get() = start?.div(10_000L)
}

fun BaseItemDto.isAudioItem(): Boolean = type.equals("Audio", true) || type.equals("AudioBook", true)
fun BaseItemDto.isBookItem(): Boolean = type.equals("Book", true)
fun BaseItemDto.bookFormat(): String = (container?.takeIf { it.isNotBlank() }
    ?: path?.substringAfterLast('.', "").orEmpty()).lowercase().trimStart('.')
