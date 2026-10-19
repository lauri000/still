import Foundation

struct BookRepository: Sendable {
    let root: URL

    init(root: URL? = nil) {
        self.root = root ?? FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0].appendingPathComponent("Still", isDirectory: true)
    }

    func directory(for id: UUID) -> URL { root.appendingPathComponent(id.uuidString, isDirectory: true) }
    func pdfURL(for id: UUID) -> URL { directory(for: id).appendingPathComponent("original.pdf") }

    func load() throws -> (books: [Book], unreadableCount: Int) {
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        let folders = try FileManager.default.contentsOfDirectory(at: root, includingPropertiesForKeys: [.isDirectoryKey], options: [.skipsHiddenFiles])
        var books: [Book] = []
        var unreadable = 0
        for folder in folders where UUID(uuidString: folder.lastPathComponent) != nil {
            do {
                let book = try JSONDecoder().decode(Book.self, from: Data(contentsOf: folder.appendingPathComponent("book.json")))
                guard book.hasValidStructure,
                      book.id.uuidString == folder.lastPathComponent,
                      FileManager.default.fileExists(atPath: pdfURL(for: book.id).path)
                else { throw RepositoryError.invalidBook }
                books.append(book)
            } catch { unreadable += 1 }
        }
        return (books.sorted { $0.importedAt > $1.importedAt }, unreadable)
    }

    func save(_ book: Book) throws {
        try FileManager.default.createDirectory(at: directory(for: book.id), withIntermediateDirectories: true)
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.prettyPrinted, .sortedKeys]
        try encoder.encode(book).write(to: directory(for: book.id).appendingPathComponent("book.json"), options: .atomic)
    }

    func insert(_ book: Book, pdfData: Data) throws {
        let folder = directory(for: book.id)
        guard !FileManager.default.fileExists(atPath: folder.path) else { throw RepositoryError.alreadyExists }
        try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        do {
            try pdfData.write(to: pdfURL(for: book.id), options: .atomic)
            try save(book)
        } catch {
            try? FileManager.default.removeItem(at: folder)
            throw error
        }
    }

    func remove(_ id: UUID) throws { try FileManager.default.removeItem(at: directory(for: id)) }
}

enum RepositoryError: LocalizedError {
    case invalidBook, alreadyExists
    var errorDescription: String? { "The book data could not be saved or opened. The original files have been kept." }
}
