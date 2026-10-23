import SwiftUI
import UniformTypeIdentifiers

@MainActor
final class LibraryStore: ObservableObject {
    @Published var books: [Book] = []
    var isImporting: Bool { importTask != nil }
    @Published var importProgress = 0.0
    @Published var importMessage = "Opening book…"
    @Published var errorMessage: String?
    @Published var savedMessage = "Saved on this device"
    var undoReprocessID: UUID? { beforeReprocess?.id }
    @Published private var beforeReprocess: Book?
    let repository: BookRepository
    private var pendingSaves: [UUID: Task<Void, Never>] = [:]
    private var preparationBases: [UUID: Book] = [:]
    private var writeTask: Task<Void, Never>?
    private var writeRevision = 0
    @Published private var importTask: Task<Void, Never>?
    private var hasLoaded = false
    private let includeSample: Bool

    init(repository: BookRepository = BookRepository(), includeSample: Bool = false) {
        self.repository = repository
        self.includeSample = includeSample
    }

    func load() async {
        guard !hasLoaded else { return }
        hasLoaded = true
        do {
            let repository = repository
            let result = try await Task.detached { try repository.load() }.value
            books = result.books
            if result.unreadableCount > 0 {
                errorMessage = "\(result.unreadableCount) book(s) could not be opened. Their files have been kept."
            }
            let marker = repository.root.appendingPathComponent(".sample-created")
            if includeSample && books.isEmpty && result.unreadableCount == 0 && !FileManager.default.fileExists(atPath: marker.path) {
                var sample = try await Task.detached {
                    try await PDFImporter.extract(data: SampleBook.pdfData(), filename: "The art of attention.pdf") { _, _ in }
                }.value
                sample.book.isSample = true
                for index in sample.book.pages.indices {
                    // The introductory PDF has running furniture; the supplied reviewed text omits it.
                    sample.book.pages[index].text = (ProcessInfo.processInfo.arguments.contains("--with-headings") ? SampleBook.sections[index].0 + "\n\n" : "") + SampleBook.sections[index].1
                    sample.book.pages[index].isReviewed = true
                }
                sample.book = ReaderPipeline.prepare(sample.book)
                if ProcessInfo.processInfo.arguments.contains("--legacy-sample") { sample.book.pipelineVersion = nil }
                try repository.insert(sample.book, sourceData: sample.sourceData)
                books = [sample.book]
                try Data().write(to: marker)
            }
        } catch { errorMessage = error.localizedDescription }
    }

    func book(_ id: UUID) -> Book? { books.first { $0.id == id } }
    func isPreparing(_ id: UUID) -> Bool { preparationBases[id] != nil }

    func update(_ id: UUID, saveImmediately: Bool = false, _ change: (inout Book) -> Void) {
        guard let index = books.firstIndex(where: { $0.id == id }) else { return }
        var updated = books[index]
        change(&updated)
        guard updated != books[index] else { return }
        let previous = books[index]
        if preparationBases[id] == nil, !previous.needsReprocessing,
           previous.title != updated.title || previous.chapters != updated.chapters ||
            previous.pages.indices.contains(where: { previous.pages[$0].text != updated.pages[$0].text }) {
            preparationBases[id] = previous
        }
        if beforeReprocess?.id == id { beforeReprocess = nil }
        books[index] = updated
        pendingSaves[id]?.cancel()
        savedMessage = "Saving…"
        if saveImmediately { save(id) } else {
            pendingSaves[id] = Task { [weak self] in
                do { try await Task.sleep(for: .milliseconds(500)) } catch { return }
                self?.save(id)
            }
        }
    }

    private func save(_ id: UUID) {
        guard let snapshot = book(id) else { return }
        pendingSaves.removeValue(forKey: id)?.cancel()
        let base = preparationBases[id], repository = repository, previous = writeTask
        writeRevision += 1
        // Serialize writes so an older background preparation can never overwrite a newer save.
        writeTask = Task {
            await previous?.value
            do {
                let prepared = try await Task.detached {
                    let result = base.map { ReaderPipeline.update(from: $0, to: snapshot) } ?? snapshot
                    try repository.save(result)
                    return result
                }.value
                if let index = books.firstIndex(where: { $0.id == id }), books[index] == snapshot {
                    preparationBases.removeValue(forKey: id)
                    books[index] = prepared
                    savedMessage = "Saved on this device"
                }
            } catch { savedMessage = "Not saved"; errorMessage = "Your latest changes could not be saved: \(error.localizedDescription)" }
        }
    }

    func flush() {
        for id in Array(pendingSaves.keys) { save(id) }
    }

    func waitForSaves() async {
        while true {
            flush()
            let revision = writeRevision
            await writeTask?.value
            if revision == writeRevision && pendingSaves.isEmpty { return }
        }
    }

    func editPage(bookID: UUID, pageID: UUID, text: String) {
        update(bookID) { book in
            guard let index = book.pages.firstIndex(where: { $0.id == pageID }), book.pages[index].text != text else { return }
            book.editText(on: index, to: text)
        }
    }

    func importFile(_ url: URL, completion: @escaping (UUID) -> Void) {
        performImport(message: "Opening file…") {
            if ["json", "stillbook"].contains(url.pathExtension.lowercased()) {
                return try BookBackup.read(url: url)
            }
            return try await BookImporter.extract(url: url) { current, total in
                await self.reportProgress(current, total)
            }
        } completion: { imported in
            var book = imported.book
            if !["json", "stillbook"].contains(url.pathExtension.lowercased()), let existing = self.duplicate(of: book) { completion(existing.id); return }
            if self.books.contains(where: { $0.id == book.id }) { book.id = UUID(); book.importedAt = Date() }
            try self.repository.insert(book, sourceData: imported.sourceData)
            self.books.insert(book, at: 0)
            completion(book.id)
        }
    }

    private func duplicate(of book: Book) -> Book? {
        books.first { existing in
            (book.catalogID != nil && book.catalogID == existing.catalogID) ||
            (book.contentHash != nil && book.contentHash == existing.contentHash) ||
            (book.epubIdentifier != nil && book.epubIdentifier == existing.epubIdentifier)
        }
    }

    func download(_ entry: CatalogBook, provider: any CatalogProvider, completion: @escaping (UUID) -> Void) {
        if let existing = books.first(where: { $0.catalogID == entry.id }) { completion(existing.id); return }
        performImport(message: "Downloading EPUB…") {
            let data = try await provider.download(entry) { fraction in await self.downloadProgress(fraction) }
            var imported = try await EPUBImporter.extract(data: data, filename: "download.epub") { current, total in await self.reportProgress(current, total) }
            let original = imported.book
            imported.book.catalogID = entry.id
            imported.book.title = entry.title
            if !entry.authors.isEmpty { imported.book.author = entry.authors.joined(separator: ", ") }
            if !entry.languages.isEmpty { imported.book.languageCodes = entry.languages }
            imported.book.synopsis = entry.summary
            imported.book.sourceURL = entry.webURL?.absoluteString ?? entry.epubURL?.absoluteString
            if imported.book.coverData == nil, let cover = entry.coverURL { imported.book.coverData = try? await provider.client.get(cover, limit: 2 * 1024 * 1024) }
            try Task.checkCancellation()
            imported.book = ReaderPipeline.update(from: original, to: imported.book)
            return imported
        } completion: { imported in
            if let existing = self.duplicate(of: imported.book) { completion(existing.id); return }
            try self.repository.insert(imported.book, sourceData: imported.sourceData)
            self.books.insert(imported.book, at: 0)
            completion(imported.book.id)
        }
    }
    private func downloadProgress(_ fraction: Double?) {
        importProgress = fraction ?? -1
        importMessage = fraction.map { "Downloading EPUB… \(Int($0 * 100))%" } ?? "Downloading EPUB…"
    }

    func cancelImport() { importTask?.cancel() }
    func reprocess(_ id: UUID, completion: (() -> Void)? = nil) {
        guard !isImporting else { return }
        performImport(message: "Reprocessing original book…") {
            await self.waitForSaves()
            guard let original = await self.book(id) else { throw RepositoryError.invalidBook }
            let url = self.repository.sourceURL(for: original)
            let updated = try await BookImporter.reprocess(url: url, book: original) { current, total in await self.reportProgress(current, total) }
            return (original, updated)
        } completion: { original, updated in
            guard let index = self.books.firstIndex(where: { $0.id == id }), self.books[index] == original else {
                self.errorMessage = "The book changed while it was being reprocessed. Please try again."
                return
            }
            try self.repository.save(updated)
            self.books[index] = updated; self.beforeReprocess = original
            self.savedMessage = "Book reprocessed"
            completion?()
        }
    }

    func restoreExtractedText(_ id: UUID, pageIndex: Int) {
        guard book(id) != nil else { return }
        performImport(message: "Reading original text…") {
            await self.waitForSaves()
            guard let original = await self.book(id) else { throw RepositoryError.invalidBook }
            let url = self.repository.sourceURL(for: original)
            return (original, try await BookImporter.restorePage(url: url, book: original, index: pageIndex))
        } completion: { original, restored in
            guard self.book(id) == original else { return }
            try self.repository.save(restored)
            self.books[self.books.firstIndex(where: { $0.id == id })!] = restored
            self.beforeReprocess = nil
            self.savedMessage = "Original text restored"
        }
    }

    private func performImport<Value: Sendable>(message: String,
        operation: @escaping @Sendable () async throws -> Value,
        completion: @escaping (Value) throws -> Void) {
        guard !isImporting else { return }
        importProgress = 0
        importMessage = message
        importTask = Task {
            let worker = Task.detached(operation: operation)
            do {
                let result = try await withTaskCancellationHandler { try await worker.value } onCancel: { worker.cancel() }
                try Task.checkCancellation()
                importTask = nil
                try completion(result)
            } catch {
                importTask = nil
                if !(error is CancellationError) { errorMessage = error.localizedDescription }
            }
        }
    }
    func undoReprocess(_ id: UUID) {
        guard let previous = beforeReprocess, previous.id == id,
              let index = books.firstIndex(where: { $0.id == id }) else { return }
        do {
            try repository.save(previous)
            books[index] = previous; beforeReprocess = nil
            savedMessage = "Reprocessing undone"
        } catch { errorMessage = error.localizedDescription }
    }
    private func reportProgress(_ current: Int, _ total: Int) {
        importProgress = 0.8 * Double(current) / Double(total)
        importMessage = current == total ? "Preparing reader…" : "Reading content \(current) of \(total)…"
    }

    func delete(_ id: UUID) {
        pendingSaves[id]?.cancel()
        pendingSaves.removeValue(forKey: id)
        preparationBases.removeValue(forKey: id)
        let removed = book(id)
        books.removeAll { $0.id == id }
        let previous = writeTask, repository = repository
        writeTask = Task {
            await previous?.value
            do { try await Task.detached { try repository.remove(id) }.value }
            catch {
                if let removed { books.append(removed); books.sort { $0.importedAt > $1.importedAt } }
                errorMessage = error.localizedDescription
            }
        }
    }
}

struct BookBackup: Codable, FileDocument {
    static var readableContentTypes: [UTType] { [.json] }
    var format = "still-book"
    var version = 1
    var book: Book
    var pdfData: Data
    var sourceData: Data? = nil
    var originalData: Data { book.isEpub ? (sourceData ?? Data()) : pdfData }

    init(book: Book, pdfData: Data) {
        self.book = book; self.pdfData = book.isEpub ? Data() : pdfData
        self.sourceData = book.isEpub ? pdfData : nil; self.version = book.isEpub ? 2 : 1
    }
    enum CodingKeys: String, CodingKey { case format, version, book, pdfData, sourceData }
    init(from decoder: Decoder) throws {
        let values = try decoder.container(keyedBy: CodingKeys.self)
        format = try values.decode(String.self, forKey: .format); version = try values.decode(Int.self, forKey: .version)
        book = try values.decode(Book.self, forKey: .book)
        pdfData = try values.decodeIfPresent(Data.self, forKey: .pdfData) ?? Data()
        sourceData = try values.decodeIfPresent(Data.self, forKey: .sourceData)
    }
    func encode(to encoder: Encoder) throws {
        var values = encoder.container(keyedBy: CodingKeys.self)
        try values.encode(format, forKey: .format); try values.encode(version, forKey: .version); try values.encode(book, forKey: .book)
        if book.isEpub { try values.encode(sourceData, forKey: .sourceData) } else { try values.encode(pdfData, forKey: .pdfData) }
    }
    init(configuration: ReadConfiguration) throws {
        guard let data = configuration.file.regularFileContents else { throw RepositoryError.invalidBook }
        self = try JSONDecoder().decode(Self.self, from: data)
        try validate()
    }
    func fileWrapper(configuration: WriteConfiguration) throws -> FileWrapper {
        FileWrapper(regularFileWithContents: try JSONEncoder().encode(self))
    }
    func validate() throws {
        guard format == "still-book", version == (book.isEpub ? 2 : 1), book.hasValidStructure, originalData.count <= 250 * 1024 * 1024,
              (book.isEpub ? version == 2 && EPUBImporter.isEPUB(originalData) : pdfData.starts(with: Array("%PDF-".utf8))) else { throw RepositoryError.invalidBook }
    }
    static func read(url: URL) throws -> ImportedBook {
        let scoped = url.startAccessingSecurityScopedResource()
        defer { if scoped { url.stopAccessingSecurityScopedResource() } }
        guard (try url.resourceValues(forKeys: [.fileSizeKey]).fileSize ?? 0) <= 400 * 1024 * 1024 else { throw RepositoryError.invalidBook }
        let backup = try JSONDecoder().decode(Self.self, from: Data(contentsOf: url))
        try backup.validate()
        return ImportedBook(book: ReaderPipeline.loaded(backup.book), sourceData: backup.originalData)
    }
}
