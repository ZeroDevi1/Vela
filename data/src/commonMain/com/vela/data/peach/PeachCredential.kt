package com.vela.data.peach

/**
 * Peach 的登录凭据。Peach 是单用户服务，没有账号体系，只有三种放行方式：
 * 访问口令（`X-Token` 请求头）、访问密码换来的会话 Cookie，以及未设口令时的开放访问。
 *
 * 凭据按 Saved Server 存在 SecureSessionStore 的 token 位里，用 [encode] 后的字符串保存，
 * 只放进请求头，不拼进 URL。
 */
sealed interface PeachCredential {
    data class Token(val value: String) : PeachCredential
    data class Session(val cookie: String) : PeachCredential
    data object Open : PeachCredential

    fun encode(): String = when (this) {
        is Token -> "$TOKEN_PREFIX$value"
        is Session -> "$SESSION_PREFIX$cookie"
        Open -> OPEN_VALUE
    }

    /** API、图片与播放器统一使用的鉴权请求头。 */
    fun headers(): Map<String, String> = when (this) {
        is Token -> mapOf(TOKEN_HEADER to value)
        is Session -> mapOf("Cookie" to "$SESSION_COOKIE=$cookie")
        Open -> emptyMap()
    }

    companion object {
        const val TOKEN_HEADER = "X-Token"
        const val SESSION_COOKIE = "peach_session"
        private const val TOKEN_PREFIX = "peach-token:"
        private const val SESSION_PREFIX = "peach-session:"
        private const val OPEN_VALUE = "peach-open"

        fun decode(raw: String?): PeachCredential? {
            val value = raw?.trim().orEmpty()
            return when {
                value == OPEN_VALUE -> Open
                value.startsWith(TOKEN_PREFIX) -> value.removePrefix(TOKEN_PREFIX)
                    .takeIf { it.isNotBlank() }?.let(::Token)
                value.startsWith(SESSION_PREFIX) -> value.removePrefix(SESSION_PREFIX)
                    .takeIf { it.isNotBlank() }?.let(::Session)
                else -> null
            }
        }

        /** 从 `Set-Cookie` 头里取出会话值；没有会话 Cookie 时返回 null。 */
        fun sessionFromSetCookie(headers: List<String>): String? {
            return headers.asSequence()
                .map { it.substringBefore(';').trim() }
                .firstOrNull { it.startsWith("$SESSION_COOKIE=") }
                ?.substringAfter('=')
                ?.takeIf { it.isNotBlank() }
        }
    }
}
