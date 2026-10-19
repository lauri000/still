import SwiftUI
import UniformTypeIdentifiers

@MainActor
final class LibraryStore: ObservableObject {
    @Published var books: [Book] = []
    var isImporting: Bool { importTask != nil }
    @Published var importProgress = 0.0
    @Published var importMessage = "Opening PDF…"
    @Published var errorMessage: String?
    @Published var savedMessage = "Saved on this device"
    var undoImprovementID: UUID? { beforeImprovement?.id }
    @Published private var beforeImprovement: Book?
    let repository: BookRepository
    private var pendingSaves: [UUID: Task<Void, Never>] = [:]
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
            let result = try repository.load()
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
                try repository.insert(sample.book, pdfData: sample.pdfData)
                books = [sample.book]
                try Data().write(to: marker)
            }
        } catch { errorMessage = error.localizedDescription }
    }

    func book(_ id: UUID) -> Book? { books.first { $0.id == id } }

    func update(_ id: UUID, saveImmediately: Bool = false, _ change: (inout Book) -> Void) {
        guard let index = books.firstIndex(where: { $0.id == id }) else { return }
        var updated = books[index]
        change(&updated)
        guard updated != books[index] else { return }
        if beforeImprovement?.id == id { beforeImprovement = nil }
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
        guard let book = book(id) else { return }
        do { try repository.save(book); savedMessage = "Saved on this device" }
        catch { savedMessage = "Not saved"; errorMessage = "Your latest changes could not be saved: \(error.localizedDescription)" }
    }

    func flush() {
        for (id, task) in pendingSaves { task.cancel(); save(id) }
        pendingSaves.removeAll()
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
            return try await PDFImporter.extract(url: url) { current, total in
                await self.reportProgress(current, total)
            }
        } completion: { imported in
            var book = imported.book
            if self.books.contains(where: { $0.id == book.id }) { book.id = UUID(); book.importedAt = Date() }
            try self.repository.insert(book, pdfData: imported.pdfData)
            self.books.insert(book, at: 0)
            completion(book.id)
        }
    }

    func cancelImport() { importTask?.cancel() }
    func improveText(_ id: UUID) {
        guard !isImporting, let original = book(id) else { return }
        flush()
        let url = repository.pdfURL(for: id)
        performImport(message: "Preparing text improvements…") {
            try await PDFImporter.improve(url: url, book: original) { current, total in
                await self.reportProgress(current, total)
            }
        } completion: { updated in
            guard let index = self.books.firstIndex(where: { $0.id == id }) else { return }
            guard self.books[index] == original else {
                self.errorMessage = "The book changed while its text was being improved. Please try again."
                return
            }
            // Save atomically before publishing the new text and its mapped positions.
            try self.repository.save(updated)
            self.books[index] = updated
            self.beforeImprovement = original
            self.savedMessage = "Text improved"
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
    func undoTextImprovement(_ id: UUID) {
        guard let previous = beforeImprovement, previous.id == id,
              let index = books.firstIndex(where: { $0.id == id }) else { return }
        do {
            try repository.save(previous)
            books[index] = previous; beforeImprovement = nil
            savedMessage = "Text improvement undone"
        } catch { errorMessage = error.localizedDescription }
    }
    private func reportProgress(_ current: Int, _ total: Int) {
        importProgress = 0.8 * Double(current) / Double(total)
        importMessage = current == total ? "Improving word spacing and line breaks…" : "Extracting page \(current) of \(total)…"
    }

    func delete(_ id: UUID) {
        pendingSaves[id]?.cancel()
        pendingSaves.removeValue(forKey: id)
        do { try repository.remove(id); books.removeAll { $0.id == id } }
        catch { errorMessage = error.localizedDescription }
    }
}

struct BookBackup: Codable, FileDocument {
    static var readableContentTypes: [UTType] { [.json] }
    var format = "still-book"
    var version = 1
    var book: Book
    var pdfData: Data

    init(book: Book, pdfData: Data) { self.book = book; self.pdfData = pdfData }
    init(configuration: ReadConfiguration) throws {
        guard let data = configuration.file.regularFileContents else { throw RepositoryError.invalidBook }
        self = try JSONDecoder().decode(Self.self, from: data)
        try validate()
    }
    func fileWrapper(configuration: WriteConfiguration) throws -> FileWrapper {
        FileWrapper(regularFileWithContents: try JSONEncoder().encode(self))
    }
    func validate() throws {
        guard format == "still-book", version == 1, book.hasValidStructure,
              pdfData.starts(with: Array("%PDF-".utf8)) else { throw RepositoryError.invalidBook }
    }
    static func read(url: URL) throws -> ImportedBook {
        let scoped = url.startAccessingSecurityScopedResource()
        defer { if scoped { url.stopAccessingSecurityScopedResource() } }
        let backup = try JSONDecoder().decode(Self.self, from: Data(contentsOf: url))
        try backup.validate()
        return ImportedBook(book: backup.book, pdfData: backup.pdfData)
    }
}
