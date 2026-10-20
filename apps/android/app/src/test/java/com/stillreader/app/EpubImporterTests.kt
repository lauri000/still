package com.stillreader.app

import com.stillreader.app.core.*
import com.stillreader.app.data.*
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.junit.Assume.assumeTrue

class EpubImporterTests {
    private fun file(name: String): File = File(javaClass.classLoader!!.getResource("epub/$name")!!.toURI())
    private val entities = javaClass.classLoader!!.getResourceAsStream("xhtml-entities.json")!!.bufferedReader().use { BookCodec.json.decodeFromString<Map<String, Int>>(it.readText()) }
    private fun imported(name: String = "structured.epub") = EpubImporter.extract(file(name), name, entities)

    @Test fun semanticTextSpineOrderAndExactAnchorsSurvivePreparedStorage() {
        val book = imported()
        assertEquals("A Structured Book", book.title)
        assertEquals("epub", book.sourceFormat)
        assertEquals(listOf("OPS/first.xhtml", "OPS/café two.xhtml"), book.pages.map { it.sourceHref })
        assertEquals("First heading\n\nCafé 🌿 something & more.\n\nAnother paragraph.\nA new line.\n\nRepeat\n\nFirst repeat body.\n\nRepeat\n\nSecond repeat body.", book.pages[0].text)
        assertEquals(listOf("A different label", "Repeat", "Repeat", "The ending"), book.chapters.map { it.title })
        val first = book.chapters[1].utf16Offset!!; val second = book.chapters[2].utf16Offset!!
        val contents = PdfContents.fromChapters(book)
        assertEquals(contents[0].id, PdfContents.current(contents, 0, utf16Offset = 0)?.id)
        assertEquals(contents[2].id, PdfContents.current(contents, 0, utf16Offset = second)?.id)
        assertTrue(second > first)
        assertTrue(book.pages[0].text.substring(second).startsWith("Repeat\n\nSecond"))
        val restored = BookCodec.decode(BookCodec.encode(book))
        assertFalse(restored.needsReprocessing)
        assertEquals(book.pages.map { it.text }, restored.pages.map { it.text })
        val reader = restored.readerCache.get(restored)
        assertEquals(listOf("First heading", "Repeat", "Repeat", "Last heading"), reader.structure.headings.map { it.title })
        assertEquals("Repeat", reader.tokens[reader.index(ReadingPosition(restored.pages[0].id, second))].word.text)
        assertEquals(second, reader.tokens[reader.structure.headings[2].words.first].word.offset)
        val trimmed = restored.withReadingStart(ReadingPosition(restored.pages[0].id, second))
        assertEquals(2, trimmed.readerCache.get(trimmed).structure.sections.size)
        val output = File("build/contract-output/android-epub.json")
        output.parentFile.mkdirs(); output.writeText(BookCodec.encode(book))
    }

    @Test fun epub2NcxBomAndStandardXhtmlEntities() {
        val book = imported("legacy.epub")
        assertEquals("Legacy heading\n\nSome words — ©.", book.pages[0].text)
        assertEquals("Legacy chapter", book.chapters.single().title)
        assertEquals(0, book.chapters.single().utf16Offset)
    }

    @Test fun swiftBackupRetainsOriginalAndPreparedText() {
        val file = File("build/contract-output/swift-epub-backup.json")
        assumeTrue("Run scripts/epub-contracts.swift first", file.exists())
        val backup = BookCodec.json.decodeFromString<BookBackup>(file.readText())
        val book = BookCodec.validate(backup.book)
        assertEquals(2, backup.version)
        assertFalse(book.needsReprocessing)
        assertEquals(imported().pages.map { it.text }, book.pages.map { it.text })
        assertArrayEquals(file("structured.epub").readBytes(), java.util.Base64.getDecoder().decode(backup.sourceData))
    }

    @Test fun realCorpusImportsAndKeepsSectionOrigins() {
        val cases = listOf("hume-enquiry-epub2.epub", "hume-enquiry-epub3.epub", "alice-epub3.epub", "moby-dick.epub", "childrens-literature.epub", "internallinks.epub", "israelsailing.epub")
        assumeTrue("Download the optional EPUB corpus with scripts/download-epub-fixtures.py", cases.all { javaClass.classLoader!!.getResource("epub/$it") != null })
        for (name in cases) {
            val book = imported(name)
            assertFalse(name, book.needsReprocessing)
            assertTrue(name, book.pages.sumOf { it.words.size } > 100)
            assertTrue(name, book.chapters.isNotEmpty())
            assertTrue(name, book.pages.all { it.sourceHref != null })
            assertEquals(book.pages.map { it.text }, BookCodec.decode(BookCodec.encode(book)).pages.map { it.text })
            if (name == "moby-dick.epub") assertTrue(book.chapters.size >= 130)
            val output = File("build/contract-output/epub/$name.json")
            output.parentFile.mkdirs(); output.writeText(BookCodec.encode(book))
        }
    }

    @Test fun malformedEncryptedFixedAndImageOnlySourcesFailClearly() {
        for ((name, message) in listOf("encrypted.epub" to "encrypted", "fixed-layout.epub" to "fixed-layout", "image-only.epub" to "no readable text", "unsafe-entity.epub" to "entity", "missing-spine.epub" to "missing", "path-escape.epub" to "package")) {
            val error = runCatching { imported(name) }.exceptionOrNull()
            assertNotNull(name, error)
            assertTrue("$name: ${error?.message}", error!!.message.orEmpty().contains(message, ignoreCase = true))
        }
    }

    @Test fun reprocessingPreservesEditsExactPositionAndSourceIdentity() {
        val book = imported()
        val offset = book.pages[0].text.indexOf("Second")
        val edited = ReaderPipeline.update(book, book.editText(1, "My corrected ending.")).copy(position = ReadingPosition(book.pages[0].id, offset))
        val result = ReaderPipeline.reprocess(edited, imported())
        assertEquals(edited.pages.map { it.id }, result.pages.map { it.id })
        assertEquals("My corrected ending.", result.pages[1].text)
        assertEquals(edited.position, result.position)
        assertFalse(result.needsReprocessing)
    }

    @Test fun epubBackupRetainsOriginalBytesAndReopensWithoutImportingAgain() {
        val root = java.nio.file.Files.createTempDirectory("still-epub-test").toFile()
        try {
            val repository = BookRepository(root)
            val book = repository.insert(imported(), file("structured.epub"))
            val out = java.io.ByteArrayOutputStream(); repository.export(book, out)
            val backup = BookCodec.json.decodeFromString<BookBackup>(out.toString("UTF-8"))
            assertEquals(2, backup.version); assertEquals("", backup.pdfData)
            assertArrayEquals(file("structured.epub").readBytes(), java.util.Base64.getDecoder().decode(backup.sourceData))
            assertArrayEquals(file("structured.epub").readBytes(), repository.sourceFile(book).readBytes())
            assertFalse(repository.pdfFile(book.id).exists())
            assertEquals(BookCodec.encode(book), BookCodec.encode(repository.load().books.single()))
            val output = File("build/contract-output/android-epub-backup.json")
            output.parentFile.mkdirs(); output.writeBytes(out.toByteArray())
        } finally { root.deleteRecursively() }
    }

    @Test fun fixedLayoutCorpusFailsClearly() {
        assumeTrue(javaClass.classLoader!!.getResource("epub/haruko-html-jpeg.epub") != null)
        val error = runCatching { imported("haruko-html-jpeg.epub") }.exceptionOrNull()
        assertTrue(error?.message.orEmpty().contains("fixed-layout"))
    }

    @Test fun cancellationStopsImportBeforePreparingReader() {
        var checks = 0
        val error = runCatching { EpubImporter.extract(file("structured.epub"), "structured.epub", checkpoint = { if (++checks > 20) throw java.util.concurrent.CancellationException() }) }.exceptionOrNull()
        assertTrue(error is java.util.concurrent.CancellationException)
    }

    @Test fun hardBreakSurvivesStorageAndPassageLayoutWithoutChangingPdfWrapping() {
        val book = BookCodec.decode(BookCodec.encode(imported()))
        val document = book.readerCache.get(book)
        val block = document.passage.blocks.first { block -> document.tokens.slice(block.words).any { it.word.text == "Another" } }
        val words = document.tokens.slice(block.words)
        val layout = PassageLayout(words, List(words.size) { 1.0 }, 1000.0)
        assertEquals(listOf("Another paragraph.", "A new line."), layout.rows.map { row -> words.slice(row.words).joinToString(" ") { it.word.text } })
        val pdf = ReaderPipeline.prepare(Book(title = "Test", originalFilename = "test.pdf", pages = listOf(BookPage(number = 1, text = "Another paragraph.\nA new line."))))
        val pdfWords = pdf.readerCache.get(pdf).tokens
        assertEquals(1, PassageLayout(pdfWords, List(pdfWords.size) { 1.0 }, 1000.0).rows.size)
    }

    @Test fun textCorrectionDoesNotCustomizeChaptersAndReprocessingRefreshesThem() {
        val original = imported()
        val edited = ReaderPipeline.update(original, original.editText(0, "Preface.\n\n" + original.pages[0].text))
        assertNotEquals(original.chapters[1].utf16Offset, edited.chapters[1].utf16Offset)
        assertFalse(edited.chaptersCustomized)
        val converted = original.copy(chapters = original.chapters.mapIndexed { i, c -> if (i == 0) c.copy(title = "Refreshed navigation") else c })
        assertEquals("Refreshed navigation", ReaderPipeline.reprocess(edited, converted).chapters[0].title)
        val reverted = original.editText(0, "x" + original.pages[0].text).editText(0, original.pages[0].text)
        assertFalse(ReaderPipeline.update(original, reverted).needsReprocessing)
    }

    @Test fun restoreRecoversHeadingsAndRepeatedChapterAnchors() {
        val original = imported()
        val edited = ReaderPipeline.update(original, original.editText(0, "Replacement content."))
        assertTrue(edited.pages[0].headings.orEmpty().isEmpty())
        val restored = ReaderPipeline.restoreSection(edited, original, 0, 0)
        assertEquals(original.pages[0].text, restored.pages[0].text)
        assertEquals(original.pages[0].headings, restored.pages[0].headings)
        assertEquals(original.chapters.map { it.utf16Offset }, restored.chapters.map { it.utf16Offset })
        assertFalse(restored.pages[0].isEdited)
        assertEquals(original.pages[0].headings, ReaderPipeline.reprocess(restored, original).pages[0].headings)
    }

    @Test fun restoreKeepsChapterRemovalsAndManualEntries() {
        val original = imported()
        val manual = BookChapter(title = "My note", pageIndex = 0, utf16Offset = 0)
        val customized = original.copy(chapters = original.chapters.filterIndexed { i, _ -> i != 1 } + manual, chaptersCustomized = true)
        val edited = ReaderPipeline.update(original, customized.editText(0, "Replacement content."))
        val restored = ReaderPipeline.restoreSection(edited, imported(), 0, 0)
        assertEquals(edited.chapters.map { it.id }, restored.chapters.map { it.id })
        assertEquals(original.chapters[2].utf16Offset, restored.chapters.first { it.id == original.chapters[2].id }.utf16Offset)
        assertFalse(restored.chapters.any { it.id == original.chapters[1].id })
        assertTrue(restored.chapters.any { it.id == manual.id })
    }
}
