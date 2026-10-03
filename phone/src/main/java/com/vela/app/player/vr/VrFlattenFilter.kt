package com.vela.app.player.vr

/**
 * GPU flatten shader for mpv `vo=gpu` / `vo=gpu-next` hooks.
 *
 * The hook samples libplacebo/mpv's RGB intermediate texture, so it works with zero-copy
 * `mediacodec` decoding (no `mediacodec-copy` readback, which costs ~50MB/frame at 8K).
 *
 * Look direction (yaw / pitch / output FOV):
 * - gpu-next: `//!PARAM ... DYNAMIC` uniforms updated through `glsl-shader-opts`, no recompile.
 * - gpu: `//!PARAM` is unsupported, so values are baked as `#define`s. mpv caches user shaders
 *   by path, so every baked variant must be written to its own file name ([shaderFileName]).
 */
object VrFlattenFilter {
    const val SHADER_ASSET = "shaders/vr_flatten.glsl"
    const val SHADER_FILE_PREFIX = "vr_flatten"
    const val DEFAULT_OUTPUT_FOV = 90f
    const val MIN_OUTPUT_FOV = 40f
    const val MAX_OUTPUT_FOV = 120f

    /**
     * @param dynamicLook true 时生成 gpu-next 动态参数版本，[yaw]/[pitch]/[outputFov] 只作为初始值，
     * 之后用 [lookShaderOpts] 更新。
     */
    fun shaderSource(
        template: String,
        layout: VrLayout,
        yaw: Float = 0f,
        pitch: Float = 0f,
        outputFov: Float = DEFAULT_OUTPUT_FOV,
        dynamicLook: Boolean = false
    ): String {
        val fov = clampFov(outputFov)
        val params = if (dynamicLook) {
            dynamicParam("YAW", yaw, -360f, 360f) +
                dynamicParam("PITCH", pitch, -90f, 90f) +
                dynamicParam("D_FOV", fov, MIN_OUTPUT_FOV, MAX_OUTPUT_FOV)
        } else {
            ""
        }
        val defines = if (dynamicLook) {
            ""
        } else {
            "#define YAW ${format(yaw)}\n#define PITCH ${format(pitch)}\n#define D_FOV ${format(fov)}"
        }
        return template
            .replace("__LOOK_PARAMS__", params)
            .replace("__LOOK_DEFINES__", defines)
            .replace("__ID_FOV__", "${layout.inputFov}.0")
            .replace("__PROJ_MODE__", "${layout.projMode}.0")
            .replace("__STEREO_MODE__", "${layout.stereoMode}.0")
    }

    /** gpu-next 动态参数的 `glsl-shader-opts` 值；只改 uniform，不触发着色器重编译。 */
    fun lookShaderOpts(yaw: Float, pitch: Float, outputFov: Float): String {
        return "YAW=${format(yaw)},PITCH=${format(pitch)},D_FOV=${format(clampFov(outputFov))}"
    }

    /** 按内容区分文件名：mpv 以路径缓存 user shader，同名覆写不会被重新读取。 */
    fun shaderFileName(source: String): String {
        return "${SHADER_FILE_PREFIX}_${source.hashCode().toUInt().toString(16)}.glsl"
    }

    private fun dynamicParam(name: String, value: Float, min: Float, max: Float): String {
        return "//!PARAM $name\n//!TYPE DYNAMIC float\n//!MINIMUM ${format(min)}\n" +
            "//!MAXIMUM ${format(max)}\n${format(value)}\n\n"
    }

    private fun clampFov(outputFov: Float): Float = outputFov.coerceIn(MIN_OUTPUT_FOV, MAX_OUTPUT_FOV)

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
