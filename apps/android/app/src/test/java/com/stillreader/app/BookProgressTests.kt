package com.stillreader.app

import com.stillreader.app.core.*
import com.stillreader.app.data.EpubImporter
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class BookProgressTests {
    @Test fun chaptersWithinOneEpubFileHaveDistinctProgressAndSurviveResume() {
        val file = File(javaClass.classLoader!!.getResource("epub/structured.epub")!!.toURI())
        val book = EpubImporter.extract(file, file.name)
        val progress = BookProgress(book)
        val first = book.chapters[1]; val second = book.chapters[2]
        assertEquals(first.pageIndex, second.pageIndex)
        assertTrue(progress.percent(first.pageIndex, first.utf16Offset!!)!! < progress.percent(second.pageIndex, second.utf16Offset!!)!!)
        val position = ReadingPosition(book.pages[second.pageIndex].id, second.utf16Offset!!)
        val restored = book.copy(position = position)
        assertEquals("Repeat", progress.chapterTitle(second.pageIndex, second.utf16Offset!!))
        assertEquals("Repeat · ${progress.percent(second.pageIndex, second.utf16Offset!!)}% read", BookProgress(restored).savedStatus())
        assertFalse(progress.textLabel(0).contains("Section"))
    }

    @Test fun progressCountsWordsRatherThanUnevenFilesAndMatchesReader() {
        val book = ReaderPipeline.prepare(book("one two three four five six seven eight nine", "ten").copy(sourceFormat = "epub"))
        val progress = BookProgress(book)
        assertEquals(90, progress.percent(1))
        val tokens = book.readerCache.get(book).tokens
        tokens.forEach { assertEquals(it.id * 100 / tokens.size, progress.percent(it.pageIndex, it.word.offset)) }
    }

    @Test fun trimsAndExcludedTextKeepProgressAndCurrentChapterAccurate() {
        val base = ReaderPipeline.prepare(book("one two three four", "ignored words", "five six").copy(sourceFormat = "epub", chapters = listOf(BookChapter(title = "Opening", pageIndex = 0, utf16Offset = 0))))
        val book = base.withReadingStart(ReadingPosition(base.pages[0].id, 5)).let { it.copy(pages = it.pages.mapIndexed { i, p -> p.copy(isIncluded = i != 1) }) }
        val progress = BookProgress(book)
        val tokens = book.readerCache.get(book).tokens
        assertEquals(tokens.size, progress.wordCount)
        tokens.forEach { assertEquals(it.id * 100 / tokens.size, progress.percent(it.pageIndex, it.word.offset)) }
        assertEquals("Opening", progress.chapterTitle(0, 8))
        assertEquals(0, progress.percent(0, 5))
    }

    @Test fun unfinishedLastWordNeverClaimsCompletionAndMissingIndexesAreNotGuessed() {
        val book = ReaderPipeline.prepare(book("one two").copy(sourceFormat = "epub"))
        assertEquals("Not started", BookProgress(book).savedStatus())
        val saved = book.copy(position = ReadingPosition(book.pages[0].id, 4))
        assertEquals("50% read", BookProgress(saved).savedStatus())
        assertEquals("Finished · 100% read", BookProgress(saved.copy(position = saved.position!!.copy(finished = true))).savedStatus())
        assertNull(BookProgress(book.copy(pages = book.pages.map { it.copy(wordIndex = null) })).percent(0))
        assertNull(BookProgress(ReaderPipeline.prepare(book(""))).percent(0))
        assertNull(BookProgress(book).percent(-1))
    }
}
