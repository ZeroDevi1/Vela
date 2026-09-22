package com.vela.player.core

import com.vela.data.model.MediaStream
import java.util.Locale

data class TrackFingerprint(
    val off: Boolean = false,
    val language: String? = null,
    val title: String? = null,
    val codec: String? = null,
    val channels: Int? = null,
    val forced: Boolean = false
) {
    fun serialize(): String {
        if (off) return OFF_TOKEN
        return listOf(
            language.orEmpty(),
            title.orEmpty(),
            codec.orEmpty(),
            channels?.toString().orEmpty(),
            if (forced) "1" else "0"
        ).joinToString(SEPARATOR)
    }

    companion object {
        const val OFF_TOKEN = "off"
        private const val SEPARATOR = "\u001f"

        fun parse(raw: String?): TrackFingerprint? {
            val value = raw?.trim().orEmpty()
            if (value.isEmpty()) return null
            if (value.equals(OFF_TOKEN, ignoreCase = true)) {
                return TrackFingerprint(off = true)
            }
            val parts = value.split(SEPARATOR)
            if (parts.size < 5) return null
            return TrackFingerprint(
                language = parts[0].ifBlank { null },
                title = parts[1].ifBlank { null },
                codec = parts[2].ifBlank { null },
                channels = parts[3].toIntOrNull(),
                forced = parts[4] == "1"
            )
        }
    }
}

object TrackDetails {
    /** 详情页和播放器共用的静音选项标识，不是展示文案。 */
    const val AUDIO_MUTE_OPTION = "\u0000audio-off"

    /** 播放器音轨 id，表示关闭音频输出。 */
    const val AUDIO_OFF_ID = "off"

    /**
     * 构造静音音轨。
     *
     * 返回的轨道不触发重新开播，streamIndex 为 -1，供选择列表和偏好持久化使用。
     */
    fun mutedAudioTrack(): AudioTrackInfo {
        return AudioTrackInfo(
            id = AUDIO_OFF_ID,
            label = "",
            language = null,
            channelCount = 0,
            codec = null,
            playerTrackId = AUDIO_OFF_ID,
            streamIndex = -1,
            requiresPlaybackRestart = false
        )
    }

    /**
     * 判断音轨是不是静音项。
     *
     * @param track 待判断的音轨
     * @return 该轨表示关闭音频时为 true
     */
    fun isMutedAudio(track: AudioTrackInfo): Boolean {
        return track.id == AUDIO_OFF_ID || track.playerTrackId == AUDIO_OFF_ID
    }

    fun seriesPreferenceId(itemType: String?, seriesId: String?): String? {
        if (!itemType.equals("Episode", ignoreCase = true)) return null
        return seriesId?.takeIf { it.isNotBlank() }
    }

    fun audioPrimaryLine(stream: MediaStream): String {
        val displayTitle = stream.displayTitleOrNull()
        val extras = audioExtras(stream)
        return when {
            !displayTitle.isNullOrBlank() && extras.isNotEmpty() ->
                "$displayTitle  ${extras.joinToString(" ")}"
            !displayTitle.isNullOrBlank() -> displayTitle
            else -> synthesizedAudioLine(stream, extras)
        }
    }

    fun audioSecondaryLine(stream: MediaStream): String {
        val title = stream.title?.trim().orEmpty()
        if (title.isEmpty()) return ""
        val primary = stream.displayTitleOrNull().orEmpty()
        return if (primary.contains(title, ignoreCase = true)) "" else title
    }

    fun subtitlePrimaryLine(stream: MediaStream): String {
        val displayTitle = stream.displayTitleOrNull()
        if (!displayTitle.isNullOrBlank()) return displayTitle
        return buildList {
            languageLabel(stream.language)?.let(::add)
            stream.codec?.takeIf { it.isNotBlank() }?.let { add("(${it.uppercase(Locale.US)})") }
            if (stream.isDefault == true) add("(默认)")
            if (stream.isForced == true) add("Forced")
            if (stream.isExternal == true) add("External")
        }.joinToString(" ").ifBlank { "Subtitle ${stream.index ?: ""}".trim() }
    }

    fun subtitleSecondaryLine(stream: MediaStream): String {
        val title = stream.title?.trim().orEmpty()
        if (title.isEmpty()) return ""
        val primary = stream.displayTitleOrNull().orEmpty()
        return if (primary.contains(title, ignoreCase = true)) "" else title
    }

    fun audioOptionLabel(stream: MediaStream): String {
        val secondary = audioSecondaryLine(stream)
        val primary = audioPrimaryLine(stream)
        return if (secondary.isBlank()) primary else "$primary\n$secondary"
    }

    fun subtitleOptionLabel(stream: MediaStream): String {
        val secondary = subtitleSecondaryLine(stream)
        val primary = subtitlePrimaryLine(stream)
        return if (secondary.isBlank()) primary else "$primary\n$secondary"
    }

    fun audioFingerprint(stream: MediaStream): TrackFingerprint {
        return TrackFingerprint(
            language = normalizeLanguage(stream.language),
            title = normalizeTitle(stream.title),
            codec = normalizeCodec(stream.codec),
            channels = stream.channels
        )
    }

    fun subtitleFingerprint(stream: MediaStream): TrackFingerprint {
        return TrackFingerprint(
            language = normalizeLanguage(stream.language),
            title = normalizeTitle(stream.title),
            codec = normalizeCodec(stream.codec),
            forced = stream.isForced == true
        )
    }

    fun subtitleOffFingerprint(): TrackFingerprint = TrackFingerprint(off = true)

    /** 静音偏好指纹，跨集匹配时解析为关闭音频。 */
    fun audioOffFingerprint(): TrackFingerprint = TrackFingerprint(off = true)

    fun matchAudioIndex(
        streams: List<MediaStream>,
        fingerprint: TrackFingerprint
    ): Int? {
        if (fingerprint.off) return -1
        val audioStreams = typedStreams(streams, "Audio")
        return matchStream(audioStreams, fingerprint, audio = true)?.index
    }

    fun matchSubtitleIndex(
        streams: List<MediaStream>,
        fingerprint: TrackFingerprint
    ): Int? {
        if (fingerprint.off) return -1
        val subtitleStreams = typedStreams(streams, "Subtitle")
        return matchStream(subtitleStreams, fingerprint, audio = false)?.index
    }

    fun applyStreamDetails(track: AudioTrackInfo, stream: MediaStream?): AudioTrackInfo {
        if (stream == null) return track
        return track.copy(
            label = audioPrimaryLine(stream).ifBlank { track.label },
            language = stream.language ?: track.language,
            channelCount = stream.channels ?: track.channelCount,
            codec = stream.codec ?: track.codec,
            title = stream.title?.trim()?.takeIf { it.isNotEmpty() } ?: track.title,
            bitRate = stream.bitRate ?: track.bitRate,
            sampleRate = stream.sampleRate ?: track.sampleRate,
            bitDepth = stream.bitDepth ?: track.bitDepth,
            channelLayout = stream.channelLayout ?: track.channelLayout,
            isDefault = stream.isDefault == true || track.isDefault,
            streamIndex = stream.index ?: track.streamIndex
        )
    }

    fun applyStreamDetails(track: SubtitleTrackInfo, stream: MediaStream?): SubtitleTrackInfo {
        if (stream == null) return track
        return track.copy(
            label = subtitlePrimaryLine(stream).ifBlank { track.label },
            language = stream.language ?: track.language,
            isForced = stream.isForced == true || track.isForced,
            isDefault = stream.isDefault == true || track.isDefault,
            title = stream.title?.trim()?.takeIf { it.isNotEmpty() } ?: track.title,
            codec = stream.codec ?: track.codec,
            isExternal = stream.isExternal == true || track.isExternal,
            streamIndex = stream.index ?: track.streamIndex
        )
    }

    fun audioDialogLines(track: AudioTrackInfo): Triple<String, String, String> {
        if (track.id == "off") {
            return Triple(track.label.ifBlank { "Off" }, "", "")
        }
        val title = track.label.ifBlank {
            listOfNotNull(
                languageLabel(track.language),
                displayCodec(track.codec),
                channelLabel(track.channelCount, track.channelLayout)
            ).joinToString(" ")
        }
        val subtitle = track.title.orEmpty().let { name ->
            if (name.isBlank() || title.contains(name, ignoreCase = true)) "" else name
        }
        val description = buildList {
            sampleRateLabel(track.sampleRate)?.let(::add)
            bitDepthLabel(track.bitDepth)?.let(::add)
            bitRateLabel(track.bitRate)?.let(::add)
            if (track.isDefault) add("默认")
        }.joinToString(" · ")
        return Triple(title, subtitle, description)
    }

    fun subtitleDialogLines(track: SubtitleTrackInfo): Triple<String, String, String> {
        if (track.id == "off" || track.streamIndex == -1) {
            return Triple(track.label.ifBlank { "Off" }, "", "")
        }
        val title = track.label.ifBlank {
            listOfNotNull(
                languageLabel(track.language),
                track.codec?.takeIf { it.isNotBlank() }?.let { "(${it.uppercase(Locale.US)})" }
            ).joinToString(" ")
        }
        val subtitle = track.title.orEmpty().let { name ->
            if (name.isBlank() || title.contains(name, ignoreCase = true)) "" else name
        }
        val description = buildList {
            if (track.isDefault) add("默认")
            if (track.isForced) add("Forced")
            if (track.isExternal) add("External")
            track.codec?.takeIf { it.isNotBlank() && !title.contains(it, ignoreCase = true) }?.let {
                add(it.uppercase(Locale.US))
            }
        }.joinToString(" · ")
        return Triple(title, subtitle, description)
    }

    private fun matchStream(
        streams: List<MediaStream>,
        fingerprint: TrackFingerprint,
        audio: Boolean
    ): MediaStream? {
        if (streams.isEmpty()) return null
        val title = fingerprint.title
        val language = fingerprint.language
        val codec = fingerprint.codec

        title?.let { expected ->
            streams.firstOrNull { normalizeTitle(it.title) == expected }?.let { return it }
        }

        val languageMatches = streams.filter { languageEquals(it.language, language) }
        val languageCodecMatches = languageMatches.filter { normalizeCodec(it.codec) == codec }
        if (audio && fingerprint.channels != null) {
            languageCodecMatches.firstOrNull { it.channels == fingerprint.channels }?.let { return it }
            languageMatches.firstOrNull { it.channels == fingerprint.channels }?.let { return it }
        }
        languageCodecMatches.singleOrNull()?.let { return it }
        if (!audio) {
            languageMatches.firstOrNull { (it.isForced == true) == fingerprint.forced }?.let { return it }
        }
        languageMatches.singleOrNull()?.let { return it }
        return languageMatches.firstOrNull()
    }

    private fun typedStreams(streams: List<MediaStream>, type: String): List<MediaStream> {
        return streams
            .filter { it.type.equals(type, ignoreCase = true) }
            .sortedBy { it.index ?: Int.MAX_VALUE }
    }

    private fun synthesizedAudioLine(stream: MediaStream, extras: List<String>): String {
        return buildList {
            languageLabel(stream.language)?.let(::add)
            displayCodec(stream.codec)?.let(::add)
            channelLabel(stream.channels ?: 0, stream.channelLayout)?.let(::add)
            addAll(extras)
            if (stream.isDefault == true) add("(默认)")
        }.joinToString(" ").ifBlank { "Audio ${stream.index ?: ""}".trim() }
    }

    private fun audioExtras(stream: MediaStream): List<String> {
        val haystack = listOfNotNull(
            stream.displayTitle,
            stream.title,
            stream.channelLayout,
            stream.codec
        ).joinToString(" ").lowercase(Locale.US)
        return buildList {
            sampleRateLabel(stream.sampleRate)
                ?.takeUnless { haystack.contains(it.lowercase(Locale.US)) }
                ?.let(::add)
            bitDepthLabel(stream.bitDepth)
                ?.takeUnless { haystack.contains("${stream.bitDepth}bit") || haystack.contains("${stream.bitDepth}-bit") }
                ?.let(::add)
            bitRateLabel(stream.bitRate)
                ?.takeUnless { haystack.contains("kbps") || haystack.contains("kb/s") }
                ?.let(::add)
        }
    }

    private fun languageLabel(language: String?): String? {
        val raw = language?.trim().orEmpty()
        if (raw.isEmpty() || raw.equals("und", ignoreCase = true)) return null
        return raw.replaceFirstChar { char ->
            if (char.isLowerCase()) char.titlecase(Locale.US) else char.toString()
        }
    }

    private fun displayCodec(codec: String?): String? {
        val raw = codec?.trim().orEmpty()
        if (raw.isEmpty()) return null
        return when (raw.lowercase(Locale.US)) {
            "aac" -> "AAC"
            "mp3" -> "MP3"
            "ac3" -> "AC3"
            "eac3" -> "EAC3"
            "truehd" -> "TrueHD"
            "dts" -> "DTS"
            "dtshd", "dts-hd", "dtsma" -> "DTS-HD"
            "flac" -> "FLAC"
            "opus" -> "Opus"
            "vorbis" -> "Vorbis"
            "pcm_s16le" -> "PCM_S16LE"
            "pcm_s24le" -> "PCM_S24LE"
            "pcm_s32le" -> "PCM_S32LE"
            "subrip", "srt" -> "SUBRIP"
            "ass", "ssa" -> "ASS"
            "pgssub", "pgs", "hdmv_pgs_subtitle" -> "PGSSUB"
            "dvdsub", "dvd_subtitle" -> "DVDSUB"
            "vtt", "webvtt" -> "WEBVTT"
            else -> raw.uppercase(Locale.US)
        }
    }

    private fun channelLabel(channelCount: Int, layout: String?): String? {
        val named = layout?.trim()?.takeIf { it.isNotEmpty() }
        if (!named.isNullOrBlank()) return named
        return when (channelCount) {
            0 -> null
            1 -> "Mono"
            2 -> "stereo"
            6 -> "5.1"
            8 -> "7.1"
            else -> "$channelCount ch"
        }
    }

    private fun sampleRateLabel(sampleRate: Int?): String? {
        val rate = sampleRate?.takeIf { it > 0 } ?: return null
        return if (rate % 1000 == 0) "${rate / 1000} kHz" else "$rate Hz"
    }

    private fun bitDepthLabel(bitDepth: Int?): String? {
        val depth = bitDepth?.takeIf { it > 0 } ?: return null
        return "${depth}bit"
    }

    private fun bitRateLabel(bitRate: Int?): String? {
        val rate = bitRate?.takeIf { it > 0 } ?: return null
        return if (rate >= 1000) "${rate / 1000} kbps" else "$rate bps"
    }

    private fun normalizeLanguage(language: String?): String? {
        val raw = language?.trim()?.lowercase(Locale.US).orEmpty()
        if (raw.isEmpty() || raw == "und" || raw == "unknown") return null
        return raw
    }

    private fun normalizeTitle(title: String?): String? {
        val raw = title?.trim()?.lowercase(Locale.US)?.replace("\\s+".toRegex(), " ").orEmpty()
        return raw.ifBlank { null }
    }

    private fun normalizeCodec(codec: String?): String? {
        val raw = codec?.trim()?.lowercase(Locale.US).orEmpty()
        if (raw.isEmpty()) return null
        return raw.replace("_", "").replace("-", "")
    }

    private fun languageEquals(left: String?, right: String?): Boolean {
        val a = normalizeLanguage(left) ?: return false
        val b = normalizeLanguage(right) ?: return false
        if (a == b) return true
        val shortA = a.take(2)
        val shortB = b.take(2)
        return shortA.length >= 2 && shortA == shortB
    }
}
