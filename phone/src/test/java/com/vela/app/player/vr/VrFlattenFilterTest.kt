package com.vela.app.player.vr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VrFlattenFilterTest {

    private val layout = VrLayout(
        projection = VrProjection.HalfEquirect,
        stereo = VrStereo.SideBySide,
        inputFov = 180
    )

    private val template = """
        __LOOK_PARAMS__//!HOOK MAIN
        __LOOK_DEFINES__
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
        assertTrue(low.contains("#define D_FOV 50.00"))
        assertTrue(high.contains("#define D_FOV 150.00"))
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


    @Test
    fun dynamicLookUsesParamsInsteadOfDefines() {
        val source = VrFlattenFilter.shaderSource(template, layout, yaw = 5f, dynamicLook = true)
        assertTrue(source.startsWith("//!PARAM YAW\n//!TYPE DYNAMIC float"))
        assertTrue(source.contains("//!PARAM PITCH"))
        assertTrue(source.contains("//!PARAM D_FOV"))
        assertTrue(source.indexOf("//!PARAM D_FOV") < source.indexOf("//!HOOK MAIN"))
        assertFalse(source.contains("#define YAW"))
        assertFalse(source.contains("__LOOK_"))
        assertEquals(
            "YAW=12.50,PITCH=-4.00,D_FOV=150.00",
            VrFlattenFilter.lookShaderOpts(12.5f, -4f, 200f)
        )
    }

    @Test
    fun bakedVariantsGetDistinctFileNames() {
        val a = VrFlattenFilter.shaderSource(template, layout, yaw = 0f)
        val b = VrFlattenFilter.shaderSource(template, layout, yaw = 1f)
        assertNotEquals(VrFlattenFilter.shaderFileName(a), VrFlattenFilter.shaderFileName(b))
        assertEquals(VrFlattenFilter.shaderFileName(a), VrFlattenFilter.shaderFileName(a))
    }

    @Test
    fun shippedShaderAvoidsHookedTexMacro() {
        // Adreno 不展开 HOOKED_tex -> MAINPRESUB_tex(pos)，用了就会整段 hook 编译失败。
        val shader = java.io.File("src/main/assets/${VrFlattenFilter.SHADER_ASSET}").readText()
        val code = shader.lines().filterNot { it.trimStart().startsWith("//") }.joinToString("\n")
        assertFalse(code.contains("HOOKED_tex"))
        assertFalse(shader.contains("?\n"))
        assertTrue(shader.contains("//!WIDTH OUTPUT.w\n"))
        assertTrue(shader.contains("__LOOK_PARAMS__"))
        assertTrue(shader.contains("__LOOK_DEFINES__"))
    }
}
