package com.stillreader.app

import com.stillreader.app.catalog.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.net.HttpURLConnection
import java.net.URL

private class Response(url: String, val status: Int = 200, val bytes: ByteArray = "ok".toByteArray(), val headers: Map<String, String> = emptyMap(), val declaredLength: Long = bytes.size.toLong()) : HttpURLConnection(URL(url)) {
    override fun connect() { }
    override fun disconnect() { }
    override fun usingProxy() = false
    override fun getResponseCode() = status
    override fun getContentLengthLong() = declaredLength
    override fun getInputStream() = bytes.inputStream()
    override fun getHeaderField(name: String) = headers[name]
}
class CatalogNetworkTests {
    @Test fun streamedDownloadFollowsRedirectsReportsProgressAndDoesNotCacheTheBook() = runTest {
        val calls = mutableListOf<String>()
        val bytes = byteArrayOf(0x50, 0x4b, 3, 4) + ByteArray(160_000) { (it % 251).toByte() }
        val client = CatalogClient { url ->
            calls += url
            if (url.endsWith("/book")) Response(url, 302, headers = mapOf("Location" to "https://cdn.example/book.epub"))
            else Response(url, bytes = bytes)
        }
        val file = java.io.File.createTempFile("streamed-epub", ".epub")
        val progress = mutableListOf<Float?>()
        try {
            repeat(2) { GutendexProvider(client).download(CatalogBook("test", "Test", epubURL = "https://catalog.example/book"), file) { progress += it } }
            assertArrayEquals(bytes, file.readBytes())
            assertEquals(4, calls.size)
            assertTrue(progress.any { it != null && it > 0f && it < 1f })
            assertEquals(1f, progress.last())
        } finally { file.delete() }
    }
    @Test fun streamedOversizeOrBrokenTransfersRemovePartialFiles() = runTest {
        val file = java.io.File.createTempFile("partial-epub", ".epub")
        try {
            for (declared in listOf(-1L, 200L)) {
                val client = CatalogClient { url -> Response(url, bytes = ByteArray(200), declaredLength = declared) }
                assertTrue(runCatching { client.getToFile("https://catalog.example/book", file, 100) }.isFailure)
                assertFalse(file.exists())
            }
            val broken = CatalogClient { url -> object : HttpURLConnection(URL(url)) {
                override fun connect() { }
                override fun disconnect() { }
                override fun usingProxy() = false
                override fun getResponseCode() = 200
                override fun getInputStream() = object : java.io.InputStream() {
                    var first = true
                    override fun read(): Int = error("unused")
                    override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                        if (!first) throw java.io.IOException("broken transfer")
                        first = false; bytes[offset] = 0x50; return 1
                    }
                }
            } }
            assertTrue(runCatching { broken.getToFile("https://catalog.example/book", file, 100) }.isFailure)
            assertFalse(file.exists())
        } finally { file.delete() }
    }
    @Test fun cancellingStreamedDownloadRemovesThePartialFile() = runTest {
        val reading = CompletableDeferred<Unit>()
        val released = java.util.concurrent.CountDownLatch(1)
        val file = java.io.File.createTempFile("cancelled-epub", ".epub")
        val client = CatalogClient { url -> object : HttpURLConnection(URL(url)) {
            override fun connect() { }
            override fun disconnect() { released.countDown() }
            override fun usingProxy() = false
            override fun getResponseCode() = 200
            override fun getInputStream() = object : java.io.InputStream() {
                override fun read(): Int {
                    reading.complete(Unit)
                    released.await(5, java.util.concurrent.TimeUnit.SECONDS)
                    throw java.io.IOException("Connection closed")
                }
            }
        } }
        val transfer = launch { client.getToFile("https://catalog.example/book", file, 100) }
        try {
            reading.await(); transfer.cancelAndJoin()
            assertEquals(0L, released.count)
            assertFalse(file.exists())
        } finally { released.countDown(); file.delete() }
    }

    @Test fun redirectsFollowHTTPSAndRejectDowngrades() = runTest {
        val calls = mutableListOf<Response>()
        val client = CatalogClient { url ->
            Response(url, if (calls.isEmpty()) 302 else 200, headers = mapOf("Location" to "https://cdn.example/file")).also { calls += it }
        }
        assertEquals("ok", client.get("https://catalog.example/feed").decodeToString())
        assertNull(calls[0].getRequestProperty("Authorization"))
        assertNull(calls[1].getRequestProperty("Authorization"))
        val unsafe = CatalogClient { url -> Response(url, 302, headers = mapOf("Location" to "http://catalog.example/file")) }
        assertTrue(runCatching { unsafe.get("https://catalog.example/feed") }.isFailure)
    }
    @Test fun rateLimitBlocksFurtherRequestsAndCachingAvoidsRepeatedFetches() = runTest {
        var requests = 0
        val throttled = CatalogClient { url -> requests++; Response(url, 429, headers = mapOf("Retry-After" to "120")) }
        repeat(2) { assertTrue(runCatching { throttled.get("https://catalog.example/feed") }.isFailure) }
        assertEquals(1, requests)
        val cached = CatalogClient { url -> requests++; Response(url) }
        cached.get("https://catalog.example/feed"); cached.get("https://catalog.example/feed")
        assertEquals(2, requests)
    }
    @Test fun httpErrorsOversizedFilesAndInvalidEpubAreRejected() = runTest {
        for (status in listOf(401, 403, 404, 500, 503)) {
            val client = CatalogClient { url -> Response(url, status) }
            assertTrue(runCatching { client.get("https://catalog.example/feed") }.isFailure)
        }
        val timeout = CatalogClient { throw java.net.SocketTimeoutException("timeout") }
        assertEquals("The provider took too long to respond. Please try again.", runCatching { timeout.get("https://catalog.example/feed") }.exceptionOrNull()?.message)
        val large = CatalogClient { url -> Response(url, bytes = ByteArray(100)) }
        assertTrue(runCatching { large.get("https://catalog.example/book", limit = 10) }.isFailure)
        val html = GutendexProvider(CatalogClient { url -> Response(url, bytes = "<html>Login</html>".toByteArray()) })
        val file = java.io.File.createTempFile("invalid-epub", ".epub")
        try {
            assertTrue(runCatching { html.download(CatalogBook("test", "Test", epubURL = "https://catalog.example/book.epub"), file) { } }.isFailure)
            assertFalse(file.exists())
        } finally { file.delete() }
    }
    @Test fun cancellationClosesAnActiveTransfer() = runTest {
        val reading = CompletableDeferred<Unit>()
        val released = java.util.concurrent.CountDownLatch(1)
        val client = CatalogClient { url -> object : HttpURLConnection(URL(url)) {
            override fun connect() { }
            override fun disconnect() { released.countDown() }
            override fun usingProxy() = false
            override fun getResponseCode() = 200
            override fun getContentLengthLong() = -1L
            override fun getInputStream() = object : java.io.InputStream() {
                override fun read(): Int {
                    reading.complete(Unit)
                    released.await(5, java.util.concurrent.TimeUnit.SECONDS)
                    throw java.io.IOException("Connection closed")
                }
            }
        } }
        val transfer = launch { client.get("https://catalog.example/book") }
        reading.await()
        transfer.cancelAndJoin()
        assertEquals(0L, released.count)
    }
    @Test fun cancellationDoesNotWaitForBlockingDisconnect() = runTest {
        val reading = CompletableDeferred<Unit>()
        val finishRead = java.util.concurrent.CountDownLatch(1)
        val finishDisconnect = java.util.concurrent.CountDownLatch(1)
        val client = CatalogClient { url -> object : HttpURLConnection(URL(url)) {
            override fun connect() { }
            override fun disconnect() { finishDisconnect.await(5, java.util.concurrent.TimeUnit.SECONDS) }
            override fun usingProxy() = false
            override fun getResponseCode(): Int {
                reading.complete(Unit)
                finishRead.await(5, java.util.concurrent.TimeUnit.SECONDS)
                return 200
            }
        } }
        val transfer = launch { client.get("https://catalog.example/feed") }
        try {
            reading.await()
            val start = System.nanoTime()
            transfer.cancel()
            assertTrue("Cancellation blocked the caller on socket cleanup", System.nanoTime() - start < 1_000_000_000L)
        } finally { finishRead.countDown(); finishDisconnect.countDown(); transfer.join() }
    }
    @Test fun cancelledRequestDoesNotOpenConnection() = runTest {
        var requests = 0
        val client = CatalogClient { url -> requests++; Response(url) }
        val job = launch(start = CoroutineStart.LAZY) { client.get("https://catalog.example/feed") }
        job.cancelAndJoin()
        assertEquals(0, requests)
    }
}
