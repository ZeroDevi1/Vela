package com.vela.player.core

import com.vela.data.model.ChapterInfo
import com.vela.data.model.parseMediaSegments
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlaybackMarkerUtilsTest {

    @Test
    fun readsMarkerTypesFromEitherServer() {
        val segments = PlaybackMarkerUtils.extractMarkerSegments(
            listOf(
                ChapterInfo(startPositionTicks = 0L, name = "Opening", markerType = "IntroStart"),
                ChapterInfo(startPositionTicks = 90_000_0000L, markerType = "IntroEnd"),
                ChapterInfo(startPositionTicks = 1_200_000_0000L, name = "片尾", markerType = "CreditsStart")
            )
        )

        assertEquals(0L, segments.intro?.startMs)
        assertEquals(90_000L, segments.intro?.endMs)
        assertEquals(1_200_000L, segments.credits?.startMs)
        assertNull(segments.credits?.endMs)
    }

    @Test
    fun parsesJellyfinArrayAndEmbyItems() {
        val jellyfin = parseMediaSegments(
            """[{"Type":"Intro","StartTicks":0,"EndTicks":850000000},{"Type":"Outro","StartTicks":12000000000,"EndTicks":13000000000}]"""
        )
        assertEquals(0L, jellyfin.intro?.startMs)
        assertEquals(85_000L, jellyfin.intro?.endMs)
        assertEquals(1_200_000L, jellyfin.credits?.startMs)

        val emby = parseMediaSegments(
            """{"Items":[{"Type":"Recap","StartTicks":100000000,"EndTicks":400000000}]}"""
        )
        assertEquals(10_000L, emby.recap?.startMs)
        assertEquals(40_000L, emby.recap?.endMs)
    }
}
