package com.stillreader.app

import com.stillreader.app.catalog.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.net.HttpURLConnection
import java.net.URL

private class Response(url: String, val status: Int = 200, val bytes: ByteArray = "ok".toByteArray(), val headers: Map<String, String> = emptyMap()) : HttpURLConnection(URL(url)) {
    override fun connect() { }
    override fun disconnect() { }
    override fun usingProxy() = false
    override fun getResponseCode() = status
    override fun getContentLengthLong() = bytes.size.toLong()
    override fun getInputStream() = bytes.inputStream()
    override fun getHeaderField(name: String) = headers[name]
}
class CatalogNetworkTests {
    @Test fun redirectsStripCredentialsAndRejectDowngrades() = runTest {
        val calls = mutableListOf<Response>()
        val client = CatalogClient(CatalogCredentials("https://catalog.example/feed", "member", "secret")) { url ->
            Response(url, if (calls.isEmpty()) 302 else 200, headers = mapOf("Location" to "https://cdn.example/file")).also { calls += it }
        }
        assertEquals("ok", client.get("https://catalog.example/feed").decodeToString())
        assertNotNull(calls[0].getRequestProperty("Authorization"))
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
        assertTrue(runCatching { html.download(CatalogBook("test", "Test", epubURL = "https://catalog.example/book.epub")) { } }.isFailure)
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
    @Test fun cancelledRequestDoesNotOpenConnection() = runTest {
        var requests = 0
        val client = CatalogClient { url -> requests++; Response(url) }
        val job = launch(start = CoroutineStart.LAZY) { client.get("https://catalog.example/feed") }
        job.cancelAndJoin()
        assertEquals(0, requests)
    }
}
