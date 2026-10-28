package com.vela.data.peach

import com.vela.data.model.MediaLibraryQuery
import com.vela.data.model.PlaybackProgressRequest
import com.vela.data.model.PlaybackStartRequest
import com.vela.data.model.PlaybackStoppedRequest
import com.vela.data.network.VelaJson
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.Test

class PeachMediaServerApiTest {

    private data class Call(val method: String, val path: String, val query: Map<String, String>, val body: String?)

    private fun api(calls: MutableList<Call>, respond: (Call) -> String): PeachMediaServerApi {
        val engine = MockEngine { request ->
            val call = Call(
                method = request.method.value,
                path = request.url.encodedPath,
                query = request.url.parameters.entries().associate { it.key to it.value.first() },
                body = (request.body as? TextContent)?.text
            )
            calls += call
            respond(respond(call), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        return PeachMediaServerApi(PeachClient(HttpClient(engine), "https://peach.local/"))
    }

    @Test
    fun libraryQueryTranslatesSortFilterAndPaging() = runTest {
        val calls = mutableListOf<Call>()
        val api = api(calls) { """{"total": 1, "items": [{"id": 1, "name": "a.mp4"}]}""" }
        val result = api.getMediaLibraryItems(
            PeachIds.USER_ID,
            MediaLibraryQuery(
                parentId = "lib:main",
                includeItemTypes = "Movie",
                startIndex = 60,
                limit = 30,
                sortBy = "PremiereDate,SortName",
                sortOrder = "Ascending",
                filters = "IsFavorite",
                genreIds = "tagname:剧情"
            )
        )
        val query = calls.single().query
        assertEquals("main", query["library"])
        assertEquals("release", query["sort"])
        assertEquals("asc", query["dir"])
        assertEquals("later", query["state"])
        assertEquals("剧情", query["tag"])
        assertEquals("60", query["offset"])
        assertEquals("30", query["limit"])
        assertEquals(1, result.body()?.totalRecordCount)
    }

    @Test
    fun nonVideoItemTypesReturnEmptyWithoutRequest() = runTest {
        val calls = mutableListOf<Call>()
        val api = api(calls) { "{}" }
        val result = api.getLatestItems(PeachIds.USER_ID, includeItemTypes = "Series")
        assertTrue(result.body()!!.isEmpty())
        assertTrue(calls.isEmpty())
    }

    @Test
    fun favoriteToggleIsSkippedWhenAlreadyInState() = runTest {
        val calls = mutableListOf<Call>()
        val api = api(calls) { call ->
            if (call.path == "/api/item") """{"id": 5, "watch_later": 1}""" else """{"ok": true, "watch_later": false}"""
        }
        api.markAsFavorite(PeachIds.USER_ID, "5")
        assertEquals(listOf("/api/item"), calls.map { it.path })
        api.unmarkAsFavorite(PeachIds.USER_ID, "5")
        assertEquals(listOf("/api/item", "/api/item", "/api/watch-later"), calls.map { it.path })
    }

    @Test
    fun playbackReportsPlayActivityAndCancelsStream() = runTest {
        val calls = mutableListOf<Call>()
        val api = api(calls) { call ->
            if (call.path == "/api/item") """{"id": 5, "duration": 100}""" else """{"ok": true}"""
        }
        api.reportPlaybackStart(PlaybackStartRequest(itemId = "5", playSessionId = "s1", positionTicks = 0L))
        api.reportPlaybackProgress(PlaybackProgressRequest(itemId = "5", playSessionId = "s1", positionTicks = 0L))
        api.reportPlaybackStopped(PlaybackStoppedRequest(itemId = "5", playSessionId = "s1", positionTicks = 97L * 10_000_000))
        assertEquals(
            listOf("/api/item", "/api/play", "/api/activity", "/api/stream-cancel", "/api/activity"),
            calls.map { it.path }
        )
        assertEquals("s1", calls[3].query["session"])
        val final = VelaJson.parseToJsonElement(calls.last().body!!).jsonObject
        assertEquals("true", final["ended"]?.jsonPrimitive?.content)
        assertEquals("100.0", final["duration"]?.jsonPrimitive?.content)
    }

    @Test
    fun playbackInfoListsPlayableSidecarSubtitles() = runTest {
        val calls = mutableListOf<Call>()
        val api = api(calls) { call ->
            when (call.path) {
                "/api/item" -> """{"id": 5, "name": "x.mkv", "duration": 10, "width": 1920, "height": 1080}"""
                else -> """{"id": 5, "subtitles": [
                    {"id": "sidecar:a", "index": 0, "label": "中文", "playable": true, "src": "/api/assets/5/subtitles/0?track=a"},
                    {"id": "sidecar:b", "index": 1, "label": "PGS", "playable": false, "note": "图形字幕", "src": "/api/assets/5/subtitles/1?track=b"}]}"""
            }
        }
        val info = api.getPlaybackInfoPost("5", com.vela.data.model.PlaybackInfoRequest(userId = PeachIds.USER_ID)).body()!!
        val source = info.mediaSources!!.single()
        assertEquals("mkv", source.container)
        val subtitles = source.mediaStreams!!.filter { it.type == "Subtitle" }
        assertEquals(1, subtitles.size)
        assertEquals("https://peach.local/api/assets/5/subtitles/0?track=a", subtitles.single().deliveryUrl)
        assertTrue(!info.playSessionId.isNullOrBlank())
    }

    @Test
    fun playbackInfoRestoresRememberedSubtitleAndSavedTracks() = runTest {
        val calls = mutableListOf<Call>()
        val api = api(calls) { call ->
            when (call.path) {
                "/api/item" -> """{"id": 5, "name": "x.mp4", "duration": 10}"""
                "/api/subtitles/memory" -> """{"selected": "subtitlecat:9:0",
                    "tracks": [{"key": "subtitlecat:9:0", "name": "zh.srt", "src": "/api/subtitles/memory/track/abc.vtt?video=item:5"}]}"""
                else -> """{"id": 5, "subtitles": [
                    {"id": "sidecar:k", "legacy_id": "sidecar:3", "index": 0, "label": "日本語", "playable": true, "src": "/api/assets/5/subtitles/0?track=k"}]}"""
            }
        }
        val source = api.getPlaybackInfoPost("5", com.vela.data.model.PlaybackInfoRequest(userId = PeachIds.USER_ID))
            .body()!!.mediaSources!!.single()
        val subtitles = source.mediaStreams!!.filter { it.type == "Subtitle" }
        assertEquals(listOf("sidecar:3", "subtitlecat:9:0"), subtitles.map { it.comment })
        assertEquals(subtitles[1].index, source.defaultSubtitleStreamIndex)
        assertEquals("item:5", calls.first { it.path == "/api/subtitles/memory" }.query["video"])
    }

    @Test
    fun subtitleSelectionSkipsUnrememberableSidecarKeys() = runTest {
        val calls = mutableListOf<Call>()
        val api = api(calls) { "{}" }
        api.saveSubtitleSelection(5, "sidecar:abcdef")
        assertTrue(calls.isEmpty())
        api.saveSubtitleSelection(5, null)
        val body = VelaJson.parseToJsonElement(calls.single().body!!).jsonObject
        assertEquals("null", body["selected"].toString())
        assertEquals("PUT", calls.single().method)
    }
}

