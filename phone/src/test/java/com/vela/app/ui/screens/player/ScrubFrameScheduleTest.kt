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
        val cached = setOf(0L, 10L, 40L)
        assertEquals(10L, nearestScrubFrameBucket(cached, 12_400L))
        assertNull(nearestScrubFrameBucket(cached, 25_000L))
        assertNull(nearestScrubFrameBucket(emptySet(), 1_000L))
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
}
