package com.stillreader.app

import com.stillreader.app.core.*
import com.stillreader.app.data.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

@Serializable
data class ExpectedWord(val text: String, val offset: Int, val length: Int, val endsParagraph: Boolean, val duration: Double)
@Serializable
data class TokenCase(val name: String, val text: String, val words: List<ExpectedWord>)

fun fixture(name: String): String = checkNotNull(CoreTests::class.java.classLoader!!.getResourceAsStream(name)).bufferedReader().use { it.readText() }
fun book(vararg texts: String) = Book(title = "Test", originalFilename = "test.pdf", pages = texts.mapIndexed { i, text -> BookPage(number = i + 1, extractedText = text, isReviewed = true) })

@OptIn(ExperimentalCoroutinesApi::class)
class CoreTests {

    @Test fun emDashesSeparateReadingWordsWithoutChangingSourceSpansOrOtherPunctuation() {
        val text = "🌿 Usher—threadbare there—there—thar a——b—“c” —leading trailing—” well-known 10–20 don’t\nline—break\n\nend"
        val words = Tokenizer.words(text, preserveLineBreaks = true)
        assertEquals(listOf("🌿", "Usher—", "threadbare", "there—", "there—", "thar", "a——", "b—", "“c”", "—leading", "trailing—”", "well-known", "10–20", "don’t", "line—", "break", "end"), words.map { it.text })
        words.forEach { assertEquals(it.text, text.substring(it.offset, it.offset + it.length)) }
        assertEquals(text.indexOf("threadbare"), words[2].offset)
        assertEquals(listOf("don’t"), words.filter { it.endsLine }.map { it.text })
        assertEquals(listOf("break"), words.filter { it.endsParagraph }.map { it.text })
        assertEquals(.27, Tokenizer.duration(words[1], 300.0, true), .000001)
        assertEquals(.2, Tokenizer.duration(words[1], 300.0, false), .000001)
    }

    @Test fun sharedSwiftTokenizerContracts() {
        val cases = BookCodec.json.decodeFromString<List<TokenCase>>(fixture("tokenization.json"))
        cases.forEach { case ->
            val words = Tokenizer.words(case.text)
            assertEquals(case.name, case.words.size, words.size)
            words.zip(case.words).forEach { (actual, expected) ->
                assertEquals(expected.text, actual.text); assertEquals(expected.offset, actual.offset)
                assertEquals(expected.length, actual.length); assertEquals(expected.endsParagraph, actual.endsParagraph)
                assertEquals(expected.duration, Tokenizer.duration(actual, 300.0, true), .000001)
            }
        }
    }
    @Test fun appleBackupKeepsDatesCorrectionsAndOffsets() {
        val backup = BookCodec.json.decodeFromString<BookBackup>(fixture("apple-backup.json"))
        val book = BookCodec.validate(backup.book)
        assertEquals(800_000_000.0, book.importedAt, .0001)
        assertEquals("i", book.pages[0].label)
        assertNotEquals(book.pages[1].extractedText, book.pages[1].text)
        assertFalse(book.pages[2].isIncluded)
        val tokens = Tokenizer.tokens(book)
        assertEquals("Begin", tokens.first().word.text)
        assertEquals("the", tokens[Tokenizer.index(book.position, tokens)].word.text)
        val encoded = BookCodec.encode(book)
        assertEquals(book, BookCodec.decode(encoded))
        assertTrue(encoded.contains("\"isSample\":false"))
    }
    @Test fun oldBackupWithoutReadingStartIsAccepted() {
        val original = book("Old book")
        val json = BookCodec.json.parseToJsonElement(BookCodec.encode(original)).jsonObject.toMutableMap()
        json.remove("readingStart")
        assertNull(BookCodec.decode(JsonObject(json).toString()).readingStart)
    }
    @Test fun validatesIDsChaptersAndFutureVersions() {
        val original = book("Hello")
        listOf(original.copy(id = "../../escape"), original.copy(schemaVersion = 999),
            original.copy(chapters = listOf(BookChapter(title = "Bad", pageIndex = 10))),
            original.copy(pages = original.pages + original.pages),
            original.copy(position = ReadingPosition(newID(), 0))).forEach {
            assertThrows(IllegalArgumentException::class.java) { BookCodec.validate(it) }
        }
    }
    @Test fun trimsAndEditsKeepOriginalAndSourcePosition() {
        val original = book("Cover", "Heading 🌿 Begin the story", "Excluded")
        var trimmed = original.copy(pages = original.pages.mapIndexed { i, page -> page.copy(isIncluded = i != 2) })
            .withReadingStart(ReadingPosition(original.pages[1].id, 11))
        assertEquals(listOf("Begin", "the", "story"), Tokenizer.tokens(trimmed).map { it.word.text })
        trimmed = trimmed.editText(1, "A longer heading 🌿 Begin the story")
        assertEquals("Begin", Tokenizer.tokens(trimmed).first().word.text)
        assertEquals(original.pages[1].extractedText, trimmed.pages[1].extractedText)
        assertFalse(trimmed.pages[1].isReviewed)
        assertFalse(trimmed.withReadingStart(null).pages[2].isIncluded)
    }
    @Test fun pauseAndResumeGiveSelectedWordFullDuration() = runTest {
        var saved: ReadingPosition? = null
        val source = book("zero one two three")
        val session = ReadingSession(source, backgroundScope) { saved = it }
        session.wordsPerMinute = 600.0; session.naturalPauses = false
        session.play(); advanceTimeBy(101); runCurrent()
        assertEquals("one", session.current?.word?.text)
        session.pause(); advanceTimeBy(500)
        assertEquals("one", session.current?.word?.text)
        val restored = ReadingSession(source.copy(position = saved), backgroundScope) { }
        restored.wordsPerMinute = 60.0; restored.play(); advanceTimeBy(500)
        assertEquals("one", restored.current?.word?.text)
        restored.pause()
    }
    @Test fun continuousSpeedChangesDoNotPostponeAdvancement() = runTest {
        val session = ReadingSession(book((0..30).joinToString(" ")), backgroundScope) {}
        session.wordsPerMinute = 600.0
        session.naturalPauses = false
        session.play()
        repeat(20) {
            advanceTimeBy(25)
            session.wordsPerMinute = 600.0 + it
        }
        runCurrent()
        assertTrue(session.state.value.index >= 4)
        session.pause()
        val stopped = session.state.value.index
        advanceTimeBy(500)
        assertEquals(stopped, session.state.value.index)
    }
    @Test fun scrubPausesClampsAndSavesOnlyOnce() = runTest {
        val source = book("zero one two three")
        val saves = mutableListOf<ReadingPosition>()
        val session = ReadingSession(source, backgroundScope, saves::add)
        session.play(); session.beginScrubbing(); session.scrub(100)
        assertEquals("three", session.current?.word?.text)
        assertFalse(session.state.value.playing); assertTrue(saves.isEmpty())
        session.beginScrubbing(); session.scrub(1)
        assertEquals("one", session.current?.word?.text)
        session.endScrubbing(); session.endScrubbing()
        assertEquals(1, saves.size)
        assertEquals("one", ReadingSession(source.copy(position = saves.single()), backgroundScope) {}.current?.word?.text)
    }
    @Test fun interruptionEndsScrubAndRetainsSelection() = runTest {
        val session = ReadingSession(book("one two three"), backgroundScope) {}
        session.beginScrubbing(); session.scrub(2); session.pause(); session.scrub(-2)
        assertEquals("three", session.current?.word?.text)
        assertFalse(session.state.value.scrubbing)
    }
    @Test fun uncheckedAndBlankPagesReadImmediatelyByDefault() = runTest {
        val source = book("First", "", "Last").let { it.copy(pages = it.pages.map { p -> p.copy(isReviewed = false) }) }
        val session = ReadingSession(source, backgroundScope) {}
        session.wordsPerMinute = 900.0; session.naturalPauses = false; session.play()
        advanceTimeBy(200); runCurrent()
        assertTrue(session.state.value.finished)
        assertNull(session.state.value.blockedPage)
        assertTrue(session.book.pages.none { it.isReviewed })
    }
    @Test fun strictReviewKeepsBlankPageBlockedAfterReopen() = runTest {
        val source = book("before", "", "after")
        var saved: ReadingPosition? = null
        val session = ReadingSession(source, backgroundScope) { saved = it }
        session.allowUnreviewed = false; session.wordsPerMinute = 900.0; session.play()
        advanceTimeBy(150); runCurrent()
        assertEquals(1, session.state.value.blockedPage)
        val restored = ReadingSession(source.copy(position = saved), backgroundScope) {}
        restored.allowUnreviewed = false; restored.play()
        assertEquals(1, restored.state.value.blockedPage); assertFalse(restored.state.value.playing)
    }
    @Test fun replayAndSeekingRespectTrimAndExcludedPages() = runTest {
        val source = book("Cover", "Heading Start here", "Excluded").let {
            it.copy(pages = it.pages.mapIndexed { i, page -> page.copy(isIncluded = i != 2) })
                .withReadingStart(ReadingPosition(it.pages[1].id, 8))
        }
        val session = ReadingSession(source, backgroundScope) {}
        session.seekPage(0); assertEquals("Start", session.current?.word?.text)
        session.wordsPerMinute = 900.0; session.naturalPauses = false; session.play()
        advanceTimeBy(200); runCurrent(); assertTrue(session.state.value.finished)
        session.play(); assertEquals("Start", session.current?.word?.text); session.pause()
    }
    @Test fun repositoryAndPortableExportRoundTrip() {
        val root = Files.createTempDirectory("still-test").toFile()
        try {
            val backup = BookCodec.json.decodeFromString<BookBackup>(fixture("apple-backup.json"))
            val pdf = File(root, "input.pdf").apply { writeBytes(java.util.Base64.getDecoder().decode(backup.pdfData)) }
            val repository = BookRepository(File(root, "books"))
            val inserted = repository.insert(backup.book, pdf)
            assertEquals(inserted, repository.load().books.single())
            val duplicate = repository.insert(backup.book, pdf)
            assertNotEquals(inserted.id, duplicate.id)
            val exported = File(root, "android-backup.json")
            exported.outputStream().use { repository.export(inserted, it) }
            val decoded = BookCodec.json.decodeFromString<BookBackup>(exported.readText())
            assertEquals(backup, decoded)
            // A persistent output for the Swift compatibility checker, under ignored build outputs.
            File("build/contract-output").mkdirs()
            exported.copyTo(File("build/contract-output/android-backup.json"), overwrite = true)
            repository.delete(inserted.id)
            assertEquals(duplicate.id, repository.load().books.single().id)
        } finally { root.deleteRecursively() }
    }
    @Test fun damagedBooksAreReportedAndPreserved() {
        val root = Files.createTempDirectory("still-damaged").toFile()
        try {
            val folder = File(root, newID()).apply { mkdirs() }
            val broken = File(folder, "book.json").apply { writeText("broken") }
            val loaded = BookRepository(root).load()
            assertEquals(1, loaded.unreadableCount); assertTrue(broken.exists())
        } finally { root.deleteRecursively() }
    }
}
