package com.vela.data.repository

import com.vela.data.model.AudioTranscodeMode
import com.vela.data.model.CodecProfile
import com.vela.data.model.DeviceProfile
import com.vela.data.model.DirectPlayProfile
import com.vela.data.model.ProfileCondition
import com.vela.data.model.SubtitleProfile
import com.vela.data.model.TranscodingProfile

internal object PlaybackDeviceProfileFactory {
    private const val DIRECT_PLAY_VIDEO_CONTAINERS = "mp4,mkv,webm,ts,m2ts,mov,avi"
    private const val DIRECT_PLAY_VIDEO_CODECS = "h264,hevc,vp9,av1,mpeg4,mpeg2video,vp8"
    private const val DIRECT_PLAY_AUDIO_CODECS = "aac,mp3,ac3,eac3,dts,flac,opus,vorbis,truehd,pcm"

    /**
     * 按本 profile 的 DirectPlayProfile 判断源文件能否直放（与服务端直放判定同口径：容器 + 音视频编码）。
     * 服务端的 VideoCodecNotSupported 可能来自"转码时能否复制视频流"的评估，不代表直放不支持，
     * 因此码率超限回退直放前用本地口径复核。[container] 可为逗号列表（如 Jellyfin 的 "mov,mp4,m4a"）。
     */
    fun supportsVideoDirectPlay(container: String?, videoCodec: String?, audioCodec: String?): Boolean {
        val containers = tokens(container)
        if (containers.isEmpty() || containers.none { it in tokens(DIRECT_PLAY_VIDEO_CONTAINERS) }) return false
        val video = videoCodec?.trim()?.lowercase()
        if (video.isNullOrEmpty() || video !in tokens(DIRECT_PLAY_VIDEO_CODECS)) return false
        val audio = audioCodec?.trim()?.lowercase()
        return audio.isNullOrEmpty() || audio in tokens(DIRECT_PLAY_AUDIO_CODECS)
    }

    private fun tokens(value: String?): Set<String> {
        return value.orEmpty().split(',').map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSet()
    }

    /**
     * @param maxVideoWidth 视频宽度上限；非 null 时声明 `Width ≤ 上限` 的必需条件，超过的片源由服务器缩放重编码。
     * 用于本机解码器打不开的超高分辨率片源（如 8192 宽的 8K VR）的「服务器转码为 4K」。
     */
    fun create(
        maxStreamingBitrate: Long? = null,
        audioTranscodeMode: AudioTranscodeMode = AudioTranscodeMode.AUTO,
        maxVideoWidth: Int? = null
    ): DeviceProfile {
        val bitrate = maxStreamingBitrate?.takeIf { it > 0L }
        val maxAudioChannels = audioTranscodeMode.maxAudioChannels
        val videoTranscodeAudioCodecs = videoTranscodeAudioCodecs(audioTranscodeMode)

        return DeviceProfile(
            name = "Vela Android",
            maxStreamingBitrate = bitrate,
            maxStaticBitrate = bitrate,
            supportedMediaTypes = "Video,Audio",
            directPlayProfiles = listOf(
                DirectPlayProfile(
                    type = "Video",
                    container = DIRECT_PLAY_VIDEO_CONTAINERS,
                    videoCodec = DIRECT_PLAY_VIDEO_CODECS,
                    audioCodec = DIRECT_PLAY_AUDIO_CODECS
                ),
                DirectPlayProfile(
                    type = "Audio",
                    container = "mp3,m4a,aac,ogg,flac,wav,webm,mka",
                    audioCodec = "aac,mp3,ac3,eac3,dts,flac,opus,vorbis,truehd,pcm"
                )
            ),
            transcodingProfiles = listOf(
                TranscodingProfile(
                    type = "Video",
                    context = "Streaming",
                    protocol = "hls",
                    container = "ts",
                    videoCodec = "h264",
                    audioCodec = videoTranscodeAudioCodecs,
                    enableSubtitlesInManifest = true,
                    maxAudioChannels = maxAudioChannels
                ),
                TranscodingProfile(
                    type = "Video",
                    context = "Streaming",
                    protocol = "hls",
                    container = "mp4",
                    videoCodec = "h264",
                    audioCodec = videoTranscodeAudioCodecs,
                    enableSubtitlesInManifest = true,
                    maxAudioChannels = maxAudioChannels
                ),
                TranscodingProfile(
                    type = "Audio",
                    context = "Streaming",
                    protocol = "http",
                    container = "mp3",
                    audioCodec = "mp3",
                    maxAudioChannels = "2"
                )
            ),
            subtitleProfiles = subtitleProfiles(),
            codecProfiles = maxVideoWidth?.takeIf { it > 0 }?.let { width ->
                listOf(
                    CodecProfile(
                        type = "Video",
                        conditions = listOf(
                            ProfileCondition(condition = "LessThanEqual", property = "Width", value = width.toString())
                        )
                    )
                )
            }
        )
    }

    private fun videoTranscodeAudioCodecs(audioTranscodeMode: AudioTranscodeMode): String {
        return when (audioTranscodeMode) {
            AudioTranscodeMode.STEREO -> "aac"
            AudioTranscodeMode.SURROUND_5_1 -> "eac3"
            AudioTranscodeMode.PASSTHROUGH -> "ac3,eac3"
            else -> "aac,mp3,ac3,eac3"
        }
    }

    private fun subtitleProfiles(): List<SubtitleProfile> {
        val textFormats = listOf(
            "webvtt",
            "vtt",
            "srt",
            "subrip",
            "ttml",
            "ass",
            "ssa",
            "microdvd",
            "mov_text",
            "mpl2",
            "pjs",
            "realtext",
            "scc",
            "smi",
            "stl",
            "sub",
            "subviewer",
            "text",
            "vplayer",
            "xsub"
        )
        val imageFormats = listOf(
            "dvdsub",
            "idx",
            "pgs",
            "pgssub",
            "teletext",
            "vobsub"
        )

        return buildList {
            textFormats.forEach { format ->
                // MPV 可直接读取容器内文本轨；ASS/SSA 只声明 Embed，避免服务端优先抽取后丢失字体/时间基准。
                add(SubtitleProfile(format = format, method = "Embed"))
                if (format != "ass" && format != "ssa") {
                    add(SubtitleProfile(format = format, method = "External"))
                }
            }
            imageFormats.forEach { format ->
                add(SubtitleProfile(format = format, method = "Embed"))
                add(SubtitleProfile(format = format, method = "Encode"))
            }
        }
    }
}
