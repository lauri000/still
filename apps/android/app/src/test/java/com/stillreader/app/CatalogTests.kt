package com.stillreader.app

import com.stillreader.app.catalog.*
import com.stillreader.app.data.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class CatalogTests {
    @Test fun gutendexNormalizesOnlyPublicDomainEpubsAndPagination() {
        val result = GutendexProvider.parse(fixture("catalog-gutendex.json").toByteArray(), "https://gutendex.com/books")
        assertEquals(3, result.total); assertEquals(1, result.books.size)
        assertEquals("gutenberg:1", result.books.single().id)
        assertEquals(listOf("Reader, Still"), result.books.single().authors)
        assertEquals("https://gutendex.com/books?page=2", result.next); assertNull(result.previous)
    }
    @Test fun searchEncodesTitleAuthorAndLanguageWithoutChangingPagination() = runTest {
        val requests = mutableListOf<String>()
        val client = object : CatalogClient() {
            override suspend fun get(url: String, limit: Int, progress: (Float?) -> Unit): ByteArray {
                requests += url; return fixture("catalog-gutendex.json").toByteArray()
            }
        }
        val provider = GutendexProvider(client)
        val page = provider.search("Austen & Brontë", "en,fr")
        provider.search("different", "de", page.next)
        assertTrue(requests[0].contains("search=Austen+%26+Bront%C3%AB"))
        assertTrue(requests[0].contains("languages=en%2Cfr")); assertTrue(requests[0].contains("copyright=false"))
        assertEquals(page.next, requests[1])
        assertThrows(IllegalArgumentException::class.java) { provider.requestURL("", "", "https://other.example/page") }
    }
    @Test fun opdsHandlesGroupsContributorsRelationsAndRelativeLinks() {
        val page = OPDSProvider.parse(fixture("catalog-opds.json").toByteArray(), "https://catalog.example/feed", "test")
        assertEquals(3, page.books.size)
        assertEquals(listOf("Still Reader", "Another Author"), page.books[0].authors)
        assertEquals("https://catalog.example/test.epub", page.books[0].epubURL)
        assertEquals("Original & readable.", page.books[0].summary)
        assertNull(page.books[1].epubURL)
        assertEquals("https://catalog.example/feed?page=2", page.next)
        assertEquals("https://catalog.example/all", page.navigation.single().url)
        assertEquals("https://catalog.example/search?query=A%26B%20C", OPDSProvider.searchURL(page.searchTemplate!!, "A&B C", "https://catalog.example/feed"))
    }
    @Test fun opdsSearchAndPageLanguageFiltering() = runTest {
        val requests = mutableListOf<String>()
        val client = object : CatalogClient() {
            override suspend fun get(url: String, limit: Int, progress: (Float?) -> Unit): ByteArray {
                requests += url; return fixture("catalog-opds.json").toByteArray()
            }
        }
        val provider = OPDSProvider("test", "Test", "https://catalog.example/feed", client)
        val result = provider.search("Author", "fi")
        assertEquals(2, requests.size); assertEquals(1, result.books.size); assertNotNull(result.next)
        provider.search("Author", "fi", result.next)
        assertEquals(3, requests.size); assertEquals(result.next, requests.last())
    }
    @Test fun standardEbooksAtomCompatibility() {
        val page = OPDSProvider.parse(fixture("catalog-atom.xml").toByteArray(), "https://catalog.example/feed/", "test")
        assertEquals("Atom edition", page.books.single().title)
        assertEquals(listOf("en"), page.books.single().languages)
        assertEquals("https://catalog.example/feed/test.epub", page.books.single().epubURL)
        assertEquals(1, page.navigation.size)
        assertEquals("https://catalog.example/feed/search?q=A%20B", OPDSProvider.searchURL(page.searchTemplate!!, "A B", "https://catalog.example/feed/"))
    }
    @Test fun atomOpenSearchDescriptionCanDriveSearch() = runTest {
        val root = "<feed xmlns=\"http://www.w3.org/2005/Atom\"><title>Feed</title><link rel=\"search\" type=\"application/opensearchdescription+xml\" href=\"/search.xml\"/></feed>"
        val description = "<OpenSearchDescription><Url type=\"application/atom+xml\" template=\"/search?q={searchTerms}\"/></OpenSearchDescription>"
        val requests = mutableListOf<String>()
        val client = object : CatalogClient() {
            override suspend fun get(url: String, limit: Int, progress: (Float?) -> Unit): ByteArray {
                requests += url
                return when { url.endsWith("search.xml") -> description; url.contains("q=") -> fixture("catalog-atom.xml"); else -> root }.toByteArray()
            }
        }
        val page = OPDSProvider("test", "Test", "https://catalog.example/feed", client).search("A B", "en")
        assertEquals(1, page.books.size)
        assertEquals("https://catalog.example/search?q=A%20B", requests.last())
    }
    @Test fun invalidResponsesAndCredentialScope() {
        assertThrows(Exception::class.java) { GutendexProvider.parse("{}".toByteArray(), "https://gutendex.com/books") }
        assertThrows(Exception::class.java) { OPDSProvider.parse("<html/>".toByteArray(), "https://test.example", "test") }
        val auth = CatalogCredentials("https://test.example/feed", "member", "secret")
        assertNotNull(auth.header("https://test.example/download.epub"))
        assertNull(auth.header("https://other.example/download")); assertNull(auth.header("http://test.example/download"))
        assertNull(auth.header("https://test.example:444/download"))
        assertNull(catalogURL("https://user:pass@test.example/feed")); assertNull(catalogURL("file:///tmp/book"))
    }
    @Test fun epubStorageDeduplicatesAndPreservesSourceAndCover() {
        val root = java.nio.file.Files.createTempDirectory("still-epub-test").toFile()
        try {
            val epub = java.io.File(root, "source.epub").apply { writeBytes(CatalogTests::class.java.classLoader!!.getResourceAsStream("sample.epub")!!.readBytes()) }
            val repo = BookRepository(java.io.File(root, "books"))
            val source = EpubImporter.extract(epub, "sample.epub").copy(catalogID = "gutenberg:1")
            assertEquals("urn:still:epub-fixture", source.epubIdentifier)
            assertEquals(listOf("en"), source.languageCodes)
            assertNotNull(source.coverData); assertNotNull(source.contentHash)
            val first = repo.insert(source, epub, deduplicate = true)
            val second = repo.insert(source.copy(id = com.stillreader.app.core.newID()), epub, deduplicate = true)
            assertEquals(first.id, second.id); assertEquals(1, repo.load().books.size)
            assertArrayEquals(epub.readBytes(), java.io.File(repo.directory(first.id), "original.epub").readBytes())
            val out = ByteArrayOutputStream(); repo.export(first, out)
            val backup = BookCodec.json.decodeFromString<BookBackup>(out.toString("UTF-8"))
            assertEquals(source.coverData, backup.book.coverData); assertNotNull(backup.sourceData)
            val restoredCopy = repo.insert(first, epub)
            assertNotEquals(first.id, restoredCopy.id)
            assertEquals(2, repo.load().books.size)
        } finally { root.deleteRecursively() }
    }
}
