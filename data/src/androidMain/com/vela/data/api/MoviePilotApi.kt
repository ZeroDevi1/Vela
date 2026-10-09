package com.vela.data.api

import com.vela.data.network.CatalogNetwork
import io.ktor.client.call.body
import io.ktor.client.request.*
import io.ktor.client.request.forms.submitForm
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import kotlinx.serialization.json.*

internal fun JsonObject.text(key: String): String? = (get(key) as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
internal fun JsonObject.number(key: String): Int? = text(key)?.toIntOrNull()
internal fun JsonObject.objects(key: String): List<JsonObject> = (get(key) as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }

/**
 * MoviePilot request failure. [status] is the HTTP status (-1 when unknown); [unauthorized] marks an expired or
 * missing token (the repository logs in again once with the saved password); [mfaRequired] means the password was
 * accepted but the account needs a two-step verification code.
 */
class MoviePilotException(
    message: String,
    val status: Int = -1,
    val unauthorized: Boolean = false,
    val mfaRequired: Boolean = false,
) : Exception(message)

/**
 * Error text from a MoviePilot error body: FastAPI's `detail` (string or `[{"msg": ...}]`), or `message` of the
 * `{"success": false, "message": ...}` wrapper newer versions return (validation details in `data[].message`).
 */
internal fun moviePilotErrorDetail(body: String): String? {
    val obj = runCatching { Json.parseToJsonElement(body) as? JsonObject }.getOrNull() ?: return null
    obj.text("detail")?.let { return it }
    (obj["detail"] as? JsonArray)?.mapNotNull { (it as? JsonObject)?.text("msg") }?.takeIf { it.isNotEmpty() }?.let { return it.joinToString("; ") }
    return obj.text("message")
}

class MoviePilotApi(private val baseUrl: String, private val token: String? = null) {
    private val client = CatalogNetwork.client
    private val root = baseUrl.trimEnd('/').removeSuffix("/api/v1") + "/api/v1"
    init {
        val uri = java.net.URI(baseUrl)
        require(uri.scheme in setOf("https", "http") && !uri.host.isNullOrBlank() && uri.userInfo == null && uri.query == null && uri.fragment == null) { "Invalid MoviePilot URL" }
    }
    suspend fun login(username: String, password: String, otp: String = ""): String {
        val response = client.submitForm("$root/login/access-token", Parameters.build {
            append("username", username); append("password", password)
            if (otp.isNotBlank()) append("otp_password", otp)
        })
        if (response.status.value !in 200..299) {
            val detail = moviePilotErrorDetail(response.bodyAsText())
            if (response.status.value == 401 || response.status.value == 403) {
                // A correct password on an account with two-step verification is also rejected with 401;
                // the server marks it with `X-MFA-Required` / "需要二次验证", so it must not read as a wrong password.
                if (response.headers["X-MFA-Required"] == "true" || detail == "需要二次验证") {
                    throw MoviePilotException("Two-step verification code required", response.status.value, mfaRequired = true)
                }
                throw MoviePilotException(detail ?: "Wrong username or password", response.status.value)
            }
            throw httpError(response)
        }
        return response.body<JsonObject>().text("access_token") ?: error("Missing MoviePilot access token")
    }
    suspend fun get(path: String, params: Map<String, String> = emptyMap()): JsonElement {
        val credential = token ?: error("MoviePilot login required")
        val response = client.get("$root/$path") {
            bearerAuth(credential)
            params.forEach { (key, value) -> parameter(key, value) }
        }
        ensureSuccess(response)
        return response.body()
    }
    suspend fun addSubscription(title: com.vela.data.model.CatalogTitle, season: Int?) {
        val credential = token ?: error("MoviePilot login required")
        val response = client.post("$root/subscribe/") {
            bearerAuth(credential)
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject {
                put("name", title.displayTitle); put("tmdbid", title.id)
                put("type", if (title.mediaType == "movie") "电影" else "电视剧")
                put("year", title.date.take(4)); if (season != null) put("season", season)
            })
        }
        ensureSuccess(response)
        val result = response.body<JsonObject>()
        check(result["success"]?.jsonPrimitive?.booleanOrNull == true) { result.text("message") ?: "MoviePilot subscription update failed" }
    }

    /** Delete by subscription row id, avoiding collisions between movie/TV TMDB namespaces. */
    suspend fun removeSubscription(id: Int) {
        require(id > 0)
        val response = client.delete("$root/subscribe/$id") { bearerAuth(token ?: error("MoviePilot login required")) }
        ensureSuccess(response)
        val result = response.body<JsonObject>()
        check(result["success"]?.jsonPrimitive?.booleanOrNull == true) { result.text("message") ?: "MoviePilot subscription update failed" }
    }

    private suspend fun ensureSuccess(response: io.ktor.client.statement.HttpResponse) {
        if (response.status.value in 200..299) return
        if (response.status.value == 401 || response.status.value == 403) {
            throw MoviePilotException("MoviePilot login expired", response.status.value, unauthorized = true)
        }
        throw httpError(response)
    }

    private suspend fun httpError(response: io.ktor.client.statement.HttpResponse): MoviePilotException {
        val detail = moviePilotErrorDetail(response.bodyAsText())
        return MoviePilotException(detail?.let { "MoviePilot HTTP ${response.status.value}: $it" } ?: "MoviePilot HTTP ${response.status.value}", response.status.value)
    }

    suspend fun subscriptions(): List<JsonObject> = get("subscribe/").jsonArray.map { it.jsonObject }
    suspend fun searchDouban(query: String): List<JsonObject> = get("media/search", mapOf("title" to query, "source" to "douban", "count" to "30")).jsonArray.map { it.jsonObject }
}
