package com.stillreader.app

import com.stillreader.app.core.*
import com.stillreader.app.data.BookCodec
import kotlinx.serialization.Serializable
import org.junit.Assert.*
import org.junit.Test

@Serializable
private data class RepairInput(val text: String, val ligatureSpaces: Set<Int> = emptySet(),
    val topMargin: Boolean = false, val bottomMargin: Boolean = false, val enabled: Boolean = true)
@Serializable
private data class RepairCase(val name: String, val pages: List<RepairInput>, val expected: List<String>)

class PdfTextRepairTests {
    private val dictionary by lazy { fixture("pdf-english-words.txt").lineSequence().toSet() }
    @Test fun sharedRepairContracts() {
        val cases = BookCodec.json.decodeFromString<List<RepairCase>>(fixture("pdf-repair.json"))
        for (case in cases) {
            val result = PdfTextRepair.repair(case.pages.map {
                PdfTextRepair.Input(it.text, it.ligatureSpaces, it.topMargin, it.bottomMargin, it.enabled)
            }, dictionary)
            assertEquals(case.name, case.expected, result.pages.map { it.text })
            result.pages.forEach { assertEquals(it.text.length, it.origins.size) }
        }
    }
    @Test fun missingSpacesNeedDocumentVocabularyAndGeometry() {
        val raw = "abstract reasonings. abstract reasonings. abstractreasonings."
        val boundary = raw.lastIndexOf("abstractreasonings") + 8
        assertEquals(raw, PdfTextRepair.repair(listOf(PdfTextRepair.Input(raw))).pages.single().text)
        val repaired = PdfTextRepair.repair(listOf(PdfTextRepair.Input(raw))) { _, offset, split -> split && offset == boundary }
        assertEquals("abstract reasonings. abstract reasonings. abstract reasonings.", repaired.pages.single().text)
    }
    @Test fun decorativeInitialAndFalseSpaceNeedNativeEvidence() {
        val raw = "The next word. The next word. T\nhe ne xt word."
        assertEquals(raw, PdfTextRepair.repair(listOf(PdfTextRepair.Input(raw))).pages.single().text)
        val repaired = PdfTextRepair.repair(listOf(PdfTextRepair.Input(raw))) { _, offset, split ->
            !split && offset in listOf(raw.indexOf('\n'), raw.lastIndexOf("ne xt") + 2)
        }
        assertEquals("The next word. The next word. The next word.", repaired.pages.single().text)
    }
    @Test fun longUnbrokenTextIsPreservedAndWorkCanBeCancelled() {
        val raw = "a".repeat(100_000)
        assertEquals(raw, PdfTextRepair.repair(listOf(PdfTextRepair.Input(raw))).pages.single().text)
        assertThrows(java.util.concurrent.CancellationException::class.java) {
            PdfTextRepair.repair(listOf(PdfTextRepair.Input(raw)), checkpoint = { throw java.util.concurrent.CancellationException() })
        }
    }
    @Test fun correctionMapsUtf16PositionsAndKeepsManualPages() {
        val source = book("🌿 An oﬃce pre-\npared for reading.", "Manual words").let {
            it.copy(pages = it.pages.mapIndexed { i, p -> if (i == 1) p.copy(text = "My correction") else p },
                position = ReadingPosition(it.pages[0].id, it.pages[0].text.indexOf("reading")),
                readingStart = ReadingPosition(it.pages[0].id, it.pages[0].text.indexOf("pared")))
        }
        val enabled = source.pages.map(PdfTextRepair::eligible)
        val result = PdfTextRepair.repair(source.pages.mapIndexed { i, p -> PdfTextRepair.Input(p.text, enabled = enabled[i]) })
        val repaired = PdfTextRepair.applyTo(source, result, enabled)
        val tokens = Tokenizer.tokens(repaired)
        assertEquals("reading.", tokens[Tokenizer.index(repaired.position, tokens)].word.text)
        assertEquals("prepared", tokens.first().word.text)
        assertEquals(source.pages[1], repaired.pages[1])
        assertEquals(source.pages[0].extractedText, repaired.pages[0].extractedText)
        assertFalse(repaired.pages[0].isEdited)
        assertFalse(PdfTextRepair.eligible(repaired.pages[0]))
        assertEquals(repaired, BookCodec.decode(BookCodec.encode(repaired)))
    }
    @Test fun crossPagePositionFollowsCarriedWord() {
        val source = book("An extraordi-", "nary beginning.").let {
            it.copy(position = ReadingPosition(it.pages[1].id, 0))
        }
        val result = PdfTextRepair.repair(source.pages.map { PdfTextRepair.Input(it.text) })
        val repaired = PdfTextRepair.applyTo(source, result, listOf(true, true))
        val tokens = Tokenizer.tokens(repaired)
        assertEquals("extraordinary", tokens[Tokenizer.index(repaired.position, tokens)].word.text)
    }
}
