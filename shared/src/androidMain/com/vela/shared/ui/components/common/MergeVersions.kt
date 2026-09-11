package com.vela.shared.ui.components.common

import com.vela.data.model.BaseItemDto
import com.vela.data.model.MediaSourceInfo
import com.vela.data.model.MediaStream
import java.util.Locale

fun BaseItemDto.activeDetailMediaSources(): List<MediaSourceInfo> {
    return mediaSources.orEmpty()
}

fun buildLocalVersionEntries(
    localVersions: List<BaseItemDto>,
    currentItemId: String?,
    videoFallbackLabel: String,
    smallFileSizeLabel: String
): List<Pair<String, BaseItemDto>> {
    val currentVersions = localVersions
        .takeIf { versions -> versions.any { version -> version.id == currentItemId } }
        .orEmpty()

    if (currentVersions.size <= 1) return emptyList()

    val orderedVersions = currentVersions.sortedByDescending { version -> version.id == currentItemId }
    return detailOptionLabels(
        orderedVersions.map { version ->
            version.localVersionVideoLabel(
                videoFallbackLabel = videoFallbackLabel,
                smallFileSizeLabel = smallFileSizeLabel
            )
        }
    )
        .zip(orderedVersions)
}

fun buildMediaSourceVersionEntries(
    sources: List<MediaSourceInfo>,
    unnamedLabel: String,
    smallFileSizeLabel: String
): List<Pair<String, MediaSourceInfo>> {
    if (sources.size <= 1) return emptyList()
    val rawLabels = sources.map { source ->
        val name = source.name?.takeIf { it.isNotBlank() } ?: unnamedLabel
        val meta = listOfNotNull(
            source.container?.uppercase(Locale.US),
            formatBitrate(source.bitrate?.toLong()),
            formatFileSize(source.size, smallFileSizeLabel)
        ).joinToString(" · ")
        if (meta.isBlank()) name else "$name · $meta"
    }
    return detailOptionLabels(rawLabels).zip(sources)
}

fun selectedVideoOption(
    localVersionEntries: List<Pair<String, BaseItemDto>>,
    currentItemId: String?,
    selectedVideo: String,
    videoOptions: List<String>,
    baseVideoOptions: List<String>
): String {
    return localVersionEntries
        .firstOrNull { (_, version) -> version.id == currentItemId }
        ?.first
        ?.takeIf { selectedVideo !in videoOptions || selectedVideo in baseVideoOptions }
        ?: selectedVideo.takeIf { it in videoOptions }
        ?: videoOptions.firstOrNull().orEmpty()
}

/**
 * 生成一个媒体文件的大小和总码率说明，不混用其它源的轨道。
 * @param mediaSources 当前可用媒体源；使用第一个源与详情默认选择保持一致。
 * @param streams 无媒体源时使用的条目轨道；存在媒体源时仅使用源内轨道。
 * @param smallFileSizeLabel 小于 1 MB 时的本地化说明。
 * @return 已知参数用斜线连接；参数全缺失时返回 null，不推算其它文件的信息。
 */
fun buildInlineText(
    mediaSources: List<MediaSourceInfo>,
    streams: List<MediaStream>,
    smallFileSizeLabel: String
): String? {
    // 大小和码率必须来自同一媒体源；空元数据不应导致跳到另一个文件。
    val source = mediaSources.firstOrNull()
    val parts = mutableListOf<String>()
    formatFileSize(
        sizeBytes = source?.size,
        smallFileSizeLabel = smallFileSizeLabel
    )?.let(parts::add)

    val fileBitrate = source?.bitrate?.toLong()?.takeIf { it > 0 }
        ?: (if (source != null) source.mediaStreams.orEmpty() else streams)
            .sumOf { (it.bitRate ?: 0).toLong() }
            .takeIf { it > 0L }
    formatBitrate(fileBitrate)?.let(parts::add)

    return parts.takeIf { it.isNotEmpty() }?.joinToString(" / ")
}

/**
 * 为接收条目的默认媒体源生成版本标签，所有技术信息均来自同一个文件。
 * @param videoFallbackLabel 未提供视频轨道标题时的本地化说明。
 * @param smallFileSizeLabel 小于 1 MB 时的本地化说明。
 * @return 视频标题与已知大小、码率组成的文本；缺失字段不借用其它媒体源。
 */
private fun BaseItemDto.localVersionVideoLabel(
    videoFallbackLabel: String,
    smallFileSizeLabel: String
): String {
    val activeSources = activeDetailMediaSources()
    // 默认源与播放选择一致；只有没有媒体源时才采用条目级轨道。
    val source = activeSources.firstOrNull()
    val streams = if (source != null) source.mediaStreams.orEmpty() else mediaStreams.orEmpty()
    val videoTitle = videoOptionLabels(streams).firstOrNull() ?: videoFallbackLabel
    val inlineText = buildInlineText(
        mediaSources = activeSources,
        streams = streams,
        smallFileSizeLabel = smallFileSizeLabel
    )
    return listOfNotNull(videoTitle, inlineText)
        .filter { it.isNotBlank() }
        .joinToString(" / ")
}

private fun videoOptionLabels(streams: List<MediaStream>): List<String> {
    return detailOptionLabels(
        streams
            .filter { it.type == "Video" }
            .sortedBy { it.index ?: Int.MAX_VALUE }
            .mapNotNull { stream -> stream.displayTitle?.takeIf { it.isNotBlank() } }
    )
}

private fun detailOptionLabels(options: List<String>): List<String> {
    val counts = mutableMapOf<String, Int>()
    return options.map { option ->
        val seen = (counts[option] ?: 0) + 1
        counts[option] = seen
        if (seen == 1) option else "$option ($seen)"
    }
}

private fun formatFileSize(
    sizeBytes: Long?,
    smallFileSizeLabel: String
): String? {
    if (sizeBytes == null || sizeBytes <= 0) return null

    val gb = sizeBytes / (1024.0 * 1024.0 * 1024.0)
    val mb = sizeBytes / (1024.0 * 1024.0)

    return when {
        gb >= 1.0 -> String.format(Locale.US, "%.1f GB", gb)
        mb >= 1.0 -> String.format(Locale.US, "%.0f MB", mb)
        else -> smallFileSizeLabel
    }
}

private fun formatBitrate(bitsPerSecond: Long?): String? {
    val value = bitsPerSecond?.takeIf { it > 0L } ?: return null
    return if (value >= 1_000_000L) {
        "${String.format(Locale.US, "%.1f", value / 1_000_000.0)} Mbps"
    } else {
        "${value / 1000L} kbps"
    }
}
