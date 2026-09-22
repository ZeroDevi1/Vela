package com.vela.data.model

import com.vela.data.network.VelaJson
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/**
 * Jellyfin / Emby 媒体片段。
 *
 * @param type 片段类型，例如 Intro、Outro、Recap、Preview
 * @param startTicks 开始时间，100 纳秒为单位
 * @param endTicks 结束时间，100 纳秒为单位
 */
@Serializable
data class MediaSegmentDto(
    @SerialName("Type")
    val type: String? = null,
    @SerialName("StartTicks")
    val startTicks: Long? = null,
    @SerialName("EndTicks")
    val endTicks: Long? = null
)

/**
 * 把媒体片段接口的 JSON 转成播放器使用的片头片尾窗口。
 *
 * 同时接受 Jellyfin 的数组，以及 Emby 可能返回的 `{ "Items": [...] }`。
 *
 * @param payload 接口正文
 * @return 识别出的片段；正文无法解析或没有可用时间时为空
 */
fun parseMediaSegments(payload: String): PlaybackSegments {
    val text = payload.trim()
    if (text.isEmpty()) return PlaybackSegments()
    val segments = runCatching {
        val element = VelaJson.parseToJsonElement(text)
        val array = when (element) {
            is JsonArray -> element
            is JsonObject -> element["Items"] as? JsonArray
            else -> null
        } ?: return PlaybackSegments()
        VelaJson.decodeFromJsonElement(
            kotlinx.serialization.builtins.ListSerializer(MediaSegmentDto.serializer()),
            array
        )
    }.getOrNull().orEmpty()

    fun window(type: String): PlaybackSegmentWindow? {
        val match = segments.firstOrNull { segment ->
            segment.type.equals(type, ignoreCase = true)
        } ?: return null
        val startTicks = match.startTicks?.takeIf { it >= 0L } ?: return null
        val startMs = startTicks / 10_000L
        val endMs = match.endTicks?.takeIf { it > startTicks }?.div(10_000L)
        return PlaybackSegmentWindow(
            startMs = startMs,
            endMs = endMs,
            source = PlaybackSegmentSource.SERVER_MARKER
        )
    }

    return PlaybackSegments(
        intro = window("Intro"),
        recap = window("Recap"),
        credits = window("Outro") ?: window("Credits"),
        preview = window("Preview")
    )
}
