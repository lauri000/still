package com.stillreader.app.core

/** Rows for one lazily displayed block of manually scrollable text. */
class PassageLayout(tokens: List<ReadingToken>, measuredWidths: List<Double>, availableWidth: Double,
                    spacing: Double = 8.0, headingIDs: Map<Int, Int> = emptyMap()) {
    data class Row(val words: IntRange, val width: Double,
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
            result += Row(start until end, used,
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
    fun rowAtOffset(offset: Double): Row? = rows.firstOrNull { it.top + it.height > offset } ?: rows.lastOrNull()
}

/** Stable paragraph/page/heading boundaries; no text is measured until a block is visible. */
class PassageDocument(tokens: List<ReadingToken>, structure: ReadingStructure) {
    data class Block(val words: IntRange) { val id get() = words.first }
    val blocks: List<Block> = buildList {
        var start = 0
        for (index in 1..tokens.size) {
            if (index == tokens.size || tokens[index - 1].word.endsParagraph ||
                tokens[index - 1].pageID != tokens[index].pageID ||
                structure.headingAt(index - 1) != structure.headingAt(index)) {
                if (start < index) add(Block(start until index))
                start = index
            }
        }
    }
    fun blockContaining(index: Int): Int {
        var low = 0; var high = blocks.size
        while (low < high) { val mid = (low + high) / 2; if (blocks[mid].words.last < index) low = mid + 1 else high = mid }
        return low.coerceAtMost((blocks.size - 1).coerceAtLeast(0))
    }
}
