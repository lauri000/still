package com.stillreader.app

import com.stillreader.app.catalog.*
import com.stillreader.app.data.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream

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
        assertTrue(requests[0].contains("search=austen+%26+bront%C3%AB"))
        assertTrue(requests[0].contains("languages=en%2Cfr")); assertFalse(requests[0].contains("copyright=")); assertFalse(requests[0].contains("mime_type="))
        assertEquals("https://gutendex.com/books/", provider.requestURL("", "", null))
        assertEquals(page.next, requests[1])
        assertThrows(IllegalArgumentException::class.java) { provider.requestURL("", "", "https://other.example/page") }
    }
    @Test fun equivalentSearchesShareOneCachedRequestWithoutChangingTheLanguageFilter() = runTest {
        var requests = 0
        val client = CatalogClient { url ->
            object : java.net.HttpURLConnection(java.net.URL(url)) {
                override fun connect() { }
                override fun disconnect() { }
                override fun usingProxy() = false
                override fun getResponseCode(): Int {
                    requests++
                    // A cold query returned headers after 39.9s in the live reproduction.
                    assertTrue("Socket timeout would kill a legitimate cold search", readTimeout > 40_000)
                    return 200
                }
                override fun getInputStream() = fixture("catalog-gutendex.json").byteInputStream()
            }
        }
        val provider = GutendexProvider(client)
        provider.search(" Anarchy ", "en")
        provider.search("ANARCHY", "en")
        assertEquals(1, requests)
        provider.search("anarchy", "")
        assertEquals(2, requests)
        assertEquals("https://gutendex.com/books/?search=anarchy", provider.requestURL(" Anarchy ", "", null))
    }

    @Test fun invalidResponsesAndUnsafeURLsAreRejected() {
        for (response in listOf("{}", "<html/>", "{\"count\":1,\"results\":{}}")) {
            assertThrows(Exception::class.java) { GutendexProvider.parse(response.toByteArray(), "https://gutendex.com/books/") }
        }
        assertNull(catalogURL("https://user:pass@test.example/feed"))
        assertNull(catalogURL("file:///tmp/book")); assertNull(catalogURL("http://test.example/book"))
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
            // In-memory progress wins over the saved copy; deduplication must not reload the library.
            val advanced = first.copy(position = com.stillreader.app.core.ReadingPosition(first.pages.first().id, 20))
            assertSame(advanced, repo.insert(source, epub, deduplicate = true, existingBooks = listOf(advanced)))
            // A fresh repository can also find duplicates using only streamed identities.
            assertEquals(first.id, BookRepository(repo.root).insert(source, epub, deduplicate = true).id)
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
