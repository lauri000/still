package com.stillreader.app.core

import kotlinx.serialization.Serializable
import java.util.Locale
import java.util.UUID

fun newID(): String = UUID.randomUUID().toString().uppercase(Locale.ROOT)
fun appleTimestamp(): Double = System.currentTimeMillis() / 1000.0 - 978307200.0

@Serializable
data class Book(
    val schemaVersion: Int = 1,
    val id: String = newID(),
    val title: String,
    val author: String = "",
    val originalFilename: String,
    val importedAt: Double = appleTimestamp(),
    val pages: List<BookPage>,
    val chapters: List<BookChapter> = emptyList(),
    val position: ReadingPosition? = null,
    val isSample: Boolean = false,
    val readingStart: ReadingPosition? = null,
) {
    val readingStartPageIndex: Int
        get() = pages.indexOfFirst { it.id == readingStart?.pageID }.coerceAtLeast(0)

    fun includesPage(index: Int) = index in pages.indices && index >= readingStartPageIndex && pages[index].isIncluded
    fun readingWords(index: Int): List<TextWord> {
        if (!includesPage(index)) return emptyList()
        val words = Tokenizer.words(pages[index].text)
        val start = readingStart?.takeIf { it.pageID == pages[index].id } ?: return words
        return words.filter { it.offset + it.length > start.utf16Offset }
    }

    fun withReadingStart(start: ReadingPosition?): Book {
        if (start != null && (start.utf16Offset < 0 || pages.none { it.id == start.pageID })) return this
        return copy(readingStart = start, position = start,
            pages = pages.map { if (it.id == start?.pageID) it.copy(isIncluded = true) else it })
    }

    fun editText(index: Int, text: String): Book {
        if (index !in pages.indices || pages[index].text == text) return this
        val start = readingStart?.let {
            if (it.pageID == pages[index].id) it.copy(utf16Offset = Tokenizer.relocatedOffset(it.utf16Offset, pages[index].text, text)) else it
        }
        return copy(
            pages = pages.mapIndexed { i, page -> if (i == index) page.copy(text = text, isReviewed = false) else page },
            readingStart = start, position = position?.copy(finished = false),
        )
    }
}

@Serializable
data class BookPage(
    val id: String = newID(),
    val number: Int,
    val label: String = number.toString(),
    val extractedText: String,
    val text: String = extractedText,
    val isReviewed: Boolean = false,
    val isIncluded: Boolean = true,
    val processedText: String? = null,
    val textProcessingVersion: Int? = null,
) {
    val hasNoText: Boolean get() = text.isBlank()
    val isEdited: Boolean get() = text != (processedText ?: extractedText)
}

@Serializable
data class BookChapter(
    val id: String = newID(),
    val title: String,
    val pageIndex: Int,
    val level: Int = 0,
    val source: String = "Manual",
)

@Serializable
data class ReadingPosition(val pageID: String, val utf16Offset: Int, val finished: Boolean = false)

data class TextWord(val text: String, val offset: Int, val length: Int, val endsParagraph: Boolean)
data class ReadingToken(val id: Int, val pageID: String, val pageIndex: Int, val word: TextWord) {
    val position get() = ReadingPosition(pageID, word.offset)
}

object Tokenizer {
    // Offsets are UTF-16 code units, matching Foundation's NSRange and the existing backup format.
    // Android's ICU regex rejects the JVM's (?U) flag. Spell out Unicode White_Space
    // so both engines match Foundation without changing source text or its offsets.
    private val wordPattern = Regex("[^\\p{Z}\\u0009-\\u000D\\u0085]+")
    private val graphemePattern = Regex("\\X")

    fun words(text: String): List<TextWord> {
        val matches = wordPattern.findAll(text).toList()
        return matches.mapIndexed { index, match ->
            val end = match.range.last + 1
            val next = matches.getOrNull(index + 1)?.range?.first ?: text.length
            val gap = text.substring(end, next)
            TextWord(match.value, match.range.first, match.value.length, "\n\n" in gap || "\r\n\r\n" in gap)
        }
    }

    fun tokens(book: Book): List<ReadingToken> = buildList {
        book.pages.indices.forEach { index ->
            book.readingWords(index).forEach { add(ReadingToken(size, book.pages[index].id, index, it)) }
        }
    }

    fun index(position: ReadingPosition?, tokens: List<ReadingToken>): Int {
        if (position == null) return 0
        val page = tokens.filter { it.pageID == position.pageID }
        return page.lastOrNull { it.word.offset <= position.utf16Offset }?.id ?: page.firstOrNull()?.id ?: 0
    }

    fun relocatedOffset(offset: Int, old: String, new: String): Int {
        var prefix = 0
        var suffix = 0
        while (prefix < minOf(old.length, new.length) && old[prefix] == new[prefix]) prefix++
        while (suffix < minOf(old.length, new.length) - prefix && old[old.lastIndex - suffix] == new[new.lastIndex - suffix]) suffix++
        val mapped = when {
            offset < prefix -> offset
            offset >= old.length - suffix -> offset + new.length - old.length
            else -> prefix
        }
        return mapped.coerceIn(0, new.length)
    }

    fun duration(word: TextWord, wordsPerMinute: Double, naturalPauses: Boolean): Double {
        val base = 60 / wordsPerMinute.coerceIn(60.0, 900.0)
        if (!naturalPauses) return base
        val stripped = word.text.trim { it in "\"'”’)]}" }
        val punctuation = when (stripped.lastOrNull()) {
            '.', '!', '?', '…' -> 1.9
            ',', ';', ':', '—' -> 1.35
            else -> 1.0
        }
        val length = if (graphemePattern.findAll(word.text).count() > 12) 1.25 else 1.0
        return base * maxOf(punctuation, length, if (word.endsParagraph) 2.1 else 1.0)
    }

    fun blockingPage(book: Book, from: Int, through: Int): Int? {
        if (book.pages.isEmpty() || from > through || through < 0 || from >= book.pages.size) return null
        return (from.coerceAtLeast(0)..through.coerceAtMost(book.pages.lastIndex)).firstOrNull {
            book.includesPage(it) && (!book.pages[it].isReviewed || book.pages[it].hasNoText)
        }
    }
}
