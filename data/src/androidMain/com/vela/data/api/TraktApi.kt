package com.vela.data.api

import com.vela.data.network.CatalogNetwork
import io.ktor.client.call.body
import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.serialization.json.*

class TraktHttpException(val status: Int, val retryAfterSeconds: Long? = null) : Exception("Trakt HTTP $status")

class TraktApi(private val clientId: String) {
    suspend fun request(path: String, token: String? = null, payload: JsonObject? = null): JsonElement {
        val response = CatalogNetwork.client.request("https://api.trakt.tv/$path") {
            method = if (payload == null) HttpMethod.Get else HttpMethod.Post
            header("trakt-api-version", "2")
            header("trakt-api-key", clientId)
            if (token != null) bearerAuth(token)
            if (payload != null) { contentType(ContentType.Application.Json); setBody(payload) }
        }
        if (response.status.value !in 200..299) throw TraktHttpException(response.status.value, response.headers["Retry-After"]?.toLongOrNull())
        return if (response.status.value == 204) JsonNull else response.body()
    }
}
