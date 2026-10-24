package com.stillreader.app

import com.stillreader.app.core.*
import com.stillreader.app.data.*
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files

class BookStreamingTests {
    @Test fun streamingSaveKeepsPortableSchemaAndDoesNotCloseTheBackupStream() {
        val raw = book("Chapter One\nCafé 🌿 — one two.\n\nNext paragraph.").copy(
            chapters = listOf(BookChapter(title = "Chapter One", pageIndex = 0)))
        for (source in listOf(raw, ReaderPipeline.prepare(raw))) {
            var closed = false
            val stream = object : ByteArrayOutputStream() { override fun close() { closed = true; super.close() } }
            BookCodec.write(source, stream)
            assertFalse(closed)
            val encoded = stream.toString("UTF-8")
            assertEquals(BookCodec.encode(source), encoded)
            val restored = BookCodec.validate(BookCodec.read(encoded.byteInputStream()))
            assertEquals(source.pages.map { it.text }, restored.pages.map { it.text })
            assertEquals(source.pages.map { it.wordIndex }, restored.pages.map { it.wordIndex })
            assertEquals(source.chapters, restored.chapters)
            assertEquals(source.needsReprocessing, restored.needsReprocessing)
            if (source.needsReprocessing) assertTrue(encoded.contains("extractedText"))
            else assertFalse(encoded.contains("extractedText"))
        }
    }

    @Test fun interruptedStreamingSaveKeepsThePreviousFileAndRemovesStagingData() {
        val root = Files.createTempDirectory("atomic-book-test").toFile()
        try {
            val file = File(root, "book.json").apply { writeText("previous saved book") }
            for (failure in listOf(IOException("Disk write interrupted"), OutOfMemoryError("Allocation failed"))) {
                val thrown = runCatching {
                    BookRepository.atomicWrite(file) { stream ->
                        stream.write("incomplete new book".toByteArray())
                        throw failure
                    }
                }.exceptionOrNull()
                assertSame(failure, thrown)
                assertEquals("previous saved book", file.readText())
                assertEquals(listOf("book.json"), root.list()!!.toList())
            }
        } finally { root.deleteRecursively() }
    }
}
