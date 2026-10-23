package com.vela.app.ui.screens.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScrubFrameScheduleTest {

    @Test
    fun bucketsPositionsInsideTheSameSecond() {
        assertEquals(20L, scrubFrameBucket(20_000L))
        assertEquals(20L, scrubFrameBucket(20_900L))
        assertEquals(0L, scrubFrameBucket(-5L))
    }

    @Test
    fun nearestCacheStaysInsideEightSeconds() {
        val cached = setOf(0L, 10_000L, 40_000L)
        assertEquals(10_000L, nearestScrubFrameKey(cached, 12_400L))
        assertNull(nearestScrubFrameKey(cached, 25_000L))
        assertNull(nearestScrubFrameKey(emptySet(), 1_000L))
    }

    /** 同一关键帧被不同秒格请求时只存一份；失败表示缓存按请求位置重复存图。 */
    @Test
    fun cacheStoresEachKeyframeOnce() {
        val evicted = mutableListOf<String>()
        val cache = ScrubFrameCache<String>(capacity = 8) { evicted += it }
        assertEquals("a", cache.put(positionMs = 10_200L, keyframeMs = 10_000L, frame = "a", grid = false))
        // 第 12 秒落在同一张关键帧上：新解出的副本被丢弃，返回已缓存的那张。
        assertEquals("a", cache.put(positionMs = 12_500L, keyframeMs = 10_000L, frame = "a2", grid = false))
        assertEquals(listOf("a2"), evicted)
        assertEquals(1, cache.size)
        assertEquals("a", cache.exact(12_900L))
        assertNull(cache.exact(14_000L))
        // 抽帧器跳过解码（没有新帧）时只记别名。
        assertEquals("a", cache.put(positionMs = 13_100L, keyframeMs = 10_000L, frame = null, grid = false))
        assertEquals("a", cache.exact(13_000L))
        assertTrue(cache.containsKeyframe(10_000L))
        assertNull(cache.put(positionMs = 30_000L, keyframeMs = 30_000L, frame = null, grid = false))
    }

    /** 现场抽帧占满容量时先淘汰非网格帧；失败表示来回拖动会把预生成网格挤出缓存。 */
    @Test
    fun cacheKeepsGridFramesWhenFull() {
        val evicted = mutableListOf<String>()
        val cache = ScrubFrameCache<String>(capacity = 3) { evicted += it }
        cache.put(0L, 0L, "grid0", grid = true)
        cache.put(60_000L, 60_000L, "grid1", grid = true)
        cache.put(5_000L, 5_000L, "user0", grid = false)
        cache.put(7_000L, 7_000L, "user1", grid = false)
        assertEquals(listOf("user0"), evicted)
        assertNull(cache.exact(5_000L))
        assertEquals("grid0", cache.nearest(1_000L, SCRUB_FRAME_NEAR_MS))
        assertEquals("grid1", cache.exact(60_500L))
        cache.clear()
        assertEquals(0, cache.size)
        assertEquals(4, evicted.size)
    }

    @Test
    fun previewSizeKeepsAspectAndHonoursRotation() {
        assertEquals(320 to 180, scrubPreviewSize(3840, 2160, 0, 320))
        assertEquals(180 to 320, scrubPreviewSize(3840, 2160, 90, 320))
        assertEquals(320 to 134, scrubPreviewSize(1920, 804, 180, 320))
        assertNull(scrubPreviewSize(0, 1080, 0, 320))
    }

    @Test
    fun sameKeyframeSkipsAnotherDecode() {
        assertTrue(shouldReuseScrubSync(lastSyncUs = 2_000_000L, syncUs = 2_000_000L))
        assertFalse(shouldReuseScrubSync(lastSyncUs = 2_000_000L, syncUs = 5_000_000L))
        assertFalse(shouldReuseScrubSync(lastSyncUs = -1L, syncUs = -1L))
    }

    @Test
    fun sweepGoesCoarseToFineWithoutDuplicateBuckets() {
        val positions = scrubSweepPositions(durationMs = 96 * 60_000L)
        // 第一档 12 张均匀覆盖全片，后续档只补新位置。
        assertEquals(12, positions.take(12).map { it / (8 * 60_000L) }.toSet().size)
        assertEquals(96, positions.size)
        assertEquals(positions.size, positions.map(::scrubFrameBucket).toSet().size)
        assertTrue(scrubSweepPositions(0L).isEmpty())
    }

    @Test
    fun shortClipsCollapseDuplicateSeconds() {
        val positions = scrubSweepPositions(durationMs = 20_000L)
        assertEquals(positions.size, positions.map(::scrubFrameBucket).toSet().size)
        assertTrue(positions.size <= 20)
    }

    @Test
    fun nearDistanceGrowsWithGridGapForLongFilms() {
        assertEquals(SCRUB_FRAME_NEAR_MS, scrubNearDistanceMs(0L))
        assertEquals(SCRUB_FRAME_NEAR_MS, scrubNearDistanceMs(10 * 60_000L))
        assertEquals(75_000L, scrubNearDistanceMs(120 * 60_000L))
        val cached = setOf(0L, 75_000L)
        assertEquals(75_000L, nearestScrubFrameKey(cached, 120_000L, scrubNearDistanceMs(120 * 60_000L)))
    }

    @Test
    fun sweepYieldsWhileMainBufferIsShort() {
        assertTrue(scrubSweepShouldYieldToBuffer(bufferAheadMs = 4_000L, remainingMs = 600_000L))
        assertFalse(scrubSweepShouldYieldToBuffer(bufferAheadMs = 25_000L, remainingMs = 600_000L))
        // 接近片尾：剩余 10 秒时缓冲到 8 秒即可，不会因为够不到阈值而永远让位。
        assertFalse(scrubSweepShouldYieldToBuffer(bufferAheadMs = 8_000L, remainingMs = 10_000L))
        assertTrue(scrubSweepShouldYieldToBuffer(bufferAheadMs = 3_000L, remainingMs = 10_000L))
        assertFalse(scrubSweepShouldYieldToBuffer(bufferAheadMs = 0L, remainingMs = 0L))
    }
}
