package com.vela.app.player.mpv

/**
 * MPV 杜比视界选项。亮度增强只改 DV 片；DV7→8.1 是解码期 filter，非 DV 片应被忽略。
 */
internal object DolbyVisionMpv {
    const val DV7_TO_DV81_VF = "format=dolbyvision=yes"
    const val DV7_TO_DV81_VD_LAVC = "enable_dovi=1"
    const val BRIGHTNESS_BOOST = "1.5"
    const val BRIGHTNESS_NEUTRAL = "1.0"

    data class DecodeOptions(
        val vf: String,
        val vdLavcO: String,
        val playbackPath: String
    )

    data class RuntimeOptions(
        val hdrComputePeak: String,
        val toneMappingMaxBoost: String,
        val playbackPath: String
    )

    fun decodeOptions(
        convertDv7ToDv81: Boolean,
        deviceSupportsDolbyVision: Boolean
    ): DecodeOptions {
        val path = if (deviceSupportsDolbyVision) {
            if (convertDv7ToDv81) "dv-native+dv7-to-dv81" else "dv-native"
        } else {
            if (convertDv7ToDv81) "hdr10-or-sdr-fallback+dv7-to-dv81" else "hdr10-or-sdr-fallback"
        }
        return DecodeOptions(
            vf = if (convertDv7ToDv81) DV7_TO_DV81_VF else "",
            vdLavcO = if (convertDv7ToDv81) DV7_TO_DV81_VD_LAVC else "",
            playbackPath = path
        )
    }

    fun runtimeOptions(
        isDolbyVisionContent: Boolean,
        brightnessEnhancement: Boolean,
        dynamicPeakEnabled: Boolean
    ): RuntimeOptions {
        return if (isDolbyVisionContent) {
            RuntimeOptions(
                hdrComputePeak = if (brightnessEnhancement) "yes" else "no",
                toneMappingMaxBoost = if (brightnessEnhancement) BRIGHTNESS_BOOST else BRIGHTNESS_NEUTRAL,
                playbackPath = if (brightnessEnhancement) "dv-brightness-on" else "dv-brightness-off"
            )
        } else {
            RuntimeOptions(
                hdrComputePeak = if (dynamicPeakEnabled) "yes" else "no",
                toneMappingMaxBoost = BRIGHTNESS_NEUTRAL,
                playbackPath = "non-dv"
            )
        }
    }

    fun isDolbyVisionTrack(
        codec: String?,
        format: String?,
        doviFlag: String?
    ): Boolean {
        return containsDolbyToken(codec) ||
            containsDolbyToken(format) ||
            doviFlag.equals("yes", ignoreCase = true) ||
            containsDolbyToken(doviFlag)
    }

    private fun containsDolbyToken(value: String?): Boolean {
        val token = value?.lowercase().orEmpty()
        if (token.isBlank()) return false
        return token.contains("dovi") ||
            token.contains("dvhe") ||
            token.contains("dvh1") ||
            token.contains("dolby")
    }
}
