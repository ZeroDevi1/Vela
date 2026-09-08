package com.vela.data.api

import com.vela.data.network.CatalogNetwork
import io.ktor.client.call.body
import io.ktor.client.request.*
import kotlinx.serialization.json.*

class BangumiApi {
    suspend fun calendar(): List<JsonObject> {
        val response = CatalogNetwork.client.get("https://api.bgm.tv/calendar") {
            header("User-Agent", "VelaCalendar/1.0 (https://github.com/ZeroDevi1/Vela)")
        }
        check(response.status.value in 200..299) { "Bangumi HTTP ${response.status.value}" }
        return response.body<JsonArray>().map { it.jsonObject }
    }
}
