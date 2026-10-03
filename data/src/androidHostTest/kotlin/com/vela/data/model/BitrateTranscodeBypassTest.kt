package com.vela.data.model

import com.vela.data.repository.PlaybackDeviceProfileFactory
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BitrateTranscodeBypassTest {

    private fun source(url: String?) = MediaSource(transcodingUrl = url)

    @Test
    fun detectsBitrateReason() {
        assertTrue(
            source("/videos/a/master.m3u8?TranscodeReasons=ContainerBitrateExceedsLimit&VideoBitrate=7810631")
                .hasBitrateTranscodeReason()
        )
    }

    @Test
    fun detectsBitrateReasonAlongsideTranscodePathCodecReason() {
        // 8K HEVC VR：转码 profile 只有 h264，服务端额外记 VideoCodecNotSupported。
        assertTrue(
            source("/videos/a/master.m3u8?TranscodeReasons=VideoCodecNotSupported%2CContainerBitrateExceedsLimit")
                .hasBitrateTranscodeReason()
        )
    }

    @Test
    fun ignoresNonBitrateReasonsAndMissingReasons() {
        assertFalse(source("/videos/a/master.m3u8?TranscodeReasons=AudioCodecNotSupported").hasBitrateTranscodeReason())
        assertFalse(source("/videos/a/master.m3u8?VideoCodec=h264").hasBitrateTranscodeReason())
        assertFalse(source(null).hasBitrateTranscodeReason())
    }

    @Test
    fun localDirectPlayCheckMatchesProfile() {
        assertTrue(PlaybackDeviceProfileFactory.supportsVideoDirectPlay("mov,mp4,m4a,3gp,3g2,mj2", "hevc", "aac"))
        assertTrue(PlaybackDeviceProfileFactory.supportsVideoDirectPlay("mkv", "h264", null))
        assertFalse(PlaybackDeviceProfileFactory.supportsVideoDirectPlay("mp4", "hevc", "pcm_s24le"))
        assertFalse(PlaybackDeviceProfileFactory.supportsVideoDirectPlay("flv", "h264", "aac"))
        assertFalse(PlaybackDeviceProfileFactory.supportsVideoDirectPlay("mp4", "vc1", "aac"))
    }
}
