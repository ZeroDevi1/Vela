package com.vela.app.player.vr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VrFlattenFilterTest {

    private val layout = VrLayout(
        projection = VrProjection.HalfEquirect,
        stereo = VrStereo.SideBySide,
        inputFov = 180
    )

    private val template = """
        #define YAW __YAW__
        #define PITCH __PITCH__
        #define D_FOV __D_FOV__
        #define ID_FOV __ID_FOV__
        #define PROJ_MODE __PROJ_MODE__
        #define STEREO_MODE __STEREO_MODE__
    """.trimIndent()

    @Test
    fun bakesDefinesIntoShader() {
        val source = VrFlattenFilter.shaderSource(
            template,
            layout,
            yaw = 12.5f,
            pitch = -4f,
            outputFov = 80f
        )
        assertTrue(source.contains("#define YAW 12.50"))
        assertTrue(source.contains("#define PITCH -4.00"))
        assertTrue(source.contains("#define D_FOV 80.00"))
        assertTrue(source.contains("#define ID_FOV 180.0"))
        assertTrue(source.contains("#define PROJ_MODE 0.0"))
        assertTrue(source.contains("#define STEREO_MODE 1.0"))
        assertFalse(source.contains("__YAW__"))
    }

    @Test
    fun clampsOutputFov() {
        val low = VrFlattenFilter.shaderSource(template, layout, outputFov = 10f)
        val high = VrFlattenFilter.shaderSource(template, layout, outputFov = 200f)
        assertTrue(low.contains("#define D_FOV 40.00"))
        assertTrue(high.contains("#define D_FOV 120.00"))
    }

    @Test
    fun mapsEquirectAndFisheyeModes() {
        val equirect = VrFlattenFilter.shaderSource(
            template,
            VrLayout(VrProjection.Equirect, VrStereo.TopBottom, 360)
        )
        val fisheye = VrFlattenFilter.shaderSource(
            template,
            VrLayout(VrProjection.Fisheye, VrStereo.Mono, 200)
        )
        assertTrue(equirect.contains("#define PROJ_MODE 1.0"))
        assertTrue(equirect.contains("#define STEREO_MODE 2.0"))
        assertTrue(fisheye.contains("#define PROJ_MODE 2.0"))
        assertTrue(fisheye.contains("#define STEREO_MODE 0.0"))
    }

}
