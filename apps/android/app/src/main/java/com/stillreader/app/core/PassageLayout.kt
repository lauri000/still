package com.stillreader.app.core

/** Stable rows for the retained book; playback only changes the vertical offset and highlight. */
class PassageLayout(tokens: List<ReadingToken>, measuredWidths: List<Double>, availableWidth: Double,
                    spacing: Double = 8.0, naturalPauses: Boolean) {
    data class Row(val words: IntRange, val duration: Double)
    val widths = measuredWidths.map { it.coerceIn(1.0, availableWidth.coerceAtLeast(1.0)) }
    val rows: List<Row>
    private val wordRows = IntArray(tokens.size)
    private val elapsed = DoubleArray(tokens.size)
    private val durations = tokens.map { Tokenizer.duration(it.word, 60.0, naturalPauses) }
    init {
        val result = mutableListOf<Row>()
        var start = 0; var used = 0.0; var duration = 0.0
        tokens.indices.forEach { index ->
            val newParagraph = index > 0 && (tokens[index - 1].word.endsParagraph || tokens[index - 1].pageID != tokens[index].pageID)
            if (index > start && (newParagraph || used + spacing + widths[index] > availableWidth.coerceAtLeast(1.0))) {
                result += Row(start until index, duration)
                start = index; used = 0.0; duration = 0.0
            }
            wordRows[index] = result.size; elapsed[index] = duration
            used += (if (index == start) 0.0 else spacing) + widths[index]
            duration += durations[index]
        }
        if (start < tokens.size) result += Row(start until tokens.size, duration)
        rows = result
    }
    fun rowContaining(index: Int) = wordRows.getOrElse(index) { 0 }

    fun position(index: Int, wordProgress: Double): Double {
        if (index !in wordRows.indices) return 0.0
        val row = wordRows[index]
        return row + (elapsed[index] + durations[index] * wordProgress.coerceIn(0.0, 1.0)) / rows[row].duration
    }
}
