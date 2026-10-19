package com.stillreader.app

import com.stillreader.app.core.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PassageTests {
    @Test fun stableRowsPreserveParagraphsPagesAndContinuousBoundaries() {
        val tokens = Tokenizer.tokens(book("one two three four.\n\nfive six", "seven"))
        val layout = PassageLayout(tokens, List(tokens.size) { 40.0 }, 160.0)
        assertEquals(listOf(0..2, 3..3, 4..5, 6..6), layout.rows.map { it.words })
        assertEquals(tokens.indices.toList(), layout.rows.flatMap { it.words.toList() })
        for (index in 0 until tokens.lastIndex) {
            assertEquals(layout.position(index, 1.0), layout.position(index + 1, 0.0), .000001)
        }
        val narrow = PassageLayout(tokens, List(tokens.size) { 900.0 }, 25.0)
        assertTrue(narrow.rows.all { it.words.count() == 1 })
        assertEquals(tokens.size, narrow.rows.size)
        assertEquals(0.0, PassageLayout(emptyList(), emptyList(), 0.0).position(0, 0.0), .000001)
    }
    @Test fun punctuationDoesNotChangePassageMotion() {
        val tokens = Tokenizer.tokens(book("one two. three"))
        val layout = PassageLayout(tokens, List(3) { 40.0 }, 500.0)
        assertEquals(layout.position(0, .1) - layout.position(0, 0.0),
            layout.position(1, .1) - layout.position(1, 0.0), .000001)
    }
    @Test fun pauseResumeAndLiveSpeedChangesKeepTheSameFractionAndExactTiming() = runTest {
        val session = ReadingSession(book("one two three"), backgroundScope) { }
        session.clockNanos = { testScheduler.currentTime * 1_000_000 }
        session.passageMode = true; session.naturalPauses = false; session.wordsPerMinute = 600.0
        session.play(); runCurrent(); advanceTimeBy(50)
        assertEquals(.5, session.wordProgress, .001)
        session.wordsPerMinute = 300.0; runCurrent()
        assertEquals(.5, session.wordProgress, .001)
        advanceTimeBy(40); session.pause()
        assertEquals(.9, session.wordProgress, .001)
        advanceTimeBy(500)
        assertEquals(.9, session.wordProgress, .001)
        session.play(); runCurrent(); advanceTimeBy(21); runCurrent()
        assertEquals(1, session.state.value.index)
        session.seek(2)
        assertEquals(0.0, session.wordProgress, .001)
        session.play(); runCurrent(); advanceTimeBy(201); runCurrent()
        assertTrue(session.state.value.finished)
        session.play(); runCurrent()
        assertEquals(0, session.state.value.index)
        assertEquals(0.0, session.wordProgress, .001)
        session.pause()
        session.passageMode = false
        session.play(); runCurrent(); advanceTimeBy(50); session.pause()
        assertEquals(0.0, session.wordProgress, .001)
    }
    @Test fun passageIgnoresSingleLinePausesAndReviewButRetainsTheirSettings() = runTest {
        val source = book("one.", "two").let { it.copy(pages = it.pages.map { page -> page.copy(isReviewed = false) }) }
        var saved: ReadingPosition? = null
        val session = ReadingSession(source, backgroundScope) { saved = it }
        session.clockNanos = { testScheduler.currentTime * 1_000_000 }
        session.allowUnreviewed = false; session.naturalPauses = true; session.wordsPerMinute = 600.0
        session.play()
        assertEquals(0, session.state.value.blockedPage)
        session.passageMode = true
        assertNull(session.state.value.blockedPage)
        session.play(); advanceTimeBy(101); runCurrent(); session.pause()
        assertEquals(1, session.state.value.index)
        assertNull(session.state.value.blockedPage)
        assertEquals(source.pages[1].id, saved?.pageID)
        assertTrue(session.naturalPauses); assertFalse(session.allowUnreviewed)
        session.passageMode = false; session.seek(0); session.play()
        assertEquals(0, session.state.value.blockedPage)
        session.allowUnreviewed = true; session.play(); advanceTimeBy(101); runCurrent()
        assertEquals(0, session.state.value.index) // Single-line punctuation still lasts 190 ms.
        advanceTimeBy(90); runCurrent()
        assertEquals(1, session.state.value.index)
    }
    @Test fun nearbyWindowsPreserveWrappingAcrossPageTransitionsInLargeBooks() {
        val tokens = Tokenizer.tokens(book(*Array(300) { (1..200).joinToString(" ") { "word$it" } }))
        val all = PassageLayout(tokens, List(tokens.size) { 40.0 }, 160.0)
        for (index in listOf(0, 19_999, 20_000, 20_199, 20_200, tokens.lastIndex)) {
            val window = PassageLayout.window(tokens, index)
            assertTrue(window.count() <= 1_000)
            assertTrue(index in window)
            val local = PassageLayout(tokens.subList(window.first, window.last + 1), List(window.count()) { 40.0 }, 160.0)
            val globalRows = local.rows.map { (it.words.first + window.first)..(it.words.last + window.first) }
            assertEquals(all.rows.filter { it.words.first in window }.map { it.words }, globalRows)
            val rowOffset = all.rowContaining(window.first)
            assertEquals(all.position(index, .4), rowOffset + local.position(index - window.first, .4), .000001)
        }
        assertTrue(PassageLayout.window(emptyList(), 0).isEmpty())
    }
}
