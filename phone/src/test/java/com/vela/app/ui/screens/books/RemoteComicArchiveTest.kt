package com.vela.app.ui.screens.books

import com.vela.data.repository.BookRange
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.zip.*

class RemoteComicArchiveTest {
    private val pages = (1..12).associate { "page$it.jpg" to ByteArray(90000).also { bytes -> java.util.Random(it.toLong()).nextBytes(bytes) } }
    private fun archive(stored: Boolean = false): ByteArray = ByteArrayOutputStream().also { output ->
        ZipOutputStream(output).use { zip ->
            pages.forEach { (name, data) ->
                zip.putNextEntry(ZipEntry(name).apply {
                    if (stored) { method = ZipEntry.STORED; size = data.size.toLong(); crc = CRC32().apply { update(data) }.value }
                })
                zip.write(data)
                zip.closeEntry()
            }
        }
    }.toByteArray()

    @Test fun opensWithoutFullDownloadAndReadsOnlyRequestedPages() = runBlocking {
        for (stored in listOf(false, true)) {
            val zip = archive(stored)
            var received = 0
            var requests = 0
            val comic = RemoteComicArchive.open { start, count ->
                val first = start?.toInt() ?: (zip.size - count).coerceAtLeast(0)
                val bytes = zip.copyOfRange(first, minOf(first + count, zip.size))
                received += bytes.size; requests++
                BookRange(bytes, first.toLong(), zip.size.toLong())
            }
            assertEquals("page2.jpg", comic.chapters[1].path)
            assertTrue(received < zip.size / 10)
            assertArrayEquals(pages["page7.jpg"], comic.bytes(6))
            val cachedRequests = requests
            assertArrayEquals(pages["page7.jpg"], comic.bytes(6))
            assertEquals(cachedRequests, requests)
            assertTrue(received < zip.size / 4)
            for (page in 0..4) comic.bytes(page)
            val beforeEviction = requests
            comic.bytes(6)
            assertTrue(requests > beforeEviction)
        }
    }

    @Test fun rejectsCorruptPageAndCanRetry() = runBlocking {
        val zip = archive(true)
        var corrupt = true
        val comic = RemoteComicArchive.open { start, count ->
            val first = start?.toInt() ?: (zip.size - count).coerceAtLeast(0)
            val bytes = zip.copyOfRange(first, minOf(first + count, zip.size))
            if (start != null && count == 90000 && corrupt) bytes[0] = (bytes[0].toInt() xor 1).toByte()
            BookRange(bytes, first.toLong(), zip.size.toLong())
        }
        try { comic.bytes(0); fail("Corrupt page accepted") } catch (_: IOException) { }
        corrupt = false
        assertArrayEquals(pages["page1.jpg"], comic.bytes(0))
    }

    @Test fun cancelledPrefetchReleasesReaderForJump() = runBlocking {
        val zip = archive(true)
        val started = CompletableDeferred<Unit>()
        var stall = true
        val comic = RemoteComicArchive.open { start, count ->
            if (start != null && count == 90000 && stall) {
                started.complete(Unit)
                awaitCancellation()
            }
            val first = start?.toInt() ?: (zip.size - count).coerceAtLeast(0)
            BookRange(zip.copyOfRange(first, minOf(first + count, zip.size)), first.toLong(), zip.size.toLong())
        }
        val prefetch = launch { comic.bytes(0) }
        started.await()
        prefetch.cancelAndJoin()
        stall = false
        assertArrayEquals(pages["page10.jpg"], comic.bytes(9))
    }

    @Test fun rejectsTruncatedDirectory() = runBlocking {
        try {
            RemoteComicArchive.open { _, _ -> BookRange(ByteArray(21), 0, 21) }
            fail("Invalid archive accepted")
        } catch (_: IOException) { }
    }
}
