package com.vela.data.api

import com.vela.data.model.AuthHeaderDto
import com.vela.data.network.ServerType
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class Jellyfin12CompatibilityTest {
    @Test fun streamingUrlsUseDialectAndPreserveReverseProxyPath() = runTest {
        val client = HttpClient(MockEngine { error("URL construction must not send a request") })
        try {
            for ((type, parameter) in listOf(ServerType.JELLYFIN to "ApiKey", ServerType.EMBY to "api_key")) {
                val api = MediaServerApiClient(client, "https://nas.example/media", type)
                val url = Url(api.getVideoStreamUrl("item", true, "source", "device", "token+/="))
                assertEquals("/media/Videos/item/stream", url.encodedPath)
                assertEquals("token+/=", url.parameters[parameter])
                assertNull(url.parameters[if (parameter == "ApiKey") "api_key" else "ApiKey"])
                assertEquals("source", url.parameters["mediaSourceId"])
                assertEquals("true", url.parameters["static"])
            }
        } finally { client.close() }
    }

    @Test fun quickConnectUsesPostAndBrowseKeepsExplicitRecursion() = runTest {
        val paths = mutableListOf<String>()
        val client = HttpClient(MockEngine { request ->
            paths += request.url.encodedPath
            when (request.url.encodedPath) {
                "/media/QuickConnect/Initiate" -> assertEquals(HttpMethod.Post, request.method)
                else -> {
                    assertEquals(HttpMethod.Get, request.method)
                    assertEquals("Movie", request.url.parameters["includeItemTypes"])
                    assertEquals(if (paths.size == 2) "true" else "false", request.url.parameters["recursive"])
                }
            }
            // 错误响应避免引入 JSON 引擎，仍检查真实客户端发出的请求契约。
            respond("unavailable", HttpStatusCode.ServiceUnavailable)
        }) { defaultRequest { url("https://nas.example/media/") } }
        try {
            val api: MediaServerApi = MediaServerApiClient(client, "https://nas.example/media", ServerType.JELLYFIN)
            api.initiateQuickConnect()
            api.getUserItems("user", includeItemTypes = "Movie", recursive = true)
            api.getUserItems("user", includeItemTypes = "Movie", recursive = false)
            assertEquals(3, paths.size)
        } finally { client.close() }
    }

    @Test fun jellyfinAuthenticationUsesMediaBrowserToken() {
        val header = AuthHeaderDto.fromServerType(ServerType.JELLYFIN, "device", "1", "token").asHeaderValue()
        assertTrue(header.startsWith("MediaBrowser "))
        assertTrue(header.contains("Token=\"token\""))
    }
}
