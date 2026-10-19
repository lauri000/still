package com.stillreader.app.core

/** Plain rows for a nearby passage. Each word has the same share of its row's reading time. */
class PassageLayout(tokens: List<ReadingToken>, measuredWidths: List<Double>, availableWidth: Double,
                    spacing: Double = 8.0, headingIDs: Map<Int, Int> = emptyMap()) {
    data class Row(val words: IntRange, val text: String, val width: Double,
                   val top: Double, val height: Double, val headingID: Int?) {
        val isHeading get() = headingID != null
    }
    val rows: List<Row>
    private val wordRows = IntArray(tokens.size)
    init {
        val result = mutableListOf<Row>()
        var start = 0; var used = 0.0
        fun append(end: Int) {
            val heading = headingIDs[tokens[start].id]
            val previous = result.lastOrNull()
            val gap = if (heading != previous?.headingID && (heading != null || previous?.isHeading == true)) .45 else 0.0
            result += Row(start until end, tokens.subList(start, end).joinToString(" ") { it.word.text }, used,
                (previous?.let { it.top + it.height } ?: 0.0) + gap, if (heading != null) 1.2 else 1.0, heading)
        }
        tokens.indices.forEach { index ->
            val gap = spacing * (if (headingIDs[tokens[index].id] != null) 1.2 else 1.0)
            val newParagraph = index > 0 && (headingIDs[tokens[index - 1].id] != headingIDs[tokens[index].id] || tokens[index - 1].word.endsParagraph || tokens[index - 1].pageID != tokens[index].pageID)
            if (index > start && (newParagraph || used + gap + measuredWidths[index] > availableWidth.coerceAtLeast(1.0))) {
                append(index); start = index; used = 0.0
            }
            wordRows[index] = result.size
            used += (if (index == start) 0.0 else gap) + measuredWidths[index].coerceAtLeast(1.0)
        }
        if (start < tokens.size) append(tokens.size)
        rows = result
    }
    fun rowContaining(index: Int) = wordRows.getOrElse(index) { 0 }
    fun position(index: Int, wordProgress: Double): Double {
        if (index !in wordRows.indices) return 0.0
        val row = wordRows[index]
        val words = rows[row].words
        val distance = rows.getOrNull(row + 1)?.let { it.top - rows[row].top } ?: rows[row].height
        return rows[row].top + distance * (index - words.first + wordProgress.coerceIn(0.0, 1.0)) / words.count()
    }

    companion object {
        /** Start at real paragraph/page boundaries so overlapping windows wrap identically. */
        fun window(tokens: List<ReadingToken>, index: Int, contextWords: Int = 256): IntRange {
            if (tokens.isEmpty()) return 0 until 0
            val current = index.coerceIn(tokens.indices)
            var start = current; var end = current + 1
            while (start > 0 && tokens[start - 1].pageID == tokens[current].pageID) start--
            while (end < tokens.size && tokens[end].pageID == tokens[current].pageID) end++
            start = (start - contextWords).coerceAtLeast(0)
            end = (end + contextWords).coerceAtMost(tokens.size)
            fun boundary(at: Int) = tokens[at - 1].word.endsParagraph || tokens[at - 1].pageID != tokens[at].pageID
            while (start > 0 && !boundary(start)) start--
            while (end < tokens.size && !boundary(end)) end++
            return start until end
        }
    }
}
