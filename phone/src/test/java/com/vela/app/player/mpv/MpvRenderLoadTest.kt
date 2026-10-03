package com.vela.app.player.mpv

import com.vela.data.model.MediaStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MpvRenderLoadTest {

    private fun video(width: Int, height: Int, range: String = "SDR") = listOf(
        MediaStream(type = "Video", width = width, height = height, videoRange = range, videoRangeType = range)
    )

    @Test
    fun eightKVrIsHeavyWithEightBitFbo() {
        assertTrue(MpvRenderLoad.isHeavySource(video(8192, 4096)))
        assertTrue(MpvRenderLoad.isHeavySource(video(5760, 2880)))
        assertEquals(MpvRenderLoad.HEAVY_FBO_FORMAT, MpvRenderLoad.fboFormat(video(8192, 4096)))
    }

    @Test
    fun fourKAndUnknownStayOnUserQuality() {
        assertFalse(MpvRenderLoad.isHeavySource(video(3840, 2160)))
        assertFalse(MpvRenderLoad.isHeavySource(video(4096, 2160)))
        assertFalse(MpvRenderLoad.isHeavySource(null))
        assertEquals(MpvRenderLoad.DEFAULT_FBO_FORMAT, MpvRenderLoad.fboFormat(video(3840, 2160)))
    }

    @Test
    fun heavyHdrKeepsHighPrecisionFbo() {
        assertEquals(MpvRenderLoad.DEFAULT_FBO_FORMAT, MpvRenderLoad.fboFormat(video(7680, 3840, "HDR10")))
    }
}
