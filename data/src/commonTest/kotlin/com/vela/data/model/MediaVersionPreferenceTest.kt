package com.vela.data.model

import kotlin.test.Test
import kotlin.test.assertEquals

class MediaVersionPreferenceTest {
    private fun source(id: String, width: Int, height: Int, bitrate: Int, range: String = "SDR") = MediaSource(
        id = id,
        bitrate = bitrate,
        mediaStreams = listOf(MediaStream(type = "Video", width = width, height = height, videoRangeType = range))
    )

    private val sd = source("720", 1280, 720, 3_000_000)
    private val hd = source("1080", 1920, 800, 8_000_000)
    private val uhd = source("4k", 3840, 2160, 40_000_000)

    @Test fun picksByQualityAndBitrate() {
        assertEquals("1080", listOf(hd, uhd).preferredVersion(MediaVersionPreference.DEFAULT)?.id)
        assertEquals("4k", listOf(hd, uhd).preferredVersion(MediaVersionPreference.BEST_RESOLUTION)?.id)
        assertEquals("4k", listOf(uhd, hd).preferredVersion(MediaVersionPreference.BEST_BITRATE)?.id)
    }

    @Test fun picksByResolutionTier() {
        assertEquals("1080", listOf(uhd, hd, sd).preferredVersion(MediaVersionPreference.FULL_HD_1080)?.id)
        assertEquals("720", listOf(uhd, hd, sd).preferredVersion(MediaVersionPreference.HD_720)?.id)
        assertEquals("1080", listOf(sd, hd).preferredVersion(MediaVersionPreference.UHD_4K)?.id)
        // 没有不超过目标的版本时取最小的一个。
        assertEquals("1080", listOf(uhd, hd).preferredVersion(MediaVersionPreference.HD_720)?.id)
    }

    @Test fun picksBestDynamicRange() {
        val dv = source("dv", 1920, 1080, 6_000_000, range = "DOVIWithHDR10")
        assertEquals("dv", listOf(uhd, dv).preferredVersion(MediaVersionPreference.BEST_DYNAMIC_RANGE)?.id)
        assertEquals(MediaVersionPreference.DEFAULT, MediaVersionPreference.fromId("unknown"))
    }
}
