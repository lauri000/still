package com.stillreader.app

import com.stillreader.app.core.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ReadingStructureTests {
    private fun source() = book("Chapter One\nfirst body.\nSmall section\nmore body.", "Chapter Two\nnext body.").copy(chapters = listOf(
        BookChapter(title = "Chapter One", pageIndex = 0), BookChapter(title = "Small section", pageIndex = 0, level = 1),
        BookChapter(title = "Chapter Two", pageIndex = 1)))
    private fun structure(book: Book) = ReadingStructure(book, Tokenizer.tokens(book))

    @Test fun mappedHeadingsKeepSourceOrderAndOnlyMajorSectionsPause() {
        val book = source(); val s = structure(book)
        assertEquals(listOf("Chapter One", "Small section", "Chapter Two"), s.headings.map { it.title })
        assertEquals("Chapter One", s.sectionAt(0)?.title)
        assertEquals("Small section", s.sectionAt(7)?.title)
        assertEquals(setOf(7), s.chapterEnds)
        assertEquals(8, s.destination("chapter TWO", 1))
        assertEquals(.8, s.duration(7, .1, true), .00001)
        assertEquals(.6, s.duration(0, .1, false), .00001)
        assertEquals(.1, s.duration(0, .1, true), .00001)
    }
    @Test fun unicodeWrappedTitlesMatchButBodyPhrasesAndDuplicatesDoNot() {
        val b = book("Café ☕\nA quieter\nbeginning\nThe word Ambiguous is in prose.\nRepeated\nbody\nRepeated").copy(chapters = listOf(
            BookChapter(title = "CAFÉ ☕", pageIndex = 0), BookChapter(title = "A quieter beginning", pageIndex = 0),
            BookChapter(title = "Ambiguous", pageIndex = 0), BookChapter(title = "Repeated", pageIndex = 0)))
        val s = structure(b)
        assertEquals(listOf("Café ☕", "A quieter beginning"), s.headings.map { it.title })
        assertNull(s.destination("Repeated", 0)); assertNull(s.destination("Ambiguous", 0))
        val tokens = Tokenizer.tokens(b)
        s.headings.forEach { h -> assertEquals(h.title, tokens.subList(h.words.first, h.words.last + 1).joinToString(" ") { it.word.text }) }
    }
    @Test fun trimsExclusionsEditsAndUnmappedBoundariesDoNotInventCues() {
        val b = source()
        assertNull(structure(b.withReadingStart(ReadingPosition(b.pages[0].id, 1))).destination("Chapter One", 0))
        val trimmed = b.withReadingStart(ReadingPosition(b.pages[0].id, 8))
        assertNull(structure(trimmed).destination("Chapter One", 0)); assertTrue(structure(trimmed).chapterEnds.isEmpty())
        val changed = b.editText(0, b.pages[0].text.replace("Chapter One", "Different title"))
        assertNull(structure(changed).destination("Chapter One", 0)); assertTrue(structure(changed).chapterEnds.isEmpty())
        val omitted = b.copy(pages = b.pages.mapIndexed { i, p -> p.copy(isIncluded = i != 1) })
        assertTrue(structure(omitted).chapterEnds.isEmpty())
        val missing = book("Chapter One\nbody", "", "Chapter Two\nbody").copy(chapters = listOf(
            BookChapter(title = "Chapter One", pageIndex = 0), BookChapter(title = "Chapter Two", pageIndex = 2)))
        assertTrue(structure(missing).chapterEnds.isEmpty())
        val ambiguous = b.copy(chapters = b.chapters + BookChapter(title = "Missing heading", pageIndex = 1))
        assertNull(structure(ambiguous).destination("Missing heading", 1))
    }
    @Test fun bookTitleIsShownOnceAndRepeatedRunningTitlesAreNotHeadings() {
        val unique = book("My Book\nby the author", "ordinary body").copy(title = "My Book")
        assertEquals(listOf("My Book"), structure(unique).headings.map { it.title })
        assertTrue(structure(unique).chapterEnds.isEmpty())
        val repeated = book("My Book\nfirst body", "My Book\nsecond body").copy(title = "My Book")
        assertTrue(structure(repeated).headings.isEmpty())
    }
    @Test fun headingRowsKeepEveryWordAndContinuousScrollAcrossSpacing() {
        val b = source(); val tokens = Tokenizer.tokens(b); val s = structure(b)
        val ids = s.headings.flatMap { h -> h.words.map { it to h.words.first } }.toMap()
        val layout = PassageLayout(tokens, List(tokens.size) { 50.0 }, 170.0, headingIDs = ids)
        assertEquals(tokens.indices.toList(), layout.rows.flatMap { it.words.toList() })
        assertTrue(layout.rows.filter { it.isHeading }.all { row -> row.words.all { it in ids } })
        assertTrue(layout.rows.filterNot { it.isHeading }.all { row -> row.words.none { it in ids } })
        for (index in 0 until tokens.lastIndex) assertEquals(layout.position(index, 1.0), layout.position(index + 1, 0.0), .000001)
        assertTrue(layout.rows.any { it.height > 1.0 })
    }
    @Test fun liftingDuringAChapterPauseFreezesMotionAndResumeDoesNotRepeatIt() = runTest {
        val s = ReadingSession(source(), backgroundScope) { }
        s.clockNanos = { testScheduler.currentTime * 1_000_000 }
        s.passageMode = true; s.wordsPerMinute = 600.0
        s.seek(7); s.play(); runCurrent(); advanceTimeBy(200); runCurrent()
        assertEquals(7, s.state.value.index); assertEquals(1.0, s.motionProgress, .001)
        s.pause(); val frozen = s.wordProgress
        advanceTimeBy(1000); runCurrent(); assertEquals(7, s.state.value.index); assertEquals(frozen, s.wordProgress, .001)
        s.play(); runCurrent(); advanceTimeBy(601); runCurrent()
        assertEquals(8, s.state.value.index)
        s.pause(); s.passageMode = false; s.seek(0); s.play(); runCurrent(); advanceTimeBy(300); runCurrent()
        assertEquals(0, s.state.value.index); s.pause(); assertEquals(.5, s.wordProgress, .001)
        s.play(); runCurrent(); advanceTimeBy(301); runCurrent(); assertEquals(1, s.state.value.index)
    }
}
