package com.vela.data.network

enum class ServerType {
    UNKNOWN,
    JELLYFIN,
    EMBY,
    PEACH
}

val ServerType?.isPeach: Boolean
    get() = this == ServerType.PEACH

fun serverTypeOf(raw: String?): ServerType? =
    raw?.takeIf { it.isNotBlank() }?.let { value -> ServerType.entries.firstOrNull { it.name.equals(value, ignoreCase = true) } }

/** Jellyfin 10.8+ 使用 ApiKey；Emby 保留其原有参数。 */
val ServerType?.tokenQueryParameter: String
    get() = if (this == ServerType.EMBY) "api_key" else "ApiKey"
