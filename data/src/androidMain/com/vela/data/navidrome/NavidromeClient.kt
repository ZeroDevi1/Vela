package com.vela.data.navidrome

import com.vela.data.network.ServerTrust.applyServerTrust
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.*
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal class NavidromeClient(
    private val baseUrl: HttpUrl,
    private val username: String,
    private val secret: String,
    private val useApiKey: Boolean
) {
    private val streamEndpoint = endpoint("stream")

    suspend fun login(): String {
        val type = request("ping").string("type")
        if (type != null && !type.equals("navidrome", ignoreCase = true)) {
            throw IOException("此地址不是 Navidrome 服务器")
        }
        return if (useApiKey) {
            request("tokenInfo").obj("tokenInfo")?.string("username")?.takeIf { it.isNotBlank() }
                ?: throw IOException("Navidrome 未返回 API 密钥所属账号")
        } else {
            request("getUser", "username" to username).obj("user")?.string("username")?.takeIf { it.isNotBlank() }
                ?: throw IOException("Navidrome 未返回账号信息")
        }
    }

    suspend fun request(method: String, vararg params: Pair<String, Any>): JsonObject {
        val url = endpoint(method, *params)
        val result = try {
            json.parseToJsonElement(fetch(url, MAX_JSON_BYTES, "application/json").decodeToString()) as? JsonObject
        } catch (_: SerializationException) {
            null
        }
        val payload = result?.obj("subsonic-response")
            ?: throw IOException("Navidrome 未返回有效的 Subsonic 响应，请检查地址和反代路径")
        return when (payload.string("status")) {
            "ok" -> payload
            "failed" -> throw NavidromeApiException(payload.number("error", "code") ?: 0)
            else -> throw IOException("Navidrome 未返回有效的 Subsonic 响应，请检查地址和反代路径")
        }
    }

    fun streamUrl(id: String, compatible: Boolean): String =
        endpoint("stream", "id" to id, "format" to if (compatible) "mp3" else "raw")
            .newBuilder().apply { if (compatible) addQueryParameter("maxBitRate", "192") }.build().toString()

    fun streamRequest(request: Request): Request {
        val url = request.url
        if (url.scheme != streamEndpoint.scheme || url.host != streamEndpoint.host ||
            url.port != streamEndpoint.port || url.encodedPath != streamEndpoint.encodedPath
        ) throw IOException("Navidrome 播放地址与当前账户不匹配")
        return request.newBuilder().url(authenticated(url)).build()
    }

    suspend fun cover(id: String, size: Int): ByteArray =
        fetch(endpoint("getCoverArt", "id" to id, "size" to size), MAX_COVER_BYTES, "image/*")

    private fun endpoint(method: String, vararg params: Pair<String, Any>): HttpUrl =
        baseUrl.newBuilder().addPathSegments("rest/$method.view").apply {
            params.forEach { (key, value) -> addQueryParameter(key, value.toString()) }
        }.build()

    private fun authenticated(url: HttpUrl): HttpUrl = url.newBuilder().apply {
        addQueryParameter("v", "1.16.1")
        addQueryParameter("c", "Vela")
        addQueryParameter("f", "json")
        if (useApiKey) {
            addQueryParameter("apiKey", secret)
        } else {
            val salt = UUID.randomUUID().toString().replace("-", "")
            val token = MessageDigest.getInstance("MD5").digest((secret + salt).encodeToByteArray())
                .joinToString("") { "%02x".format(it) }
            addQueryParameter("u", username)
            addQueryParameter("s", salt)
            addQueryParameter("t", token)
        }
    }.build()

    private suspend fun fetch(url: HttpUrl, limit: Long, accept: String): ByteArray =
        suspendCancellableCoroutine { continuation ->
            val call = http.newCall(Request.Builder().url(authenticated(url)).header("Accept", accept).build())
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    // OkHttp 的异常可能含签名后的 URL。
                    continuation.resumeWithException(IOException("无法连接 Navidrome，请检查网络和服务器地址"))
                }

                override fun onResponse(call: Call, response: Response) {
                    val result = runCatching {
                        response.use {
                            if (!it.isSuccessful) throw NavidromeHttpException(it.code)
                            val bytes = try {
                                val source = it.body.source()
                                if (source.request(limit + 1)) null else source.readByteArray()
                            } catch (_: IOException) {
                                throw IOException("读取 Navidrome 响应失败")
                            }
                            bytes ?: throw IOException("Navidrome 响应过大")
                        }
                    }
                    continuation.resumeWith(result)
                }
            })
        }

    companion object {
        private const val MAX_JSON_BYTES = 32L * 1024 * 1024
        private const val MAX_COVER_BYTES = 4L * 1024 * 1024
        private val json = Json
        private val http = OkHttpClient.Builder().applyServerTrust()
            .followRedirects(false).followSslRedirects(false)
            .callTimeout(20, TimeUnit.SECONDS).build()

        fun address(input: String): HttpUrl {
            val trimmed = input.trim()
            val value = if ("://" in trimmed) trimmed else "https://$trimmed"
            val parsed = value.toHttpUrlOrNull()?.takeIf {
                it.encodedUsername.isEmpty() && it.encodedPassword.isEmpty() && it.query == null
            } ?: throw IOException("请输入有效的 Navidrome 地址")
            val webPath = parsed.encodedPath.trimEnd('/')
            val fragment = parsed.fragment
            val url = if (fragment != null && webPath.endsWith("/app") && fragment.startsWith("/")) {
                parsed.newBuilder().encodedPath(webPath.removeSuffix("/app") + "/").fragment(null).build()
            } else {
                if (fragment != null) throw IOException("请输入有效的 Navidrome 地址")
                parsed
            }
            // 保留反代子路径，仅移除末尾斜杠。
            return checkNotNull(url.toString().trimEnd('/').toHttpUrlOrNull())
        }
    }
}

internal class NavidromeApiException(val code: Int) : IOException(when (code) {
    in 40..44 -> "Navidrome 无法验证凭据，请检查账号、密码或 API 密钥"
    50 -> "当前 Navidrome 账号没有此操作权限"
    70 -> "Navidrome 未找到内容或不支持此功能"
    else -> "Navidrome 请求失败（Subsonic $code）"
})

internal class NavidromeHttpException(val code: Int) : IOException(when (code) {
    401, 403 -> "Navidrome 拒绝访问，请检查凭据与权限"
    in 300..399 -> "Navidrome 地址发生重定向，请填写最终访问地址"
    else -> "Navidrome 请求失败（HTTP $code）"
})

internal fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject
internal fun JsonObject.rows(key: String): List<JsonObject> = (this[key] as? JsonArray).orEmpty().filterIsInstance<JsonObject>()
internal fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
internal fun JsonObject.number(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull
private fun JsonObject.number(group: String, key: String): Int? = obj(group)?.number(key)
