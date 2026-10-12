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

    private fun videoAt(width: Int, height: Int, fps: Float?) = listOf(
        MediaStream(type = "Video", width = width, height = height, realFrameRate = fps)
    )

    @Test
    fun fourKHighFrameRateIsHighThroughput() {
        assertTrue(MpvRenderLoad.isHighThroughputSource(videoAt(3840, 2160, 60f)))
        assertTrue(MpvRenderLoad.isHighThroughputSource(videoAt(3840, 2160, 50f)))
        assertEquals(120, MpvRenderLoad.decoderOperatingRate(videoAt(3840, 2160, 60f)))
        assertEquals(120, MpvRenderLoad.decoderOperatingRate(videoAt(3840, 2160, 59.94f)))
    }

    @Test
    fun ordinarySourcesAreNotHighThroughput() {
        assertFalse(MpvRenderLoad.isHighThroughputSource(videoAt(3840, 2160, 24f)))
        assertFalse(MpvRenderLoad.isHighThroughputSource(videoAt(3840, 2160, 30f)))
        assertFalse(MpvRenderLoad.isHighThroughputSource(videoAt(1920, 1080, 60f)))
        // 帧率未知时不改变硬解方式。
        assertFalse(MpvRenderLoad.isHighThroughputSource(videoAt(3840, 2160, null)))
        assertFalse(MpvRenderLoad.isHighThroughputSource(null))
        assertEquals(null, MpvRenderLoad.decoderOperatingRate(videoAt(3840, 2160, 24f)))
    }

    @Test
    fun fastRenderOnlyWhenOutputExceedsSixtyFps() {
        assertTrue(MpvRenderLoad.needsFastRender(heavySource = false, videoFps = 60.0, speed = 2.0))
        assertTrue(MpvRenderLoad.needsFastRender(heavySource = false, videoFps = 60.0, speed = 1.25))
        assertTrue(MpvRenderLoad.needsFastRender(heavySource = false, videoFps = 24.0, speed = 3.0))
        // 1x 与上屏帧率不超过 60fps 的倍速保持用户画质。
        assertFalse(MpvRenderLoad.needsFastRender(heavySource = false, videoFps = 60.0, speed = 1.0))
        assertFalse(MpvRenderLoad.needsFastRender(heavySource = false, videoFps = 24.0, speed = 2.0))
        assertFalse(MpvRenderLoad.needsFastRender(heavySource = false, videoFps = 30.0, speed = 2.0))
        assertFalse(MpvRenderLoad.needsFastRender(heavySource = false, videoFps = 0.0, speed = 2.0))
        assertTrue(MpvRenderLoad.needsFastRender(heavySource = true, videoFps = 0.0, speed = 1.0))
    }
}
