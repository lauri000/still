package com.stillreader.app

import com.stillreader.app.core.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PassageTests {
    @Test fun stableRowsPreserveAllWordsIncludingNarrowAndEmptyLayouts() {
        val tokens = Tokenizer.tokens(book("one two three four.\n\nfive six", "seven"))
        val layout = PassageLayout(tokens, List(tokens.size) { 40.0 }, 160.0)
        assertEquals(listOf(0..2, 3..3, 4..5, 6..6), layout.rows.map { it.words })
        assertEquals(tokens.indices.toList(), layout.rows.flatMap { it.words.toList() })
        assertEquals(layout.rows[1], layout.rowAtOffset(1.2))
        assertTrue(PassageLayout(tokens, List(tokens.size) { 900.0 }, 25.0).rows.all { it.words.count() == 1 })
        assertNull(PassageLayout(emptyList(), emptyList(), 0.0).rowAtOffset(0.0))
    }
    @Test fun documentIndexesParagraphsPagesAndHeadingsWithoutMeasuringText() {
        val b = book("one two.\n\nChapter One\nbody words", "another page").copy(chapters = listOf(BookChapter(title = "Chapter One", pageIndex = 0)))
        val tokens = Tokenizer.tokens(b)
        val document = PassageDocument(tokens, ReadingStructure(b, tokens))
        assertEquals(listOf(0..1, 2..3, 4..5, 6..7), document.blocks.map { it.words })
        for (index in tokens.indices) assertTrue(index in document.blocks[document.blockContaining(index)].words)
    }
    @Test fun modeSwitchAndScrollKeepExactWordAndDoNotWriteEveryMovement() = runTest {
        val writes = mutableListOf<ReadingPosition>()
        val s = ReadingSession(book("one two. three four"), backgroundScope) { writes += it }
        s.seek(1); s.passageMode = true
        assertEquals(1, s.state.value.index)
        val before = writes.size
        s.play(); advanceTimeBy(5_000); runCurrent()
        assertFalse(s.state.value.playing); assertEquals(1, s.state.value.index)
        s.browse(2); s.browse(3)
        assertEquals(before, writes.size)
        s.checkpoint(); assertEquals(s.tokens[3].position, writes.last())
        s.passageMode = false
        assertEquals(3, s.state.value.index)
        s.browse(0); assertEquals(3, s.state.value.index)
        s.seek(1); s.wordsPerMinute = 600.0; s.naturalPauses = true
        s.play(); advanceTimeBy(101); runCurrent(); assertEquals(1, s.state.value.index)
        advanceTimeBy(90); runCurrent(); assertEquals(2, s.state.value.index)
    }
    @Test fun scrollingBypassesReviewWithoutChangingSingleLinePreferences() = runTest {
        val s = ReadingSession(book("one.", "two").let { it.copy(pages = it.pages.map { page -> page.copy(isReviewed = false) }) }, backgroundScope) { }
        s.allowUnreviewed = false; s.naturalPauses = true; s.wordsPerMinute = 321.0
        s.play(); assertEquals(0, s.state.value.blockedPage)
        s.passageMode = true; s.browse(1)
        assertNull(s.state.value.blockedPage); assertEquals(1, s.state.value.index)
        s.play(); advanceTimeBy(1000); runCurrent(); assertFalse(s.state.value.playing)
        assertTrue(s.naturalPauses); assertFalse(s.allowUnreviewed); assertEquals(321.0, s.wordsPerMinute, .001)
        s.passageMode = false; s.play(); assertEquals(1, s.state.value.blockedPage)
    }
    @Test fun largeBookAndTrimKeepStableSourcePositions() {
        val b = book(*Array(300) { (1..200).joinToString(" ") { "word$it" } })
        val tokens = Tokenizer.tokens(b); val document = PassageDocument(tokens, ReadingStructure(b, tokens))
        assertEquals(300, document.blocks.size)
        assertEquals(tokens.indices.toList(), document.blocks.flatMap { it.words.toList() })
        for (index in listOf(0, 19_999, 20_000, tokens.lastIndex)) assertTrue(index in document.blocks[document.blockContaining(index)].words)
        val trimmed = b.withReadingStart(tokens[201].position)
        val kept = Tokenizer.tokens(trimmed); val doc = PassageDocument(kept, ReadingStructure(trimmed, kept))
        assertEquals(tokens[201].position, kept.first().position)
        assertEquals(kept.indices.toList(), doc.blocks.flatMap { it.words.toList() })
        assertTrue(PassageDocument(emptyList(), ReadingStructure(book(""), emptyList())).blocks.isEmpty())
    }
}
