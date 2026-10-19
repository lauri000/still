package com.stillreader.app.core

/** Original PDF outline, separate from the user's editable chapters and backup schema. */
data class PdfContentsEntry(
    val id: String, val title: String, val pageIndex: Int?,
    val ancestors: List<String> = emptyList(), val hasChildren: Boolean = false,
) {
    val level get() = ancestors.size
}

object PdfContents {
    fun current(entries: List<PdfContentsEntry>, page: Int, selectedID: String? = null): PdfContentsEntry? =
        entries.firstOrNull { it.id == selectedID && it.pageIndex == page }
            ?: entries.withIndex().filter { it.value.pageIndex?.let { destination -> destination <= page } == true }
                .maxWithOrNull(compareBy<IndexedValue<PdfContentsEntry>> { it.value.pageIndex }
                    .thenBy { it.value.level }.thenBy { it.index })?.value

    fun visible(entries: List<PdfContentsEntry>, collapsed: Set<String>): List<PdfContentsEntry> =
        entries.filter { entry -> entry.ancestors.none { it in collapsed } }
}
