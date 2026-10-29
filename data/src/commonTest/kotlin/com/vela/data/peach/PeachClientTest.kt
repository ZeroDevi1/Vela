package com.vela.data.peach

import com.vela.data.network.VelaJson
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class PeachClientTest {

    private fun client(status: HttpStatusCode, body: String, onRequest: (String, String?) -> Unit = { _, _ -> }): PeachClient {
        val engine = MockEngine { request ->
            onRequest(request.url.toString(), (request.body as? TextContent)?.text)
            respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        return PeachClient(HttpClient(engine), "https://peach.local/")
    }

    @Test
    fun concurrentCatalogReadsShareOneRequestButRefreshIsFresh() = runTest {
        val release = CompletableDeferred<Unit>()
        var requests = 0
        val requestsMutex = Mutex()
        val engine = MockEngine {
            requestsMutex.withLock { requests++ }
            release.await()
            respond("{\"items\": []}", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val peach = PeachClient(HttpClient(engine), "https://peach.local")
        val readers = List(4) { async { peach.get("/api/libraries") } }
        runCurrent()
        release.complete(Unit)
        readers.awaitAll()
        assertEquals(1, requests)
        peach.get("/api/libraries")
        assertEquals(2, requests)
    }

    @Test
    fun differentPagesAndRandomListsDoNotShareRequests() = runTest {
        var requests = 0
        val requestsMutex = Mutex()
        val engine = MockEngine {
            requestsMutex.withLock { requests++ }
            respond("{}", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val peach = PeachClient(HttpClient(engine), "https://peach.local")
        listOf(
            async { peach.get("/api/items", listOf("offset" to 0)) },
            async { peach.get("/api/items", listOf("offset" to 60)) },
            async { peach.get("/api/items", listOf("sort" to "rand")) },
            async { peach.get("/api/items", listOf("sort" to "rand")) },
            async { peach.get("/api/feeds/check") },
            async { peach.get("/api/feeds/check") }
        ).awaitAll()
        assertEquals(6, requests)
    }

    @Test
    fun cancelledOwnerDoesNotCancelOtherReaders() = runTest {
        val release = CompletableDeferred<Unit>()
        val started = CompletableDeferred<Unit>()
        var requests = 0
        val requestsMutex = Mutex()
        val engine = MockEngine {
            requestsMutex.withLock { requests++ }
            started.complete(Unit)
            release.await()
            respond("{}", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val peach = PeachClient(HttpClient(engine), "https://peach.local")
        val owner = async { peach.get("/api/libraries") }
        started.await()
        val waiter = async { peach.get("/api/libraries") }
        runCurrent()
        owner.cancelAndJoin()
        release.complete(Unit)
        waiter.await()
        assertEquals(2, requests)
    }

    @Test
    fun successfulWritePreventsJoiningAnOlderRead() = runTest {
        val release = CompletableDeferred<Unit>()
        var reads = 0
        val readsMutex = Mutex()
        val started = CompletableDeferred<Unit>()
        val engine = MockEngine { request ->
            if (request.method.value == "GET") {
                readsMutex.withLock { reads++ }
                started.complete(Unit)
                release.await()
            }
            respond("{}", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val peach = PeachClient(HttpClient(engine), "https://peach.local")
        val oldRead = async { peach.get("/api/item", listOf("id" to 1)) }
        started.await()
        peach.post("/api/feedback", peachBody("id" to 1, "kind" to "seen"))
        val freshRead = async { peach.get("/api/item", listOf("id" to 1)) }
        runCurrent()
        release.complete(Unit)
        freshRead.await()
        oldRead.await()
        assertEquals(2, reads)
    }

    @Test
    fun cancellingWaiterLeavesOwnerRunning() = runTest {
        val release = CompletableDeferred<Unit>()
        val started = CompletableDeferred<Unit>()
        var requests = 0
        val engine = MockEngine {
            requests++
            started.complete(Unit)
            release.await()
            respond("{}", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val peach = PeachClient(HttpClient(engine), "https://peach.local")
        val owner = async { peach.get("/api/libraries") }
        started.await()
        val waiter = async { peach.get("/api/libraries") }
        runCurrent()
        waiter.cancelAndJoin()
        release.complete(Unit)
        owner.await()
        assertEquals(1, requests)
    }

    @Test
    fun separateSessionsDoNotShareReads() = runTest {
        val release = CompletableDeferred<Unit>()
        fun session(value: Int): PeachClient {
            val engine = MockEngine {
                release.await()
                respond("{\"session\": $value}", HttpStatusCode.OK,
                    headersOf(HttpHeaders.ContentType, "application/json"))
            }
            return PeachClient(HttpClient(engine), "https://peach.local")
        }
        val first = session(1)
        val second = session(2)
        val readers = listOf(
            async { first.get("/api/libraries").string("session") },
            async { second.get("/api/libraries").string("session") }
        )
        runCurrent()
        release.complete(Unit)
        assertEquals(listOf("1", "2"), readers.awaitAll())
    }

    @Test
    fun failedReadIsSharedButNotCached() = runTest {
        val release = CompletableDeferred<Unit>()
        var requests = 0
        val requestsMutex = Mutex()
        val engine = MockEngine {
            val attempt = requestsMutex.withLock { ++requests }
            release.await()
            respond("{}", if (attempt == 1) HttpStatusCode.ServiceUnavailable else HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val peach = PeachClient(HttpClient(engine), "https://peach.local")
        val readers = List(3) {
            async { assertFailsWith<PeachApiException> { peach.get("/api/libraries") } }
        }
        runCurrent()
        release.complete(Unit)
        assertTrue(readers.awaitAll().all { it.status == 503 })
        assertEquals(1, requests)
        peach.get("/api/libraries")
        assertEquals(2, requests)
    }

    @Test
    fun classifiesUnauthorized() = runTest {
        val error = assertFailsWith<PeachApiException> {
            client(HttpStatusCode.Unauthorized, """{"error":"unauthorized"}""").get("/api/items")
        }
        assertEquals(PeachApiException.Kind.UNAUTHORIZED, error.kind)
    }

    @Test
    fun classifiesLocalOnlyConfiguration() = runTest {
        val error = assertFailsWith<PeachApiException> {
            client(HttpStatusCode.Forbidden, """{"error":"请在运行 Peach 的电脑上打开配置"}""").get("/api/configuration")
        }
        assertEquals(PeachApiException.Kind.LOCAL_ONLY, error.kind)
        assertEquals("请在运行 Peach 的电脑上打开配置", error.message)
    }

    @Test
    fun classifiesReadOnlyLedgerWithUserMessage() = runTest {
        val error = assertFailsWith<PeachApiException> {
            client(
                HttpStatusCode.Conflict,
                """{"error":"ledger read-only","detail":"replica","message":"另一台电脑正在写入"}"""
            ).post("/api/feedback", peachBody("id" to 1, "kind" to "seen"))
        }
        assertEquals(PeachApiException.Kind.READ_ONLY, error.kind)
        assertEquals("另一台电脑正在写入", error.message)
    }

    @Test
    fun inlineErrorOnSuccessIsNotFound() = runTest {
        val error = assertFailsWith<PeachApiException> {
            client(HttpStatusCode.OK, """{"error":"not found"}""").get("/api/item", listOf("id" to 9))
        }
        assertEquals(PeachApiException.Kind.NOT_FOUND, error.kind)
    }

    @Test
    fun sendsQueryAndJsonBody() = runTest {
        var url: String? = null
        var body: String? = null
        client(HttpStatusCode.OK, """{"ok":true}""") { requestUrl, requestBody ->
            url = requestUrl
            body = requestBody
        }.post("/api/downloads", peachBody("magnet" to "magnet:?xt=1", "provider" to "pikpak", "code" to null, "origin" to "vela"))
        assertEquals("https://peach.local/api/downloads", url)
        val sent = VelaJson.parseToJsonElement(body!!).jsonObject
        assertEquals(setOf("magnet", "provider", "origin"), sent.keys)
    }

    @Test
    fun credentialEncodingRoundTrips() {
        val token = PeachCredential.Token("abc")
        assertEquals(token, PeachCredential.decode(token.encode()))
        assertEquals(mapOf("X-Token" to "abc"), token.headers())
        val session = PeachCredential.Session("s1")
        assertEquals(session, PeachCredential.decode(session.encode()))
        assertEquals(mapOf("Cookie" to "peach_session=s1"), session.headers())
        assertEquals(PeachCredential.Open, PeachCredential.decode(PeachCredential.Open.encode()))
        assertNull(PeachCredential.decode("plain-jellyfin-token"))
        assertEquals(
            "v",
            PeachCredential.sessionFromSetCookie(listOf("other=1; Path=/", "peach_session=v; HttpOnly; Path=/"))
        )
    }

    @Test
    fun webEntryAddsTokenOnlyForTokenLogin() {
        assertEquals(
            "https://peach.local/configuration?t=a%2Bb",
            PeachWebEntry("https://peach.local", "/configuration", PeachCredential.Token("a+b")).initialUrl()
        )
        assertEquals(
            "https://peach.local/",
            PeachWebEntry("https://peach.local", "/", PeachCredential.Session("x")).initialUrl()
        )
    }

    @Test
    fun parsesDownloadsAndChannels() {
        val downloads = PeachDownloads.parse(
            VelaJson.parseToJsonElement(
                """
                {"available": true, "providers": [
                   {"key": "pikpak", "label": "PikPak", "configured": true, "can_cancel": true},
                   {"key": "115", "label": "115", "configured": false}],
                 "problems": [], "has_more": false, "next_before_id": null,
                 "tasks": [{"id": 3, "display_code": "ABC-1", "display_title": "", "state": "remote_running",
                            "state_label": "下载中", "progress": 0.4, "postprocess_state": "not_requested",
                            "postprocess_label": "未请求", "cancellable": true}]}
                """
            ).jsonObject
        )
        assertEquals(listOf("pikpak"), downloads.configuredChannels.map { it.key })
        val task = downloads.tasks.single()
        assertTrue(task.inProgress)
        assertNull(task.postprocessLabel)
        assertEquals("ABC-1", task.code)
    }

    @Test
    fun magnetsSkipNonMagnetLinksAndFormatSizes() {
        val magnets = PeachMagnet.parseList(
            VelaJson.parseToJsonElement(
                """
                {"items": [
                  {"name": "A", "uri": "magnet:?xt=urn:btih:1", "size": 2147483648, "size_text": "", "seeders": 5, "chinese": true},
                  {"name": "B", "uri": "ed2k://file", "size": "1.2GB"},
                  {"name": "C", "uri": "magnet:?xt=urn:btih:2", "size": "700MB"}
                ]}
                """
            ).jsonObject
        )
        assertEquals(listOf("A", "C"), magnets.map { it.name })
        assertEquals("2.0 GB", magnets[0].sizeText)
        assertEquals("700MB", magnets[1].sizeText)
    }
}



class PeachClientDetailErrorTest {
    @Test
    fun configurationFieldErrorsAreReadable() = runTest {
        val engine = MockEngine {
            respond(
                """{"detail":{"message":"有几项需要修改","errors":{"media_dirs":["目录不存在",""],"port":"端口无效"}}}""",
                HttpStatusCode.BadRequest,
                headersOf(HttpHeaders.ContentType, "application/json")
            )
        }
        val error = assertFailsWith<PeachApiException> {
            PeachClient(HttpClient(engine), "https://peach.local/").post("/api/configuration")
        }
        assertEquals("有几项需要修改\n目录不存在\n端口无效", error.message)
    }
}
