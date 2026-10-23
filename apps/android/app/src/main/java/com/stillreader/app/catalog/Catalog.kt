package com.stillreader.app.catalog

import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import kotlinx.serialization.SerializationException
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Base64
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class CatalogBook(val id: String, val title: String, val authors: List<String> = emptyList(), val languages: List<String> = emptyList(),
    val summary: String = "", val subjects: List<String> = emptyList(), val coverURL: String? = null, val epubURL: String? = null, val webURL: String? = null)
data class CatalogLink(val title: String, val url: String)
data class CatalogPage(val books: List<CatalogBook>, val next: String? = null, val previous: String? = null,
    val navigation: List<CatalogLink> = emptyList(), val searchTemplate: String? = null, val total: Int? = null, val searchDescription: String? = null)
data class CatalogCredentials(val origin: String, val username: String, val password: String) {
    fun header(url: String): String? = if (username.isNotEmpty() && sameOrigin(origin, url)) "Basic " + Base64.getEncoder().encodeToString("$username:$password".toByteArray()) else null
}
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
fun plainCatalogText(text: String) = text.replace(Regex("<[^>]+>"), " ").replace("&amp;", "&").replace("&nbsp;", " ").trim()
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
/** Memory-only credentials; manual redirects prevent credentials crossing origins or HTTPS downgrades. */
open class CatalogClient(private val credentials: CatalogCredentials? = null, private val connect: (String) -> HttpURLConnection = { URI(it).toURL().openConnection() as HttpURLConnection }) {
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
                    connection.setRequestProperty("Accept", "application/opds+json, application/atom+xml, application/json, application/epub+zip, image/*")
                    connection.setRequestProperty("User-Agent", "Still/0.1 (personal ebook reader)")
                    credentials?.header(target)?.let { connection.setRequestProperty("Authorization", it) }
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
                    check(code != 401 && code != 403) { "Catalog access requires valid credentials or membership. Check Catalog settings and your provider’s access requirements." }
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
            continuation.invokeOnCancellation { active.getAndSet(null)?.disconnect() }
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
private fun JsonElement?.names(): List<String> = when (this) {
    is JsonPrimitive -> listOfNotNull(str())
    is JsonObject -> this["name"].strings()
    is JsonArray -> flatMap { it.names() }
    else -> emptyList()
}
class GutendexProvider(override val client: CatalogClient = CatalogClient(), val endpoint: String = "https://gutendex.com/books") : CatalogProvider {
    override val id = "gutenberg"
    override val name = "Project Gutenberg"
    fun requestURL(query: String, language: String, page: String?): String {
        if (page != null) { require(sameOrigin(page, endpoint)); return page }
        val parameters = mutableListOf("mime_type=application%2Fepub%2Bzip", "copyright=false")
        if (query.isNotBlank()) parameters += "search=" + URLEncoder.encode(query.trim(), "UTF-8")
        if (language.isNotEmpty()) parameters += "languages=" + URLEncoder.encode(language, "UTF-8")
        return endpoint + "?" + parameters.joinToString("&")
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
class OPDSProvider(override val id: String, override val name: String, val endpoint: String, override val client: CatalogClient = CatalogClient()) : CatalogProvider {
    override suspend fun search(query: String, language: String, page: String?): CatalogPage {
        var url = page ?: endpoint
        var result = parse(client.get(url), url, id)
        if (page == null && query.isNotBlank()) {
            var template = result.searchTemplate
            result.searchDescription?.takeIf { template == null }?.let { description ->
                val document = CatalogXml.parse(client.get(description))
                template = document.descendants("Url").firstOrNull { it.attributes["type"]?.let { type -> type.contains("atom+xml") || type.contains("opds+json") } == true }?.attributes?.get("template")
                url = description
            }
            url = searchURL(template ?: error("This catalog does not advertise search. Browse its categories instead."), query, url)
            result = parse(client.get(url), url, id)
        }
        return if (language.isEmpty()) result else result.copy(books = result.books.filter { book -> book.languages.any { it.substringBefore('-').equals(language, true) } }, total = null)
    }
    companion object {
        fun searchURL(template: String, query: String, base: String): String {
            var hasQuery = false
            val escaped = URLEncoder.encode(query, "UTF-8").replace("+", "%20")
            val value = Regex("\\{([^}]+)}").replace(template) { match ->
                val expression = match.groupValues[1]
                val fields = expression.trimStart('?', '&').split(',')
                when {
                    expression in listOf("query", "searchTerms") -> { hasQuery = true; escaped }
                    "query" in fields || "searchTerms" in fields -> {
                        hasQuery = true
                        (if (expression.startsWith('?')) "?" else "&") + (if ("query" in fields) "query=" else "searchTerms=") + escaped
                    }
                    else -> ""
                }
            }
            require(hasQuery) { "This catalog uses an unsupported search template." }
            return catalogURL(value, base) ?: invalid()
        }
        fun parse(data: ByteArray, base: String, providerID: String): CatalogPage {
            if (data.decodeToString().trimStart().startsWith('<')) return parseAtom(data, base, providerID)
            val root = catalogObject(data)
            if (root["metadata"] !is JsonObject || listOf("publications", "navigation", "groups").none { it in root }) invalid()
            val collections = listOf(root) + root["groups"].array().mapNotNull { it.obj() }
            fun links(obj: JsonObject) = obj["links"].array().mapNotNull { it.obj() }
            fun relation(link: JsonObject, rel: String) = rel in link["rel"].strings()
            val books = collections.flatMap { it["publications"].array() }.mapNotNull { value ->
                val item = value.obj() ?: return@mapNotNull null
                val meta = item["metadata"].obj() ?: return@mapNotNull null
                val title = meta["title"].str()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                val download = links(item).firstOrNull { link -> link["type"].str()?.substringBefore(';') == "application/epub+zip" && link["rel"].strings().any { it in listOf("download", "acquisition", "http://opds-spec.org/acquisition", "http://opds-spec.org/acquisition/open-access") } }
                val epub = catalogURL(download?.get("href").str(), base)
                val identity = meta["identifier"].str() ?: epub ?: return@mapNotNull null
                CatalogBook("$providerID:$identity", title, meta["author"].names(), meta["language"].strings(), plainCatalogText(meta["description"].str().orEmpty()),
                    meta["subject"].names(), catalogURL(item["images"].array().firstOrNull().obj()?.get("href").str(), base), epub)
            }.distinctBy { it.id }
            val navigation = collections.flatMap { it["navigation"].array() }.mapNotNull { value ->
                val link = value.obj() ?: return@mapNotNull null
                val href = catalogURL(link["href"].str(), base) ?: return@mapNotNull null
                if (link["type"].str()?.contains("opds") != true) return@mapNotNull null
                CatalogLink(link["title"].str() ?: "Browse", href)
            }.distinctBy { it.url }
            val links = links(root)
            return CatalogPage(books, catalogURL(links.firstOrNull { relation(it, "next") }?.get("href").str(), base),
                catalogURL(links.firstOrNull { relation(it, "previous") || relation(it, "prev") }?.get("href").str(), base), navigation,
                links.firstOrNull { relation(it, "search") && it["type"].str()?.contains("opds") == true }?.get("href").str(),
                (root["metadata"].obj()?.get("numberOfItems") as? JsonPrimitive)?.intOrNull)
        }
        private fun parseAtom(data: ByteArray, base: String, providerID: String): CatalogPage {
            val root = CatalogXml.parse(data)
            require(root.name == "feed") { "This is not an OPDS feed." }
            val links = root.children.filter { it.name == "link" }
            fun href(rel: String) = catalogURL(links.firstOrNull { it.attributes["rel"] == rel }?.attributes?.get("href"), base)
            val books = mutableListOf<CatalogBook>(); val navigation = mutableListOf<CatalogLink>()
            root.children.filter { it.name == "entry" }.forEach { entry ->
                val entryLinks = entry.children.filter { it.name == "link" }
                val epub = catalogURL(entryLinks.firstOrNull { it.attributes["type"] == "application/epub+zip" && it.attributes["rel"] in listOf("http://opds-spec.org/acquisition", "http://opds-spec.org/acquisition/open-access") }?.attributes?.get("href"), base)
                val title = entry.first("title")?.text?.toString()?.trim() ?: "Untitled"
                if (epub != null) {
                    val identity = entry.first("id")?.text?.toString() ?: epub
                    books += CatalogBook("$providerID:$identity", title, entry.children.filter { it.name == "author" }.mapNotNull { it.first("name")?.text?.toString() },
                        listOfNotNull(entry.first("language")?.text?.toString()), plainCatalogText((entry.first("summary") ?: entry.first("content"))?.text?.toString().orEmpty()),
                        coverURL = catalogURL(entryLinks.firstOrNull { it.attributes["rel"]?.contains("image") == true }?.attributes?.get("href"), base), epubURL = epub)
                } else {
                    val url = catalogURL(entryLinks.firstOrNull { it.attributes["type"]?.contains("atom+xml") == true }?.attributes?.get("href"), base)
                    if (url != null) navigation += CatalogLink(title, url)
                }
            }
            return CatalogPage(books.distinctBy { it.id }, href("next"), href("previous") ?: href("prev"), navigation.distinctBy { it.url },
                links.firstOrNull { it.attributes["rel"] == "search" && it.attributes["href"]?.contains('{') == true }?.attributes?.get("href"), searchDescription = catalogURL(links.firstOrNull { it.attributes["rel"] == "search" && it.attributes["type"]?.contains("opensearchdescription") == true }?.attributes?.get("href"), base))
        }
    }
}
