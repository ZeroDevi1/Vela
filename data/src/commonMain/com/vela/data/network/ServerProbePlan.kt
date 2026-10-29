package com.vela.data.network

/** Explicit selection must not fall through to another protocol; null/UNKNOWN retain discovery. */
internal data class ServerProbePlan(val mediaUrls: List<String>, val probePeach: Boolean)

internal fun serverProbePlan(serverUrl: String, serverType: ServerType?): ServerProbePlan {
    val selected = serverType?.takeUnless { it == ServerType.UNKNOWN }
    return ServerProbePlan(
        mediaUrls = if (selected == ServerType.PEACH) emptyList() else buildBaseUrlCandidates(serverUrl),
        probePeach = selected == null || selected == ServerType.PEACH
    )
}
