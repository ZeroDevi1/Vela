package com.vela.app.player.mpv

import com.vela.data.model.MediaStream
import com.vela.player.preferences.PlayerPreferences

/**
 * SDR HEVC 经 MediaCodec 输出时可能被错误标为 BT.2020。
 * copy 保留 YUV，在 GPU 转 RGB 前修正缺失的色彩标签，避免整段视频软解。
 */
internal object HevcHwdecColor {
    const val BT709_FORMAT_VF =
        "format=convert=no:colormatrix=bt.709:primaries=bt.709:gamma=bt.1886"

    fun hardwareDecoding(
        userPreference: String,
        mediaStreams: List<MediaStream>?
    ): String {
        if (userPreference == PlayerPreferences.MPV_HARDWARE_DECODING_NONE) {
            return userPreference
        }
        return if (needsCopyColorPath(mediaStreams)) {
            PlayerPreferences.MPV_HARDWARE_DECODING_MEDIACODEC_COPY
        } else {
            userPreference
        }
    }

    fun formatVf(mediaStreams: List<MediaStream>?): String {
        if (!needsBt709InputOverride(mediaStreams)) return ""
        val video = videoStream(mediaStreams) ?: return ""
        val levels = when (video.colorRange?.lowercase()) {
            "pc", "full", "jpeg" -> "full"
            else -> "limited"
        }
        return "$BT709_FORMAT_VF:colorlevels=$levels"
    }

    fun composedVf(dolbyVf: String, mediaStreams: List<MediaStream>?): String {
        return listOf(formatVf(mediaStreams), dolbyVf)
            .filter { it.isNotBlank() }
            .joinToString(",")
    }

    fun needsCopyColorPath(mediaStreams: List<MediaStream>?): Boolean {
        val video = videoStream(mediaStreams) ?: return false
        if (!isHevc(video) || isHdr(video) || !isHdOrUnknown(video)) return false
        return needsBt709InputOverride(mediaStreams)
    }

    fun needsBt709InputOverride(mediaStreams: List<MediaStream>?): Boolean {
        val video = videoStream(mediaStreams) ?: return false
        if (!isHevc(video) || isHdr(video) || !isHdOrUnknown(video)) return false
        if (isBt2020Sdr(video)) return false
        // 有明确的非 BT.709 标签时不凭分辨率或 hev1 封装覆盖它。
        return listOf(video.colorSpace, video.colorPrimaries, video.colorTransfer).all {
            isBlankOrUnspecified(it) || it?.lowercase() in setOf("bt709", "bt.709", "bt1886", "bt.1886")
        } && (hasUnspecifiedColor(video) || isHev1(video) || isUhd(video))
    }

    private fun videoStream(mediaStreams: List<MediaStream>?): MediaStream? {
        return mediaStreams.orEmpty().firstOrNull { stream ->
            stream.type.equals("Video", ignoreCase = true)
        }
    }

    private fun isHevc(video: MediaStream): Boolean {
        val codec = video.codec?.lowercase().orEmpty()
        val tag = video.codecTag?.lowercase().orEmpty()
        return codec.contains("hevc") ||
            codec.contains("h265") ||
            codec.contains("hev1") ||
            codec.contains("hvc1") ||
            tag.contains("hev1") ||
            tag.contains("hvc1")
    }

    private fun isHev1(video: MediaStream): Boolean {
        val tag = video.codecTag?.lowercase().orEmpty()
        val codec = video.codec?.lowercase().orEmpty()
        return tag.contains("hev1") || codec.contains("hev1")
    }

    private fun isUhd(video: MediaStream): Boolean {
        val width = video.width ?: 0
        val height = video.height ?: 0
        return width >= 3840 || height >= 2160
    }

    private fun isHdOrUnknown(video: MediaStream): Boolean {
        val width = video.width ?: 0
        val height = video.height ?: 0
        if (width <= 0 && height <= 0) return true
        return width >= 1280 || height >= 720
    }

    private fun hasUnspecifiedColor(video: MediaStream): Boolean {
        return isBlankOrUnspecified(video.colorPrimaries) &&
            isBlankOrUnspecified(video.colorSpace) &&
            isBlankOrUnspecified(video.colorTransfer)
    }

    private fun isBt2020Sdr(video: MediaStream): Boolean {
        if (isHdr(video)) return false
        return containsBt2020(video.colorSpace) || containsBt2020(video.colorPrimaries)
    }

    private fun isHdr(video: MediaStream): Boolean {
        val transfer = video.colorTransfer?.lowercase().orEmpty()
        val range = video.videoRange?.lowercase().orEmpty()
        val rangeType = video.videoRangeType?.lowercase().orEmpty()
        if (
            transfer.contains("2084") ||
            transfer.contains("smpte2084") ||
            transfer.contains("pq") ||
            transfer.contains("hlg") ||
            transfer.contains("arib-std-b67")
        ) {
            return true
        }
        if (rangeType.contains("dovi") || range.contains("dovi")) return true
        if (rangeType.contains("hdr") || range == "hdr") return true
        return video.dvProfile != null || video.rpuPresentFlag == 1
    }

    private fun containsBt2020(value: String?): Boolean {
        val token = value?.lowercase().orEmpty()
        return token.contains("bt2020") || token.contains("bt.2020")
    }

    private fun isBlankOrUnspecified(value: String?): Boolean {
        val token = value?.trim()?.lowercase().orEmpty()
        return token.isEmpty() ||
            token == "unspecified" ||
            token == "unknown" ||
            token == "na"
    }
}
