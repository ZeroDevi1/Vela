package com.vela.data.api

import com.vela.data.network.CatalogNetwork
import io.ktor.client.call.body
import io.ktor.client.request.*
import io.ktor.client.request.forms.submitForm
import io.ktor.http.*
import kotlinx.serialization.json.*

internal fun JsonObject.text(key: String): String? = (get(key) as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
internal fun JsonObject.number(key: String): Int? = text(key)?.toIntOrNull()
internal fun JsonObject.objects(key: String): List<JsonObject> = (get(key) as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }

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
        check(response.status.value in 200..299) { "MoviePilot login HTTP ${response.status.value}" }
        return response.body<JsonObject>().text("access_token") ?: error("Missing MoviePilot access token")
    }
    suspend fun get(path: String, params: Map<String, String> = emptyMap()): JsonElement {
        val credential = token ?: error("MoviePilot login required")
        val response = client.get("$root/$path") {
            bearerAuth(credential)
            params.forEach { (key, value) -> parameter(key, value) }
        }
        check(response.status.value in 200..299) { "MoviePilot HTTP ${response.status.value}" }
        return response.body()
    }
    suspend fun setSubscription(title: com.vela.data.model.CatalogTitle, season: Int?, remove: Boolean) {
        val credential = token ?: error("MoviePilot login required")
        val response = client.request(if (remove) "$root/subscribe/media/${title.id}" else "$root/subscribe/") {
            method = if (remove) HttpMethod.Delete else HttpMethod.Post
            bearerAuth(credential)
            if (remove) {
                if (season != null) parameter("season", season)
            } else {
                contentType(ContentType.Application.Json)
                setBody(buildJsonObject {
                    put("name", title.displayTitle); put("tmdbid", title.id)
                    put("type", if (title.mediaType == "movie") "电影" else "电视剧")
                    put("year", title.date.take(4)); if (season != null) put("season", season)
                })
            }
        }
        check(response.status.value in 200..299) { "MoviePilot HTTP ${response.status.value}" }
        val result = response.body<JsonObject>()
        check(result["success"]?.jsonPrimitive?.booleanOrNull == true) { result.text("message") ?: "MoviePilot subscription update failed" }
    }

    suspend fun subscriptions(): List<JsonObject> = get("subscribe/").jsonArray.map { it.jsonObject }
    suspend fun searchDouban(query: String): List<JsonObject> = get("media/search", mapOf("title" to query, "source" to "douban", "count" to "30")).jsonArray.map { it.jsonObject }
}
