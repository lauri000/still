package com.stillreader.app

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.stillreader.app.core.*
import com.stillreader.app.data.*
import com.stillreader.app.catalog.*
import java.util.Base64
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.File

data class LibraryState(
    val books: List<Book> = emptyList(),
    val loading: Boolean = true,
    val selectedID: String? = null,
    val reader: ReadingSession? = null,
    val importing: ImportProgress? = null,
    val error: String? = null,
    val saving: Boolean = false,
    val opening: Boolean = false,
    val preparationID: String? = null,
    val undoReprocessID: String? = null,
)

class LibraryViewModel @JvmOverloads constructor(application: Application, private val saved: SavedStateHandle, root: File? = null) : AndroidViewModel(application) {
    val repository = BookRepository(root ?: File(application.filesDir, "Still"))
    val importer = BookImporter(application)
    private val mutable = MutableStateFlow(LibraryState(selectedID = saved["selectedID"]))
    val state = mutable.asStateFlow()
    private val preferences = application.getSharedPreferences("reading", 0)
    var focusLetter: Boolean
        get() = preferences.getBoolean("focusLetter", false)
        set(value) { preferences.edit().putBoolean("focusLetter", value).apply() }
    var activeWordSizePercent: Int
        get() = preferences.getInt("activeWordSizePercent", 100).coerceIn(100, 300)
        set(value) { preferences.edit().putInt("activeWordSizePercent", value.coerceIn(100, 300)).apply() }
    var passageTextSize: Int
        get() = preferences.getInt("passageTextSize", 22).coerceIn(16, 32)
        set(value) { preferences.edit().putInt("passageTextSize", value.coerceIn(16, 32)).apply() }
    var animatedScrolling: Boolean
        get() = preferences.getBoolean("animatedScrolling", false)
        set(value) { preferences.edit().putBoolean("animatedScrolling", value).apply() }
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val operations = Channel<suspend () -> Unit>(Channel.UNLIMITED)
    private var importJob: Job? = null
    private var openJob: Job? = null
    private var openRequest = 0
    private var pendingWrites = 0
    private val pendingEdits = mutableMapOf<String, Job>()
    private val preparationBases = mutableMapOf<String, Book>()
    private var beforeReprocess: Book? = null

    init {
        ioScope.launch { for (operation in operations) operation() }.invokeOnCompletion { ioScope.cancel() }
        viewModelScope.launch {
            try {
                val loaded = withContext(Dispatchers.IO) { repository.load() }
                mutable.update { it.copy(books = loaded.books, loading = false,
                    selectedID = it.selectedID?.takeIf { id -> loaded.books.any { book -> book.id == id } },
                    error = if (loaded.unreadableCount > 0) "${loaded.unreadableCount} book(s) could not be opened. Their files have been kept." else null) }
                if (saved.get<Boolean>("readerOpen") == true) selectedBook()?.let { openReader() }
            } catch (error: Exception) { mutable.update { it.copy(loading = false, error = error.message) } }
        }
    }

    fun selectedBook(): Book? = state.value.books.firstOrNull { it.id == state.value.selectedID }
    fun select(id: String?) {
        closeReader()
        selectedBook()?.readerCache?.clear()
        saved["selectedID"] = id
        mutable.update { it.copy(selectedID = id, preparationID = null) }
    }
    fun readBook(id: String) { select(id); openReader() }
    fun dismissPreparation() { select(null) }
    fun dismissError() { mutable.update { it.copy(error = null) } }
    private fun report(error: Throwable) {
        val message = if (error is OutOfMemoryError) "This document needs more memory than is available. Try a smaller book or backup."
            else error.message?.takeIf { it.isNotBlank() } ?: "The file could not be opened. It may be damaged or password protected."
        mutable.update { it.copy(error = message) }
    }

    fun updateBook(id: String, debounce: Boolean = false, rebuildReader: Boolean = true, change: (Book) -> Book) {
        val original = state.value.books.firstOrNull { it.id == id } ?: return
        val changed = change(original)
        val updated = changed
        if (updated == original) return
        if (!rebuildReader) preparationBases.remove(id)
        else if (id !in preparationBases && !original.needsReprocessing &&
            (original.title != updated.title || original.chapters != updated.chapters ||
                original.pages.indices.any { original.pages[it].text != updated.pages[it].text })) {
            preparationBases[id] = original
        }
        if (beforeReprocess?.id == id) beforeReprocess = null
        mutable.update { it.copy(books = it.books.map { book -> if (book.id == id) updated else book }, saving = true,
            undoReprocessID = it.undoReprocessID?.takeUnless { undoID -> undoID == id }) }
        pendingEdits.remove(id)?.cancel()
        if (debounce) {
            pendingEdits[id] = viewModelScope.launch {
                delay(500)
                pendingEdits.remove(id)
                enqueueSave(updated)
            }
        } else enqueueSave(updated)
    }

    private fun enqueueSave(updated: Book) {
        val base = preparationBases[updated.id]
        pendingWrites++
        operations.trySend {
            try {
                val prepared = base?.let { ReaderPipeline.update(it, updated) } ?: updated
                repository.save(prepared)
                withContext(Dispatchers.Main) {
                    if (state.value.books.firstOrNull { it.id == updated.id } == updated) {
                        preparationBases.remove(updated.id)
                        mutable.update { state -> state.copy(books = state.books.map { if (it.id == updated.id) prepared else it }) }
                    }
                }
            } catch (error: Exception) { report(error) }
            withContext(Dispatchers.Main) {
                pendingWrites--
                mutable.update { it.copy(saving = pendingWrites > 0 || pendingEdits.isNotEmpty()) }
            }
        }
    }

    suspend fun flush() {
        do {
            flushEdits()
            repositoryOperation { }
        } while (pendingEdits.isNotEmpty() || pendingWrites > 0)
    }

    /** Await the same serial queue used by autosaves, without owning the caller's cancellation policy. */
    private suspend fun <T> repositoryOperation(operation: () -> T): T {
        val result = CompletableDeferred<T>()
        operations.send {
            try { result.complete(operation()) }
            catch (error: Throwable) { result.completeExceptionally(error) }
        }
        return result.await()
    }

    /** Import and repair share one job, progress indicator, cancellation path, and error policy. */
    private fun prepareBook(message: String, prepare: suspend CoroutineScope.() -> Unit) {
        if (state.value.importing != null) return
        mutable.update { it.copy(importing = ImportProgress(message, 0f)) }
        importJob = viewModelScope.launch {
            try { prepare() }
            catch (_: CancellationException) { }
            catch (error: Exception) { report(error) }
            catch (error: OutOfMemoryError) { report(error) }
            finally { mutable.update { it.copy(importing = null) } }
        }
    }

    fun importFile(uri: Uri) = beginImport { progress -> importer.import(uri, progress) }

    fun download(entry: CatalogBook, provider: CatalogProvider) {
        state.value.books.firstOrNull { it.catalogID == entry.id }?.let { readBook(it.id); return }
        beginImport { progress ->
            val file = File.createTempFile("still-download-", ".epub", getApplication<Application>().cacheDir)
            try {
                provider.download(entry, file) { fraction ->
                    progress(ImportProgress(fraction?.let { "Downloading EPUB… ${(it * 100).toInt()}%" } ?: "Downloading EPUB…", fraction ?: -1f))
                }
                progress(ImportProgress("Preparing book…", 0f))
                val result = importer.importEpub(file, progress)
                val cover = if (result.book.coverData == null && entry.coverURL != null) {
                    try { Base64.getEncoder().encodeToString(provider.client.get(entry.coverURL, 2 * 1024 * 1024)) }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { null }
                } else result.book.coverData
                val metadata = result.book.copy(catalogID = entry.id, title = entry.title,
                    author = entry.authors.takeIf { it.isNotEmpty() }?.joinToString(", ") ?: result.book.author,
                    languageCodes = entry.languages.takeIf { it.isNotEmpty() } ?: result.book.languageCodes,
                    synopsis = entry.summary, sourceURL = entry.webURL ?: entry.epubURL, coverData = cover)
                result.copy(book = withContext(Dispatchers.Default) { ReaderPipeline.update(result.book, metadata) })
            } catch (error: Throwable) { file.delete(); throw error }
        }
    }

    private fun beginImport(load: suspend ((ImportProgress) -> Unit) -> ImportedBook) = prepareBook("Opening book…") {
        closeReader()
        // Old reader projections are disposable; persisted text, indexes and progress remain intact.
        state.value.books.forEach { it.readerCache.clear() }
        var imported: ImportedBook? = null
        try {
            imported = load { progress -> mutable.update { it.copy(importing = progress) } }
            ensureActive()
            // Once the commit begins, complete it and publish the saved book even if Cancel was tapped.
            withContext(NonCancellable) {
                val source = imported
                val book = repositoryOperation { repository.insert(source.book, source.source, source.deduplicate, state.value.books) }
                saved["selectedID"] = book.id
                mutable.update { it.copy(books = listOf(book) + it.books.filterNot { existing -> existing.id == book.id }, selectedID = book.id) }
                openReader()
            }
        } finally { imported?.source?.delete() }
    }
    fun cancelImport() { importJob?.cancel() }

    fun reprocess(bookID: String, openAfter: Boolean = false) = prepareBook("Reprocessing original book…") {
        closeReader()
        mutable.update { it.copy(preparationID = null) }
        flush()
        val original = state.value.books.firstOrNull { it.id == bookID } ?: return@prepareBook
        val updated = importer.reprocess(repository.sourceFile(original), original) { progress -> mutable.update { it.copy(importing = progress) } }
        ensureActive()
        check(state.value.books.firstOrNull { it.id == bookID } == original) { "The book changed while it was being reprocessed. Please try again." }
        withContext(NonCancellable) {
            repositoryOperation { repository.save(updated) }
            beforeReprocess = original
            mutable.update { it.copy(books = it.books.map { book -> if (book.id == bookID) updated else book }, undoReprocessID = bookID) }
            if (openAfter && state.value.selectedID == bookID) openReader()
        }
    }

    fun restoreExtractedText(bookID: String, index: Int) = prepareBook("Reading original text…") {
        flush()
        val original = state.value.books.firstOrNull { it.id == bookID } ?: return@prepareBook
        val restored = importer.restorePage(repository.sourceFile(original), original, index)
        ensureActive()
        check(state.value.books.firstOrNull { it.id == bookID } == original) { "The book changed. Please try again." }
        updateBook(bookID, rebuildReader = false) { restored }
    }

    fun undoReprocess(bookID: String) {
        val previous = beforeReprocess?.takeIf { it.id == bookID } ?: return
        beforeReprocess = null
        mutable.update { it.copy(undoReprocessID = null) }
        updateBook(bookID, rebuildReader = false) { previous }
    }

    fun openReader(startPage: Int? = null, seekPage: Int? = null, startOffset: Int = 0, seekOffset: Int? = null) {
        openJob?.cancel()
        val request = ++openRequest
        val id = selectedBook()?.id ?: return
        state.value.reader?.pause()
        mutable.update { it.copy(opening = true) }
        openJob = viewModelScope.launch {
            try {
                flush()
                val book = selectedBook()?.takeIf { it.id == id } ?: return@launch
                if (book.needsReprocessing) {
                    mutable.update { it.copy(preparationID = id) }
                    return@launch
                }
                if (startPage != null) {
                    val index = startPage.coerceIn(book.pages.indices)
                    updateBook(book.id) { it.withReadingStart(ReadingPosition(it.pages[index].id, startOffset)) }
                }
                val sessionBook = selectedBook() ?: return@launch
                withContext(Dispatchers.Default) { sessionBook.readerCache.get(sessionBook) }
                ensureActive()
                if (state.value.selectedID != sessionBook.id || selectedBook() != sessionBook) return@launch
                val session = ReadingSession(sessionBook, viewModelScope) { position ->
                    updateBook(sessionBook.id) { it.copy(position = position) }
                }
                session.wordsPerMinute = preferences.getInt("wpm", 250).toDouble()
                session.naturalPauses = preferences.getBoolean("naturalPauses", true)
                session.allowUnreviewed = !preferences.getBoolean("strictReview", false)
                seekPage?.let { if (seekOffset != null) session.seekPosition(ReadingPosition(sessionBook.pages[it].id, seekOffset)) else session.seekPage(it) }
                mutable.update { it.copy(reader = session, opening = false) }
                saved["readerOpen"] = true
                if (session.tokens.isEmpty()) session.play()
            } catch (_: CancellationException) { }
            catch (error: Exception) { report(error) }
            finally { if (request == openRequest) mutable.update { it.copy(opening = false) } }
        }
    }
    fun closeReader() {
        openRequest++
        openJob?.cancel(); openJob = null
        state.value.reader?.let { reader -> reader.pause(); reader.book.readerCache.clear() }
        mutable.update { it.copy(reader = null, opening = false) }
        saved["readerOpen"] = false
    }

    private fun flushEdits() {
        val ids = pendingEdits.keys.toList()
        pendingEdits.values.forEach { it.cancel() }
        pendingEdits.clear()
        ids.forEach { id -> state.value.books.firstOrNull { it.id == id }?.let(::enqueueSave) }
    }
    fun pause() { state.value.reader?.pause(); flushEdits() }
    fun speed(value: Double) {
        state.value.reader?.wordsPerMinute = value
        preferences.edit().putInt("wpm", value.toInt()).apply()
    }
    fun options(natural: Boolean, strict: Boolean) {
        state.value.reader?.let { it.naturalPauses = natural; it.allowUnreviewed = !strict }
        preferences.edit().putBoolean("naturalPauses", natural).putBoolean("strictReview", strict).apply()
    }
    fun export(uri: Uri, bookID: String) {
        flushEdits()
        val snapshot = state.value.books.firstOrNull { it.id == bookID } ?: return
        val base = preparationBases[bookID]
        operations.trySend {
            try {
                val book = base?.let { ReaderPipeline.update(it, snapshot) } ?: snapshot
                getApplication<Application>().contentResolver.openOutputStream(uri, "wt")?.use { repository.export(book, it) }
                    ?: error("The backup could not be written to this location.")
            } catch (error: Exception) { report(error) }
        }
    }
    fun delete(bookID: String) {
        flushEdits()
        if (state.value.selectedID == bookID) select(null)
        operations.trySend {
            try {
                repository.delete(bookID)
                mutable.update { it.copy(books = it.books.filterNot { book -> book.id == bookID }) }
            } catch (error: Exception) { report(error) }
        }
    }
    override fun onCleared() {
        pause()
        // Drain queued atomic saves even when the Activity's ViewModel is being disposed.
        operations.close()
    }
}
