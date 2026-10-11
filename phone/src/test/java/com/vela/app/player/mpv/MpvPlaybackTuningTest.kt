package com.vela.app.player.mpv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MpvPlaybackTuningTest {

    @Test
    fun normalSpeedKeepsSmoothMotionPath() {
        assertFalse(MpvPlaybackTuning.isHighSpeed(1.0))
        assertEquals("yes", MpvPlaybackTuning.interpolation(smoothMotion = true, speed = 1.0))
        assertEquals("display-resample", MpvPlaybackTuning.videoSync(smoothMotion = true, speed = 1.0))
        assertFalse(MpvPlaybackTuning.skipLoopFilter(1.0))
    }

    @Test
    fun highSpeedDisablesInterpolation() {
        assertTrue(MpvPlaybackTuning.isHighSpeed(1.5))
        assertEquals("no", MpvPlaybackTuning.interpolation(smoothMotion = true, speed = 1.5))
        assertEquals("audio", MpvPlaybackTuning.videoSync(smoothMotion = true, speed = 1.5))
        assertFalse(MpvPlaybackTuning.skipLoopFilter(1.5))
    }

    @Test
    fun doubleSpeedOnlySkipsLoopFilter() {
        assertTrue(MpvPlaybackTuning.skipLoopFilter(2.0))
        assertEquals("vo", MpvPlaybackTuning.FRAMEDROP)
        assertEquals("audio", MpvPlaybackTuning.videoSync(smoothMotion = false, speed = 2.0))
        assertEquals("no", MpvPlaybackTuning.interpolation(smoothMotion = false, speed = 2.0))
    }

    @Test
    fun surfaceFrameRateFollowsPlaybackSpeed() {
        assertEquals(24f, MpvPlaybackTuning.surfaceFrameRate(24.0, 1.0)!!, 0.001f)
        assertEquals(120f, MpvPlaybackTuning.surfaceFrameRate(60.0, 2.0)!!, 0.001f)
        assertEquals(48f, MpvPlaybackTuning.surfaceFrameRate(24.0, 2.0)!!, 0.001f)
        // 慢放不降刷新率，仍按片源帧率声明。
        assertEquals(30f, MpvPlaybackTuning.surfaceFrameRate(30.0, 0.5)!!, 0.001f)
        assertEquals(240f, MpvPlaybackTuning.surfaceFrameRate(120.0, 3.0)!!, 0.001f)
        assertNull(MpvPlaybackTuning.surfaceFrameRate(0.0, 2.0))
    }
}
