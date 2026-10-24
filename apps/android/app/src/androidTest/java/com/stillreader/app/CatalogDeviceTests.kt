package com.stillreader.app

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.SavedStateHandle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.stillreader.app.catalog.*
import com.stillreader.app.data.*
import com.stillreader.app.ui.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class CatalogDeviceTests {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val application get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
    private fun fixture(name: String) = InstrumentationRegistry.getInstrumentation().context.assets.open(name).use { it.readBytes() }
    @Test fun searchDownloadOpensReaderOfflineAndDeduplicates() {
        val root = File(application.cacheDir, "catalog-test-${java.util.UUID.randomUUID()}")
        val requests = java.util.Collections.synchronizedList(mutableListOf<String>())
        val client = object : CatalogClient() {
            override suspend fun get(url: String, limit: Int, progress: (Float?) -> Unit): ByteArray {
                requests += url
                return if (url.endsWith(".epub")) fixture("sample.epub").also { progress(.5f); progress(1f) }
                else if (url.endsWith(".jpg")) byteArrayOf() else fixture("catalog-gutendex.json")
            }
            override suspend fun getToFile(url: String, destination: File, limit: Int, progress: (Float?) -> Unit) {
                requests += url
                destination.writeBytes(fixture("sample.epub")); progress(.5f); progress(1f)
            }
        }
        lateinit var library: LibraryViewModel
        lateinit var catalog: CatalogViewModel
        try {
            compose.runOnUiThread {
                library = LibraryViewModel(application, SavedStateHandle(), root)
                catalog = CatalogViewModel(initialProvider = GutendexProvider(client))
            }
            compose.setContent {
                StillTheme {
                    val state by library.state.collectAsState()
                    if (state.selectedID != null) StillApp(library) else CatalogScreen(library, {}, catalog)
                }
            }
            compose.waitUntil(10_000) { !library.state.value.loading && catalog.state.value.page != null }
            compose.onNodeWithTag("catalogSearch").performTextInput("Austen")
            compose.onNodeWithTag("catalogSearch").performImeAction()
            compose.waitUntil(5_000) { requests.any { "search=austen" in it } }
            compose.onNodeWithText("A quiet test").performClick()
            compose.onNodeWithTag("downloadEPUB").performClick()
            compose.waitUntil(20_000) { library.state.value.books.isNotEmpty() && library.state.value.importing == null }
            assertNull(library.state.value.error)
            val book = library.state.value.books.single()
            assertEquals("gutenberg:1", book.catalogID); assertEquals("A quiet test", book.title)
            assertNotNull(book.coverData); assertTrue(book.pages.size == 2)
            assertTrue(book.pages.joinToString("") { it.text }.contains("Café, naïve, and 世界"))
            assertEquals(1, library.repository.load().books.size)
            assertTrue(File(library.repository.directory(book.id), "original.epub").isFile)
            compose.onNodeWithTag("startReading").assertDoesNotExist()
            compose.waitUntil(5_000) { library.state.value.reader != null }
            assertTrue(library.state.value.reader!!.tokens.isNotEmpty())
            compose.runOnUiThread {
                library.closeReader()
                library.download(catalog.state.value.page!!.books.single(), catalog.provider)
            }
            compose.waitForIdle()
            assertEquals(1, library.state.value.books.size)
            assertEquals(1, requests.count { it.endsWith(".epub") })
            runBlocking { withContext(Dispatchers.Main) { library.flush() } }
        } finally {
            runCatching { compose.runOnUiThread { library.pause() } }
            runCatching { compose.runOnUiThread { catalog.cancel() } }
            root.deleteRecursively()
        }
    }
    @Test fun liveGutenbergBrowse() = runBlocking {
        org.junit.Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("liveCatalog") == "true")
        val page = withTimeout(CATALOG_REQUEST_TIMEOUT_MILLIS) { GutendexProvider().search("", "") }
        assertTrue("Live Gutenberg returned no downloadable books", page.books.isNotEmpty())
        assertTrue(page.books.all { it.id.startsWith("gutenberg:") && it.epubURL != null })
        assertNotNull(page.next)
    }
    @Test fun liveGutenbergAnarchySearch() = runBlocking {
        org.junit.Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("liveCatalog") == "true")
        val provider = GutendexProvider()
        for (language in listOf("", "en")) {
            val start = System.nanoTime()
            val page = withTimeout(CATALOG_REQUEST_TIMEOUT_MILLIS) { provider.search("Anarchy", language) }
            assertTrue("Live anarchy search returned no matching EPUBs", page.books.any { it.title.contains("anarchy", ignoreCase = true) })
            assertTrue(page.books.all { it.id.startsWith("gutenberg:") && it.epubURL != null })
            android.util.Log.i("CatalogSearchTest", "anarchy / $language: ${page.books.size} books in ${(System.nanoTime() - start) / 1_000_000}ms")
        }
    }

    @Test fun networkPermissionAndCancelRetryStates() {
        val root = File(application.cacheDir, "catalog-permission-${java.util.UUID.randomUUID()}")
        val allowed = mutableStateOf(false)
        var requests = 0
        val client = object : CatalogClient() {
            override suspend fun get(url: String, limit: Int, progress: (Float?) -> Unit): ByteArray {
                requests++
                if (requests == 1) awaitCancellation()
                return fixture("catalog-gutendex.json")
            }
        }
        lateinit var library: LibraryViewModel
        lateinit var catalog: CatalogViewModel
        try {
            compose.runOnUiThread {
                library = LibraryViewModel(application, SavedStateHandle(), root)
                catalog = CatalogViewModel(initialProvider = GutendexProvider(client))
            }
            compose.setContent { StillTheme { CatalogScreen(library, {}, catalog, networkAllowed = allowed.value) } }
            compose.onNodeWithText("Network access is disabled for Still.").assertIsDisplayed()
            compose.onNodeWithText("Open app settings").assertIsDisplayed()
            assertEquals(0, requests)
            compose.runOnUiThread { allowed.value = true }
            compose.onNodeWithText("Loading books…").assertIsDisplayed()
            compose.onNodeWithText("Cancel").performClick()
            compose.onNodeWithText("Loading stopped. Tap Try again when you’re ready.").assertIsDisplayed()
            compose.onNodeWithText("Try again").performClick()
            compose.waitUntil(5_000) { catalog.state.value.page != null }
            compose.onNodeWithText("A quiet test").assertIsDisplayed()
            compose.onNodeWithText("Catalogs").assertDoesNotExist()
            compose.onNodeWithText("Standard Ebooks").assertDoesNotExist()
        } finally {
            compose.runOnUiThread { catalog.cancel(); library.pause() }
            root.deleteRecursively()
        }
    }
    @Test fun localEpubImportAndBackupRestoreUseSamePipeline() = runBlocking {
        val source = File(application.cacheDir, "local-${java.util.UUID.randomUUID()}.epub").apply { writeBytes(fixture("sample.epub")) }
        val root = File(application.cacheDir, "epub-backup-${java.util.UUID.randomUUID()}")
        val importer = BookImporter(application)
        var imported: ImportedBook? = null
        var restored: ImportedBook? = null
        try {
            imported = importer.import(android.net.Uri.fromFile(source)) { }
            val repo = BookRepository(root)
            val book = repo.insert(imported.book, imported.source, imported.deduplicate)
            val backup = File(application.cacheDir, "test-${java.util.UUID.randomUUID()}.stillbook")
            try {
                backup.outputStream().use { repo.export(book, it) }
                restored = importer.import(android.net.Uri.fromFile(backup)) { }
                assertArrayEquals(source.readBytes(), restored.source.readBytes())
                assertEquals(book.pages.map { it.text }, restored.book.pages.map { it.text })
                assertEquals(book.pages.map { it.wordIndex }, restored.book.pages.map { it.wordIndex })
                assertEquals(book.chapters, restored.book.chapters)
                assertEquals(book.coverData, restored.book.coverData)
                assertTrue(restored.book.isEpub); assertFalse(restored.book.needsReprocessing)
            } finally { backup.delete() }
        } finally { imported?.source?.delete(); restored?.source?.delete(); source.delete(); root.deleteRecursively() }
    }
}
