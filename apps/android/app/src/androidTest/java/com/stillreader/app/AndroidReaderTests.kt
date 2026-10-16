package com.stillreader.app

import android.app.Application
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.compose.ui.geometry.Offset
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
    private fun launch(seed: Boolean = true) {
        if (seed) {
            val backup = backup()
            val pdf = File(root, "source.pdf").apply { writeBytes(Base64.getDecoder().decode(backup.pdfData)) }
            BookRepository(File(root, "books")).insert(backup.book, pdf)
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
        assertTrue(imported.pages.all { !it.isReviewed && it.text == it.extractedText })
        val image = importer.preview(file, 1, 600)
        assertEquals(600, image.width)
        assertTrue(image.height > image.width)
        image.recycle()
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
        compose.onNodeWithTag("currentWord").assertTextEquals("story.")
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithText("Continue reading").performClick()
        compose.onNodeWithTag("currentWord").assertTextEquals("story.")
        runBlocking { withContext(Dispatchers.Main) { model.pause(); model.flush() } }
        val persisted = BookRepository(File(root, "books")).load().books.single()
        val tokens = Tokenizer.tokens(persisted)
        assertEquals("story.", tokens[Tokenizer.index(persisted.position, tokens)].word.text)
    }
    @Test fun passageReplacesRibbonAndKeepsSelectedWordCentered() {
        launch(seed = false)
        val file = File(root, "sample.pdf")
        fixture("sample.pdf").use { input -> file.outputStream().use { input.copyTo(it) } }
        compose.runOnUiThread { model.importFile(Uri.fromFile(file)) }
        compose.waitUntil(20_000) { model.state.value.importing == null && model.state.value.selectedID != null }
        compose.onNodeWithTag("startReading").performClick()
        compose.waitUntil(5_000) { compose.onNodeWithTag("currentWord").isDisplayed() }
        compose.onNodeWithTag("readingOptions").performClick()
        compose.onNodeWithContentDescription("Animated scrolling").assertIsOff().performClick()
        compose.onNodeWithText("Done").performClick()
        assertTrue(application.getSharedPreferences("reading", 0).getBoolean("animatedScrolling", false))
        val originalCenter = compose.onNodeWithTag("currentWord").fetchSemanticsNode().boundsInRoot.center
        compose.onNodeWithTag("toggleContext").performClick()
        compose.onNodeWithTag("wordRibbon").assertDoesNotExist()
        compose.onNodeWithTag("nearbyPassage").assertIsDisplayed()
        val expandedCenter = compose.onNodeWithTag("currentWord").fetchSemanticsNode().boundsInRoot.center
        assertEquals(originalCenter.x, expandedCenter.x, 1f)
        assertEquals(originalCenter.y, expandedCenter.y, 1f)
        fun assertCentered() {
            val word = compose.onNodeWithTag("currentWord").fetchSemanticsNode().boundsInRoot
            val surface = compose.onNodeWithTag("readingSurface").fetchSemanticsNode().boundsInRoot
            assertEquals(surface.center.x, word.center.x, 1f)
            assertEquals(surface.center.y, word.center.y, 1f)
        }
        assertCentered() // Beginning of book also remains at the fixed center.
        compose.runOnIdle { model.state.value.reader!!.seek(40) }
        assertCentered()
        val centerY = compose.onNodeWithTag("currentWord").fetchSemanticsNode().boundsInRoot.center.y
        val visible = (0..79).filter { it != 40 }.mapNotNull { id ->
            val node = compose.onNodeWithTag("passageWord-$id")
            if (node.isDisplayed()) id to node.fetchSemanticsNode().boundsInRoot else null
        }
        val column = compose.onNodeWithTag("nearbyPassage").fetchSemanticsNode().boundsInRoot
        val inset = (24f * application.resources.displayMetrics.density).coerceAtMost(column.width / 8)
        val alignedRows = visible.groupBy { it.second.center.y.toInt() }.values.count { words ->
            words.size > 1 && kotlin.math.abs(words.minOf { it.second.left } - column.left - inset) < 2f &&
                kotlin.math.abs(words.maxOf { it.second.right } - column.right + inset) < 2f
        }
        assertTrue("Full passage rows align to both column edges", alignedRows >= 2)
        assertTrue(visible.any { it.first < 40 && it.second.center.y < centerY - 20 })
        val below = visible.first { it.first > 40 && it.second.center.y > centerY + 20 }.first
        val activeLayouts = mutableListOf<TextLayoutResult>()
        val neighborLayouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithTag("currentWord").performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(activeLayouts) }
        compose.onNodeWithTag("passageWord-$below").performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(neighborLayouts) }
        assertEquals(activeLayouts.single().layoutInput.style.fontSize, neighborLayouts.single().layoutInput.style.fontSize)
        val beforeSelection = compose.onNodeWithTag("passageWord-$below").fetchSemanticsNode().boundsInRoot
        compose.runOnIdle { model.state.value.reader!!.seek(below) }
        val afterSelection = compose.onNodeWithTag("currentWord").fetchSemanticsNode().boundsInRoot
        assertEquals(beforeSelection.width, afterSelection.width, 1f)
        assertEquals(beforeSelection.height, afterSelection.height, 1f)
        compose.runOnIdle { assertEquals(below, model.state.value.reader!!.state.value.index) }
        assertCentered()
        compose.onNodeWithTag("readingSurface").performTouchInput { swipe(Offset(width * .8f, height * .3f), Offset(width * .2f, height * .3f)) }
        compose.runOnIdle {
            assertTrue(model.state.value.reader!!.state.value.index > below)
            assertFalse(model.state.value.reader!!.state.value.playing)
            assertEquals(250.0, model.state.value.reader!!.wordsPerMinute, .01)
        }
        assertCentered()
        compose.onNodeWithTag("holdToRead").performTouchInput { down(center) }
        compose.runOnIdle { assertTrue(model.state.value.reader!!.state.value.playing) }
        compose.onNodeWithTag("holdToRead").performTouchInput { up() }
        assertCentered()
        compose.runOnIdle { model.state.value.reader!!.seek(model.state.value.reader!!.tokens.lastIndex) }
        assertCentered()
        val index = model.state.value.reader!!.state.value.index
        compose.onNodeWithTag("toggleContext").performClick()
        compose.onNodeWithTag("nearbyPassage").assertDoesNotExist()
        compose.onNodeWithTag("wordRibbon").assertIsDisplayed()
        compose.runOnIdle { assertEquals(index, model.state.value.reader!!.state.value.index) }
        assertCentered()
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithText("Continue reading").performClick()
        compose.runOnIdle { assertEquals(index, model.state.value.reader!!.state.value.index) }
        compose.onNodeWithTag("readingOptions").performClick()
        compose.onNodeWithContentDescription("Animated scrolling").assertIsOn()
        compose.onNodeWithText("Done").performClick()
    }

    @Test fun activeWordSizeAppliesToBothViewsAndPersists() {
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
            assertEquals(surface.center.x, word.center.x, 1f)
            assertEquals(surface.center.y, word.center.y, 1f)
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
        assertSize(passageSize * 3)
        changeSize(100)
        assertSize(passageSize)
        changeSize(200)
        assertSize(passageSize * 2)
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
        assertFocusedCharacterCentered()
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
        assertFocusedCharacterCentered()
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
        assertEquals(surface.center.x, center, 1f)
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
        compose.onNodeWithText("Cancel").performClick()
    }

    @Test fun gestureKeepsStartingRoleAcrossZonesAndIgnoresSecondFinger() {
        launch()
        compose.onNodeWithTag("book-The art of attention").performClick()
        compose.onNodeWithText("Continue reading").performClick()
        val surface = compose.onNodeWithTag("readingSurface")
        repeat(2) { mode ->
            if (mode == 1) compose.onNodeWithTag("toggleContext").performClick()
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
        repeat(2) { mode ->
            if (mode == 1) compose.onNodeWithTag("toggleContext").performClick()
            val neighbor = compose.onNodeWithTag(if (mode == 0) "nearbyWord-1" else "passageWord-1")
            neighbor.assertHasNoClickAction().performTouchInput { click() }
            compose.onNodeWithTag("currentWord").assertHasNoClickAction().assertTextEquals("story.")
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
        launch()
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
}
