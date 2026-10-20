package com.stillreader.app.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.stillreader.app.core.*
import kotlinx.coroutines.*
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.decodeFromStream
import java.io.File
import java.util.Base64

data class ImportProgress(val message: String, val fraction: Float)
data class ImportedBook(val book: Book, val source: File)

/** Owns file picking and source dispatch; format parsers produce the same prepared reader data. */
class BookImporter(private val context: Context) {
    val pdf = PdfImporter(context)
    private val entities by lazy { context.assets.open("xhtml-entities.json").bufferedReader().use { BookCodec.json.decodeFromString<Map<String, Int>>(it.readText()) } }
    suspend fun import(uri: Uri, progress: (ImportProgress) -> Unit): ImportedBook = withContext(Dispatchers.IO) {
        val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        } ?: uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() } ?: "Book"
        val file = File.createTempFile("still-import-", ".tmp", context.cacheDir)
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                file.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        require(total <= 400L * 1024 * 1024) { "This file is too large to import." }
                        output.write(buffer, 0, count)
                    }
                }
            } ?: error("This file could not be opened. Try saving a local copy first.")
            val isPDF = file.hasPdfHeader()
            require(isPDF || !name.endsWith(".pdf", ignoreCase = true)) { "This file could not be opened as a PDF. Try exporting a new copy." }
            if (isPDF) {
                require(file.length() <= 250L * 1024 * 1024) { "This PDF is larger than 250 MB. Try splitting it into smaller files." }
                ImportedBook(pdf.extract(file, name, progress), file)
            } else if (name.endsWith(".epub", true) || context.contentResolver.getType(uri) == "application/epub+zip" || file.inputStream().use { it.read() == 0x50 && it.read() == 0x4b }) {
                val job = currentCoroutineContext()
                ImportedBook(EpubImporter.extract(file, name, entities, { job.ensureActive() }, progress), file)
            } else {
                progress(ImportProgress("Opening book backup…", 0f))
                @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
                val backup = try {
                    file.inputStream().use { BookCodec.json.decodeFromStream<BookBackup>(it) }
                } catch (_: SerializationException) {
                    error("This file is not a valid PDF, EPUB, or Still book backup.")
                }
                require(backup.format == "still-book" && backup.version in 1..2) { "This is not a supported Still book backup." }
                val book = BookCodec.validate(backup.book)
                require(backup.version == (if (book.isEpub) 2 else 1)) { "The backup uses an invalid format." }
                val original = File.createTempFile("still-backup-", ".tmp", context.cacheDir)
                try {
                    Base64.getDecoder().wrap((if (book.isEpub) backup.sourceData ?: error("The backup has no original EPUB.") else backup.pdfData).byteInputStream()).use { input -> original.outputStream().use { input.copyTo(it) } }
                    require(original.length() <= 250L * 1024 * 1024 && BookRepository.validSource(original, book)) { "The backup contains an invalid original file." }
                    currentCoroutineContext().ensureActive()
                    file.delete()
                    ImportedBook(book, original)
                } catch (error: Throwable) { original.delete(); throw error }
            }
        } catch (error: Throwable) { file.delete(); throw error }
    }

    suspend fun reprocess(file: File, book: Book, progress: (ImportProgress) -> Unit): Book = withContext(Dispatchers.IO) {
        if (!book.isEpub) return@withContext pdf.reprocess(file, book, progress)
        val job = currentCoroutineContext()
        val converted = EpubImporter.extract(file, book.originalFilename, entities, { job.ensureActive() }, progress)
        require(book.pages.map { it.sourceHref } == converted.pages.map { it.sourceHref }) { "The EPUB sections changed. Import it as a new book to keep your current corrections and position." }
        job.ensureActive()
        ReaderPipeline.reprocess(book, converted)
    }
    suspend fun restorePage(file: File, book: Book, index: Int): Book = withContext(Dispatchers.IO) {
        if (!book.isEpub) return@withContext ReaderPipeline.update(book, book.editText(index, pdf.extractedPage(file, index)))
        val job = currentCoroutineContext()
        val converted = EpubImporter.extract(file, book.originalFilename, entities, { job.ensureActive() })
        val sourceIndex = converted.pages.indexOfFirst { it.sourceHref == book.pages[index].sourceHref }
        require(sourceIndex >= 0) { "The original section could not be found." }
        ReaderPipeline.restoreSection(book, converted, index, sourceIndex)
    }
    suspend fun contents(file: File, book: Book): List<PdfContentsEntry> = if (book.isEpub) PdfContents.fromChapters(book) else pdf.contents(file)
}
