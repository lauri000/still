package com.stillreader.app.data

import com.stillreader.app.core.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.*
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

@OptIn(ExperimentalSerializationApi::class)
object BookCodec {
    val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }
    private val uuid = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
    private fun canonicalID(id: String): String {
        require(uuid.matches(id)) { "The book contains an invalid identifier." }
        return id.uppercase(Locale.ROOT)
    }
    fun validate(book: Book): Book {
        require(book.schemaVersion in 1..3) { "This book uses a newer format. Update Still to open it." }
        require(book.sourceFormat in setOf("pdf", "epub") && (!book.isEpub || book.schemaVersion == 3)) { "The book source format is not supported." }
        require(book.pages.isNotEmpty() && book.importedAt.isFinite()) { "The book data is incomplete." }
        val normalized = book.copy(id = canonicalID(book.id),
            pages = book.pages.map { it.copy(id = canonicalID(it.id)) },
            chapters = book.chapters.map { it.copy(id = canonicalID(it.id)) },
            position = book.position?.let { it.copy(pageID = canonicalID(it.pageID)) },
            readingStart = book.readingStart?.let { it.copy(pageID = canonicalID(it.pageID)) })
        val ids = normalized.pages.map { it.id }.toSet()
        require(ids.size == normalized.pages.size) { "The book contains duplicate pages." }
        require(normalized.chapters.all { it.pageIndex in normalized.pages.indices && it.level >= 0 && (it.utf16Offset == null || it.utf16Offset in 0..normalized.pages[it.pageIndex].text.length) }) { "A chapter points outside this book." }
        listOfNotNull(normalized.position, normalized.readingStart).forEach {
            require(it.pageID in ids && it.utf16Offset >= 0) { "The book contains an invalid reading position." }
        }
        require(normalized.pages.all { page -> page.headings.orEmpty().all { it.start >= 0 && it.end > it.start && it.end <= page.text.length && it.level in 0..5 } }) { "The book contains an invalid heading." }
        val validIndex = normalized.pages.all { page ->
            val words = page.wordIndex ?: return@all false
            var end = 0
            words.size % 3 == 0 && (words.indices step 3).all { index ->
                val offset = words[index]; val length = words[index + 1]; val paragraph = words[index + 2]
                (offset >= end && length > 0 && offset <= page.text.length - length && paragraph in 0..(if (normalized.isEpub) 2 else 1)).also { end = offset + length }
            }
        } && normalized.headingMatches?.all { match ->
            match.pageIndex in normalized.pages.indices && match.start >= 0 && match.end > match.start && match.end <= normalized.pages[match.pageIndex].text.length
        } == true
        return if (validIndex) ReaderPipeline.upgradeWordBoundaries(normalized) else normalized.copy(pipelineVersion = null, headingMatches = null,
            pages = normalized.pages.map { it.copy(wordIndex = null) })
    }
    fun decode(text: String): Book = validate(json.decodeFromString<Book>(text))
    private fun persisted(book: Book): Book {
        val valid = validate(book)
        // Shallow page copies retain the same text/index arrays. No intermediate JSON object tree.
        return if (valid.needsReprocessing) valid else valid.copy(pages = valid.pages.map {
            it.copy(extractedText = "", processedText = null, textProcessingVersion = null)
        })
    }
    fun encode(book: Book): String = json.encodeToString(persisted(book))
    fun write(book: Book, output: OutputStream) = json.encodeToStream(persisted(book), output)
    fun read(input: java.io.InputStream): Book = json.decodeFromStream<Book>(input)
}

@Serializable
data class BookBackup(val format: String = "still-book", val version: Int = 1, val book: Book, val pdfData: String = "", val sourceData: String? = null)

data class LibraryResult(val books: List<Book>, val unreadableCount: Int)

/** Call from the repository's serial IO queue. All book files are replaced atomically. */
@OptIn(ExperimentalSerializationApi::class)
class BookRepository(val root: File) {
    @Serializable
    private data class Identity(val id: String, val sourceFormat: String = "pdf", val catalogID: String? = null,
        val contentHash: String? = null, val epubIdentifier: String? = null)
    private fun matches(book: Book, catalogID: String?, contentHash: String?, epubIdentifier: String?) =
        (book.catalogID != null && book.catalogID == catalogID) ||
        (book.contentHash != null && book.contentHash == contentHash) ||
        (book.epubIdentifier != null && book.epubIdentifier == epubIdentifier)

    // Standalone repository callers need only identities; never deserialize every book for a lookup.
    private fun findDuplicate(book: Book): Book? {
        for (folder in root.listFiles().orEmpty().filter { it.isDirectory && !it.name.startsWith('.') }) {
            try {
                val file = File(folder, "book.json")
                val identity = file.inputStream().use { BookCodec.json.decodeFromStream<Identity>(it) }
                if (identity.id == folder.name && identity.sourceFormat in setOf("pdf", "epub") &&
                    File(folder, "original.${identity.sourceFormat}").isFile &&
                    matches(book, identity.catalogID, identity.contentHash, identity.epubIdentifier)) {
                    return file.inputStream().use { BookCodec.validate(BookCodec.read(it)) }
                }
            } catch (_: Exception) { /* Match load(): leave unreadable books untouched. */ }
        }
        return null
    }
    fun directory(id: String): File {
        // Never resolve a path supplied by an imported document without validating it.
        require(Regex("[A-Fa-f0-9-]{36}").matches(id))
        return File(root, id)
    }
    fun sourceFile(book: Book) = File(directory(book.id), book.sourceFilename)
    fun pdfFile(id: String) = File(directory(id), "original.pdf")
    fun load(): LibraryResult {
        check(root.isDirectory || root.mkdirs()) { "Could not open the book library." }
        var unreadable = 0
        val books = root.listFiles().orEmpty().filter { it.isDirectory && !it.name.startsWith('.') }.mapNotNull { folder ->
            try {
                val stored = File(folder, "book.json").inputStream().use { BookCodec.read(it) }
                var book = BookCodec.validate(stored)
                require(book.id == folder.name && sourceFile(book).isFile)
                if (book.isEpub && book.contentHash == null) book = book.copy(contentHash = EpubImporter.sha256(sourceFile(book)))
                // Persist the one-time index upgrade atomically; never touch the original file.
                if (book.pipelineVersion != stored.pipelineVersion && !book.needsReprocessing) save(book)
                book
            } catch (_: Exception) { unreadable++; null }
        }
        return LibraryResult(books.sortedByDescending { it.importedAt }, unreadable)
    }
    fun save(book: Book) {
        val target = File(directory(book.id), "book.json")
        require(sourceFile(book).isFile) { "The original file is missing. Your existing files have been kept." }
        atomicWrite(target) { BookCodec.write(book, it) }
    }
    fun insert(source: Book, original: File, deduplicate: Boolean = false, existingBooks: List<Book>? = null): Book {
        var book = BookCodec.validate(source)
        if (deduplicate) {
            val duplicate = if (existingBooks == null) findDuplicate(book) else existingBooks.firstOrNull {
                matches(book, it.catalogID, it.contentHash, it.epubIdentifier)
            }
            duplicate?.let { return it }
        }
        if (directory(book.id).exists()) book = book.copy(id = newID(), importedAt = appleTimestamp())
        check(root.isDirectory || root.mkdirs())
        val staging = File(root, ".import-${newID()}")
        check(staging.mkdir())
        try {
            require(validSource(original, book)) { "The original file is invalid." }
            original.copyTo(File(staging, book.sourceFilename))
            atomicWrite(File(staging, "book.json")) { BookCodec.write(book, it) }
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
        // Stream the original source as base64 without another full-size byte copy.
        output.write("{\"format\":\"still-book\",\"version\":${if (book.isEpub) 2 else 1},\"book\":".toByteArray())
        BookCodec.write(book, output)
        output.write(",\"${if (book.isEpub) "sourceData" else "pdfData"}\":\"".toByteArray())
        val nonClosing = object : FilterOutputStream(output) { override fun close() { flush() } }
        Base64.getEncoder().wrap(nonClosing).use { encoded -> sourceFile(book).inputStream().use { it.copyTo(encoded) } }
        output.write("\"}".toByteArray())
        output.flush()
    }
    companion object {
        fun validSource(file: File, book: Book): Boolean = if (!book.isEpub) file.hasPdfHeader() else try {
            java.util.zip.ZipFile(file).use { zip ->
                val entry = zip.getEntry("mimetype") ?: return false
                entry.size == 20L && zip.getInputStream(entry).use { input ->
                    val mime = ByteArray(20)
                    java.io.DataInputStream(input).readFully(mime)
                    input.read() == -1 && mime.contentEquals("application/epub+zip".toByteArray())
                }
            }
        } catch (_: Exception) { false }

        fun atomicWrite(target: File, write: (OutputStream) -> Unit) {
            val temp = File(target.parentFile, ".${target.name}-${newID()}")
            try {
                temp.outputStream().use { stream -> write(stream); stream.fd.sync() }
                Files.move(temp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } finally { temp.delete() }
        }
    }
}
