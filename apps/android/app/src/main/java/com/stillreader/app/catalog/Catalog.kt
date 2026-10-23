package com.stillreader.app.catalog

import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import kotlinx.serialization.SerializationException
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class CatalogBook(val id: String, val title: String, val authors: List<String> = emptyList(), val languages: List<String> = emptyList(),
    val summary: String = "", val subjects: List<String> = emptyList(), val coverURL: String? = null, val epubURL: String? = null, val webURL: String? = null)
data class CatalogPage(val books: List<CatalogBook>, val next: String? = null, val previous: String? = null, val total: Int? = null)
fun catalogURL(value: String?, base: String? = null): String? = runCatching {
    val raw = value ?: return null
    val uri = if (base == null) URI(raw) else if (raw.startsWith("?")) URI(base.substringBefore('?').substringBefore('#') + raw) else URI(base).resolve(raw)
    require(uri.scheme == "https" && uri.host != null && uri.userInfo == null)
    uri.toASCIIString()
}.getOrNull()
fun sameOrigin(a: String, b: String): Boolean = runCatching {
    val x = URI(a); val y = URI(b)
    x.scheme == y.scheme && x.host.equals(y.host, true) && (if (x.port == -1) 443 else x.port) == (if (y.port == -1) 443 else y.port)
}.getOrDefault(false)
private fun invalid(): Nothing = error("This catalog returned an invalid or unsupported response.")
interface CatalogProvider {
    val id: String
    val name: String
    val client: CatalogClient
    suspend fun search(query: String, language: String, page: String? = null): CatalogPage
    suspend fun download(book: CatalogBook, progress: (Float?) -> Unit): ByteArray {
        val data = client.get(book.epubURL ?: error("This edition has no supported EPUB download."), 80 * 1024 * 1024, progress)
        require(data.take(4) == listOf<Byte>(0x50, 0x4b, 0x03, 0x04)) { "The provider did not return an EPUB file." }
        return data
    }
}
// A socket disconnect can wait for its reader; cancellation must not block the UI.
private val catalogCleanup = CoroutineScope(SupervisorJob() + Dispatchers.IO)

/** Bounded HTTPS requests with caching, rate-limit cooldowns, and explicit redirects. */
open class CatalogClient(private val connect: (String) -> HttpURLConnection = { URI(it).toURL().openConnection() as HttpURLConnection }) {
    private val cooldown = ConcurrentHashMap<String, Long>()
    private val cache = linkedMapOf<String, Pair<Long, ByteArray>>()
    private var cacheBytes = 0
    open suspend fun get(url: String, limit: Int = 8 * 1024 * 1024, progress: (Float?) -> Unit = {}): ByteArray = withContext(Dispatchers.IO) {
        val active = AtomicReference<HttpURLConnection?>()
        fun receive(): ByteArray {
            val deadline = System.nanoTime() + 180_000_000_000L
            var target = url
            repeat(6) { redirect ->
                ensureActive()
                require(catalogURL(target) != null) { "Catalogs and downloads must use HTTPS." }
                val host = URI(target).host
                check((cooldown[host] ?: 0) <= System.currentTimeMillis()) { "This provider asked us to wait. Please try again later." }
                synchronized(cache) { cache[target]?.let { (time, bytes) ->
                    if (System.currentTimeMillis() - time < 300_000 && bytes.size <= limit) return bytes
                } }
                val connection = connect(target)
                active.set(connection)
                try {
                    ensureActive()
                    connection.instanceFollowRedirects = false
                    connection.connectTimeout = 15_000; connection.readTimeout = 30_000
                    connection.setRequestProperty("Accept", "application/json, application/epub+zip, image/*")
                    connection.setRequestProperty("User-Agent", "Still/0.1 (personal ebook reader)")
                    val code = connection.responseCode
                    if (code in listOf(301, 302, 303, 307, 308)) {
                        require(redirect < 5) { "The provider redirected too many times." }
                        target = catalogURL(connection.getHeaderField("Location"), target) ?: invalid()
                        return@repeat
                    }
                    if (code == 429 || code == 503) {
                        val retry = connection.getHeaderField("Retry-After").orEmpty()
                        val formatter = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US).apply { timeZone = TimeZone.getTimeZone("GMT") }
                        cooldown[host] = retry.toLongOrNull()?.coerceIn(1, Long.MAX_VALUE / 2_000)?.let { System.currentTimeMillis() + it * 1000 }
                            ?: runCatching { formatter.parse(retry)?.time }.getOrNull() ?: (System.currentTimeMillis() + 60_000)
                        error("This provider is busy or has reached its request limit. Please wait before retrying.")
                    }
                    check(code in 200..299) { "The provider could not complete this request (HTTP $code). Try again later." }
                    val expected = connection.contentLengthLong
                    require(expected <= limit) { "This download is too large." }
                    val result = ByteArrayOutputStream()
                    connection.inputStream.use { input ->
                        val buffer = ByteArray(65_536)
                        while (true) {
                            ensureActive()
                            check(System.nanoTime() < deadline) { "This download timed out. Please try again." }
                            val n = input.read(buffer); if (n < 0) break
                            require(result.size().toLong() + n <= limit) { "This download is too large." }
                            result.write(buffer, 0, n)
                            progress(if (expected > 0) (result.size().toFloat() / expected).coerceAtMost(1f) else null)
                        }
                    }
                    ensureActive(); progress(1f)
                    val bytes = result.toByteArray()
                    if (bytes.size <= 2 * 1024 * 1024) synchronized(cache) {
                        if (cacheBytes + bytes.size > 16 * 1024 * 1024) { cache.clear(); cacheBytes = 0 }
                        cache.remove(target)?.let { cacheBytes -= it.second.size }
                        cache[target] = System.currentTimeMillis() to bytes; cacheBytes += bytes.size
                    }
                    return bytes
                } finally { active.compareAndSet(connection, null); connection.disconnect() }
            }
            invalid()
        }
        suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation {
                active.getAndSet(null)?.let { connection -> catalogCleanup.launch { runCatching { connection.disconnect() } } }
            }
            try { continuation.resume(receive()) }
            catch (error: Exception) {
                val failure = when (error) {
                    is java.net.SocketTimeoutException -> java.io.IOException("The provider took too long to respond. Please try again.", error)
                    is java.net.UnknownHostException -> java.io.IOException("Couldn’t reach this provider. Check your internet connection and try again.", error)
                    is javax.net.ssl.SSLException -> java.io.IOException("A secure connection to this provider could not be established.", error)
                    is java.io.IOException -> java.io.IOException("The connection to this provider was interrupted. Please try again.", error)
                    else -> error
                }
                continuation.resumeWithException(failure)
            }
        }
    }
}
private fun catalogObject(data: ByteArray): JsonObject = try {
    Json.parseToJsonElement(data.decodeToString()) as? JsonObject ?: invalid()
} catch (_: SerializationException) { invalid() }
private fun JsonElement?.obj() = this as? JsonObject
private fun JsonElement?.array() = (this as? JsonArray)?.toList().orEmpty()
private fun JsonElement?.str() = (this as? JsonPrimitive)?.contentOrNull
private fun JsonElement?.strings(): List<String> = if (this is JsonPrimitive) listOfNotNull(str()) else array().mapNotNull { it.str() }
class GutendexProvider(override val client: CatalogClient = CatalogClient(), val endpoint: String = "https://gutendex.com/books/") : CatalogProvider {
    override val id = "gutenberg"
    override val name = "Project Gutenberg"
    fun requestURL(query: String, language: String, page: String?): String {
        if (page != null) { require(sameOrigin(page, endpoint)); return page }
        // Filter safety/format locally. These extra server-side joins can stall the public instance.
        val parameters = mutableListOf<String>()
        if (query.isNotBlank()) parameters += "search=" + URLEncoder.encode(query.trim(), "UTF-8")
        if (language.isNotEmpty()) parameters += "languages=" + URLEncoder.encode(language, "UTF-8")
        return if (parameters.isEmpty()) endpoint else endpoint + "?" + parameters.joinToString("&")
    }
    override suspend fun search(query: String, language: String, page: String?): CatalogPage {
        val url = requestURL(query, language, page)
        return parse(client.get(url), url)
    }
    companion object {
        fun parse(data: ByteArray, base: String): CatalogPage {
            val root = catalogObject(data)
            val results = root["results"] as? JsonArray ?: invalid()
            val count = (root["count"] as? JsonPrimitive)?.intOrNull ?: invalid()
            val books = results.mapNotNull { value ->
                val item = value.obj() ?: return@mapNotNull null
                val id = (item["id"] as? JsonPrimitive)?.intOrNull ?: return@mapNotNull null
                val title = item["title"].str()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                if ((item["copyright"] as? JsonPrimitive)?.booleanOrNull != false) return@mapNotNull null
                val formats = item["formats"].obj() ?: return@mapNotNull null
                val epub = catalogURL(formats["application/epub+zip"].str()) ?: return@mapNotNull null
                CatalogBook("gutenberg:$id", title, item["authors"].array().mapNotNull { it.obj()?.get("name").str() },
                    item["languages"].strings(), item["summaries"].strings().joinToString("\n\n"), item["subjects"].strings(),
                    catalogURL(formats["image/jpeg"].str()), epub, "https://www.gutenberg.org/ebooks/$id")
            }.distinctBy { it.id }
            return CatalogPage(books, catalogURL(root["next"].str(), base), catalogURL(root["previous"].str(), base), total = count)
        }
    }
}
