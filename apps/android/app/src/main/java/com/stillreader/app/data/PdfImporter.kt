package com.stillreader.app.data

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import com.stillreader.app.core.*
import io.legere.pdfiumandroid.PdfDocument
import io.legere.pdfiumandroid.PdfiumCore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.decodeFromStream
import kotlinx.serialization.SerializationException
import java.io.File
import java.util.Base64
import kotlin.math.roundToInt

data class ImportProgress(val message: String, val fraction: Float)
data class ImportedBook(val book: Book, val pdf: File)

class PdfImporter(private val context: Context) {
    private val core by lazy { PdfiumCore(context) }

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
                ImportedBook(extract(file, name, progress), file)
            } else {
                progress(ImportProgress("Opening book backup…", 0f))
                @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
                val backup = try {
                    file.inputStream().use { BookCodec.json.decodeFromStream<BookBackup>(it) }
                } catch (_: SerializationException) {
                    error("This file is not a valid PDF or Still book backup.")
                }
                require(backup.format == "still-book" && backup.version == 1) { "This is not a supported Still book backup." }
                val book = BookCodec.validate(backup.book)
                val pdf = File.createTempFile("still-backup-", ".pdf", context.cacheDir)
                try {
                    Base64.getDecoder().wrap(backup.pdfData.byteInputStream()).use { input -> pdf.outputStream().use { input.copyTo(it) } }
                    require(pdf.length() <= 250L * 1024 * 1024 && pdf.hasPdfHeader()) { "The backup contains an invalid PDF." }
                    currentCoroutineContext().ensureActive()
                    file.delete()
                    ImportedBook(book, pdf)
                } catch (error: Throwable) { pdf.delete(); throw error }
            }
        } catch (error: Throwable) { file.delete(); throw error }
    }

    suspend fun extract(file: File, filename: String, progress: (ImportProgress) -> Unit = {}, prepareReader: Boolean = true, preserving: Book? = null): Book = withContext(Dispatchers.IO) {
        currentCoroutineContext().ensureActive()
        withDocument(file) { document ->
            val count = document.getPageCount()
            require(count > 0) { "This PDF has no readable pages." }
            require(preserving == null || preserving.pages.size == count) { "The original PDF does not match this book." }
            val pages = ArrayList<BookPage>(count)
            val inputs = ArrayList<PdfTextRepair.Input>(count)
            for (index in 0 until count) {
                currentCoroutineContext().ensureActive()
                val page = document.openPage(index) ?: error("PDF page ${index + 1} could not be opened.")
                val input = page.use { it.openTextPage().use { textPage ->
                    val text = textPage.textPageGetText(0, textPage.textPageCountChars()) ?: ""
                    PdfTextGeometry(textPage, text).input(it.getPageHeightPoint().toDouble())
                } }
                val text = input.text
                val correction = preserving?.pages?.get(index)?.takeIf { it.isEdited }
                inputs.add(correction?.let { PdfTextRepair.Input(it.text, enabled = false) } ?: input)
                pages.add(BookPage(number = index + 1, extractedText = text))
                progress(ImportProgress("Extracting page ${index + 1} of $count…", .7f * (index + 1f) / count))
            }
            progress(ImportProgress("Improving word spacing and line breaks…", .7f))
            val repaired = repair(document, inputs)
            currentCoroutineContext().ensureActive()
            for (index in pages.indices) pages[index] = pages[index].copy(text = repaired.pages[index].text,
                processedText = repaired.pages[index].text, textProcessingVersion = PdfTextRepair.VERSION)
            progress(ImportProgress("Saving book…", 1f))
            val chapters = try {
                contents(file).mapNotNull { entry -> entry.pageIndex?.let {
                    BookChapter(title = entry.title, pageIndex = it, level = entry.level, source = "PDF bookmark")
                } }
            } catch (error: kotlinx.coroutines.CancellationException) { throw error }
            catch (_: Exception) { emptyList() } // Outline failure must not prevent text import.
            val metadata = document.getDocumentMeta()
            val book = Book(title = metadata.title?.trim()?.takeIf { it.isNotEmpty() } ?: filename.substringBeforeLast('.').replace('_', ' '),
                author = metadata.author.orEmpty(), originalFilename = filename, pages = pages,
                chapters = chapters.sortedBy { it.pageIndex })
            if (prepareReader) {
                progress(ImportProgress("Preparing reader…", .95f))
                ReaderPipeline.prepare(book)
            } else book
        }
    }

    suspend fun reprocess(file: File, book: Book, progress: (ImportProgress) -> Unit): Book = withContext(Dispatchers.IO) {
        val converted = extract(file, book.originalFilename, progress, prepareReader = false, preserving = book)
        currentCoroutineContext().ensureActive()
        progress(ImportProgress("Preparing reader…", .95f))
        ReaderPipeline.reprocess(book, converted)
    }

    suspend fun extractedPage(file: File, index: Int): String = withContext(Dispatchers.IO) {
        withDocument(file) { document -> document.openPage(index)!!.use { page -> page.openTextPage().use {
            it.textPageGetText(0, it.textPageCountChars()).orEmpty()
        } } }
    }

    /** Keep PDFium documents and their backing descriptors under one lexical owner. */
    private inline fun <T> withDocument(file: File, read: (PdfDocument) -> T): T =
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
            core.newDocument(descriptor).use(read)
        }

    private suspend fun repair(document: PdfDocument, inputs: List<PdfTextRepair.Input>): PdfTextRepair.Result {
        val job = currentCoroutineContext()
        var nativePage: io.legere.pdfiumandroid.PdfPage? = null
        var nativeText: io.legere.pdfiumandroid.PdfTextPage? = null
        var geometry: PdfTextGeometry? = null
        var activeIndex = -1
        return try {
            PdfTextRepair.repair(inputs, PdfVocabulary.words(context), checkpoint = { job.ensureActive() }) { index, offset, split ->
                if (index != activeIndex) {
                    nativeText?.close(); nativeText = null
                    nativePage?.close(); nativePage = null
                    nativePage = document.openPage(index)
                    nativeText = nativePage?.openTextPage()
                    geometry = nativeText?.let {
                        val native = it.textPageGetText(0, it.textPageCountChars()).orEmpty()
                        if (native == inputs[index].text) PdfTextGeometry(it, native) else null
                    }
                    activeIndex = index
                }
                geometry?.boundary(offset, split) == true
            }
        } finally { nativeText?.close(); nativePage?.close() }
    }

    suspend fun contents(file: File): List<PdfContentsEntry> = withContext(Dispatchers.IO) {
        currentCoroutineContext().ensureActive()
        val coroutine = currentCoroutineContext()
        PdfContentsReader(context).read(file) { coroutine.ensureActive() }
    }

    suspend fun preview(file: File, index: Int, width: Int): Bitmap = withContext(Dispatchers.IO) {
        currentCoroutineContext().ensureActive()
        withDocument(file) { document ->
            val page = document.openPage(index) ?: error("This PDF page could not be displayed.")
            page.use {
                val w = width.coerceIn(1, 1600)
                val h = (w.toDouble() * page.getPageHeightPoint() / page.getPageWidthPoint().coerceAtLeast(1)).roundToInt().coerceIn(1, 2400)
                val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                try {
                    page.renderPageBitmap(bitmap, 0, 0, w, h, true)
                    currentCoroutineContext().ensureActive()
                    bitmap
                } catch (error: Throwable) { bitmap.recycle(); throw error }
            }
        }
    }
}
