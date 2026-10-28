package com.vela.data.peach

import com.vela.data.network.VelaJson
import io.ktor.client.HttpClient
import io.ktor.client.request.forms.FormDataContent
import io.ktor.client.request.parameter
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.Parameters
import io.ktor.http.contentType
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/**
 * Peach 请求失败。按 [kind] 区分界面上需要不同处理的几类：
 * 未登录、只允许本机、账本只读、版本冲突与其余错误。
 */
class PeachApiException(
    val status: Int,
    val kind: Kind,
    override val message: String,
    val body: JsonObject? = null
) : Exception(message) {
    enum class Kind { UNAUTHORIZED, LOCAL_ONLY, READ_ONLY, CONFLICT, NOT_FOUND, UNSUPPORTED, OTHER }

    companion object {
        /** 把 Peach 的几种错误体（`detail` / `error` / `message`）归成一条可读信息。 */
        fun from(status: Int, body: JsonObject?, rawText: String?): PeachApiException {
            val detail = body?.string("detail")
            val error = body?.string("error")
            val readable = body?.string("message") ?: detail ?: error ?: rawText?.take(200)?.takeIf { it.isNotBlank() }
            val kind = when {
                status == 401 -> Kind.UNAUTHORIZED
                status == 403 && (readable.orEmpty().contains("本机") || readable.orEmpty().contains("电脑")) -> Kind.LOCAL_ONLY
                status == 403 -> Kind.LOCAL_ONLY
                status == 409 && error == "ledger read-only" -> Kind.READ_ONLY
                status == 409 -> Kind.CONFLICT
                status == 404 -> Kind.NOT_FOUND
                status == 415 -> Kind.UNSUPPORTED
                else -> Kind.OTHER
            }
            val message = when (kind) {
                Kind.UNAUTHORIZED -> "Peach 登录已失效，请重新登录"
                Kind.LOCAL_ONLY -> readable ?: "此项需在运行 Peach 的电脑上设置"
                else -> readable ?: "Peach 请求失败（HTTP $status）"
            }
            return PeachApiException(status, kind, message, body)
        }
    }
}

/**
 * Peach `/api/…` 的薄封装：鉴权头、证书固定与超时由传入的 [HttpClient] 负责，
 * 这里只管拼请求、解析 JSON 与归类错误。所有方法失败时抛 [PeachApiException]。
 */
class PeachClient(
    private val client: HttpClient,
    val baseUrl: String,
    /** 登录要读 303 响应上的 `Set-Cookie`，这个客户端必须不跟随重定向。 */
    private val loginClient: HttpClient = client
) {
    private val root = baseUrl.trimEnd('/')

    fun absoluteUrl(path: String): String =
        if (path.startsWith("http://") || path.startsWith("https://")) path else "$root/${path.trimStart('/')}"

    suspend fun get(path: String, query: List<Pair<String, Any?>> = emptyList()): JsonObject =
        execute(HttpMethod.Get, path, query, body = null)

    suspend fun post(
        path: String,
        body: JsonObject = JsonObject(emptyMap()),
        query: List<Pair<String, Any?>> = emptyList()
    ): JsonObject = execute(HttpMethod.Post, path, query, body)

    suspend fun put(path: String, body: JsonObject, query: List<Pair<String, Any?>> = emptyList()): JsonObject =
        execute(HttpMethod.Put, path, query, body)

    /** `GET` 返回原始文本（M3U、WebVTT 等）。 */
    suspend fun getText(path: String, query: List<Pair<String, Any?>> = emptyList()): String {
        val response = client.request(absoluteUrl(path)) {
            method = HttpMethod.Get
            query.forEach { (name, value) -> value?.let { parameter(name, it) } }
        }
        val text = response.bodyAsText()
        if (response.status.value !in 200..299) {
            throw PeachApiException.from(response.status.value, parseObject(text), text)
        }
        return text
    }

    /**
     * 访问密码登录：`POST /login` 表单，成功时服务端 303 并下发会话 Cookie。
     * 返回 `Set-Cookie` 里的会话值；开放访问模式下没有 Cookie，返回 null。
     */
    suspend fun loginWithPassword(password: String, days: Int = 30): LoginOutcome {
        val response: HttpResponse = loginClient.request("$root/login") {
            method = HttpMethod.Post
            setBody(FormDataContent(Parameters.build {
                append("token", password)
                append("days", days.toString())
                append("next", "/")
            }))
        }
        val status = response.status.value
        val cookie = PeachCredential.sessionFromSetCookie(response.headers.getAll("Set-Cookie").orEmpty())
        return when {
            cookie != null -> LoginOutcome.Session(cookie)
            status in 200..399 -> LoginOutcome.NoSession
            status == 401 -> LoginOutcome.Rejected("访问密码不正确")
            status == 429 -> LoginOutcome.Rejected("尝试次数较多，请一分钟后重试")
            else -> {
                val text = runCatching { response.bodyAsText() }.getOrNull()
                LoginOutcome.Rejected(PeachApiException.from(status, parseObject(text), null).message)
            }
        }
    }

    sealed interface LoginOutcome {
        data class Session(val cookie: String) : LoginOutcome
        data object NoSession : LoginOutcome
        data class Rejected(val reason: String) : LoginOutcome
    }

    private suspend fun execute(
        method: HttpMethod,
        path: String,
        query: List<Pair<String, Any?>>,
        body: JsonObject?
    ): JsonObject {
        val response = client.request(absoluteUrl(path)) {
            this.method = method
            query.forEach { (name, value) -> value?.let { parameter(name, it) } }
            if (body != null) {
                contentType(ContentType.Application.Json)
                setBody(VelaJson.encodeToString(JsonObject.serializer(), body))
            }
        }
        val text = response.bodyAsText()
        val parsed = parseObject(text)
        if (response.status.value !in 200..299) {
            throw PeachApiException.from(response.status.value, parsed, text)
        }
        // `/api/item` 与分卷、版次接口找不到时仍回 200，只在体里写 `error`。
        val inlineError = parsed?.string("error")
        if (inlineError != null && parsed.size <= 2) {
            throw PeachApiException(404, PeachApiException.Kind.NOT_FOUND, inlineError, parsed)
        }
        return parsed ?: JsonObject(emptyMap())
    }

    private fun parseObject(text: String?): JsonObject? {
        if (text.isNullOrBlank()) return null
        return runCatching { VelaJson.parseToJsonElement(text) }.getOrNull() as? JsonObject
    }
}

/** 构造请求体：值为 null 的键不写入。 */
fun peachBody(vararg pairs: Pair<String, Any?>): JsonObject = buildJsonObject {
    pairs.forEach { (key, value) ->
        val element = value.toJsonElement() ?: return@forEach
        put(key, element)
    }
}

internal fun Any?.toJsonElement(): JsonElement? = when (this) {
    null -> null
    is JsonElement -> this
    is String -> JsonPrimitive(this)
    is Number -> JsonPrimitive(this)
    is Boolean -> JsonPrimitive(this)
    is Map<*, *> -> JsonObject(entries.mapNotNull { (k, v) -> v.toJsonElement()?.let { k.toString() to it } }.toMap())
    is Iterable<*> -> kotlinx.serialization.json.JsonArray(mapNotNull { it.toJsonElement() ?: JsonNull })
    else -> JsonPrimitive(toString())
}
