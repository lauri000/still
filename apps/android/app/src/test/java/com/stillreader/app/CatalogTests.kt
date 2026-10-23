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
        assertTrue(requests[0].contains("search=Austen+%26+Bront%C3%AB"))
        assertTrue(requests[0].contains("languages=en%2Cfr")); assertFalse(requests[0].contains("copyright=")); assertFalse(requests[0].contains("mime_type="))
        assertEquals("https://gutendex.com/books/", provider.requestURL("", "", null))
        assertEquals(page.next, requests[1])
        assertThrows(IllegalArgumentException::class.java) { provider.requestURL("", "", "https://other.example/page") }
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
