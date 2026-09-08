package com.vela.data.network

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

/** Public metadata services never inherit a media server authorization header. */
object CatalogNetwork {
    val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }
    val client by lazy {
        HttpClient(OkHttp) {
            followRedirects = false
            install(ContentNegotiation) { json(json) }
            install(HttpTimeout) { requestTimeoutMillis = 20_000; connectTimeoutMillis = 10_000 }
        }
    }
    val tmdb by lazy { com.vela.data.api.TmdbApi(client) }
}
