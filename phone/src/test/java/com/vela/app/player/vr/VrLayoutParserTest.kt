package com.vela.app.player.vr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VrLayoutParserTest {

    @Test
    fun parses180SbsFilename() {
        val layout = VrLayoutParser.parse(mediaSourcePath = "/media/Studio_Title_180_sbs.mp4")
        assertEquals(VrProjection.HalfEquirect, layout?.projection)
        assertEquals(VrStereo.SideBySide, layout?.stereo)
        assertEquals(180, layout?.inputFov)
    }

    @Test
    fun parses180x180ThreeDh() {
        val layout = VrLayoutParser.parse(itemPath = "Title_180x180_3dh.mkv")
        assertEquals(VrProjection.HalfEquirect, layout?.projection)
        assertEquals(VrStereo.SideBySide, layout?.stereo)
    }

    @Test
    fun parsesMkx200() {
        val layout = VrLayoutParser.parse(mediaSourceName = "Title_MKX200_LR_180.mp4")
        assertEquals(VrProjection.Fisheye, layout?.projection)
        assertEquals(VrStereo.SideBySide, layout?.stereo)
        assertEquals(200, layout?.inputFov)
    }

    @Test
    fun parsesFisheye190() {
        val layout = VrLayoutParser.parse(itemName = "Clip_FISHEYE190_sbs.mp4")
        assertEquals(VrProjection.Fisheye, layout?.projection)
        assertEquals(190, layout?.inputFov)
        assertEquals(VrStereo.SideBySide, layout?.stereo)
    }

    @Test
    fun parses360WithTopBottom() {
        val layout = VrLayoutParser.parse(mediaSourcePath = "World_360_tb.mp4")
        assertEquals(VrProjection.Equirect, layout?.projection)
        assertEquals(VrStereo.TopBottom, layout?.stereo)
        assertEquals(360, layout?.inputFov)
    }

    @Test
    fun defaultsStereoToSbsWhenOnlyProjection() {
        val layout = VrLayoutParser.parse(mediaSourcePath = "Travel_vr360.mp4")
        assertEquals(VrProjection.Equirect, layout?.projection)
        assertEquals(VrStereo.SideBySide, layout?.stereo)
    }

    @Test
    fun usesVideo3DFormatOnlyForStereo() {
        val layout = VrLayoutParser.parse(
            itemName = "Scene_vr180",
            video3DFormat = "FullTopAndBottom"
        )
        assertEquals(VrProjection.HalfEquirect, layout?.projection)
        assertEquals(VrStereo.TopBottom, layout?.stereo)
    }

    @Test
    fun ignoresBare180Title() {
        assertNull(VrLayoutParser.parse(itemName = "180 Days"))
        assertNull(VrLayoutParser.parse(mediaSourcePath = "/movies/180 Days.mkv"))
    }

    @Test
    fun ignoresBluraySbsWithoutProjection() {
        assertNull(
            VrLayoutParser.parse(
                itemName = "Avatar 3D",
                video3DFormat = "HalfSideBySide"
            )
        )
    }

    @Test
    fun detectsVrTag() {
        val layout = VrLayoutParser.parse(
            itemName = "Holiday Clip",
            tags = listOf("VR")
        )
        assertEquals(VrProjection.HalfEquirect, layout?.projection)
        assertEquals(VrStereo.SideBySide, layout?.stereo)
    }

    @Test
    fun prefersPathOverName() {
        val layout = VrLayoutParser.parse(
            mediaSourcePath = "clip_360_sbs.mp4",
            itemName = "180 Days"
        )
        assertEquals(VrProjection.Equirect, layout?.projection)
        assertEquals(VrStereo.SideBySide, layout?.stereo)
    }

    @Test
    fun parsesMono() {
        val layout = VrLayoutParser.parse(mediaSourcePath = "Lookaround_vr180_mono.mp4")
        assertEquals(VrStereo.Mono, layout?.stereo)
        assertEquals(VrProjection.HalfEquirect, layout?.projection)
    }

    @Test
    fun reconstructsLayoutFromId() {
        val layout = VrLayout(
            projection = VrProjection.Fisheye,
            stereo = VrStereo.TopBottom,
            inputFov = 190
        )
        assertEquals(layout, VrLayoutParser.layoutForId(layout.id))
    }

    @Test
    fun parsesCrvrTitleAsHalfEquirect() {
        val layout = VrLayoutParser.parse(itemName = "CRVR-146 【VR】深田えいみ")
        assertEquals(VrProjection.HalfEquirect, layout?.projection)
        assertEquals(VrStereo.SideBySide, layout?.stereo)
        assertEquals(180, layout?.inputFov)
    }

    @Test
    fun rejectsVrTitleFlattenedTo16by9() {
        assertNull(
            VrLayoutParser.parse(
                itemName = "SIVR-357 【VR】可愛い",
                videoWidth = 3840,
                videoHeight = 2160
            )
        )
    }

    @Test
    fun keeps8kSbsVrWhenSizeMatches() {
        val layout = VrLayoutParser.parse(
            itemName = "CRVR-416 【VR】【8KVR】",
            videoWidth = 7680,
            videoHeight = 3840
        )
        assertEquals(VrStereo.SideBySide, layout?.stereo)
    }

    @Test
    fun infersStereoFromAspectWhenNameIsSilent() {
        assertEquals(
            VrStereo.Mono,
            VrLayoutParser.parse(mediaSourcePath = "Clip_vr180.mp4", videoWidth = 4096, videoHeight = 4096)?.stereo
        )
        assertEquals(
            VrStereo.TopBottom,
            VrLayoutParser.parse(mediaSourcePath = "Clip_vr180.mp4", videoWidth = 2880, videoHeight = 5760)?.stereo
        )
        assertEquals(
            VrStereo.Mono,
            VrLayoutParser.parse(mediaSourcePath = "Travel_vr360.mp4", videoWidth = 5760, videoHeight = 2880)?.stereo
        )
    }

    @Test
    fun aspectOverridesContradictingStereoToken() {
        val layout = VrLayoutParser.parse(
            mediaSourcePath = "World_360_tb.mp4",
            videoWidth = 7680,
            videoHeight = 1920
        )
        assertEquals(VrStereo.SideBySide, layout?.stereo)
    }
}
