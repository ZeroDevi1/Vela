package com.vela.player.core

import com.vela.data.model.MediaStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TrackDetailsTest {

    @Test
    fun audioPrimaryLineAppendsMissingTechnicalDetails() {
        val stream = MediaStream(
            type = "Audio",
            index = 1,
            language = "jpn",
            codec = "pcm_s16le",
            channels = 2,
            displayTitle = "Japanese PCM_S16LE 2 ch",
            title = "日语",
            sampleRate = 48000,
            bitDepth = 16,
            bitRate = 1536000
        )

        val primary = TrackDetails.audioPrimaryLine(stream)
        assertEquals("Japanese PCM_S16LE 2 ch  48 kHz 16bit 1536 kbps", primary)
        assertEquals("日语", TrackDetails.audioSecondaryLine(stream))
    }

    @Test
    fun subtitleLinesKeepDisplayTitleAndFileTitle() {
        val stream = MediaStream(
            type = "Subtitle",
            index = 3,
            language = "chi",
            codec = "subrip",
            displayTitle = "Chinese Simplified (SUBRIP)",
            title = "华创上视国语简体中文",
            isDefault = true
        )

        assertEquals("Chinese Simplified (SUBRIP)", TrackDetails.subtitlePrimaryLine(stream))
        assertEquals("华创上视国语简体中文", TrackDetails.subtitleSecondaryLine(stream))
    }

    @Test
    fun seriesFingerprintMatchesSameDubOnAnotherEpisode() {
        val selected = MediaStream(
            type = "Audio",
            index = 2,
            language = "chi",
            codec = "ac3",
            channels = 2,
            title = "台配国语",
            displayTitle = "Chinese AC3 stereo (默认)"
        )
        val nextEpisode = listOf(
            MediaStream(
                type = "Audio",
                index = 1,
                language = "jpn",
                codec = "pcm_s16le",
                channels = 2,
                title = "日语"
            ),
            MediaStream(
                type = "Audio",
                index = 4,
                language = "chi",
                codec = "ac3",
                channels = 2,
                title = "台配国语"
            )
        )

        val fingerprint = TrackDetails.audioFingerprint(selected)
        assertEquals(4, TrackDetails.matchAudioIndex(nextEpisode, fingerprint))
    }

    @Test
    fun subtitleOffFingerprintResolvesToDisabledIndex() {
        assertEquals(-1, TrackDetails.matchSubtitleIndex(emptyList(), TrackDetails.subtitleOffFingerprint()))
    }

    @Test
    fun audioOffFingerprintResolvesToMutedIndex() {
        assertEquals(-1, TrackDetails.matchAudioIndex(emptyList(), TrackDetails.audioOffFingerprint()))
    }

    @Test
    fun fingerprintRoundTripPreservesOffAndTitle() {
        val off = TrackFingerprint(off = true)
        assertEquals(off, TrackFingerprint.parse(off.serialize()))

        val audio = TrackFingerprint(
            language = "chi",
            title = "台配国语",
            codec = "ac3",
            channels = 2
        )
        assertEquals(audio, TrackFingerprint.parse(audio.serialize()))
        assertNull(TrackFingerprint.parse(""))
        assertNull(TrackFingerprint.parse("not-a-fingerprint"))
    }
}
