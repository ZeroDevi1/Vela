package com.vela.app.player.vr

/**
 * GPU flatten shader for mpv `vo=gpu` / `vo=gpu-next` hooks.
 *
 * `//!PARAM` / `glsl-shader-opts` only work on gpu-next. Default vo is gpu,
 * so values are baked as `#define`s and the hook is reloaded.
 */
object VrFlattenFilter {
    const val SHADER_ASSET = "shaders/vr_flatten.glsl"
    const val SHADER_FILE_NAME = "vr_flatten.glsl"
    const val DEFAULT_OUTPUT_FOV = 90f
    const val MIN_OUTPUT_FOV = 40f
    const val MAX_OUTPUT_FOV = 120f

    fun shaderSource(
        template: String,
        layout: VrLayout,
        yaw: Float = 0f,
        pitch: Float = 0f,
        outputFov: Float = DEFAULT_OUTPUT_FOV
    ): String {
        val fov = outputFov.coerceIn(MIN_OUTPUT_FOV, MAX_OUTPUT_FOV)
        return template
            .replace("__YAW__", format(yaw))
            .replace("__PITCH__", format(pitch))
            .replace("__D_FOV__", format(fov))
            .replace("__ID_FOV__", "${layout.inputFov}.0")
            .replace("__PROJ_MODE__", "${layout.projMode}.0")
            .replace("__STEREO_MODE__", "${layout.stereoMode}.0")
    }

    private fun format(value: Float): String {
        return "%.2f".format(java.util.Locale.US, value)
    }
}

internal val VrLayout.projMode: Int
    get() = when (projection) {
        VrProjection.HalfEquirect -> 0
        VrProjection.Equirect -> 1
        VrProjection.Fisheye -> 2
    }

internal val VrLayout.stereoMode: Int
    get() = when (stereo) {
        VrStereo.Mono -> 0
        VrStereo.SideBySide -> 1
        VrStereo.TopBottom -> 2
    }
