package com.stillreader.app

import android.app.Application
import android.net.Uri
import androidx.activity.ComponentActivity
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
        previousActiveWordSize = if (preferences.contains("activeWordSizePercent")) preferences.getInt("activeWordSizePercent", 100) else null
        preferences.edit().putBoolean("animatedScrolling", false).putBoolean("focusLetter", false).putInt("wpm", 250).putInt("activeWordSizePercent", 100).putBoolean("naturalPauses", true).putBoolean("strictReview", false).commit()
    }
    private fun launch(seed: Boolean = true, legacy: Boolean = false) {
        if (seed) {
            val backup = backup()
            val pdf = File(root, "source.pdf").apply { writeBytes(Base64.getDecoder().decode(backup.pdfData)) }
            BookRepository(File(root, "books")).insert(if (legacy) backup.book else ReaderPipeline.prepare(backup.book), pdf)
        }
        compose.runOnUiThread {
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
            previousActiveWordSize?.let { putInt("activeWordSizePercent", it) } ?: remove("activeWordSizePercent")
        }.commit()
    }
    @Test fun bookTypographyAndReaderContentsKeepTheSourcePositionAndTrim() {
        launch(seed = false)
        val file = File(root, "sample.pdf")
        fixture("sample.pdf").use { input -> file.outputStream().use { input.copyTo(it) } }
        compose.runOnUiThread { model.importFile(Uri.fromFile(file)) }
        compose.waitUntil(20_000) { model.state.value.importing == null && model.state.value.selectedID != null }
        compose.onNodeWithTag("startReading").performClick()
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
        compose.runOnIdle { assertFalse(session.state.value.playing); assertEquals(trim, model.selectedBook()!!.readingStart) }
        compose.onNodeWithTag("toggleContext").performClick()
        compose.onNodeWithTag("currentHeading").assertTextEquals("Keep the meaning")
        val savedIndex = session.state.value.index
        compose.onNodeWithContentDescription("Back").performClick(); compose.onNodeWithTag("startReading").performClick()
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

    @Test fun contentsNavigatePreviewWithoutChangingTrimUntilRead() {
        launch(seed = false)
        val file = File(root, "outline.pdf")
        fixture("outline.pdf").use { input -> file.outputStream().use { input.copyTo(it) } }
        compose.runOnUiThread { model.importFile(Uri.fromFile(file)) }
        compose.waitUntil(20_000) { model.state.value.importing == null && model.state.value.selectedID != null }
        compose.runOnIdle {
            val book = model.state.value.books.single()
            model.updateBook(book.id) { it.withReadingStart(ReadingPosition(it.pages[1].id, 6)).copy(position = ReadingPosition(it.pages[2].id, 11)) }
        }
        compose.onNodeWithTag("pdfContents").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("contentsEntry-0.0.0").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("contentsEntry-0.0.0").assertIsSelected()
        outlineScreenshot("android-contents-phone")
        compose.onNodeWithTag("contentsToggle-0").performClick()
        compose.onNodeWithTag("contentsEntry-0.0.0").assertDoesNotExist()
        compose.onNodeWithTag("contentsToggle-0").performClick()
        compose.onNodeWithTag("contentsEntry-0").assertIsNotEnabled()
        compose.onNodeWithTag("contentsEntry-0.1").performClick()
        compose.onNodeWithTag("pageNumber").assertTextEquals("2")
        compose.runOnIdle {
            val book = model.state.value.books.single()
            assertEquals(6, book.readingStart?.utf16Offset)
            assertEquals(11, book.position?.utf16Offset)
            assertNull(model.state.value.reader)
        }
        compose.onNodeWithTag("startReading").performClick()
        compose.waitUntil(10_000) { model.state.value.reader != null }
        compose.runOnIdle {
            val book = model.state.value.books.single()
            assertEquals(book.pages[1].id, book.readingStart?.pageID)
            assertEquals(0, book.readingStart?.utf16Offset)
            model.state.value.reader!!.seek(2)
        }
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithTag("pdfContents").performClick()
        compose.onNodeWithTag("closeContents").performClick()
        compose.onNodeWithText("Continue reading").performClick()
        compose.runOnIdle { assertEquals(2, model.state.value.reader!!.state.value.index) }
    }

    @Test fun contentsWideSidebarRetainsPreviewAndSelection() {
        org.junit.Assume.assumeTrue(application.resources.configuration.screenWidthDp >= 808)
        launch(seed = false)
        val file = File(root, "outline.pdf")
        fixture("outline.pdf").use { input -> file.outputStream().use { input.copyTo(it) } }
        compose.runOnUiThread { model.importFile(Uri.fromFile(file)) }
        compose.waitUntil(20_000) { model.state.value.importing == null && model.state.value.selectedID != null }
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("contentsSidebar").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("contentsSidebar").assertIsDisplayed()
        compose.onNodeWithTag("contentsEntry-0.0").performClick()
        compose.onNodeWithTag("pageNumber").assertTextEquals("3")
        compose.onNodeWithTag("contentsEntry-0.0").assertIsSelected()
        compose.onNodeWithContentDescription("PDF page 3 preview").assertIsDisplayed()
        outlineScreenshot("android-contents-wide")
        compose.onNodeWithTag("pdfContents").performClick()
        compose.onNodeWithTag("contentsSidebar").assertDoesNotExist()
        compose.onNodeWithTag("pdfContents").performClick()
        compose.onNodeWithTag("contentsEntry-0.0").assertIsSelected()
        compose.onNodeWithContentDescription("Previous page").performClick()
        compose.onNodeWithTag("contentsEntry-0.1").assertIsSelected()
    }

    private fun outlineScreenshot(name: String) {
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        File(application.getExternalFilesDir(null), "$name.png").outputStream().use {
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
        bitmap.recycle()
    }

    @Test fun emptyContentsKeepsPageSelectionAvailable() {
        launch(seed = false)
        val file = File(root, "no-outline.pdf")
        fixture("no-outline.pdf").use { input -> file.outputStream().use { input.copyTo(it) } }
        compose.runOnUiThread { model.importFile(Uri.fromFile(file)) }
        compose.waitUntil(20_000) { model.state.value.importing == null && model.state.value.selectedID != null }
        compose.onNodeWithTag("pdfContents").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("No PDF contents").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("No PDF contents").assertIsDisplayed()
        compose.onNodeWithTag("closeContents").performClick()
        compose.onNodeWithContentDescription("Next page").performClick()
        compose.onNodeWithTag("pageNumber").assertTextEquals("2")
    }

    @Test fun emptyLibraryAndPdfImportOpenStartingPage() {
        launch(seed = false)
        compose.onNodeWithText("No PDFs yet").assertIsDisplayed()
        compose.onNodeWithTag("importPDF").assertIsDisplayed()
        val file = File(root, "sample.pdf")
        fixture("sample.pdf").use { input -> file.outputStream().use { input.copyTo(it) } }
        compose.runOnUiThread { model.importFile(Uri.fromFile(file)) }
        compose.waitUntil(20_000) { model.state.value.importing == null && model.state.value.selectedID != null }
        compose.onNodeWithTag("pageNumber").performTextReplacement("2")
        compose.onNodeWithTag("pageNumber").performImeAction()
        compose.waitUntil(5_000) {
            ViewCompat.getRootWindowInsets(compose.activity.window.decorView)?.isVisible(WindowInsetsCompat.Type.ime()) != true
        }
        compose.onNodeWithTag("startReading").performClick()
        compose.waitUntil(10_000) { model.state.value.reader != null }
        compose.waitUntil(5_000) { compose.onNodeWithTag("currentWord").isDisplayed() }
        compose.onNodeWithTag("currentWord").assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(1, model.state.value.reader!!.current!!.pageIndex)
            assertFalse(model.state.value.reader!!.state.value.playing)
        }
    }
    @Test fun swipePauseAndResumeKeepExactWord() {
        launch()
        compose.onNodeWithTag("book-The art of attention").performClick()
        compose.onNodeWithText("Continue reading").performClick()
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
        compose.onNodeWithText("Continue reading").performClick()
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
        compose.onNodeWithTag("startReading").performClick()
        compose.waitUntil(10_000) { model.state.value.reader != null }
        compose.waitUntil(5_000) { compose.onNodeWithTag("currentWord").isDisplayed() }
        compose.onNodeWithTag("readingOptions").performClick()
        compose.onNodeWithContentDescription("Animated scrolling").performClick()
        compose.onNodeWithContentDescription("Focus letter").performClick()
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
        // Tap a noninitial word in a fully visible row using its real glyph bounds.
        val row = compose.onNodeWithTag("currentPassageRow")
        val layouts = mutableListOf<TextLayoutResult>()
        row.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val layout = layouts.single()
        val links = layout.layoutInput.text.getStringAnnotations("word", 0, layout.layoutInput.text.length)
        val link = links[1.coerceAtMost(links.lastIndex)]
        val selected = link.item.toInt()
        val selectedText = layout.layoutInput.text.text.substring(link.start, link.end)
        row.performTouchInput { click(layout.getBoundingBox(link.start).center) }
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
        compose.onNodeWithText("Continue reading").performClick()
        compose.runOnIdle { assertEquals(selected, model.state.value.reader!!.state.value.index) }
        compose.onNodeWithTag("readingOptions").performClick()
        compose.onNodeWithContentDescription("Animated scrolling").assertIsOn()
        compose.onNodeWithContentDescription("Focus letter").assertIsOn()
        compose.onNodeWithText("Done").performClick()
    }

    @Test fun activeWordSizeOnlyAffectsSingleLineAndPersists() {
        launch()
        compose.onNodeWithTag("book-The art of attention").performClick()
        compose.onNodeWithText("Continue reading").performClick()
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
        val compact = compose.onNodeWithTag("nearbyPassage").fetchSemanticsNode().boundsInRoot.height /
            application.resources.displayMetrics.density < 240
        val passageSize = if (compact) 20f else 24f
        val passageLayouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithTag("currentPassageRow").performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(passageLayouts) }
        assertEquals(passageSize, passageLayouts.single().layoutInput.style.fontSize.value, .01f)
        compose.onNodeWithTag("readingOptions").assertDoesNotExist()
        compose.onNodeWithTag("toggleContext").performClick()
        assertSize(66f)
        changeSize(200)
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithText("Continue reading").performClick()
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
        compose.onNodeWithText("Continue reading").performClick()
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
        compose.onNodeWithText("Continue reading").performClick()
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
        compose.onNodeWithTag("book-The art of attention").performClick()
        compose.onNodeWithTag("bookOptions").performClick()
        compose.onNodeWithText("Edit text").performClick()
        compose.onNodeWithTag("pageText").performScrollTo().performTextReplacement("Heading 🌿 Begin a corrected story.")
        compose.onNodeWithContentDescription("Back").performClick()
        runBlocking { withContext(Dispatchers.Main) { model.flush() } }
        val loaded = BookRepository(File(root, "books")).load().books.single()
        assertTrue(loaded.pages[1].text.contains("corrected story"))
        assertFalse(loaded.pages[1].isReviewed)
        assertFalse(loaded.pages[1].extractedText.contains("corrected story"))
        compose.onNodeWithText("Continue reading").performClick()
        compose.onNodeWithTag("holdToRead").performTouchInput { down(center) }
        compose.runOnIdle { assertTrue(model.state.value.reader!!.state.value.playing) }
        compose.onNodeWithTag("holdToRead").performTouchInput { up() }
    }

    @Test fun holdControlsSpeedRelativelyAndReleaseOrCancelPauses() {
        launch()
        compose.onNodeWithTag("book-The art of attention").performClick()
        compose.onNodeWithText("Continue reading").performClick()
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
        compose.onNodeWithText("Continue reading").performClick()
        compose.runOnIdle { assertEquals(speed.toInt().toDouble(), model.state.value.reader!!.wordsPerMinute, .01) }
    }

    @Test fun cornerRetainsTouchOutsideBoundsAndReversesImmediatelyAtSpeedLimit() {
        launch()
        compose.onNodeWithTag("book-The art of attention").performClick()
        compose.onNodeWithText("Continue reading").performClick()
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
        compose.onNodeWithText("Continue reading").performClick()
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
        compose.onNodeWithText("Continue reading").performClick()
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
        compose.onNodeWithText("Continue reading").performClick()
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
        compose.onNodeWithText("Continue reading").performClick()
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
        compose.onNodeWithTag("startReading").performClick()
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
        compose.onNodeWithTag("startReading").assertTextContains("Reprocess book")
        assertNull(model.state.value.reader)
        compose.onNodeWithTag("startReading").performClick()
        compose.waitUntil(20_000) { model.state.value.importing == null && model.selectedBook()?.needsReprocessing == false }
        assertNull(model.state.value.error)
        assertArrayEquals(pdf, model.repository.pdfFile(original.id).readBytes())
        val stored = File(model.repository.directory(original.id), "book.json").readText()
        assertFalse(stored.contains("extractedText")); assertFalse(stored.contains("processedText"))
        compose.onNodeWithTag("startReading").performClick()
        compose.waitUntil(10_000) { model.state.value.reader != null }
        assertEquals("the", model.state.value.reader!!.current!!.word.text)
    }

    @Test fun reprocessingLegacyBookPreservesCorrectionsAndCanBeUndone() {
        launch(legacy = true)
        val original = model.state.value.books.single()
        compose.onNodeWithTag("book-The art of attention").performClick()
        compose.onNodeWithTag("bookOptions").performClick()
        compose.onNodeWithTag("reprocessBook").performClick()
        compose.waitUntil(20_000) { model.state.value.importing == null }
        compose.runOnIdle {
            assertNull(model.state.value.error)
            val improved = model.selectedBook()!!
            assertEquals(PdfTextRepair.VERSION, improved.pages[0].textProcessingVersion)
            assertEquals(original.pages[1].text, improved.pages[1].text) // manually corrected
            assertFalse(improved.pages[2].isIncluded) // excluded
            assertFalse(improved.needsReprocessing)
            assertEquals(original.position, improved.position)
        }
        compose.onNodeWithTag("bookOptions").performClick()
        compose.onNodeWithText("Undo reprocessing").performClick()
        runBlocking { withContext(Dispatchers.Main) { model.flush() } }
        assertEquals(original, BookRepository(File(root, "books")).load().books.single())
    }
}
