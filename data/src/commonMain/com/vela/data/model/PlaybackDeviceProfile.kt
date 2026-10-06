package com.vela.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Minimal playback device profile used for server-side stream negotiation.
 */
@Serializable
data class DeviceProfile(
    @SerialName("Name")
    val name: String? = null,
    @SerialName("MaxStreamingBitrate")
    val maxStreamingBitrate: Long? = null,
    @SerialName("MaxStaticBitrate")
    val maxStaticBitrate: Long? = null,
    @SerialName("SupportedMediaTypes")
    val supportedMediaTypes: String? = null,
    @SerialName("DirectPlayProfiles")
    val directPlayProfiles: List<DirectPlayProfile>? = null,
    @SerialName("TranscodingProfiles")
    val transcodingProfiles: List<TranscodingProfile>? = null,
    @SerialName("SubtitleProfiles")
    val subtitleProfiles: List<SubtitleProfile>? = null,
    /** 编解码条件：不满足时服务器不直放 / 不复制视频流，并按条件缩放转码（如 Width ≤ 上限）。 */
    @SerialName("CodecProfiles")
    val codecProfiles: List<CodecProfile>? = null
)

@Serializable
data class CodecProfile(
    @SerialName("Type")
    val type: String? = null,
    @SerialName("Conditions")
    val conditions: List<ProfileCondition>? = null
)

@Serializable
data class ProfileCondition(
    @SerialName("Condition")
    val condition: String,
    @SerialName("Property")
    val property: String,
    @SerialName("Value")
    val value: String,
    @SerialName("IsRequired")
    val isRequired: Boolean = true
)

@Serializable
data class DirectPlayProfile(
    @SerialName("Container")
    val container: String? = null,
    @SerialName("AudioCodec")
    val audioCodec: String? = null,
    @SerialName("VideoCodec")
    val videoCodec: String? = null,
    @SerialName("Type")
    val type: String? = null
)

@Serializable
data class TranscodingProfile(
    @SerialName("Container")
    val container: String? = null,
    @SerialName("Type")
    val type: String? = null,
    @SerialName("VideoCodec")
    val videoCodec: String? = null,
    @SerialName("AudioCodec")
    val audioCodec: String? = null,
    @SerialName("Protocol")
    val protocol: String? = null,
    @SerialName("Context")
    val context: String? = null,
    @SerialName("EnableSubtitlesInManifest")
    val enableSubtitlesInManifest: Boolean? = null,
    @SerialName("MaxAudioChannels")
    val maxAudioChannels: String? = null
)

@Serializable
data class SubtitleProfile(
    @SerialName("Format")
    val format: String? = null,
    @SerialName("Method")
    val method: String? = null
)
