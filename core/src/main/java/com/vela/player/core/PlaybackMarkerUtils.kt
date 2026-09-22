package com.vela.player.core

import com.vela.data.model.ChapterInfo
import com.vela.data.model.PlaybackSegmentSource
import com.vela.data.model.PlaybackSegments
import com.vela.data.model.PlaybackSegmentWindow

object PlaybackMarkerUtils {
    fun buildChapterMarkers(chapters: List<ChapterInfo>?): List<ChapterMarker> {
        return chapters
            ?.mapNotNull { chapter ->
                val positionMs = chapter.startPositionTicks
                    ?.takeIf { it >= 0L }
                    ?.div(10_000L)
                    ?: return@mapNotNull null
                ChapterMarker(
                    positionMs = positionMs,
                    label = chapter.name?.trim()?.takeIf { it.isNotEmpty() }
                )
            }
            ?.distinctBy { it.positionMs }
            ?.sortedBy { it.positionMs }
            .orEmpty()
    }

    /**
     * 从章节标记还原片头、片尾、回顾和预告。
     *
     * 同时识别 Jellyfin / Emby 的 MarkerType，以及常见章节名。
     * 片尾没有结束标记时，结束时间留空，表示一直持续到影片结束。
     *
     * @param chapters 服务器返回的章节
     * @return 能识别出的片段；没有可用标记时各字段为空
     */
    fun extractMarkerSegments(chapters: List<ChapterInfo>?): PlaybackSegments {
        val chapterList = timedChapters(chapters)
        if (chapterList.isEmpty()) return PlaybackSegments()

        var introStartMs: Long? = null
        var introEndMs: Long? = null
        var creditsStartMs: Long? = null
        var recapStartMs: Long? = null
        var recapEndMs: Long? = null
        var previewStartMs: Long? = null
        var previewEndMs: Long? = null

        chapterList.forEachIndexed { index, timed ->
            val nextStartMs = chapterList.getOrNull(index + 1)?.startMs
            when {
                timed.chapter.isIntroEndMarker() -> introEndMs = timed.startMs
                timed.chapter.isIntroStartMarker() && introStartMs == null -> introStartMs = timed.startMs
                timed.chapter.isCreditsMarker() && creditsStartMs == null -> creditsStartMs = timed.startMs
                timed.chapter.isRecapMarker() && recapStartMs == null -> {
                    recapStartMs = timed.startMs
                    recapEndMs = nextStartMs
                }
                timed.chapter.isPreviewMarker() && previewStartMs == null -> {
                    previewStartMs = timed.startMs
                    previewEndMs = nextStartMs
                }
            }
        }

        val resolvedIntroStartMs = introStartMs
        if (resolvedIntroStartMs != null && introEndMs == null) {
            introEndMs = chapterList.firstOrNull { it.startMs > resolvedIntroStartMs }?.startMs
        }

        return PlaybackSegments(
            intro = window(introStartMs, introEndMs),
            recap = window(recapStartMs, recapEndMs),
            credits = creditsStartMs?.let { start ->
                PlaybackSegmentWindow(
                    startMs = start,
                    endMs = null,
                    source = PlaybackSegmentSource.SERVER_MARKER
                )
            },
            preview = window(previewStartMs, previewEndMs)
        )
    }

    fun extractIntroWindow(chapters: List<ChapterInfo>?): PlaybackSegmentWindow? {
        return extractMarkerSegments(chapters).intro
    }

    private data class TimedChapter(
        val chapter: ChapterInfo,
        val startMs: Long
    )

    private fun timedChapters(chapters: List<ChapterInfo>?): List<TimedChapter> {
        return chapters
            ?.mapNotNull { chapter ->
                val positionMs = chapter.startPositionTicks
                    ?.takeIf { it >= 0L }
                    ?.div(10_000L)
                    ?: return@mapNotNull null
                TimedChapter(chapter, positionMs)
            }
            ?.sortedBy { it.startMs }
            .orEmpty()
    }

    private fun window(startMs: Long?, endMs: Long?): PlaybackSegmentWindow? {
        if (startMs == null || endMs == null || endMs <= startMs) return null
        return PlaybackSegmentWindow(
            startMs = startMs,
            endMs = endMs,
            source = PlaybackSegmentSource.SERVER_MARKER
        )
    }

    private fun ChapterInfo.isIntroStartMarker(): Boolean {
        return markerType.isMarker("introstart") || name.isNamed("intro", "introstart", "opening", "op", "片头")
    }

    private fun ChapterInfo.isIntroEndMarker(): Boolean {
        return markerType.isMarker("introend") || name.isNamed("introend")
    }

    private fun ChapterInfo.isCreditsMarker(): Boolean {
        return markerType.isMarker("creditsstart") ||
            name.isNamed("credits", "creditsstart", "creditstart", "ending", "outro", "ed", "片尾")
    }

    private fun ChapterInfo.isRecapMarker(): Boolean {
        return name.isNamed("recap", "previously", "回顾", "前情")
    }

    private fun ChapterInfo.isPreviewMarker(): Boolean {
        return name.isNamed("preview", "nexttime", "预告")
    }

    private fun String?.isMarker(expected: String): Boolean {
        return markerKey() == expected
    }

    private fun String?.isNamed(vararg keys: String): Boolean {
        val key = markerKey()
        return key.isNotEmpty() && keys.any { it == key }
    }

    private fun String?.markerKey(): String {
        return this
            ?.lowercase()
            ?.filterNot { it.isWhitespace() || it == '_' || it == '-' }
            .orEmpty()
    }
}

/**
 * 用一段新的片头片尾数据更新播放状态。
 *
 * @param segments 要合并的片段
 * @param overrideExisting 为 true 时新数据覆盖已有片段；为 false 时只填补空位
 * @return 合并后的播放状态
 */
fun PlayerState.applyingPlaybackSegments(
    segments: PlaybackSegments,
    overrideExisting: Boolean
): PlayerState {
    fun pick(current: Long?, incoming: Long?): Long? {
        return if (overrideExisting) incoming ?: current else current ?: incoming
    }
    return copy(
        recapStartMs = pick(recapStartMs, segments.recap?.startMs),
        recapEndMs = pick(recapEndMs, segments.recap?.endMs),
        introStartMs = pick(introStartMs, segments.intro?.startMs),
        introEndMs = pick(introEndMs, segments.intro?.endMs),
        creditsStartMs = pick(creditsStartMs, segments.credits?.startMs),
        creditsEndMs = pick(creditsEndMs, segments.credits?.endMs),
        previewStartMs = pick(previewStartMs, segments.preview?.startMs),
        previewEndMs = pick(previewEndMs, segments.preview?.endMs)
    )
}
