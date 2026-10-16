package com.stillreader.app

import com.stillreader.app.core.ReadingFocus
import org.junit.Assert.*
import org.junit.Test

class ReadingFocusTests {
    @Test fun focusPreservesUnicodeAndIgnoresPunctuation() {
        listOf("a" to "a", "word" to "o", "reading" to "a", "“reading,”" to "a",
            "a\u0301e\u0301bc" to "e\u0301", "👨‍👩‍👧‍👦reading!" to "a", "𐐀𐐁𐐂" to "𐐁",
            "123456" to "3", "extraordinarily" to "a").forEach { (word, expected) ->
            assertEquals(word, expected, ReadingFocus.range(word)?.let { word.substring(it) })
        }
    }

    @Test fun symbolsHaveNoFocusCharacter() {
        listOf("", "…", "👨‍👩‍👧‍👦", "—?!").forEach { assertNull(ReadingFocus.range(it)) }
    }
}
