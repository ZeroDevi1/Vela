package com.vela.data.repository

import java.io.IOException
import kotlinx.coroutines.runBlocking
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import kotlin.test.*

class BookRangeSourceTest {
    private fun source(response: (Request) -> Response): BookRangeSource = BookRangeSource(
        OkHttpClient.Builder().addInterceptor { response(it.request()) }.build(),
        Request.Builder().url("https://example.test/book").header("Authorization", "test-account").build())

    private fun response(request: Request, code: Int = 206, range: String = "bytes 7-9/10", body: String = "789", etag: String = "\"v1\""): Response =
        Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code).message("test")
            .header("Content-Range", range).header("ETag", etag).body(body.toResponseBody()).build()

    @Test fun sendsRangeAndPinsVersion() = runBlocking {
        var calls = 0
        val source = source { request ->
            assertEquals("test-account", request.header("Authorization"))
            assertEquals("identity", request.header("Accept-Encoding"))
            if (calls++ == 0) {
                assertEquals("bytes=-3", request.header("Range"))
                response(request)
            } else {
                assertEquals("bytes=1-3", request.header("Range"))
                assertEquals("\"v1\"", request.header("If-Range"))
                response(request, range = "bytes 1-3/10", body = "123")
            }
        }
        assertEquals(10L, source.read(null, 3).total)
        assertEquals("123", source.read(1, 3).bytes.decodeToString())
    }

    @Test fun distinguishesUnsupportedRangesFromInvalidResponses() = runBlocking {
        assertFailsWith<BookRangeUnsupportedException> { source { response(it, code = 200) }.read(null, 3) }
        for ((range, body) in listOf("bytes 6-9/10" to "6789", "bytes 7-9/10" to "78", "bytes 7-9/10" to "7890")) {
            assertFailsWith<IOException> { source { response(it, range = range, body = body) }.read(null, 3) }
        }
    }

    @Test fun rejectsVersionChangesInsteadOfMixingPages() = runBlocking {
        var calls = 0
        val source = source { response(it, etag = if (calls++ == 0) "\"v1\"" else "\"v2\"") }
        source.read(null, 3)
        assertFailsWith<IOException> { source.read(7, 3) }
        Unit
    }
}
