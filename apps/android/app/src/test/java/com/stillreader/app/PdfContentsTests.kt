package com.stillreader.app

import com.stillreader.app.core.*
import org.junit.Assert.*
import org.junit.Test

class PdfContentsTests {
    private val entries = listOf(
        PdfContentsEntry("0", "Part", null, hasChildren = true),
        PdfContentsEntry("0.0", "Later", 2, listOf("0"), true),
        PdfContentsEntry("0.0.0", "Deep", 2, listOf("0", "0.0")),
        PdfContentsEntry("0.1", "Earlier", 1, listOf("0")),
        PdfContentsEntry("1", "First", 0), PdfContentsEntry("2", "Duplicate", 0),
        PdfContentsEntry("3", "Unavailable", null),
    )
    @Test fun currentSectionPrefersNearestThenDeepestAndExplicitDuplicate() {
        assertEquals("Duplicate", PdfContents.current(entries, 0)?.title)
        assertEquals("First", PdfContents.current(entries, 0, "1")?.title)
        assertEquals("Earlier", PdfContents.current(entries, 1, "1")?.title)
        assertEquals("Deep", PdfContents.current(entries, 2)?.title)
        assertEquals("Deep", PdfContents.current(entries, 3)?.title)
        assertNull(PdfContents.current(entries, -1))
        assertNull(PdfContents.current(emptyList(), 0))
        assertEquals("Deep", PdfContents.current(entries, Int.MAX_VALUE)?.title)
    }
    @Test fun collapsingRetainsParentsAndSourceOrder() {
        assertEquals(listOf("0", "1", "2", "3"), PdfContents.visible(entries, setOf("0")).map { it.id })
        assertEquals(listOf("0", "0.0", "0.1", "1", "2", "3"), PdfContents.visible(entries, setOf("0.0")).map { it.id })
        assertEquals(entries, PdfContents.visible(entries, emptySet()))
    }
}
