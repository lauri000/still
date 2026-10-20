package com.stillreader.app.data

import android.content.Context
import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
import com.stillreader.app.core.*
import io.legere.pdfiumandroid.PdfDocument
import io.legere.pdfiumandroid.PdfiumCore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.roundToInt

class PdfImporter(private val context: Context) {
    private val core by lazy { PdfiumCore(context) }

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
