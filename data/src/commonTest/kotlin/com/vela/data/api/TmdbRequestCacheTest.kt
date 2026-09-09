package com.vela.data.api

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.coroutines.CancellationException

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class TmdbRequestCacheTest {
    @Test fun duplicatesShareOneRequest() = runTest {
        val cache = TmdbRequestCache { testScheduler.currentTime }
        var requests = 0
        val values = List(20) { async { cache.get("same") { requests++; delay(100); "ok" } } }.awaitAll()
        assertEquals(List(20) { "ok" }, values)
        assertEquals(1, requests)
    }

    @Test fun outageStopsQueuedRequestsAndRecoversAfterCooldown() = runTest {
        val cache = TmdbRequestCache { testScheduler.currentTime }
        var requests = 0
        List(30) { index -> async {
            runCatching { cache.get("$index") { requests++; delay(100); error("offline") } }
        } }.awaitAll()
        assertEquals(2, requests)
        testScheduler.advanceTimeBy(60_001)
        assertEquals("recovered", cache.get("new") { "recovered" })
    }

    @Test fun staleDataExpiresAndRateLimitIsRespected() = runTest {
        var now = 0L
        val cache = TmdbRequestCache { now }
        cache.get("title") { "cached" }
        now = 30 * 60_000L
        assertEquals("cached", cache.get("title") { throw TmdbHttpException(429, 120_000) })
        now += 60_001
        assertFailsWith<IllegalStateException> { cache.get("other") { "too early" } }
        now += 60_000
        assertEquals("ok", cache.get("other") { "ok" })
        now = 24 * 60 * 60_000L
        assertFailsWith<IllegalStateException> { cache.get("title") { error("offline") } }
    }

    @Test fun cancellationDoesNotTripCircuitOrGetSwallowed() = runTest {
        val cache = TmdbRequestCache { 0L }
        assertFailsWith<CancellationException> { cache.get("one") { throw CancellationException() } }
        assertEquals("ok", cache.get("two") { "ok" })
        val client = HttpClient(MockEngine) {
            engine {
                dispatcher = kotlinx.coroutines.test.StandardTestDispatcher(testScheduler)
                addHandler { throw CancellationException() }
            }
        }
        try {
            assertFailsWith<CancellationException> { TmdbApi(client).titleLogoPath("movie", "1") }
        } finally { client.close() }
    }

    @Test fun optionalFailureAndCatalogShareCircuit() = runTest {
        var requests = 0
        val client = HttpClient(MockEngine) {
            engine {
                dispatcher = kotlinx.coroutines.test.StandardTestDispatcher(testScheduler)
                addHandler { requests++; respond("{}", HttpStatusCode.ServiceUnavailable) }
            }
        }
        try {
            val api = TmdbApi(client)
            assertEquals(null, api.titleLogoPath("movie", "1"))
            assertFailsWith<IllegalStateException> { api.trending("day") }
            assertEquals(1, requests)
        } finally { client.close() }
    }

    @Test fun slowRequestTimesOutAndOpensCircuit() = runTest {
        var requests = 0
        val client = HttpClient(MockEngine) {
            engine {
                dispatcher = kotlinx.coroutines.test.StandardTestDispatcher(testScheduler)
                addHandler { requests++; delay(20_000); respond("{}") }
            }
        }
        try {
            val api = TmdbApi(client)
            assertEquals(null, api.titleLogoPath("movie", "1"))
            assertFailsWith<IllegalStateException> { api.trending("day") }
            assertEquals(1, requests)
        } finally { client.close() }
    }

    @Test fun notFoundDoesNotBlockOtherTitlesAndCacheIsBounded() = runTest {
        val cache = TmdbRequestCache { 0L }
        assertFailsWith<TmdbHttpException> { cache.get("missing") { throw TmdbHttpException(404) } }
        repeat(129) { index -> cache.get("$index") { "$index" } }
        assertEquals("refetched", cache.get("0") { "refetched" })
    }

    @Test fun languageAndFiltersHaveSeparateCacheEntries() = runTest {
        var requests = 0
        val client = HttpClient(MockEngine) {
            engine {
                dispatcher = kotlinx.coroutines.test.StandardTestDispatcher(testScheduler)
                addHandler { requests++; respond("""{"id":1,"title":"Title","results":[]}""") }
            }
        }
        try {
            val api = TmdbApi(client)
            api.titleDetail("movie", "1")
            api.catalogDetail("movie", 1)
            api.titleDetail("movie", "1")
            api.search("one")
            api.search("two")
            api.search("one")
            assertEquals(4, requests)
        } finally { client.close() }
    }
}
