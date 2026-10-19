package com.stillreader.app.data

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import com.stillreader.app.core.*
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

    suspend fun extract(file: File, filename: String, progress: (ImportProgress) -> Unit = {}): Book = withContext(Dispatchers.IO) {
        currentCoroutineContext().ensureActive()
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
            core.newDocument(descriptor).use { document ->
                val count = document.getPageCount()
                require(count > 0) { "This PDF has no readable pages." }
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
                    inputs.add(input)
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
                Book(title = metadata.title?.trim()?.takeIf { it.isNotEmpty() } ?: filename.substringBeforeLast('.').replace('_', ' '),
                    author = metadata.author.orEmpty(), originalFilename = filename, pages = pages,
                    chapters = chapters.sortedBy { it.pageIndex })
            }
        }
    }

    suspend fun improve(file: File, book: Book, progress: (ImportProgress) -> Unit): Book = withContext(Dispatchers.IO) {
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
            core.newDocument(descriptor).use { document ->
                require(document.getPageCount() == book.pages.size) { "The original PDF does not match this book." }
                val enabled = book.pages.map { PdfTextRepair.eligible(it) && it.isIncluded }
                val inputs = book.pages.mapIndexed { index, source ->
                    currentCoroutineContext().ensureActive()
                    progress(ImportProgress("Checking page ${index + 1} of ${book.pages.size}…", .7f * (index + 1f) / book.pages.size))
                    if (!enabled[index]) PdfTextRepair.Input(source.text, enabled = false)
                    else document.openPage(index)!!.use { page -> page.openTextPage().use { textPage ->
                        val native = textPage.textPageGetText(0, textPage.textPageCountChars()).orEmpty()
                        // Backups can originate in another engine. Keep their immutable source and
                        // use text-only repair if its offsets no longer match this engine's text.
                        if (native == source.extractedText) PdfTextGeometry(textPage, native).input(page.getPageHeightPoint().toDouble())
                        else PdfTextRepair.Input(source.extractedText)
                    } }
                }
                progress(ImportProgress("Improving word spacing and line breaks…", .7f))
                val result = repair(document, inputs)
                currentCoroutineContext().ensureActive()
                PdfTextRepair.applyTo(book, result, enabled)
            }
        }
    }

    private suspend fun repair(document: io.legere.pdfiumandroid.PdfDocument, inputs: List<PdfTextRepair.Input>): PdfTextRepair.Result {
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
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
            core.newDocument(descriptor).use { document ->
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
}
