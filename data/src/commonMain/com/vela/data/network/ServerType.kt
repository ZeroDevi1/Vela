package com.vela.data.network

enum class ServerType {
    UNKNOWN,
    JELLYFIN,
    EMBY
}

/** Jellyfin 10.8+ 使用 ApiKey；Emby 保留其原有参数。 */
val ServerType?.tokenQueryParameter: String
    get() = if (this == ServerType.EMBY) "api_key" else "ApiKey"
