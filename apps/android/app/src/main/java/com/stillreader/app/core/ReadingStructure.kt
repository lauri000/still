package com.stillreader.app.core

import java.util.Locale

/** Derived from the current text, never persisted as stale offsets or rebuilt during playback. */
class ReadingStructure(book: Book, tokens: List<ReadingToken>) {
    data class Heading(val title: String, val words: IntRange, val level: Int, val pageIndex: Int)
    data class Section(val title: String, val pageIndex: Int, val level: Int, val index: Int, val heading: Heading?)
    val sections: List<Section>
    val headings: List<Heading>
    val chapterEnds: Set<Int>
    private val headingWords: Map<Int, Heading>

    init {
        val starts = IntArray(book.pages.size) { -1 }
        val ends = IntArray(book.pages.size)
        tokens.forEach { if (starts[it.pageIndex] < 0) starts[it.pageIndex] = it.id; ends[it.pageIndex] = it.id + 1 }
        val lines = mutableMapOf<Int, List<Line>>()
        fun match(title: String, page: Int, level: Int): Heading? {
            val normalized = normalize(title)
            if (normalized.length < 3 || normalized.length > 240 || starts[page] < 0) return null
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
            val span = matches.singleOrNull() ?: return null
            val pageTokens = tokens.subList(starts[page], ends[page])
            val first = pageTokens.firstOrNull { it.word.offset >= span.first } ?: return null
            val last = pageTokens.lastOrNull { it.word.offset + it.word.length <= span.last + 1 } ?: return null
            if (last.id < first.id || book.readingStart?.let { it.pageID == first.pageID && it.utf16Offset > first.word.offset } == true) return null
            val actual = tokens.subList(first.id, last.id + 1).joinToString(" ") { it.word.text }
            // Partial trims never turn the rest of a heading into a new opening.
            if (normalize(actual) != normalized) return null
            return Heading(actual, first.id..last.id, level, page)
        }
        val chapters = book.chapters.filter { it.pageIndex in book.pages.indices && starts[it.pageIndex] >= 0 }
        val candidates = chapters.map { chapter ->
            val heading = match(chapter.title, chapter.pageIndex, chapter.level)
            Section(chapter.title, chapter.pageIndex, chapter.level, heading?.words?.first ?: starts[chapter.pageIndex], heading)
        }.distinctBy { Triple(it.pageIndex, normalize(it.title), it.level) }
            .sortedWith(compareBy<Section> { it.index }.thenBy { it.level })
        sections = candidates
        val mapped = candidates.mapNotNull { it.heading }.toMutableList()
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
    }?.heading?.words?.first
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
