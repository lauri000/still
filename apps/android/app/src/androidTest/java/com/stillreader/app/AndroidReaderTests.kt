package com.stillreader.app

import android.app.Application
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.stillreader.app.core.*
import com.stillreader.app.data.*
import com.stillreader.app.ui.StillApp
import com.stillreader.app.ui.StillTheme
import kotlinx.coroutines.*
import kotlinx.serialization.Serializable
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.Base64

@Serializable
private data class ExpectedWord(val text: String, val offset: Int, val length: Int, val endsParagraph: Boolean, val duration: Double)
@Serializable
private data class TokenCase(val name: String, val text: String, val words: List<ExpectedWord>)

@RunWith(AndroidJUnit4::class)
class AndroidReaderTests {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private lateinit var root: File
    private lateinit var model: LibraryViewModel
    private var previousAnimatedScrolling = false
    private var previousFocusLetter = false
    private var previousWPM = 250
    private var previousNaturalPauses = true
    private var previousStrictReview = false
    private var previousActiveWordSize: Int? = null
    private var previousPassageTextSize: Int? = null
    private val store = ViewModelStore()
    private val application get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
    private fun fixture(name: String) = InstrumentationRegistry.getInstrumentation().context.assets.open(name)
    private fun backup() = BookCodec.json.decodeFromString<BookBackup>(fixture("apple-backup.json").bufferedReader().use { it.readText() })

    @Before fun before() {
        root = File(application.cacheDir, "reader-test-${newID()}").apply { mkdirs() }
        val preferences = application.getSharedPreferences("reading", 0)
        previousAnimatedScrolling = preferences.getBoolean("animatedScrolling", false)
        previousFocusLetter = preferences.getBoolean("focusLetter", false)
        previousWPM = preferences.getInt("wpm", 250)
        previousNaturalPauses = preferences.getBoolean("naturalPauses", true)
        previousStrictReview = preferences.getBoolean("strictReview", false)
        previousPassageTextSize = if (preferences.contains("passageTextSize")) preferences.getInt("passageTextSize", 22) else null
        previousActiveWordSize = if (preferences.contains("activeWordSizePercent")) preferences.getInt("activeWordSizePercent", 100) else null
        preferences.edit().putInt("passageTextSize", 22).putBoolean("animatedScrolling", false).putBoolean("focusLetter", false).putInt("wpm", 250).putInt("activeWordSizePercent", 100).putBoolean("naturalPauses", true).putBoolean("strictReview", false).commit()
    }
    private fun launch(seed: Boolean = true, legacy: Boolean = false, book: Book? = null) {
        if (seed) {
            val backup = backup()
            val pdf = File(root, "source.pdf").apply { writeBytes(Base64.getDecoder().decode(backup.pdfData)) }
            BookRepository(File(root, "books")).insert(if (legacy) backup.book else ReaderPipeline.prepare(book ?: backup.book), pdf)
        }
        compose.runOnUiThread {
            compose.activity.enableEdgeToEdge()
            model = ViewModelProvider(store, object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T =
                    LibraryViewModel(application, SavedStateHandle(), File(root, "books")) as T
            })[LibraryViewModel::class.java]
        }
        compose.setContent { StillTheme { StillApp(model) } }
        compose.waitUntil(10_000) { !model.state.value.loading }
    }
    @After fun after() {
        if (::model.isInitialized) {
            compose.runOnUiThread { model.pause() }
            runBlocking { withContext(Dispatchers.Main) { model.flush() } }
            compose.runOnUiThread { store.clear() }
        }
        root.deleteRecursively()
        application.getSharedPreferences("reading", 0).edit().apply {
            putBoolean("animatedScrolling", previousAnimatedScrolling).putBoolean("focusLetter", previousFocusLetter).putInt("wpm", previousWPM)
            putBoolean("naturalPauses", previousNaturalPauses).putBoolean("strictReview", previousStrictReview)
            previousPassageTextSize?.let { putInt("passageTextSize", it) } ?: remove("passageTextSize")
            previousActiveWordSize?.let { putInt("activeWordSizePercent", it) } ?: remove("activeWordSizePercent")
        }.commit()
    }
    private fun readBook() {
        if (model.state.value.reader == null && !model.state.value.opening) {
            if (compose.onNodeWithTag("startReading").isDisplayed()) compose.onNodeWithTag("startReading").performClick()
            else compose.onNodeWithTag("book-${model.state.value.books.first().title}").performClick()
        }
        compose.waitUntil(20_000) { model.state.value.reader != null && !model.state.value.opening }
        compose.waitForIdle()
    }
    private fun bookOptions() {
        if (model.state.value.reader != null || model.state.value.opening) compose.runOnUiThread { model.select(null) }
        val book = model.state.value.books.first()
        compose.onNodeWithTag("bookOptions-${book.id}").performClick()
    }

    @Test fun epubLongSectionStartingWordPickerKeepsExactPosition() {
        val source = runCatching { fixture("epub/hume-enquiry-epub3.epub") }.getOrNull()
        org.junit.Assume.assumeTrue("Download the optional EPUB corpus first", source != null)
        launch(seed = false)
        val file = File(root, "hume.epub")
        source!!.use { input -> file.outputStream().use { input.copyTo(it) } }
        compose.runOnUiThread { model.importFile(Uri.fromFile(file)) }
        compose.waitUntil(30_000) { model.state.value.importing == null && model.state.value.selectedID != null }
        val book = (model.selectedBook() ?: model.state.value.books.first())
        val page = book.pages.indices.maxBy { book.pages[it].text.length }
        val word = book.pages[page].words[10_000]
        compose.runOnUiThread { model.closeReader(); model.updateBook(book.id) { it.withReadingStart(ReadingPosition(book.pages[page].id, word.offset)) } }
        bookOptions()
        compose.onNodeWithText("Choose starting word").performClick()
        compose.onNodeWithTag("startingWords").assertIsDisplayed()
        compose.onNodeWithText("Start at “${word.text}”").assertIsDisplayed()
        compose.onNodeWithText("Start here").performClick()
        readBook()
        compose.waitUntil(10_000) { model.state.value.reader != null }
        compose.runOnIdle { assertEquals(word.offset, model.state.value.reader!!.current!!.word.offset) }
    }

    @Test fun epubRapidEditsOpenAndRestoreUseLatestPreparedSnapshot() {
        launch(seed = false)
        val file = File(root, "structured.epub")
        fixture("epub/structured.epub").use { input -> file.outputStream().use { input.copyTo(it) } }
        compose.runOnUiThread { model.importFile(Uri.fromFile(file)) }
        compose.waitUntil(20_000) { model.state.value.importing == null && model.state.value.selectedID != null }
        val original = (model.selectedBook() ?: model.state.value.books.first())
        compose.runOnUiThread { model.closeReader() }
        compose.runOnUiThread {
            model.updateBook(original.id, debounce = true) { it.editText(0, "First draft") }
            model.pause() // Queue an earlier snapshot before the final edit.
            model.updateBook(original.id, debounce = true) { it.editText(0, "Final draft\nwith a line break") }
            model.updateBook(original.id) { it.copy(pages = it.pages.mapIndexed { i, p -> if (i == 0) p.copy(isReviewed = true) else p }) }
            model.openReader()
        }
        compose.waitUntil(20_000) { model.state.value.reader != null || model.state.value.error != null }
        compose.runOnIdle {
            assertNull(model.state.value.error)
            assertEquals("Final", model.state.value.reader!!.current!!.word.text)
            assertFalse((model.selectedBook() ?: model.state.value.books.first()).needsReprocessing)
            assertFalse((model.selectedBook() ?: model.state.value.books.first()).chaptersCustomized)
        }
        runBlocking { withContext(Dispatchers.Main) { model.flush() } }
        val saved = model.repository.load().books.single()
        assertEquals("Final draft\nwith a line break", saved.pages[0].text)
        assertTrue(saved.pages[0].words.any { it.endsLine })
        assertTrue(saved.pages[0].isReviewed)
        compose.runOnUiThread { model.closeReader(); model.restoreExtractedText(original.id, 0) }
        compose.waitUntil(20_000) { model.state.value.importing == null }
        runBlocking { withContext(Dispatchers.Main) { model.flush() } }
        assertNull(model.state.value.error)
        assertEquals(original.pages[0].headings, (model.selectedBook() ?: model.state.value.books.first()).pages[0].headings)
        assertEquals(original.chapters.map { it.utf16Offset }, (model.selectedBook() ?: model.state.value.books.first()).chapters.map { it.utf16Offset })
    }

    @Test fun epubImportChaptersModesResumeAndBackup() {
        launch(seed = false)
        val file = File(root, "structured.epub")
        fixture("epub/structured.epub").use { input -> file.outputStream().use { input.copyTo(it) } }
        compose.runOnUiThread { model.importFile(Uri.fromFile(file)) }
        compose.waitUntil(20_000) { model.state.value.importing == null && model.state.value.selectedID != null }
        assertNull(model.state.value.error)
        val book = (model.selectedBook() ?: model.state.value.books.first())
        assertTrue(book.isEpub)
        readBook()
        compose.onNodeWithTag("epubPreview").assertDoesNotExist()
        // Two same-title chapters are in one source section; the second must retain its exact anchor.
        val chapter = book.chapters[2]
        compose.onNodeWithTag("readerContents").performClick()
        val destination = "contentsEntry-chapter-${chapter.id}"
        compose.waitUntil(5_000) { compose.onNodeWithTag(destination).isDisplayed() }
        compose.onNodeWithTag(destination).performClick()
        readBook()
        compose.waitUntil(10_000) { model.state.value.reader != null }
        val session = model.state.value.reader!!
        compose.onNodeWithTag("currentHeading").assertTextEquals("Repeat")
        compose.runOnIdle { assertEquals(chapter.utf16Offset, session.current!!.word.offset) }
        val index = session.state.value.index
        compose.onNodeWithTag("toggleContext").performClick()
        compose.onNodeWithTag("currentPassageRow").assertTextContains("Repeat", substring = true)
        compose.onNodeWithTag("toggleContext").performClick()
        compose.runOnIdle { assertEquals(index, session.state.value.index) }
        compose.onNodeWithTag("readerContents").performClick()
        compose.waitUntil(5_000) { compose.onNodeWithTag(destination).isDisplayed() }
        compose.onNodeWithTag(destination).assertIsSelected()
        compose.onNodeWithTag("contentsEntry-chapter-${book.chapters[0].id}").assertIsEnabled()
        compose.onNodeWithText("Done").performClick()
        compose.onNodeWithContentDescription("Back").performClick()
        readBook()
        compose.waitUntil(10_000) { model.state.value.reader != null }
        compose.runOnIdle { assertEquals(chapter.utf16Offset, model.state.value.reader!!.current!!.word.offset) }
        compose.onNodeWithContentDescription("Back").performClick()
        val saved = (model.selectedBook() ?: model.state.value.books.first())
        val backup = File(root, "epub-backup.json")
        backup.outputStream().use { model.repository.export(saved, it) }
        compose.runOnUiThread { model.importFile(Uri.fromFile(backup)) }
        compose.waitUntil(20_000) { model.state.value.importing == null && model.state.value.books.size == 2 }
        assertNull(model.state.value.error)
        val copy = (model.selectedBook() ?: model.state.value.books.first())
        assertNotEquals(saved.id, copy.id)
        assertEquals(saved.position, copy.position)
        assertArrayEquals(file.readBytes(), model.repository.sourceFile(copy).readBytes())
    }

    @Test fun bookTypographyAndReaderContentsKeepTheSourcePositionAndTrim() {
        launch(seed = false)
        val file = File(root, "sample.pdf")
        fixture("sample.pdf").use { input -> file.outputStream().use { input.copyTo(it) } }
        compose.runOnUiThread { model.importFile(Uri.fromFile(file)) }
        compose.waitUntil(20_000) { model.state.value.importing == null && model.state.value.selectedID != null }
        readBook()
        compose.waitUntil(10_000) { model.state.value.reader != null }
        compose.onNodeWithTag("readerContents").performClick()
        compose.waitUntil(5_000) { compose.onNodeWithTag("contentsEntry-0").isDisplayed() }
        compose.onNodeWithTag("contentsEntry-0").performClick()
        compose.onNodeWithTag("currentHeading").assertTextEquals("A place to begin").assertIsDisplayed()
        compose.onNodeWithTag("currentWord").assertDoesNotExist()
        fun snapshot(name: String) {
            File(application.getExternalFilesDir(null), "$name.png").outputStream().use {
                compose.onRoot().captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
        }
        snapshot("structure-single")
        val session = model.state.value.reader!!
        compose.onNodeWithTag("holdToRead").performTouchInput { down(center); advanceEventTime(400); up() }
        compose.onNodeWithTag("currentHeading").assertTextEquals("A place to begin")
        val selected = session.state.value.index
        compose.onNodeWithTag("toggleContext").performClick()
        compose.runOnIdle { assertEquals(selected, session.state.value.index) }
        compose.onNodeWithTag("currentPassageRow").assertTextContains("place", substring = true)
        compose.onNodeWithTag("currentHeading").assertDoesNotExist()
        compose.onNodeWithTag("readingOptions").assertDoesNotExist()
        snapshot("structure-multiline")
        val trim = session.book.readingStart
        compose.onNodeWithTag("readerContents").performClick()
        compose.waitUntil(5_000) { compose.onNodeWithTag("contentsEntry-1").isDisplayed() }
        compose.onNodeWithTag("contentsEntry-1").performClick()
        compose.onNodeWithTag("currentPassageRow").assertTextContains("Keep the meaning", substring = true)
        compose.runOnIdle { assertFalse(session.state.value.playing); assertEquals(trim, (model.selectedBook() ?: model.state.value.books.first()).readingStart) }
        compose.onNodeWithTag("toggleContext").performClick()
        compose.onNodeWithTag("currentHeading").assertTextEquals("Keep the meaning")
        val savedIndex = session.state.value.index
        compose.onNodeWithContentDescription("Back").performClick(); readBook()
        compose.waitUntil(10_000) { model.state.value.reader != null }
        compose.onNodeWithTag("currentHeading").assertTextEquals("Keep the meaning")
        compose.runOnIdle { assertEquals(savedIndex, model.state.value.reader!!.state.value.index) }
        compose.onNodeWithTag("readerContents").performClick()
        compose.waitUntil(5_000) { compose.onNodeWithTag("contentsEntry-0").isDisplayed() }
        compose.onNodeWithTag("contentsEntry-0").assertIsEnabled().performClick()
        compose.onNodeWithTag("currentHeading").assertTextEquals("A place to begin")
    }

    @Test fun androidUnicodeMatchesSwiftFixtures() {
        val cases = BookCodec.json.decodeFromString<List<TokenCase>>(fixture("tokenization.json").bufferedReader().use { it.readText() })
        cases.forEach { case ->
            val actual = Tokenizer.words(case.text)
            assertEquals(case.name, case.words.size, actual.size)
            actual.zip(case.words).forEach { (word, expected) ->
                assertEquals(case.name, expected.text, word.text)
                assertEquals(expected.offset, word.offset)
                assertEquals(expected.length, word.length)
                assertEquals(expected.endsParagraph, word.endsParagraph)
                assertEquals(expected.duration, Tokenizer.duration(word, 300.0, true), .00001)
            }
        }
    }
    @Test fun realPdfExtractionRetainsPagesMetadataAndChapters() = runBlocking {
        val file = File(root, "sample.pdf")
        fixture("sample.pdf").use { input -> file.outputStream().use { input.copyTo(it) } }
        val importer = PdfImporter(application)
        val imported = importer.extract(file, "sample.pdf")
        assertEquals(3, imported.pages.size)
        assertEquals("The art of attention", imported.title)
        assertEquals(listOf(0, 1, 2), imported.chapters.map { it.pageIndex })
        assertTrue(imported.pages[0].text.contains("particular kind of quiet"))
        assertTrue(imported.pages.all { !it.isReviewed && it.text == it.processedText && !it.isEdited })
        val image = importer.preview(file, 1, 600)
        assertEquals(600, image.width)
        assertTrue(image.height > image.width)
        image.recycle()
    }
    @Test fun originalPdfOutlineRetainsGroupsNamedActionsAndUnavailableDestinations() = runBlocking {
        val importer = PdfImporter(application)
        val file = File(root, "outline.pdf")
        fixture("outline.pdf").use { input -> file.outputStream().use { input.copyTo(it) } }
        val before = file.readBytes()
        val entries = importer.contents(file)
        assertEquals(listOf("Part", "Later section", "Deep section", "Named section", "GoTo section", "Back to beginning", "Same page", "Untitled section", "Website", "Remote", "Broken named", "Out of range"), entries.map { it.title })
        assertEquals(listOf(null, 2, 2, 1, 3, 0, 0, 2, null, null, null, null), entries.map { it.pageIndex })
        assertEquals(listOf(0, 1, 2, 1, 1, 0, 0, 0, 0, 0, 0, 0), entries.map { it.level })
        assertEquals(listOf("0", "0.0"), entries[2].ancestors)
        val imported = importer.extract(file, "outline.pdf")
        assertEquals(listOf(0, 0, 1, 2, 2, 2, 3), imported.chapters.map { it.pageIndex })
        assertTrue(imported.pages.all {
            it.textProcessingVersion == PdfTextRepair.VERSION && it.text == it.processedText && !it.isEdited
        })
        assertArrayEquals(before, file.readBytes())
        fixture("no-outline.pdf").use { input -> file.outputStream().use { input.copyTo(it) } }
        assertTrue(importer.contents(file).isEmpty())
    }

    @Test fun contentsNavigateDirectReaderWithoutChangingTrim() {
        launch(seed = false)
        val file = File(root, "outline.pdf")
        fixture("outline.pdf").use { input -> file.outputStream().use { input.copyTo(it) } }
        compose.runOnUiThread { model.importFile(Uri.fromFile(file)) }
        compose.waitUntil(20_000) { model.state.value.importing == null && model.state.value.reader != null }
        compose.runOnIdle {
            model.closeReader()
            val book = model.state.value.books.single()
            model.updateBook(book.id) { it.withReadingStart(ReadingPosition(it.pages[1].id, 6)).copy(position = ReadingPosition(it.pages[2].id, 11)) }
            model.openReader()
        }
        readBook()
        val trim = model.state.value.books.single().readingStart
        compose.onNodeWithTag("readerContents").performClick()
        compose.waitUntil(10_000) { compose.onNodeWithTag("contentsEntry-0.0.0").isDisplayed() }
        compose.onNodeWithTag("contentsToggle-0").performClick()
        compose.onNodeWithTag("contentsEntry-0.0.0").assertDoesNotExist()
        compose.onNodeWithTag("contentsToggle-0").performClick()
        compose.onNodeWithTag("contentsEntry-0").assertIsNotEnabled()
        compose.onNodeWithTag("contentsEntry-0.1").performClick()
        compose.runOnIdle {
            assertEquals(trim, model.state.value.books.single().readingStart)
            assertEquals(1, model.state.value.reader!!.current!!.pageIndex)
            model.state.value.reader!!.seek(2)
        }
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithTag("importPDF").assertIsDisplayed()
        readBook()
        compose.runOnIdle { assertEquals(2, model.state.value.reader!!.state.value.index) }
    }

    @Test fun libraryMenuOpensToolsWithoutOpeningReader() {
        launch()
        val original = model.state.value.books.single()
        bookOptions()
        compose.onNodeWithText("Book details").performClick()
        compose.runOnIdle { assertNull(model.state.value.reader) }
        compose.onNodeWithText("Title").performTextReplacement("Renamed book")
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithTag("book-Renamed book").assertIsDisplayed()
        compose.onNodeWithTag("startReading").assertDoesNotExist()
        compose.onNodeWithTag("book-Renamed book").performClick()
        compose.waitUntil(10_000) { model.state.value.reader != null }
        compose.runOnIdle { assertEquals(original.position, model.state.value.books.single().position) }
        compose.onNodeWithContentDescription("Back").performClick()
        bookOptions()
        compose.onNodeWithText("Delete book").performClick()
        compose.onNodeWithText("Cancel").performClick()
        compose.onNodeWithTag("book-Renamed book").assertIsDisplayed()
        bookOptions()
        compose.onNodeWithText("Delete book").performClick()
        compose.onNodeWithText("Delete").performClick()
        compose.waitUntil(5_000) { model.state.value.books.isEmpty() }
        compose.onNodeWithText("No books yet").assertIsDisplayed()
    }

    private fun outlineScreenshot(name: String) {
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        File(application.getExternalFilesDir(null), "$name.png").outputStream().use {
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
        bitmap.recycle()
    }

    @Test fun emptyContentsKeepsReaderPageSelectionAvailable() {
        launch(seed = false)
        val file = File(root, "no-outline.pdf")
        fixture("no-outline.pdf").use { input -> file.outputStream().use { input.copyTo(it) } }
        compose.runOnUiThread { model.importFile(Uri.fromFile(file)) }
        compose.waitUntil(20_000) { model.state.value.importing == null && model.state.value.reader != null }
        compose.onNodeWithTag("readerContents").performClick()
        compose.waitUntil(10_000) { compose.onNodeWithText("No PDF contents").isDisplayed() }
        compose.onNodeWithText("Go to page").performClick()
        compose.onNodeWithTag("pageNumber").performTextReplacement("2")
        compose.onNodeWithText("Go").performClick()
        compose.runOnIdle { assertEquals(1, model.state.value.reader!!.current!!.pageIndex) }
    }

    @Test fun emptyLibraryAndPdfImportOpenReaderDirectly() {
        launch(seed = false)
        compose.onNodeWithText("No books yet").assertIsDisplayed()
        val file = File(root, "sample.pdf")
        fixture("sample.pdf").use { input -> file.outputStream().use { input.copyTo(it) } }
        compose.runOnUiThread { model.importFile(Uri.fromFile(file)) }
        compose.waitUntil(20_000) { model.state.value.importing == null && model.state.value.reader != null }
        compose.onNodeWithTag("readingSurface").assertIsDisplayed()
        compose.onNodeWithTag("pdfPreview").assertDoesNotExist()
        compose.onNodeWithTag("startReading").assertDoesNotExist()
        compose.runOnIdle { assertFalse(model.state.value.reader!!.state.value.playing) }
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithTag("importPDF").assertIsDisplayed()
        outlineScreenshot("direct-reading-library")
        compose.onNodeWithTag("book-The art of attention").performClick()
        compose.waitUntil(10_000) { model.state.value.reader != null }
        compose.onNodeWithTag("readingSurface").assertIsDisplayed()
        outlineScreenshot("direct-reading-open")
    }

    @Test fun directOpenSkipsABlankCover() {
        val source = backup().book
        launch(book = source.editText(0, "").copy(position = null, readingStart = null))
        compose.onNodeWithTag("book-The art of attention").performClick()
        compose.waitUntil(10_000) { model.state.value.reader != null }
        compose.runOnIdle { assertEquals(1, model.state.value.reader!!.current!!.pageIndex) }
        compose.onNodeWithTag("pdfPreview").assertDoesNotExist()
    }

    @Test fun directOpenKeepsTrimWithoutASavedPositionAndCanBeCancelled() {
        val source = backup().book
        val trim = ReadingPosition(source.pages[1].id, source.pages[1].words[1].offset)
        launch(book = source.withReadingStart(trim).copy(position = null))
        compose.runOnUiThread { model.readBook(source.id); model.select(null) }
        compose.waitForIdle()
        compose.runOnIdle { assertNull(model.state.value.reader); assertFalse(model.state.value.opening) }
        compose.onNodeWithTag("book-The art of attention").performClick()
        compose.waitUntil(10_000) { model.state.value.reader != null }
        compose.runOnIdle {
            assertEquals(trim, model.state.value.books.single().readingStart)
            assertEquals(trim.utf16Offset, model.state.value.reader!!.current!!.word.offset)
        }
    }

    @Test fun swipePauseAndResumeKeepExactWord() {
        launch()
        compose.onNodeWithTag("book-The art of attention").performClick()
        readBook()
        compose.onNodeWithTag("currentWord").assertTextEquals("the")
        compose.onNodeWithTag("holdToRead").performTouchInput { down(center) }
        compose.runOnIdle { assertTrue(model.state.value.reader!!.state.value.playing) }
        compose.onNodeWithTag("holdToRead").performTouchInput { up() }
        compose.onNodeWithTag("readingSurface").performTouchInput {
            swipe(Offset(width * .75f, height * .3f), Offset(width * .25f, height * .3f), 300)
        }
        compose.runOnIdle { assertFalse(model.state.value.reader!!.state.value.playing) }
        var forward = 0
        compose.runOnIdle { forward = model.state.value.reader!!.state.value.index }
        compose.onNodeWithTag("readingSurface").performTouchInput {
            swipe(Offset(width * .3f, height * .3f), Offset(width * .6f, height * .3f), 300)
        }
        compose.runOnIdle { assertTrue(model.state.value.reader!!.state.value.index < forward) }
        compose.onNodeWithTag("toggleContext").performClick()
        compose.runOnIdle { model.state.value.reader!!.seek(2) }
        compose.onNodeWithTag("currentPassageRow").assertTextContains("story.", substring = true)
        compose.onNodeWithContentDescription("Back").performClick()
        readBook()
        compose.onNodeWithTag("currentWord").assertTextEquals("story.")
        runBlocking { withContext(Dispatchers.Main) { model.pause(); model.flush() } }
        val persisted = BookRepository(File(root, "books")).load().books.single()
        val tokens = Tokenizer.tokens(persisted)
        assertEquals("story.", tokens[Tokenizer.index(persisted.position, tokens)].word.text)
    }
    @Test fun manualPassageScrollsAndWordTapPreservesExactPosition() {
        launch(seed = false)
        val file = File(root, "sample.pdf")
        fixture("sample.pdf").use { input -> file.outputStream().use { input.copyTo(it) } }
        compose.runOnUiThread { model.importFile(Uri.fromFile(file)) }
        compose.waitUntil(20_000) { model.state.value.importing == null && model.state.value.selectedID != null }
        readBook()
        compose.waitUntil(10_000) { model.state.value.reader != null }
        compose.waitUntil(5_000) { compose.onNodeWithTag("currentWord").isDisplayed() }
        compose.onNodeWithTag("readingOptions").performClick()
        compose.onNodeWithContentDescription("Animated scrolling").performScrollTo().assertIsOff().performClick().assertIsOn()
        compose.onNodeWithContentDescription("Focus letter").performScrollTo().assertIsOff().performClick().assertIsOn()
        compose.onNodeWithText("Done").performClick()
        compose.runOnIdle { model.state.value.reader!!.seek(40) }
        compose.onNodeWithTag("toggleContext").performClick()
        compose.onNodeWithTag("wordRibbon").assertDoesNotExist()
        compose.onNodeWithTag("currentWord").assertDoesNotExist()
        compose.onNodeWithTag("readingOptions").assertDoesNotExist()
        compose.onNodeWithTag("holdToRead").assertDoesNotExist()
        compose.onNodeWithTag("currentPassageRow").assertIsDisplayed()
        compose.runOnIdle { assertEquals(40, model.state.value.reader!!.state.value.index) }
        compose.onNodeWithTag("passageScroll").performTouchInput { swipeUp() }
        compose.runOnIdle {
            val session = model.state.value.reader!!
            assertTrue(session.state.value.index > 40)
            assertFalse(session.state.value.playing)
            assertTrue(session.naturalPauses)
            assertEquals(250.0, session.wordsPerMinute, .01)
        }
        // The top row may be clipped after scrolling. Pick a fully visible body
        // row so glyph coordinates remain valid at every viewport width.
        val rowNode = compose.onAllNodes(SemanticsMatcher("passage body row") {
            val tag = it.config.getOrElse(androidx.compose.ui.semantics.SemanticsProperties.TestTag) { "" }
            val index = tag.removePrefix("passageRow-").toIntOrNull()
            index != null && model.state.value.reader!!.structure.headingAt(index) == null
        }).fetchSemanticsNodes().first { it.size.height > 0 && it.boundsInRoot.height >= it.size.height - 1 }
        val row = compose.onNodeWithTag(rowNode.config[androidx.compose.ui.semantics.SemanticsProperties.TestTag])
        val layouts = mutableListOf<TextLayoutResult>()
        row.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val layout = layouts.single()
        val links = layout.layoutInput.text.getStringAnnotations("word", 0, layout.layoutInput.text.length)
        val link = links[1.coerceAtMost(links.lastIndex)]
        val selected = link.item.toInt()
        val selectedText = layout.layoutInput.text.text.substring(link.start, link.end)
        val glyph = layout.getBoundingBox(link.start)
        // Avoid the exact midpoint between text cursor offsets at fractional densities.
        row.performTouchInput { click(Offset(glyph.left + glyph.width * .25f, glyph.center.y)) }
        compose.onNodeWithTag("wordRibbon").assertIsDisplayed()
        compose.onNodeWithTag("holdToRead").assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(selected, model.state.value.reader!!.state.value.index)
            assertFalse(model.state.value.reader!!.state.value.playing)
        }
        compose.onNodeWithTag("currentWord").assertTextEquals(selectedText)
        repeat(2) {
            compose.onNodeWithTag("toggleContext").performClick()
            compose.onNodeWithTag("currentPassageRow").assertIsDisplayed()
            compose.runOnIdle { assertEquals(selected, model.state.value.reader!!.state.value.index) }
            compose.onNodeWithTag("toggleContext").performClick()
            compose.runOnIdle { assertEquals(selected, model.state.value.reader!!.state.value.index) }
        }
        compose.onNodeWithContentDescription("Back").performClick()
        readBook()
        compose.runOnIdle { assertEquals(selected, model.state.value.reader!!.state.value.index) }
        compose.onNodeWithTag("readingOptions").performClick()
        compose.onNodeWithContentDescription("Animated scrolling").assertIsOn()
        compose.onNodeWithContentDescription("Focus letter").assertIsOn()
        compose.onNodeWithText("Done").performClick()
    }

    @Test fun passageTextSizePreservesPositionAndPersistsSeparately() {
        launch()
        compose.onNodeWithTag("book-The art of attention").performClick()
        readBook()
        compose.runOnIdle { model.state.value.reader!!.seek(2) }
        val original = model.state.value.reader!!.current!!.position
        compose.onNodeWithTag("toggleContext").performClick()
        compose.onNodeWithTag("passageTextOptions").performClick()
        compose.onNodeWithTag("passageTextSizeValue").assertTextEquals("22")
        repeat(5) { compose.onNodeWithTag("largerPassageText").performScrollTo().performClick() }
        compose.onNodeWithTag("passageTextSizeValue").assertTextEquals("32")
        compose.onNodeWithTag("largerPassageText").assertIsNotEnabled()
        outlineScreenshot("scroll-text-options")
        compose.onNodeWithText("Done").performClick()
        compose.onNodeWithTag("currentPassageRow").assertIsDisplayed()
        compose.runOnIdle { assertEquals(original, model.state.value.reader!!.current!!.position) }
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithTag("currentPassageRow").performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertEquals(32f, layouts.single().layoutInput.style.fontSize.value, .01f)
        compose.onNodeWithTag("passageTextOptions").performClick()
        repeat(8) { compose.onNodeWithTag("smallerPassageText").performScrollTo().performClick() }
        compose.onNodeWithTag("smallerPassageText").assertIsNotEnabled()
        compose.onNodeWithTag("passageTextSizeValue").assertTextEquals("16")
        compose.onNodeWithText("Reset").performClick()
        compose.onNodeWithTag("passageTextSizeValue").assertTextEquals("22")
        repeat(2) { compose.onNodeWithTag("largerPassageText").performScrollTo().performClick() }
        compose.onNodeWithText("Done").performClick()
        compose.runOnIdle { assertEquals(original, model.state.value.reader!!.current!!.position) }
        compose.onNodeWithTag("toggleContext").performClick()
        val single = mutableListOf<TextLayoutResult>()
        compose.onNodeWithTag("currentWord").performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(single) }
        assertEquals(22f, single.single().layoutInput.style.fontSize.value, .01f)
        compose.onNodeWithContentDescription("Back").performClick()
        readBook()
        compose.onNodeWithTag("toggleContext").performClick()
        compose.runOnIdle { assertEquals(original, model.state.value.reader!!.current!!.position) }
        compose.onNodeWithTag("passageTextOptions").performClick()
        compose.onNodeWithTag("passageTextSizeValue").assertTextEquals("26")
        assertEquals(26, application.getSharedPreferences("reading", 0).getInt("passageTextSize", -1))
        compose.onNodeWithText("Done").performClick()
        outlineScreenshot("scroll-text-body")
    }

    @Test fun activeWordSizeOnlyAffectsSingleLineAndPersists() {
        launch()
        compose.onNodeWithTag("book-The art of attention").performClick()
        readBook()
        compose.runOnIdle { model.state.value.reader!!.seek(2) }
        val index = model.state.value.reader!!.state.value.index
        fun assertSize(size: Float) {
            val layouts = mutableListOf<TextLayoutResult>()
            val node = compose.onNodeWithTag("currentWord")
            node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            assertEquals(size, layouts.single().layoutInput.style.fontSize.value, .01f)
            val word = node.fetchSemanticsNode().boundsInRoot
            val surface = compose.onNodeWithTag("readingSurface").fetchSemanticsNode().boundsInRoot
            if (compose.onNodeWithTag("nearbyPassage").isDisplayed()) {
                val metrics = application.resources.displayMetrics
                val row = maxOf(56 * metrics.density, size * metrics.scaledDensity * 1.4f + 12 * metrics.density)
                assertTrue(kotlin.math.abs(surface.center.y - word.center.y) <= row / 2 + 2)
            } else {
                assertEquals(surface.center.x, word.center.x, 1f)
                assertEquals(surface.center.y, word.center.y, 1f)
            }
            compose.runOnIdle {
                assertEquals(index, model.state.value.reader!!.state.value.index)
                assertFalse(model.state.value.reader!!.state.value.playing)
            }
        }
        fun changeSize(percent: Int) {
            compose.onNodeWithTag("readingOptions").performClick()
            compose.onNodeWithTag("activeWordSize").performSemanticsAction(SemanticsActions.SetProgress) { it(percent.toFloat()) }
            compose.onNodeWithTag("activeWordSizeValue").assertTextEquals("$percent%")
            compose.onNodeWithText("Done").performClick()
        }
        assertSize(22f)
        changeSize(300)
        assertSize(66f)
        compose.onNodeWithTag("toggleContext").performClick()
        val passageSize = 22f
        val passageLayouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithTag("currentPassageRow").performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(passageLayouts) }
        assertEquals(passageSize, passageLayouts.single().layoutInput.style.fontSize.value, .01f)
        compose.onNodeWithTag("readingOptions").assertDoesNotExist()
        compose.onNodeWithTag("toggleContext").performClick()
        assertSize(66f)
        changeSize(200)
        compose.onNodeWithContentDescription("Back").performClick()
        readBook()
        assertSize(44f)
        assertEquals(200, application.getSharedPreferences("reading", 0).getInt("activeWordSizePercent", -1))
        compose.onNodeWithTag("readingOptions").performClick()
        compose.onNodeWithTag("activeWordSizeValue").assertTextEquals("200%")
        compose.onNodeWithContentDescription("Focus letter").performScrollTo().performClick()
        compose.onNodeWithText("Done").performClick()
        assertFocusedCharacterCentered()
        compose.onNodeWithTag("toggleContext").performClick()
        compose.onNodeWithTag("currentWord").assertDoesNotExist()
        compose.onNodeWithTag("currentPassageRow").assertIsDisplayed()
    }

    @Test fun focusLetterToggleAlignsAndPersistsWithoutMovingPosition() {
        launch()
        compose.onNodeWithTag("book-The art of attention").performClick()
        readBook()
        compose.runOnIdle { model.state.value.reader!!.seek(2) }
        compose.onNodeWithTag("currentWord").assertTextEquals("story.")
        val index = model.state.value.reader!!.state.value.index
        compose.onNodeWithTag("readingOptions").performClick()
        compose.onNodeWithContentDescription("Focus letter").assertIsOff().performClick()
        compose.onNodeWithText("Done").performClick()
        assertFocusedCharacterCentered()
        compose.onNodeWithTag("toggleContext").performClick()
        compose.onNodeWithTag("currentWord").assertDoesNotExist()
        compose.onNodeWithTag("currentPassageRow").assertIsDisplayed()
        compose.onNodeWithTag("wordRibbon").assertDoesNotExist()
        compose.runOnIdle { assertEquals(index, model.state.value.reader!!.state.value.index) }
        assertTrue(application.getSharedPreferences("reading", 0).getBoolean("focusLetter", false))
        compose.onNodeWithContentDescription("Back").performClick()
        readBook()
        assertFocusedCharacterCentered()
        compose.onNodeWithTag("readingOptions").performClick()
        compose.onNodeWithContentDescription("Focus letter").assertIsOn().performClick()
        compose.onNodeWithText("Done").performClick()
        val word = compose.onNodeWithTag("currentWord").fetchSemanticsNode()
        val surface = compose.onNodeWithTag("readingSurface").fetchSemanticsNode()
        assertEquals(surface.boundsInRoot.center.x, word.boundsInRoot.center.x, 1f)
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithTag("currentWord").performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertTrue(layouts.single().layoutInput.text.spanStyles.isEmpty())
        compose.runOnIdle { assertEquals(index, model.state.value.reader!!.state.value.index) }
    }

    @Test fun shortWordsFillTheWindowWidth() {
        val page = BookPage(number = 1, extractedText = List(200) { "i" }.joinToString(" "))
        val book = backup().book.copy(pages = listOf(page), chapters = emptyList(),
            position = ReadingPosition(page.id, 200), readingStart = null)
        launch(book = book)
        compose.onNodeWithTag("book-The art of attention").performClick()
        readBook()
        val surface = compose.onNodeWithTag("readingSurface").fetchSemanticsNode().boundsInRoot
        val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
        assertEquals(root.left, surface.left, 1f)
        assertEquals(root.right, surface.right, 1f)

        fun assertFilled() {
            val neighbors = compose.onAllNodes(SemanticsMatcher("nearby word") {
                it.config.getOrElse(androidx.compose.ui.semantics.SemanticsProperties.TestTag) { "" }.startsWith("nearbyWord-")
            }).fetchSemanticsNodes().map { it.boundsInRoot }.filter { it.width > 0 }
            val gap = 18 * compose.activity.resources.displayMetrics.density + 1
            assertTrue("Text should reach the left edge", neighbors.minOf { it.left } <= surface.left + gap)
            assertTrue("Text should reach the right edge", neighbors.maxOf { it.right } >= surface.right - gap)
            compose.runOnIdle { assertEquals(100, model.state.value.reader!!.state.value.index) }
        }
        assertFilled()
        compose.onNodeWithTag("readingOptions").performClick()
        compose.onNodeWithContentDescription("Focus letter").performClick()
        compose.onNodeWithText("Done").performClick()
        assertFilled()
        compose.onNodeWithTag("toggleContext").performClick()
        compose.waitUntil(5_000) { compose.onNodeWithTag("currentPassageRow").isDisplayed() }
        val passage = compose.onNodeWithTag("nearbyPassage").fetchSemanticsNode().boundsInRoot
        val safeInsets = ViewCompat.getRootWindowInsets(compose.activity.window.decorView)!!
            .getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
        assertEquals(root.width - safeInsets.left - safeInsets.right, passage.width, 2f)
        compose.onNodeWithTag("toggleContext").performClick()
        assertFilled()
    }

    private fun assertFocusedCharacterCentered() {
        val node = compose.onNodeWithTag("currentWord")
        node.assertTextEquals("story.")
        val layouts = mutableListOf<TextLayoutResult>()
        node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val layout = layouts.single()
        val span = layout.layoutInput.text.spanStyles.single()
        assertEquals("t", layout.layoutInput.text.text.substring(span.start, span.end))
        val center = node.fetchSemanticsNode().boundsInRoot.left + layout.getBoundingBox(span.start).center.x
        val surface = compose.onNodeWithTag("readingSurface").fetchSemanticsNode().boundsInRoot
        if (!compose.onNodeWithTag("nearbyPassage").isDisplayed()) assertEquals(surface.center.x, center, 1f)
        else assertTrue(center in surface.left..surface.right)
    }

    @Test fun textCorrectionsRemainOptionalAndPersist() {
        launch()
        bookOptions()
        compose.onNodeWithText("Edit text").performClick()
        compose.onNodeWithTag("pageText").performScrollTo().performTextReplacement("Heading 🌿 Begin a corrected story.")
        compose.onNodeWithContentDescription("Back").performClick()
        runBlocking { withContext(Dispatchers.Main) { model.flush() } }
        val loaded = BookRepository(File(root, "books")).load().books.single()
        assertTrue(loaded.pages[1].text.contains("corrected story"))
        assertFalse(loaded.pages[1].isReviewed)
        assertFalse(loaded.pages[1].extractedText.contains("corrected story"))
        readBook()
        compose.onNodeWithTag("holdToRead").performTouchInput { down(center) }
        compose.runOnIdle { assertTrue(model.state.value.reader!!.state.value.playing) }
        compose.onNodeWithTag("holdToRead").performTouchInput { up() }
    }

    @Test fun holdControlsSpeedRelativelyAndReleaseOrCancelPauses() {
        launch()
        compose.onNodeWithTag("book-The art of attention").performClick()
        readBook()
        val corner = compose.onNodeWithTag("holdToRead")
        compose.onNodeWithTag("playPause").assertDoesNotExist()
        compose.onNodeWithContentDescription("Words per minute").assertDoesNotExist()
        corner.performTouchInput { down(Offset(width * .4f, height * .5f)) }
        compose.runOnIdle {
            assertTrue(model.state.value.reader!!.state.value.playing)
            assertEquals(250.0, model.state.value.reader!!.wordsPerMinute, .01)
        }
        corner.performTouchInput { moveTo(Offset(width * .7f, height * .5f)) }
        var faster = 0.0
        compose.runOnIdle { faster = model.state.value.reader!!.wordsPerMinute; assertTrue(faster > 250) }
        corner.performTouchInput { moveTo(Offset(width * .6f, height * .5f)) }
        compose.runOnIdle { assertTrue(model.state.value.reader!!.wordsPerMinute < faster) }
        corner.performTouchInput { up() }
        var speed = 0.0
        compose.runOnIdle { assertFalse(model.state.value.reader!!.state.value.playing); speed = model.state.value.reader!!.wordsPerMinute }
        // A different initial position must retain the speed, not jump along a slider.
        corner.performTouchInput { down(Offset(width * .1f, height * .8f)) }
        compose.runOnIdle { assertEquals(speed, model.state.value.reader!!.wordsPerMinute, .01) }
        corner.performTouchInput { moveTo(Offset(width * .1f, height * .2f)) }
        compose.runOnIdle { assertEquals(speed, model.state.value.reader!!.wordsPerMinute, .01) }
        corner.performTouchInput { cancel() }
        compose.runOnIdle { assertFalse(model.state.value.reader!!.state.value.playing) }
        compose.onNodeWithTag("readingSurface").performTouchInput { swipe(Offset(width * .8f, height * .3f), Offset(width * .2f, height * .3f)) }
        compose.runOnIdle {
            assertEquals(speed, model.state.value.reader!!.wordsPerMinute, .01)
            assertFalse(model.state.value.reader!!.state.value.playing)
        }
        compose.onNodeWithContentDescription("Back").performClick()
        readBook()
        compose.runOnIdle { assertEquals(speed.toInt().toDouble(), model.state.value.reader!!.wordsPerMinute, .01) }
    }

    @Test fun cornerRetainsTouchOutsideBoundsAndReversesImmediatelyAtSpeedLimit() {
        launch()
        compose.onNodeWithTag("book-The art of attention").performClick()
        readBook()
        val corner = compose.onNodeWithTag("holdToRead")
        corner.performTouchInput {
            down(center)
            moveTo(Offset(-width.toFloat(), centerY))
        }
        compose.runOnIdle {
            assertEquals(60.0, model.state.value.reader!!.wordsPerMinute, .01)
            assertTrue(model.state.value.reader!!.state.value.playing)
            assertFalse(model.state.value.reader!!.state.value.scrubbing)
        }
        corner.performTouchInput { moveTo(Offset(-width + 30f, centerY)) }
        compose.runOnIdle { assertTrue(model.state.value.reader!!.wordsPerMinute > 60) }
        corner.performTouchInput { up() }
        compose.runOnIdle { assertFalse(model.state.value.reader!!.state.value.playing) }
    }
    @Test fun headerAndQuarterZoneLeaveAllRemainingSpaceForText() {
        launch()
        compose.onNodeWithTag("book-The art of attention").performClick()
        readBook()
        val header = compose.onNodeWithTag("readerHeader").fetchSemanticsNode().boundsInRoot
        val surface = compose.onNodeWithTag("readingSurface").fetchSemanticsNode().boundsInRoot
        val corner = compose.onNodeWithTag("holdToRead").fetchSemanticsNode().boundsInRoot
        val progress = compose.onNodeWithTag("readingProgress").fetchSemanticsNode().boundsInRoot
        val page = compose.onNodeWithTag("readerPage", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
        val density = application.resources.displayMetrics.density
        assertTrue("Compact header", header.height / density <= 56f)
        assertEquals(header.bottom, surface.top, 1f)
        val safeBottom = ViewCompat.getRootWindowInsets(compose.activity.window.decorView)
            ?.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())?.bottom ?: 0
        assertTrue("No footer below text", root.bottom - surface.bottom <= safeBottom + 1f)
        assertEquals(surface.width / 2, corner.width, 1f)
        assertEquals(surface.height / 2, corner.height, 1f)
        assertEquals(surface.right, corner.right, 1f)
        assertEquals(surface.bottom, corner.bottom, 1f)
        assertTrue(page.top >= header.top && page.bottom <= header.bottom)
        // Material expands progress semantics vertically; its painted 2 dp line
        // remains centered at the header's lower edge.
        assertEquals(header.bottom - density, progress.center.y, 1f)
        compose.onNodeWithTag("speedOverlay").assertDoesNotExist()
        compose.onNodeWithTag("readerPage", useUnmergedTree = true).performTouchInput { click() }
        compose.onNodeWithText("Go to page").assertIsDisplayed()
        compose.onNodeWithText("Done").performClick()
    }

    @Test fun gestureKeepsStartingRoleAcrossZonesAndIgnoresSecondFinger() {
        launch()
        compose.onNodeWithTag("book-The art of attention").performClick()
        readBook()
        val surface = compose.onNodeWithTag("readingSurface")
        run {
            compose.runOnIdle { model.state.value.reader!!.seek(4) }
            var speed = 0.0
            compose.runOnIdle { speed = model.state.value.reader!!.wordsPerMinute }
            // Begin scrubbing on the left; crossing into the hold zone cannot play.
            surface.performTouchInput {
                down(Offset(width * .25f, height * .75f))
                moveTo(Offset(width * .8f, height * .75f))
            }
            compose.runOnIdle {
                val session = model.state.value.reader!!
                assertTrue(session.state.value.index < 4)
                assertTrue(session.state.value.scrubbing)
                assertFalse(session.state.value.playing)
                assertEquals(speed, session.wordsPerMinute, .01)
            }
            surface.performTouchInput { up() }
            compose.runOnIdle { model.state.value.reader!!.seek(2) }
            // Begin in the corner; crossing left remains speed control.
            surface.performTouchInput {
                down(0, Offset(width * .75f, height * .75f))
                moveTo(0, Offset(width * .3f, height * .75f))
                down(1, Offset(width * .2f, height * .2f))
                moveTo(1, Offset(width * .8f, height * .2f))
            }
            compose.runOnIdle {
                val session = model.state.value.reader!!
                assertTrue(session.state.value.playing)
                assertFalse(session.state.value.scrubbing)
                assertTrue(session.wordsPerMinute < speed)
            }
            surface.performTouchInput { up(0) }
            var pausedIndex = 0
            compose.runOnIdle {
                val session = model.state.value.reader!!
                assertFalse(session.state.value.playing)
                pausedIndex = session.state.value.index
                speed = session.wordsPerMinute
            }
            surface.performTouchInput { moveTo(1, Offset(width * .1f, height * .8f)); up(1) }
            compose.runOnIdle {
                val session = model.state.value.reader!!
                assertEquals(pausedIndex, session.state.value.index)
                assertEquals(speed, session.wordsPerMinute, .01)
                assertFalse(session.state.value.scrubbing)
            }
            compose.runOnIdle { model.speed(250.0) }
        }
    }

    @Test fun wordTapsDoNotSeekAndAccessibleNavigationStillWorks() {
        launch()
        compose.onNodeWithTag("book-The art of attention").performClick()
        readBook()
        compose.runOnIdle { model.state.value.reader!!.seek(2) }
        run {
            val neighbor = compose.onNodeWithTag("nearbyWord-1")
            neighbor.assertHasNoClickAction().performTouchInput { click() }
            compose.onNodeWithTag("currentWord").assertHasNoClickAction().assertTextContains("story.", substring = true)
            compose.runOnIdle {
                assertEquals(2, model.state.value.reader!!.state.value.index)
                assertFalse(model.state.value.reader!!.state.value.playing)
            }
        }
        val actions = compose.onNodeWithTag("currentWord").fetchSemanticsNode().config[SemanticsActions.CustomActions]
        compose.runOnIdle { assertTrue(actions.single { it.label == "Next word" }.action()) }
        compose.runOnIdle { assertEquals(3, model.state.value.reader!!.state.value.index) }
        compose.onNodeWithTag("holdToRead").performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.runOnIdle { assertTrue(model.state.value.reader!!.state.value.playing) }
        compose.onNodeWithTag("holdToRead").performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.runOnIdle { assertFalse(model.state.value.reader!!.state.value.playing) }
    }

    @Test fun wpmAppearsOnlyAfterSpeedChangesAndFadesWhileStillHeld() {
        launch()
        compose.onNodeWithTag("book-The art of attention").performClick()
        readBook()
        val corner = compose.onNodeWithTag("holdToRead")
        compose.onNodeWithTag("speedOverlay").assertDoesNotExist()
        corner.performTouchInput { down(center) }
        compose.onNodeWithTag("speedOverlay").assertDoesNotExist()
        // Vertical movement does not adjust speed or reveal WPM.
        corner.performTouchInput { moveBy(Offset(0f, -20f)) }
        compose.onNodeWithTag("speedOverlay").assertDoesNotExist()
        corner.performTouchInput { moveBy(Offset(30f, 0f)) }
        compose.onNodeWithTag("speedOverlay").assertIsDisplayed()
        compose.waitUntil(3_000) { !compose.onNodeWithTag("speedOverlay").isDisplayed() }
        compose.runOnIdle { assertFalse(model.state.value.reader!!.state.value.scrubbing) }
        corner.performTouchInput { cancel() }
        compose.runOnIdle { assertFalse(model.state.value.reader!!.state.value.playing) }
    }

    @Test fun reviewPromptStillAllowsScrubbingAndOpeningCorrections() {
        launch(seed = false)
        val file = File(root, "sample.pdf")
        fixture("sample.pdf").use { input -> file.outputStream().use { input.copyTo(it) } }
        compose.runOnUiThread { model.importFile(Uri.fromFile(file)) }
        compose.waitUntil(20_000) { model.state.value.importing == null && model.state.value.selectedID != null }
        readBook()
        compose.waitUntil(10_000) { model.state.value.reader != null }
        compose.runOnIdle { model.options(true, true) }
        val corner = compose.onNodeWithTag("holdToRead")
        corner.performTouchInput { down(center); up() }
        compose.onNodeWithText("Review this page").assertIsDisplayed()
        compose.onNodeWithTag("readingSurface").performTouchInput {
            swipe(Offset(width * .8f, height * .3f), Offset(width * .2f, height * .3f))
        }
        compose.runOnIdle {
            assertNull(model.state.value.reader!!.state.value.blockedPage)
            assertTrue(model.state.value.reader!!.state.value.index > 0)
        }
        corner.performTouchInput { down(center); up() }
        compose.onNodeWithText("Review this page").performClick()
        compose.onNodeWithTag("pageText").assertExists()
    }

    @Test fun duplicateBackupImportsRemainSeparate() {
        launch(legacy = true)
        val file = File(root, "backup.json")
        fixture("apple-backup.json").use { input -> file.outputStream().use { input.copyTo(it) } }
        compose.runOnUiThread { model.importFile(Uri.fromFile(file)) }
        compose.waitUntil(20_000) { model.state.value.importing == null }
        assertNull(model.state.value.error)
        assertEquals(2, model.state.value.books.size)
        assertEquals(2, model.state.value.books.map { it.id }.distinct().size)
        assertEquals(model.state.value.books[0].pages, model.state.value.books[1].pages)
        assertEquals(model.state.value.books[0].position, model.state.value.books[1].position)
    }

    @Test fun cancellingImportOrRepairBeforeCommitKeepsTheSavedBook() {
        launch()
        val original = model.state.value.books.single()
        val file = File(root, "backup.json")
        fixture("apple-backup.json").use { input -> file.outputStream().use { input.copyTo(it) } }
        compose.runOnUiThread {
            model.importFile(Uri.fromFile(file))
            model.cancelImport()
        }
        compose.waitUntil(20_000) { model.state.value.importing == null }
        assertNull(model.state.value.error)
        assertEquals(listOf(original), model.state.value.books)
        compose.runOnUiThread {
            model.reprocess(original.id)
            model.cancelImport()
        }
        compose.waitUntil(20_000) { model.state.value.importing == null }
        runBlocking { withContext(Dispatchers.Main) { model.flush() } }
        assertNull(model.state.value.error)
        assertNull(model.state.value.undoReprocessID)
        assertEquals(listOf(original), model.state.value.books)
        assertEquals(listOf(original), BookRepository(File(root, "books")).load().books)
    }

    @Test fun failedImportClearsProgressAndAllowsAnotherImport() {
        launch(seed = false)
        val file = File(root, "broken.pdf").apply { writeText("not a PDF") }
        compose.runOnUiThread { model.importFile(Uri.fromFile(file)) }
        compose.waitUntil(20_000) { model.state.value.importing == null }
        assertNotNull(model.state.value.error)
        assertTrue(model.state.value.books.isEmpty())
        fixture("sample.pdf").use { input -> file.outputStream().use { input.copyTo(it) } }
        compose.runOnUiThread { model.dismissError(); model.importFile(Uri.fromFile(file)) }
        compose.waitUntil(20_000) { model.state.value.importing == null }
        assertNull(model.state.value.error)
        assertEquals(1, model.state.value.books.size)
    }

    @Test fun legacyBookRequiresExplicitReprocessingAndThenOpensPreparedText() {
        launch(legacy = true)
        val original = model.state.value.books.single()
        val pdf = model.repository.pdfFile(original.id).readBytes()
        compose.onNodeWithTag("book-The art of attention").performClick()
        compose.waitUntil(10_000) { model.state.value.preparationID != null }
        compose.onNodeWithTag("prepareBook").assertIsDisplayed()
        assertNull(model.state.value.reader)
        compose.onNodeWithTag("prepareBook").performClick()
        compose.waitUntil(20_000) { model.state.value.importing == null && model.selectedBook()?.needsReprocessing == false }
        assertNull(model.state.value.error)
        assertArrayEquals(pdf, model.repository.pdfFile(original.id).readBytes())
        val stored = File(model.repository.directory(original.id), "book.json").readText()
        assertFalse(stored.contains("extractedText")); assertFalse(stored.contains("processedText"))
        readBook()
        compose.waitUntil(10_000) { model.state.value.reader != null }
        assertEquals("the", model.state.value.reader!!.current!!.word.text)
    }

    @Test fun reprocessingLegacyBookPreservesCorrectionsAndCanBeUndone() {
        launch(legacy = true)
        val original = model.state.value.books.single()
        bookOptions()
        compose.onNodeWithTag("reprocessBook").performClick()
        compose.waitUntil(20_000) { model.state.value.importing == null }
        compose.runOnIdle {
            assertNull(model.state.value.error)
            val improved = (model.selectedBook() ?: model.state.value.books.first())
            assertEquals(PdfTextRepair.VERSION, improved.pages[0].textProcessingVersion)
            assertEquals(original.pages[1].text, improved.pages[1].text) // manually corrected
            assertFalse(improved.pages[2].isIncluded) // excluded
            assertFalse(improved.needsReprocessing)
            assertEquals(original.position, improved.position)
        }
        bookOptions()
        compose.onNodeWithText("Undo reprocessing").performClick()
        runBlocking { withContext(Dispatchers.Main) { model.flush() } }
        assertEquals(original, BookRepository(File(root, "books")).load().books.single())
    }
}
