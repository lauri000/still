package com.stillreader.app.data

import com.stillreader.app.core.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FilterOutputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Base64
import java.util.Locale

fun File.hasPdfHeader(): Boolean = inputStream().use { input ->
    val header = ByteArray(5)
    input.read(header) == 5 && header.contentEquals("%PDF-".toByteArray())
}

object BookCodec {
    val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }
    private val uuid = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
    private fun canonicalID(id: String): String {
        require(uuid.matches(id)) { "The book contains an invalid identifier." }
        return id.uppercase(Locale.ROOT)
    }
    fun validate(book: Book): Book {
        require(book.schemaVersion == 1) { "This book uses a newer format. Update Still to open it." }
        require(book.pages.isNotEmpty() && book.importedAt.isFinite()) { "The book data is incomplete." }
        val normalized = book.copy(id = canonicalID(book.id),
            pages = book.pages.map { it.copy(id = canonicalID(it.id)) },
            chapters = book.chapters.map { it.copy(id = canonicalID(it.id)) },
            position = book.position?.let { it.copy(pageID = canonicalID(it.pageID)) },
            readingStart = book.readingStart?.let { it.copy(pageID = canonicalID(it.pageID)) })
        val ids = normalized.pages.map { it.id }.toSet()
        require(ids.size == normalized.pages.size) { "The book contains duplicate pages." }
        require(normalized.chapters.all { it.pageIndex in normalized.pages.indices && it.level >= 0 }) { "A chapter points outside this book." }
        listOfNotNull(normalized.position, normalized.readingStart).forEach {
            require(it.pageID in ids && it.utf16Offset >= 0) { "The book contains an invalid reading position." }
        }
        return normalized
    }
    fun decode(text: String): Book = validate(json.decodeFromString<Book>(text))
    fun encode(book: Book): String = json.encodeToString(validate(book))
}

@Serializable
data class BookBackup(val format: String = "still-book", val version: Int = 1, val book: Book, val pdfData: String)

data class LibraryResult(val books: List<Book>, val unreadableCount: Int)

/** Call from the repository's serial IO queue. All book files are replaced atomically. */
class BookRepository(val root: File) {
    fun directory(id: String): File {
        // Never resolve a path supplied by an imported document without validating it.
        require(Regex("[A-Fa-f0-9-]{36}").matches(id))
        return File(root, id)
    }
    fun pdfFile(id: String) = File(directory(id), "original.pdf")
    fun load(): LibraryResult {
        check(root.isDirectory || root.mkdirs()) { "Could not open the book library." }
        var unreadable = 0
        val books = root.listFiles().orEmpty().filter { it.isDirectory && !it.name.startsWith('.') }.mapNotNull { folder ->
            try {
                val book = BookCodec.decode(File(folder, "book.json").readText())
                require(book.id == folder.name && pdfFile(book.id).isFile)
                book
            } catch (_: Exception) { unreadable++; null }
        }
        return LibraryResult(books.sortedByDescending { it.importedAt }, unreadable)
    }
    fun save(book: Book) {
        val target = File(directory(book.id), "book.json")
        require(pdfFile(book.id).isFile) { "The original PDF is missing. Your existing files have been kept." }
        atomicWrite(target, BookCodec.encode(book))
    }
    fun insert(source: Book, pdf: File): Book {
        var book = BookCodec.validate(source)
        if (directory(book.id).exists()) book = book.copy(id = newID(), importedAt = appleTimestamp())
        check(root.isDirectory || root.mkdirs())
        val staging = File(root, ".import-${newID()}")
        check(staging.mkdir())
        try {
            require(pdf.hasPdfHeader()) { "The original PDF is invalid." }
            pdf.copyTo(File(staging, "original.pdf"))
            atomicWrite(File(staging, "book.json"), BookCodec.encode(book))
            check(staging.renameTo(directory(book.id))) { "The book could not be saved." }
        } finally { staging.deleteRecursively() }
        return book
    }
    fun delete(id: String) {
        val original = directory(id)
        val tombstone = File(root, ".deleted-${newID()}")
        check(original.renameTo(tombstone)) { "The book could not be removed." }
        tombstone.deleteRecursively()
    }
    fun export(book: Book, output: OutputStream) {
        // Stream the PDF as base64 instead of allocating another copy of the entire PDF.
        output.write("{\"format\":\"still-book\",\"version\":1,\"book\":".toByteArray())
        output.write(BookCodec.encode(book).toByteArray())
        output.write(",\"pdfData\":\"".toByteArray())
        val nonClosing = object : FilterOutputStream(output) { override fun close() { flush() } }
        Base64.getEncoder().wrap(nonClosing).use { encoded -> pdfFile(book.id).inputStream().use { it.copyTo(encoded) } }
        output.write("\"}".toByteArray())
        output.flush()
    }
    companion object {
        fun atomicWrite(target: File, text: String) {
            val temp = File(target.parentFile, ".${target.name}-${newID()}")
            try {
                temp.outputStream().use { stream -> stream.write(text.toByteArray()); stream.fd.sync() }
                Files.move(temp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } finally { temp.delete() }
        }
    }
}
