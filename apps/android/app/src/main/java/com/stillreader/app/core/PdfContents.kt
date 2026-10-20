package com.stillreader.app.core

/** Original PDF outline, separate from the user's editable chapters and backup schema. */
data class PdfContentsEntry(
    val id: String, val title: String, val pageIndex: Int?,
    val ancestors: List<String> = emptyList(), val hasChildren: Boolean = false, val utf16Offset: Int? = null,
) {
    val level get() = ancestors.size
}

object PdfContents {
    fun fromChapters(book: Book): List<PdfContentsEntry> {
        val stack = mutableListOf<Pair<Int, String>>()
        return book.chapters.mapIndexed { index, chapter ->
            while (stack.lastOrNull()?.first?.let { it >= chapter.level } == true) stack.removeAt(stack.lastIndex)
            val id = "chapter-${chapter.id}"
            val entry = PdfContentsEntry(id, chapter.title, chapter.pageIndex, stack.map { it.second },
                book.chapters.getOrNull(index + 1)?.level?.let { it > chapter.level } == true, chapter.utf16Offset)
            stack += chapter.level to id
            entry
        }
    }

    fun current(entries: List<PdfContentsEntry>, page: Int, selectedID: String? = null, utf16Offset: Int? = null): PdfContentsEntry? =
        entries.firstOrNull { it.id == selectedID && it.pageIndex == page && (utf16Offset == null || (it.utf16Offset ?: 0) <= utf16Offset) }
            ?: entries.withIndex().filter { it.value.pageIndex?.let { destination -> destination < page || destination == page && (utf16Offset == null || (it.value.utf16Offset ?: 0) <= utf16Offset) } == true }
                .maxWithOrNull(compareBy<IndexedValue<PdfContentsEntry>> { it.value.pageIndex }
                    .thenBy { if (utf16Offset != null) it.value.utf16Offset ?: 0 else 0 }.thenBy { it.value.level }.thenBy { it.index })?.value

    fun visible(entries: List<PdfContentsEntry>, collapsed: Set<String>): List<PdfContentsEntry> =
        entries.filter { entry -> entry.ancestors.none { it in collapsed } }
}
