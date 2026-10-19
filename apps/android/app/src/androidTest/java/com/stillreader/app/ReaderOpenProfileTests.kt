package com.stillreader.app

import android.app.Application
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.stillreader.app.core.*
import com.stillreader.app.data.*
import com.stillreader.app.ui.StillApp
import com.stillreader.app.ui.StillTheme
import kotlinx.coroutines.*
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.regex.Pattern

/** Local-only profiling. No PDF or extracted text is bundled or logged. */
@RunWith(AndroidJUnit4::class)
class ReaderOpenProfileTests {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private lateinit var model: LibraryViewModel
    private fun <T> measure(label: String, operation: () -> T): T {
        val began = System.nanoTime()
        return operation().also { Log.i("StillOpenProfile", "$label ms=${(System.nanoTime() - began) / 1e6}") }
    }

    @Test fun profileContinueReading() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("readerOpenEvaluation") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as Application
        val input = File(app.filesDir, "reader-open-evaluation/hume.pdf")
        val cache = File(input.parentFile, "hume-book.json")
        val cached = if (cache.isFile) measure("decode_cached_book") { BookCodec.decode(cache.readText()) } else null
        val loaded = cached?.takeUnless { it.needsReprocessing } ?: run {
                val began = System.nanoTime()
                PdfImporter(app).extract(input, "hume_enquiry.pdf").also {
                    Log.i("StillOpenProfile", "import_ms=${(System.nanoTime() - began) / 1e6}")
                    cache.writeText(BookCodec.encode(it))
                }
            }
        val encoded = BookCodec.encode(loaded.copy(readingStart = ReadingPosition(loaded.pages.first().id, 0),
            position = ReadingPosition(loaded.pages[loaded.pages.size / 2].id, 200)))
        val book = measure("load_prepared_output") { BookCodec.decode(encoded) }
        measure("materialize_prepared_output") { book.readerCache.get(book) }
        Log.i("StillOpenProfile", "stored_bytes=${encoded.toByteArray().size} prepared=${!book.needsReprocessing}")
        Log.i("StillOpenProfile", "pages=${book.pages.size} chars=${book.pages.sumOf { it.text.length }} max_page_chars=${book.pages.maxOf { it.text.length }} chapters=${book.chapters.size}")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        var tokens = emptyList<ReadingToken>()
        instrumentation.runOnMainSync {
            repeat(3) { iteration ->
                tokens = measure("tokens_$iteration") { Tokenizer.tokens(book) }
                measure("structure_$iteration") { ReadingStructure(book, tokens) }
                measure("position_$iteration") { Tokenizer.index(book.position, tokens) }
                measure("whole_session_$iteration") { ReadingSession(book, scope) {} }
                val candidate = measure("single_matcher_tokens_$iteration") { singleMatcherTokens(book) }
                assertEquals(tokens, candidate)
            }
        }
        Log.i("StillOpenProfile", "words=${tokens.size} exact_token_parity=true")
        val sampling = AtomicBoolean(true)
        val samples = mutableMapOf<String, Int>()
        val thread = Thread {
            while (sampling.get()) {
                val stack = Looper.getMainLooper().thread.stackTrace
                val key = stack.take(9).joinToString(" > ") { "${it.className}.${it.methodName}" }
                samples[key] = (samples[key] ?: 0) + 1
                Thread.sleep(3)
            }
        }.apply { start() }
        instrumentation.runOnMainSync { measure("sampled_whole_session") { ReadingSession(book, scope) {} } }
        sampling.set(false); thread.join()
        samples.entries.sortedByDescending { it.value }.take(6).forEach { Log.i("StillOpenProfile", "samples=${it.value} stack=${it.key}") }

        val root = File(app.cacheDir, "reader-open-profile-${newID()}")
        val store = ViewModelStore()
        BookRepository(root).insert(book, input)
        try {
            compose.runOnUiThread {
                model = ViewModelProvider(store, object : ViewModelProvider.Factory {
                    @Suppress("UNCHECKED_CAST")
                    override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T =
                        LibraryViewModel(app, SavedStateHandle(), root) as T
                })[LibraryViewModel::class.java]
            }
            compose.setContent { StillTheme { StillApp(model) } }
            compose.waitUntil(20_000) { !model.state.value.loading }
            compose.runOnUiThread { model.select(book.id) }
            compose.waitForIdle()
            repeat(3) { iteration ->
                val before = SystemClock.elapsedRealtimeNanos()
                compose.onNodeWithTag("startReading").performClick()
                compose.waitUntil(20_000) { model.state.value.reader != null }
                compose.waitForIdle()
                Log.i("StillOpenProfile", "continue_to_idle_$iteration ms=${(SystemClock.elapsedRealtimeNanos() - before) / 1e6}")
                assertEquals(Tokenizer.index(book.position, tokens), model.state.value.reader!!.state.value.index)
                compose.runOnUiThread { model.closeReader() }
                compose.waitForIdle()
            }
        } finally {
            if (::model.isInitialized) {
                withContext(Dispatchers.Main) { model.pause(); model.flush(); store.clear() }
            }
            scope.cancel()
            root.deleteRecursively()
        }
    }

    private val pattern = Pattern.compile("[^\\p{Z}\\u0009-\\u000D\\u0085]+")
    private fun singleMatcherTokens(book: Book): List<ReadingToken> = buildList {
        book.pages.forEachIndexed { pageIndex, page ->
            if (!book.includesPage(pageIndex)) return@forEachIndexed
            val matcher = pattern.matcher(page.text)
            if (!matcher.find()) return@forEachIndexed
            do {
                val start = matcher.start()
                val end = matcher.end()
                val word = matcher.group()
                val hasNext = matcher.find()
                val gap = page.text.substring(end, if (hasNext) matcher.start() else page.text.length)
                if (book.readingStart?.let { it.pageID == page.id && end <= it.utf16Offset } != true) {
                    add(ReadingToken(size, page.id, pageIndex, TextWord(word, start, end - start, "\n\n" in gap || "\r\n\r\n" in gap)))
                }
                if (!hasNext) break
            } while (true)
        }
    }
}
