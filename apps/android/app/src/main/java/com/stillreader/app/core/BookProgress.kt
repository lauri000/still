package com.stillreader.app.core

/** Lightweight presentation of saved word indexes; never reparses a book or changes its structure. */
class BookProgress(private val book: Book, readingOnly: Boolean = true) {
    private val starts = IntArray(book.pages.size)
    private val firsts = IntArray(book.pages.size)
    private val counts = IntArray(book.pages.size)
    private val chapters = book.chapters.filter { it.pageIndex in book.pages.indices }
        .sortedWith(compareBy<BookChapter> { it.pageIndex }.thenBy { it.utf16Offset ?: 0 }.thenBy { it.level })
    val wordCount: Int?

    init {
        var total = 0
        var prepared = true
        book.pages.forEachIndexed { i, page ->
            starts[i] = total
            if (!readingOnly || book.includesPage(i)) {
                val words = page.wordIndex
                if (words == null) prepared = false
                else {
                    val trim = if (readingOnly) book.readingStart?.takeIf { it.pageID == page.id }?.utf16Offset ?: 0 else 0
                    var low = 0; var high = words.size / 3
                    while (low < high) {
                        val mid = (low + high) / 2
                        if (words[mid * 3] + words[mid * 3 + 1] <= trim) low = mid + 1 else high = mid
                    }
                    firsts[i] = low
                    counts[i] = words.size / 3 - low
                    total += counts[i]
                }
            }
        }
        wordCount = total.takeIf { prepared }
    }

    fun percent(page: Int, offset: Int = 0): Int? {
        val total = wordCount?.takeIf { it > 0 } ?: return null
        if (page !in book.pages.indices) return null
        val words = book.pages[page].wordIndex ?: return null
        var low = firsts[page]; var high = firsts[page] + counts[page]
        while (low < high) {
            val mid = (low + high) / 2
            if (words[mid * 3] <= offset) low = mid + 1 else high = mid
        }
        val index = starts[page] + (low - 1 - firsts[page]).coerceAtLeast(0)
        return (100L * index.coerceAtMost(total - 1) / total).toInt().coerceIn(0, 99)
    }

    fun chapterTitle(page: Int, offset: Int = 0): String? = chapters.lastOrNull {
        it.pageIndex < page || it.pageIndex == page && (it.utf16Offset ?: 0) <= offset
    }?.title

    fun textLabel(page: Int): String {
        if (!book.isEpub) return "Page ${book.pages[page].label}"
        val local = chapters.filter { it.pageIndex == page }
        val first = chapterTitle(page) ?: local.firstOrNull()?.title ?: "Beginning"
        val last = local.lastOrNull()?.title ?: first
        val title = if (first == last) first else "$first – $last"
        return listOfNotNull(title, percent(page)?.let { "$it% of book" }).joinToString(" · ")
    }

    fun savedStatus(): String {
        val position = book.position ?: return "Not started"
        if (position.finished) return "Finished · 100% read"
        val page = book.pages.indexOfFirst { it.id == position.pageID }
        if (page < 0) return "Not started"
        return listOfNotNull(chapterTitle(page, position.utf16Offset), percent(page, position.utf16Offset)?.let { "$it% read" })
            .joinToString(" · ").ifEmpty { "Ready to read" }
    }
}
