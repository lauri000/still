package com.stillreader.app.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import java.util.regex.Pattern
import java.util.Locale
import java.util.UUID

fun newID(): String = UUID.randomUUID().toString().uppercase(Locale.ROOT)
fun appleTimestamp(): Double = System.currentTimeMillis() / 1000.0 - 978307200.0

@Serializable
data class Book(
    val schemaVersion: Int = 1,
    val sourceFormat: String = "pdf",
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
    val catalogID: String? = null,
    val contentHash: String? = null,
    val epubIdentifier: String? = null,
    val languageCodes: List<String>? = null,
    val synopsis: String? = null,
    val sourceURL: String? = null,
    val coverData: String? = null,
    val pipelineVersion: Int? = null,
    val chaptersCustomized: Boolean = false,
    val headingMatches: List<HeadingMatch>? = null,
    @Transient val readerCache: ReaderCache = ReaderCache(),
) {
    val isEpub get() = sourceFormat == "epub"
    val unitName get() = if (isEpub) "Section" else "Page"
    val unitPlural get() = if (isEpub) "sections" else "pages"
    val sourceFilename get() = if (isEpub) "original.epub" else "original.pdf"
    val needsReprocessing: Boolean get() = pipelineVersion != ReaderPipeline.version(this) || pages.any { it.wordIndex == null } || headingMatches == null

    val readingStartPageIndex: Int
        get() = pages.indexOfFirst { it.id == readingStart?.pageID }.coerceAtLeast(0)

    fun includesPage(index: Int) = index in pages.indices && index >= readingStartPageIndex && pages[index].isIncluded
    fun readingWords(index: Int): List<TextWord> {
        if (!includesPage(index)) return emptyList()
        val words = pages[index].words
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
        val mapping = TextEditMapping(pages[index].text, text)
        val start = readingStart?.let {
            if (it.pageID == pages[index].id) it.copy(utf16Offset = mapping.offset(it.utf16Offset)) else it
        }
        return copy(
            pages = pages.mapIndexed { i, page -> if (i == index) page.copy(text = text, isReviewed = false, edited = true, wordIndex = null,
                headings = page.headings?.mapNotNull { h ->
                    val start = mapping.offset(h.start)
                    val end = mapping.offset(h.end)
                    if (end > start) TextHeading(start, end, h.level) else null
                }) else page },
            chapters = chapters.map { chapter -> if (chapter.pageIndex == index && chapter.utf16Offset != null)
                chapter.copy(utf16Offset = mapping.offset(chapter.utf16Offset)) else chapter },
            readingStart = start, position = position?.copy(finished = false),
        )
    }
}

@Serializable
data class BookPage(
    val id: String = newID(),
    val number: Int,
    val label: String = number.toString(),
    val extractedText: String = "",
    val text: String = extractedText,
    val isReviewed: Boolean = false,
    val isIncluded: Boolean = true,
    val processedText: String? = null,
    val textProcessingVersion: Int? = null,
    val edited: Boolean? = null,
    val wordIndex: List<Int>? = null,
    val sourceHref: String? = null,
    val headings: List<TextHeading>? = null,
) {
    // The index is compact UTF-16 (offset, length, paragraph flag) triples.
    val words: List<TextWord> get() {
        val index = wordIndex ?: return Tokenizer.words(text, preserveLineBreaks = sourceHref != null)
        return buildList(index.size / 3) {
            for (i in index.indices step 3) {
                val offset = index[i]; val length = index[i + 1]
                add(TextWord(text.substring(offset, offset + length), offset, length, index[i + 2] == 1, index[i + 2] == 2))
            }
        }
    }
    val hasNoText: Boolean get() = text.isBlank()
    val isEdited: Boolean get() = edited ?: (text != (processedText ?: extractedText))
}

@Serializable
data class TextHeading(val start: Int, val end: Int, val level: Int)

@Serializable
data class BookChapter(
    val id: String = newID(),
    val title: String,
    val pageIndex: Int,
    val level: Int = 0,
    val source: String = "Manual",
    val utf16Offset: Int? = null,
    val sourceAnchor: String? = null,
)

@Serializable
data class ReadingPosition(val pageID: String, val utf16Offset: Int, val finished: Boolean = false)

data class TextWord(val text: String, val offset: Int, val length: Int, val endsParagraph: Boolean, val endsLine: Boolean = false)
data class ReadingToken(val id: Int, val pageID: String, val pageIndex: Int, val word: TextWord) {
    val position get() = ReadingPosition(pageID, word.offset)
}

object Tokenizer {
    // Offsets are UTF-16 code units, matching Foundation's NSRange and the existing backup format.
    // Android's ICU regex rejects the JVM's (?U) flag. Spell out Unicode White_Space
    // so both engines match Foundation without changing source text or its offsets.
    private val wordPattern = Pattern.compile("[^\\p{Z}\\u0009-\\u000D\\u0085]+")
    private val graphemePattern = Regex("\\X")

    fun words(text: String, preserveLineBreaks: Boolean = false): List<TextWord> {
        val matcher = wordPattern.matcher(text)
        return buildList {
            if (!matcher.find()) return@buildList
            do {
                val start = matcher.start(); val end = matcher.end()
                val word = matcher.group()
                val next = matcher.find()
                val gap = text.substring(end, if (next) matcher.start() else text.length)
                val paragraph = "\n\n" in gap || "\r\n\r\n" in gap
                add(TextWord(word, start, end - start, paragraph, preserveLineBreaks && !paragraph && '\n' in gap))
            } while (next)
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

    fun relocatedOffset(offset: Int, old: String, new: String): Int = TextEditMapping(old, new).offset(offset)

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

/** Scan an edit once before relocating the section's headings and chapter destinations. */
class TextEditMapping(old: String, new: String) {
    private var prefix = 0
    private var suffix = 0
    private val oldCount = old.length
    private val newCount = new.length
    init {
        while (prefix < minOf(old.length, new.length) && old[prefix] == new[prefix]) prefix++
        while (suffix < minOf(old.length, new.length) - prefix && old[old.lastIndex - suffix] == new[new.lastIndex - suffix]) suffix++
    }
    fun offset(offset: Int): Int = when {
        offset < prefix -> offset
        offset >= oldCount - suffix -> offset + newCount - oldCount
        else -> prefix
    }.coerceIn(0, newCount)
}
