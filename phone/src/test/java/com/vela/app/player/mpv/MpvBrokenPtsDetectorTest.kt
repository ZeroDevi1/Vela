package com.vela.app.player.mpv

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MpvBrokenPtsDetectorTest {

    private val warning = "Invalid video timestamp: 2298.552529 -> 2298.452428"

    @Test
    fun repeatedWarningsWithinWindowTriggerOnce() {
        val detector = MpvBrokenPtsDetector()
        assertFalse(detector.onLog("cplayer", warning, 0))
        assertFalse(detector.onLog("cplayer", warning, 230))
        assertFalse(detector.onLog("cplayer", warning, 460))
        assertTrue(detector.onLog("cplayer", warning, 690))
        assertTrue(detector.triggered)
        assertFalse(detector.onLog("cplayer", warning, 920))
    }

    @Test
    fun sparseWarningsAtDiscontinuitiesDoNotTrigger() {
        val detector = MpvBrokenPtsDetector()
        for (i in 0 until 10) {
            assertFalse(detector.onLog("cplayer", warning, i * 5_000L))
        }
        assertFalse(detector.triggered)
    }

    @Test
    fun unrelatedMessagesAreIgnored() {
        val detector = MpvBrokenPtsDetector()
        for (i in 0 until 10) {
            assertFalse(detector.onLog("vd", warning, i * 10L))
            assertFalse(detector.onLog("cplayer", "Audio/Video desynchronisation detected!", i * 10L))
        }
        assertFalse(detector.triggered)
    }

    @Test
    fun resetAllowsNextSourceToTrigger() {
        val detector = MpvBrokenPtsDetector()
        repeat(4) { detector.onLog("cplayer", warning, it * 100L) }
        assertTrue(detector.triggered)
        detector.reset()
        assertFalse(detector.triggered)
        repeat(3) { assertFalse(detector.onLog("cplayer", warning, 10_000L + it * 100L)) }
        assertTrue(detector.onLog("cplayer", warning, 10_300L))
    }
}
