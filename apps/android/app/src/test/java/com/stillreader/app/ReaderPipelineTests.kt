package com.stillreader.app

import com.stillreader.app.core.*
import com.stillreader.app.data.BookCodec
import org.junit.Assert.*
import org.junit.Test

class ReaderPipelineTests {

    private fun legacyDashBook(epub: Boolean): Book {
        val text = "Head—Contrasted\n\n🌿 Usher—threadbare\nthere—there—thar"
        val raw = book(text, "Excluded—text").copy(schemaVersion = if (epub) 3 else 2, sourceFormat = if (epub) "epub" else "pdf", chaptersCustomized = true,
            chapters = listOf(BookChapter(title = "Head—Contrasted", pageIndex = 0, utf16Offset = 0, sourceAnchor = "chapter")))
        val prepared = ReaderPipeline.prepare(raw.copy(pages = raw.pages.mapIndexed { i, p -> p.copy(
            edited = true, isIncluded = i == 0, sourceHref = if (epub) "chapter$i.xhtml" else null,
            headings = if (i == 0 && epub) listOf(TextHeading(0, "Head—Contrasted".length, 0)) else null) }))
        return prepared.copy(pipelineVersion = if (epub) 2 else 1,
            position = ReadingPosition(raw.pages[0].id, text.indexOf("threadbare")),
            readingStart = ReadingPosition(raw.pages[0].id, text.indexOf("Usher")),
            pages = prepared.pages.map { page -> page.copy(wordIndex = Regex("\\S+").findAll(page.text).flatMap { match ->
                listOf(match.range.first, match.value.length, 0)
            }.toList()) })
    }

    @Test fun oldSavedIndexesUpgradeWithoutReextractingOrChangingUserState() {
        for (epub in listOf(false, true)) {
            val old = legacyDashBook(epub)
            assertEquals("Usher—threadbare", old.pages[0].words[2].text)
            val updated = BookCodec.decode(BookCodec.json.encodeToString(old))
            assertFalse(updated.needsReprocessing)
            assertEquals(old.id, updated.id); assertEquals(old.chapters, updated.chapters)
            assertEquals(old.position, updated.position); assertEquals(old.readingStart, updated.readingStart)
            assertTrue(updated.chaptersCustomized)
            assertEquals(old.pages, updated.pages.zip(old.pages).map { (new, previous) -> new.copy(wordIndex = previous.wordIndex) })
            val document = updated.readerCache.get(updated)
            assertEquals("threadbare", document.tokens[document.index(updated.position)].word.text)
            assertEquals(listOf("Usher—", "threadbare", "there—", "there—", "thar"), document.tokens.map { it.word.text })
            assertEquals(BookCodec.encode(updated), BookCodec.encode(BookCodec.decode(BookCodec.encode(updated))))
            assertEquals(old.pages.map { it.text }, updated.pages.map { it.text })
        }
    }

    @Test fun openingExistingLibraryPersistsUpgradeAndKeepsOriginalEpub() {
        val root = java.nio.file.Files.createTempDirectory("still-dash-migration").toFile()
        try {
            val repository = com.stillreader.app.data.BookRepository(root)
            val old = legacyDashBook(true)
            val folder = repository.directory(old.id); folder.mkdirs()
            val source = javaClass.classLoader!!.getResourceAsStream("sample.epub")!!.use { it.readBytes() }
            repository.sourceFile(old).writeBytes(source)
            val stored = java.io.File(folder, "book.json")
            stored.writeText(BookCodec.json.encodeToString(old))
            val loaded = repository.load()
            assertEquals(0, loaded.unreadableCount)
            val updated = loaded.books.single()
            assertFalse(updated.needsReprocessing)
            assertEquals(ReaderPipeline.VERSION, BookCodec.json.decodeFromString<Book>(stored.readText()).pipelineVersion)
            assertArrayEquals(source, repository.sourceFile(updated).readBytes())
            assertEquals(old.position, updated.position)
            val saved = stored.readText()
            assertEquals(BookCodec.encode(updated), BookCodec.encode(repository.load().books.single()))
            assertEquals(saved, stored.readText())
        } finally { root.deleteRecursively() }
    }

    @Test fun sharedPreparedFormatFromAppleKeepsExactOffsetsAndHeadings() {
        val book = BookCodec.decode(fixture("prepared-book.json"))
        assertFalse(book.needsReprocessing)
        val document = book.readerCache.get(book)
        assertEquals("begin.", document.tokens[document.index(book.position)].word.text)
        assertEquals(listOf("Chapter One", "Chapter Two"), document.structure.headings.map { it.title })
        book.pages.forEach { assertEquals(Tokenizer.words(it.text), it.words) }
        val output = java.io.File("build/contract-output/android-prepared.json")
        output.parentFile?.mkdirs(); output.writeText(BookCodec.encode(book))
    }

    private fun source() = book("Chapter One\nCafé 🌿 one two.\n\nNext paragraph.", "Chapter Two\nlast page").copy(chapters = listOf(
        BookChapter(title = "Chapter One", pageIndex = 0), BookChapter(title = "Chapter Two", pageIndex = 1)))

    @Test fun persistedOutputReopensWithIdenticalWordsHeadingsAndNoIntermediateText() {
        val raw = source()
        val prepared = ReaderPipeline.prepare(raw)
        val encoded = BookCodec.encode(prepared)
        assertFalse(encoded.contains("extractedText")); assertFalse(encoded.contains("processedText"))
        val restored = BookCodec.decode(encoded)
        assertFalse(restored.needsReprocessing)
        val expected = ReaderDocument(raw); val actual = restored.readerCache.get(restored)
        assertEquals(expected.tokens, actual.tokens)
        assertEquals(expected.structure.headings, actual.structure.headings)
        assertEquals(expected.structure.chapterEnds, actual.structure.chapterEnds)
        assertEquals(expected.passage.blocks, actual.passage.blocks)
        assertSame(actual, restored.copy(position = actual.tokens.last().position).readerCache.get(restored.copy(position = actual.tokens.last().position)))
    }

    @Test fun fullPipelineIgnoresIndexesFromEarlierText() {
        val old = ReaderPipeline.prepare(source())
        val changed = old.copy(pages = old.pages.map { it.copy(text = "short") })
        val prepared = ReaderPipeline.prepare(changed)
        assertEquals(listOf("short", "short"), prepared.readerCache.get(prepared).tokens.map { it.word.text })
    }

    @Test fun editsRebuildChangedPageAndHeadingWhileTrimAndExclusionUseSavedWords() {
        val prepared = ReaderPipeline.prepare(source())
        val edited = ReaderPipeline.update(prepared, prepared.editText(0, "Different heading\nCafé 🌿 one two."))
        assertTrue(edited.pages[0].isEdited)
        assertNull(edited.readerCache.get(edited).structure.destination("Chapter One", 0))
        assertEquals(prepared.pages[1].wordIndex, edited.pages[1].wordIndex)
        val selected = prepared.withReadingStart(ReadingPosition(prepared.pages[0].id, 9))
        assertNull(selected.readerCache.get(selected).structure.destination("Chapter One", 0))
        val excluded = selected.copy(pages = selected.pages.mapIndexed { i, p -> p.copy(isIncluded = i == 0) })
        assertTrue(excluded.readerCache.get(excluded).structure.chapterEnds.isEmpty())
        assertTrue(excluded.readerCache.get(excluded).tokens.all { it.pageIndex == 0 })
    }

    @Test fun oldFutureAndCorruptOutputsRequireExplicitReprocessing() {
        assertTrue(BookCodec.decode(BookCodec.encode(source())).needsReprocessing)
        val prepared = ReaderPipeline.prepare(source())
        assertTrue(BookCodec.decode(BookCodec.encode(prepared.copy(pipelineVersion = 999))).needsReprocessing)
        val malformed = prepared.copy(pages = prepared.pages.map { it.copy(wordIndex = listOf(Int.MAX_VALUE, 5, 0)) })
        assertTrue(BookCodec.decode(BookCodec.encode(malformed)).needsReprocessing)
    }

    @Test fun reprocessingKeepsTheSelectedWordAcrossSeveralTextChanges() {
        val old = book("Old header alpha beta target gamma old footer").let {
            it.copy(position = ReadingPosition(it.pages[0].id, it.pages[0].text.indexOf("target")))
        }
        val updated = ReaderPipeline.reprocess(old, book("New longer heading alpha beta target gamma new ending"))
        val document = updated.readerCache.get(updated)
        assertEquals("target", document.tokens[document.index(updated.position)].word.text)
    }

    @Test fun reprocessingPreservesManualStateAndRefreshesDerivedText() {
        val original = source().editText(1, "My correction 🌿").let { it.copy(
            pages = it.pages.mapIndexed { i, p -> p.copy(isIncluded = i == 1) },
            position = ReadingPosition(it.pages[1].id, 3), readingStart = ReadingPosition(it.pages[1].id, 3)) }
        val updated = ReaderPipeline.reprocess(original, book("New pipeline output", "Other derived text"))
        assertEquals(original.id, updated.id)
        assertEquals(original.pages.map { it.id }, updated.pages.map { it.id })
        assertEquals("New pipeline output", updated.pages[0].text)
        assertEquals("My correction 🌿", updated.pages[1].text)
        assertEquals(original.position, updated.position); assertEquals(original.readingStart, updated.readingStart)
        assertFalse(updated.pages[0].isIncluded)
        assertFalse(updated.needsReprocessing)
        val restored = BookCodec.decode(BookCodec.encode(updated))
        assertTrue(restored.pages[1].isEdited); assertFalse(restored.pages[0].isEdited)
    }
}
