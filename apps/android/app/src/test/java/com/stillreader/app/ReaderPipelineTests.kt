package com.stillreader.app

import com.stillreader.app.core.*
import com.stillreader.app.data.BookCodec
import org.junit.Assert.*
import org.junit.Test

class ReaderPipelineTests {
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
