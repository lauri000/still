package com.stillreader.app.core

object ReadingFocus {
    private val graphemes = Regex("\\X")

    /** UTF-16 range of a complete character; punctuation does not affect letter counting. */
    fun range(word: String): IntRange? {
        val letters = graphemes.findAll(word).filter { match ->
            match.value.codePoints().anyMatch(Character::isLetterOrDigit)
        }.toList()
        if (letters.isEmpty()) return null
        val position = when (letters.size) {
            1 -> 0
            in 2..5 -> 1
            in 6..9 -> 2
            in 10..13 -> 3
            else -> 4
        }
        return letters[position].range
    }
}
