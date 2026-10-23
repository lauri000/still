package com.stillreader.app.core

import java.util.Locale

/** Applies saved heading matches to the current trim and page selection. */
class ReadingStructure(book: Book, tokens: List<ReadingToken>) {
    data class Heading(val title: String, val words: IntRange, val level: Int, val pageIndex: Int)
    data class Section(val chapterID: String, val title: String, val pageIndex: Int, val level: Int, val index: Int, val heading: Heading?)
    val matches = mutableListOf<HeadingMatch>()
    val sections: List<Section>
    val headings: List<Heading>
    val chapterEnds: Set<Int>
    private val isEpub = book.isEpub
    private val headingWords: Map<Int, Heading>

    init {
        val starts = IntArray(book.pages.size) { -1 }
        val ends = IntArray(book.pages.size)
        tokens.forEach { if (starts[it.pageIndex] < 0) starts[it.pageIndex] = it.id; ends[it.pageIndex] = it.id + 1 }
        // An EPUB section can contain an entire book: locate saved spans without rescanning its words.
        fun lowerBound(page: Int, offset: Int, wordEnd: Boolean = false): Int {
            var low = starts[page]; var high = ends[page]
            while (low < high) {
                val middle = (low + high) / 2
                val word = tokens[middle].word
                if (word.offset + (if (wordEnd) word.length else 0) < offset) low = middle + 1 else high = middle
            }
            return low
        }
        val lines = mutableMapOf<Int, List<Line>>()
        fun match(title: String, page: Int, level: Int, expectedStart: Int? = null): Heading? {
            val normalized = normalize(title)
            if (normalized.length < (if (book.isEpub) 1 else 3) || normalized.length > 240 || starts[page] < 0) return null
            val span = if (book.headingMatches != null) {
                book.headingMatches.firstOrNull { it.pageIndex == page && it.title == normalized && it.level == level && (expectedStart == null || it.start == expectedStart) }
                    ?.let { it.start until it.end }
            } else {
                val source = book.pages[page].text
                val rows = lines.getOrPut(page) { Regex("[^\\r\\n]+").findAll(source).map { Line(it.value, it.range.first, it.range.last + 1) }.toList() }
                // A bookmark label must match a complete line/block, not a phrase in body prose.
                val matches = mutableListOf<IntRange>()
                rows.indices.forEach { first ->
                    if (rows[first].text.isBlank()) return@forEach
                    var text = ""
                    for (last in first..minOf(first + 2, rows.lastIndex)) {
                        if (last > first && source.substring(rows[last - 1].end, rows[last].start).count { it == '\n' } > 1) break
                        text += " " + rows[last].text
                        val value = normalize(text)
                        if (value == normalized) matches += rows[first].start until rows[last].end
                        if (value.length >= normalized.length) break
                    }
                }
                matches.singleOrNull()
            } ?: return null
            matches += HeadingMatch(normalized, page, level, span.first, span.last + 1)
            val firstIndex = lowerBound(page, span.first)
            val lastIndex = lowerBound(page, span.last + 2, wordEnd = true) - 1
            if (firstIndex >= ends[page] || lastIndex < firstIndex) return null
            val first = tokens[firstIndex]; val last = tokens[lastIndex]
            if (book.readingStart?.let { it.pageID == first.pageID && it.utf16Offset > first.word.offset } == true) return null
            val actual = buildString {
                for (index in first.id..last.id) {
                    if (index > first.id) append(tokens[index - 1].separatorTo(tokens[index]))
                    append(tokens[index].word.text)
                }
            }
            // Partial trims never turn the rest of a heading into a new opening.
            if (normalize(actual) != normalized) return null
            return Heading(actual, first.id..last.id, level, page)
        }
        val chapters = book.chapters.filter { it.pageIndex in book.pages.indices && starts[it.pageIndex] >= 0 && !(it.utf16Offset != null && book.readingStart?.let { start -> start.pageID == book.pages[it.pageIndex].id && it.utf16Offset < start.utf16Offset } == true) }
        val candidates = chapters.map { chapter ->
            val explicit = chapter.utf16Offset?.let { offset -> book.headingMatches?.firstOrNull { it.pageIndex == chapter.pageIndex && offset in it.start until it.end } }
            val heading = if (explicit != null) match(explicit.title, explicit.pageIndex, explicit.level, explicit.start) else match(chapter.title, chapter.pageIndex, chapter.level)
            val destination = chapter.utf16Offset?.let { offset -> lowerBound(chapter.pageIndex, offset + 1, wordEnd = true).coerceAtMost(ends[chapter.pageIndex] - 1) }
            Section(chapter.id, chapter.title, chapter.pageIndex, chapter.level, destination ?: heading?.words?.first ?: starts[chapter.pageIndex], heading)
        }.distinctBy { listOf(it.pageIndex, normalize(it.title), it.level, it.index) }
            .sortedWith(compareBy<Section> { it.index }.thenBy { it.level })
        sections = candidates
        val mapped = candidates.mapNotNull { it.heading }.toMutableList()
        if (book.isEpub) book.headingMatches.orEmpty().forEach { h -> match(h.title, h.pageIndex, h.level, h.start)?.let { mapped += it } }
        // A document title is typography only; it never creates a chapter ending.
        val title = (0 until minOf(3, book.pages.size)).mapNotNull { match(book.title, it, -1) }.singleOrNull()
        if (title != null && mapped.none { it.words.first <= title.words.last && title.words.first <= it.words.last }) mapped += title
        val accepted = mutableListOf<Heading>()
        mapped.sortedBy { it.words.first }.forEach { heading ->
            if (accepted.lastOrNull()?.words?.last?.let { it < heading.words.first } != false) accepted += heading
        }
        headings = accepted
        headingWords = headings.flatMap { h -> h.words.map { it to h } }.toMap()
        val nextPeer = IntArray(candidates.size) { -1 }
        val stack = mutableListOf<Int>()
        for (index in candidates.indices.reversed()) {
            while (stack.isNotEmpty() && candidates[stack.last()].level > candidates[index].level) stack.removeAt(stack.lastIndex)
            nextPeer[index] = stack.lastOrNull() ?: -1
            stack += index
        }
        val pauses = mutableSetOf<Int>()
        candidates.forEachIndexed { index, section ->
            val opening = section.heading ?: return@forEachIndexed
            val major = section.level == 0 || Regex("(?i)^chapter\\s+\\S+").containsMatchIn(section.title)
            if (!major || headingWords[opening.words.first] != opening) return@forEachIndexed
            val next = candidates.getOrNull(nextPeer[index]) ?: return@forEachIndexed
            val nextHeading = next.heading ?: return@forEachIndexed
            val end = nextHeading.words.first - 1
            if (end <= opening.words.last || headingWords[nextHeading.words.first] != nextHeading) return@forEachIndexed
            // Missing/excluded pages cannot be presented as a completed chapter.
            if ((section.pageIndex..next.pageIndex).all { book.includesPage(it) && !book.pages[it].hasNoText }) pauses += end
        }
        chapterEnds = pauses
    }

    fun headingAt(index: Int) = headingWords[index]
    fun sectionAt(index: Int): Section? {
        var low = 0; var high = sections.size
        while (low < high) { val middle = (low + high) / 2; if (sections[middle].index <= index) low = middle + 1 else high = middle }
        return sections.getOrNull(low - 1)
    }
    fun destination(title: String, page: Int): Int? = sections.firstOrNull {
        it.pageIndex == page && normalize(it.title) == normalize(title)
    }?.let { if (isEpub) it.index else it.heading?.words?.first }
    fun duration(index: Int, base: Double): Double {
        val heading = headingAt(index)
        val reading = if (heading != null) maxOf(base, 1.2 / heading.words.count()) else base
        return if (index in chapterEnds) maxOf(reading, .8) else reading
    }
    private data class Line(val text: String, val start: Int, val end: Int)
    companion object {
        private val whitespace = Regex("[\\p{Z}\\s]+")
        fun normalize(text: String) = text.trim().replace(whitespace, " ").lowercase(Locale.ROOT)
    }
}
