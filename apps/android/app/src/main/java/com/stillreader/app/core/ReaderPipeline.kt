package com.stillreader.app.core

import kotlinx.serialization.Serializable

@Serializable
data class HeadingMatch(val title: String, val pageIndex: Int, val level: Int, val start: Int, val end: Int)

/** Runtime projection of persisted data; it never extracts or repairs a PDF. */
class ReaderDocument(book: Book) {
    val tokens = Tokenizer.tokens(book)
    val structure = ReadingStructure(book, tokens)
    val passage = PassageDocument(tokens, structure)
    private val pageRanges = buildMap<String, IntRange> {
        var start = 0
        while (start < tokens.size) {
            var end = start + 1
            while (end < tokens.size && tokens[end].pageID == tokens[start].pageID) end++
            put(tokens[start].pageID, start until end); start = end
        }
    }
    fun hasPage(id: String) = id in pageRanges
    fun index(position: ReadingPosition?): Int {
        val range = pageRanges[position?.pageID] ?: return 0
        var low = range.first; var high = range.last + 1
        while (low < high) {
            val middle = (low + high) / 2
            if (tokens[middle].word.offset <= position!!.utf16Offset) low = middle + 1 else high = middle
        }
        return (low - 1).coerceAtLeast(range.first)
    }
}

/** Shared by progress-only copies. Only content/selection changes invalidate the projection. */
class ReaderCache {
    // Runtime cache contents do not participate in persisted Book value equality.
    override fun equals(other: Any?) = other is ReaderCache
    override fun hashCode() = 0
    private var source: Book? = null
    private var document: ReaderDocument? = null
    @Synchronized fun clear() { source = null; document = null }
    @Synchronized fun get(book: Book): ReaderDocument {
        val old = source
        if (old == null || old.pages != book.pages || old.chapters != book.chapters || old.title != book.title || old.readingStart != book.readingStart) {
            document = ReaderDocument(book)
            // Do not retain the cache itself through its source snapshot.
            source = book.copy(readerCache = ReaderCache())
        }
        return document!!
    }
}

object ReaderPipeline {
    // Bump for changes to extraction, repair, word boundaries, or heading recognition.
    const val VERSION = 3
    fun version(book: Book) = VERSION

    /** Version 1 PDFs and version 2 EPUBs need only new word boundaries, not extraction.
     * Call after validating indexes. Source text, edits, IDs and UTF-16 positions stay intact. */
    fun upgradeWordBoundaries(book: Book): Book =
        if (book.pipelineVersion == (if (book.isEpub) 2 else 1)) prepare(book) else book

    fun prepare(book: Book, reuseIndexes: Boolean = false): Book {
        val pages = book.pages.map { page ->
            page.copy(edited = page.isEdited, wordIndex = (if (reuseIndexes) page.wordIndex else null) ?: Tokenizer.words(page.text, preserveLineBreaks = book.isEpub).flatMap { listOf(it.offset, it.length, if (it.endsParagraph) 1 else if (it.endsLine) 2 else 0) })
        }
        val full = book.copy(pages = pages.map { it.copy(isIncluded = true) }, readingStart = null, headingMatches = null)
        val matches = if (book.isEpub) pages.flatMapIndexed { index, page -> page.headings.orEmpty().map { h ->
            HeadingMatch(ReadingStructure.normalize(page.text.substring(h.start, h.end)), index, h.level, h.start, h.end)
        } } else ReadingStructure(full, Tokenizer.tokens(full)).matches.distinct()
        return book.copy(schemaVersion = if (book.isEpub) 3 else 2, pages = pages, pipelineVersion = version(book), headingMatches = matches, readerCache = ReaderCache()).also {
            it.readerCache.get(it)
        }
    }

    /** User edits update only changed page indexes. Old pipeline output requires explicit reprocessing. */
    fun update(old: Book, updated: Book): Book {
        if (old.needsReprocessing || updated.pipelineVersion != version(updated)) return updated
        val textChanged = old.pages.indices.any { old.pages[it].text != updated.pages[it].text || updated.pages[it].wordIndex == null }
        if (!textChanged && old.title == updated.title && old.chapters == updated.chapters) return updated
        val pages = updated.pages.mapIndexed { index, page ->
            if (old.pages[index].text == page.text) page else page.copy(wordIndex = null, edited = true)
        }
        return prepare(updated.copy(pages = pages), reuseIndexes = true)
    }

    /** Restore structure along with text; keep manually removed/added chapters. */
    fun restoreSection(original: Book, converted: Book, index: Int, sourceIndex: Int): Book {
        val source = converted.pages[sourceIndex]
        val updated = original.editText(index, source.text)
        val chapters = converted.chapters.filter { it.pageIndex == sourceIndex }.map { it.copy(pageIndex = index) }
        return prepare(updated.copy(
            pages = updated.pages.mapIndexed { i, page -> if (i == index)
                page.copy(headings = source.headings, wordIndex = source.wordIndex, edited = false) else page },
            chapters = if (original.chaptersCustomized) updated.chapters.map { chapter ->
                val restored = chapters.firstOrNull { chapter.sourceAnchor != null && it.sourceAnchor == chapter.sourceAnchor }
                if (chapter.pageIndex == index && restored != null) chapter.copy(utf16Offset = restored.utf16Offset) else chapter
            } else (updated.chapters.filter { it.pageIndex != index } + chapters)
                .sortedWith(compareBy<BookChapter> { it.pageIndex }.thenBy { it.utf16Offset ?: 0 }),
        ), reuseIndexes = true)
    }

    private fun relocateOffset(offset: Int, old: String, new: String): Int {
        if (old == new) return offset.coerceIn(0, new.length)
        val before = Tokenizer.words(old); val after = Tokenizer.words(new)
        val target = before.indexOfLast { it.offset <= offset }
        if (target < 0) return 0
        val candidates = after.indices.filter { after[it].text == before[target].text }
        val best = candidates.maxWithOrNull(compareBy<Int> { candidate ->
            (-2..2).count { delta -> before.getOrNull(target + delta)?.text?.let { it == after.getOrNull(candidate + delta)?.text } == true }
        }.thenBy { -kotlin.math.abs(after[it].offset - offset.toDouble() * new.length / old.length.coerceAtLeast(1)) })
        return best?.let { after[it].offset + (offset - before[target].offset).coerceIn(0, after[it].length - 1) }
            ?: Tokenizer.relocatedOffset(offset, old, new)
    }

    /** Re-extraction replaces derived data, preserving user-owned state and stable page IDs. */
    fun reprocess(original: Book, converted: Book): Book {
        require(original.pages.size == converted.pages.size) { "The original file does not match this book." }
        val pages = converted.pages.mapIndexed { index, page ->
            val old = original.pages[index]
            val text = if (old.isEdited) old.text else page.text
            page.copy(id = old.id, label = old.label, text = text, wordIndex = null, edited = old.isEdited,
                headings = if (old.isEdited) old.headings else page.headings,
                isIncluded = old.isIncluded, isReviewed = old.isReviewed && text == old.text)
        }
        fun relocate(position: ReadingPosition?): ReadingPosition? = position?.let { saved ->
            val index = original.pages.indexOfFirst { it.id == saved.pageID }
            if (index < 0) null else saved.copy(utf16Offset = relocateOffset(saved.utf16Offset, original.pages[index].text, pages[index].text))
        }
        val keepChapters = original.chaptersCustomized || original.pipelineVersion == null || original.chapters.any { it.source !in setOf("PDF bookmark", "EPUB navigation", "EPUB heading") }
        return prepare(original.copy(pages = pages, position = relocate(original.position), readingStart = relocate(original.readingStart),
            chapters = (if (keepChapters) original.chapters else converted.chapters).map { chapter ->
                if (keepChapters && chapter.utf16Offset != null) chapter.copy(utf16Offset = relocateOffset(chapter.utf16Offset,
                    original.pages[chapter.pageIndex].text, pages[chapter.pageIndex].text))
                else if (!keepChapters && original.pages[chapter.pageIndex].isEdited && chapter.utf16Offset != null)
                    chapter.copy(utf16Offset = relocateOffset(chapter.utf16Offset, converted.pages[chapter.pageIndex].text, pages[chapter.pageIndex].text))
                else chapter
            },
            chaptersCustomized = keepChapters))
    }
}
