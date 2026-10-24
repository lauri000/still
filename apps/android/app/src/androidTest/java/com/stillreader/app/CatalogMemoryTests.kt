package com.stillreader.app

import android.app.Application
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.stillreader.app.catalog.*
import com.stillreader.app.core.*
import com.stillreader.app.ui.StillApp
import com.stillreader.app.ui.StillTheme
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** Opt-in real-book regression. Fixtures are copied to app files; no books are fetched by tests. */
@RunWith(AndroidJUnit4::class)
class CatalogMemoryTests {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun successiveLargeDownloadsKeepReadingAndResumeWithoutRestart() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("largeCatalogImports") == "true")
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
        val fixtures = File(app.filesDir, "import-memory")
        val root = File(app.cacheDir, "import-memory-${newID()}")
        val titles = linkedMapOf("2701" to "Moby Dick", "1232" to "The Prince", "2600" to "War and Peace")
        titles.keys.forEach { assertTrue("Missing fixture $it.epub", File(fixtures, "$it.epub").isFile) }
        val requests = AtomicLong()
        val provider = GutendexProvider(CatalogClient { url ->
            object : HttpURLConnection(URL(url)) {
                val file = File(fixtures, URL(url).path.substringAfterLast('/'))
                override fun connect() { }
                override fun disconnect() { }
                override fun usingProxy() = false
                override fun getResponseCode(): Int { requests.incrementAndGet(); return 200 }
                override fun getContentLengthLong() = file.length()
                override fun getInputStream() = file.inputStream()
            }
        })
        val store = ViewModelStore()
        lateinit var library: LibraryViewModel
        val sampling = AtomicBoolean(true)
        val peak = AtomicLong()
        val runtime = Runtime.getRuntime()
        val sampler = Thread {
            while (sampling.get()) {
                peak.accumulateAndGet(runtime.totalMemory() - runtime.freeMemory(), ::maxOf)
                Thread.sleep(20)
            }
        }.apply { start() }
        try {
            compose.runOnUiThread {
                library = ViewModelProvider(store, object : ViewModelProvider.Factory {
                    @Suppress("UNCHECKED_CAST")
                    override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T =
                        LibraryViewModel(app, SavedStateHandle(), root) as T
                })[LibraryViewModel::class.java]
            }
            compose.setContent { StillTheme { StillApp(library) } }
            compose.waitUntil(10_000) { !library.state.value.loading }
            for ((ordinal, entry) in titles.entries.withIndex()) {
                val (id, title) = entry
                peak.set(0)
                val started = System.nanoTime()
                compose.runOnUiThread {
                    library.download(CatalogBook("gutenberg:$id", title, epubURL = "https://fixture.example/$id.epub"), provider)
                }
                compose.waitUntil(120_000) { library.state.value.importing == null }
                Log.i("StillImportMemory", "$title import_ms=${(System.nanoTime()-started)/1_000_000} peak_mib=${peak.get()/1048576} max_mib=${runtime.maxMemory()/1048576} error=${library.state.value.error}")
                assertNull(library.state.value.error)
                assertEquals(ordinal + 1, library.state.value.books.size)
                compose.waitUntil(30_000) { library.state.value.reader != null || library.state.value.error != null }
                assertNull(library.state.value.error)
                compose.waitForIdle()
                compose.runOnUiThread {
                    assertTrue(library.state.value.reader!!.tokens.size > 40_000)
                    library.state.value.reader!!.seekPage(1)
                    library.closeReader()
                }
                withContext(Dispatchers.Main) { library.flush() }
                compose.waitForIdle()
            }
            // Reopen each prior book and verify its position, then exercise download deduplication.
            val positions = library.state.value.books.associate { it.id to it.position }
            for (bookID in positions.keys) {
                compose.runOnUiThread { library.readBook(bookID) }
                compose.waitUntil(30_000) { library.state.value.reader != null }
                assertEquals(positions[bookID], library.state.value.reader!!.current!!.position)
                compose.runOnUiThread { library.closeReader() }
            }
            compose.runOnUiThread {
                library.download(CatalogBook("gutenberg:2600", "War and Peace", epubURL = "https://fixture.example/2600.epub"), provider)
            }
            compose.waitUntil(30_000) { library.state.value.reader != null }
            assertEquals(3, library.state.value.books.size)
            assertEquals(3L, requests.get())
            assertEquals(3, root.listFiles()!!.count { it.isDirectory && !it.name.startsWith('.') })
            assertTrue(app.cacheDir.listFiles().orEmpty().none { it.name.startsWith("still-download-") })
        } finally {
            sampling.set(false); sampler.join()
            withContext(Dispatchers.Main) { library.closeReader(); library.flush(); store.clear() }
            root.deleteRecursively()
        }
    }
}
