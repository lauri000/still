import Foundation

/// Source selection is independent of the reader; both parsers produce prepared books.
enum BookImporter {
    static func extract(url: URL, progress: @Sendable (Int, Int) async -> Void) async throws -> ImportedBook {
        let scoped = url.startAccessingSecurityScopedResource()
        defer { if scoped { url.stopAccessingSecurityScopedResource() } }
        guard (try url.resourceValues(forKeys: [.fileSizeKey]).fileSize ?? 0) <= 250 * 1024 * 1024 else { throw EPUBImporter.ImportError(message: "This book is larger than 250 MB.") }
        let data = try Data(contentsOf: url)
        if url.pathExtension.lowercased() == "epub" || data.starts(with: [0x50, 0x4b]) {
            return try await EPUBImporter.extract(data: data, filename: url.lastPathComponent, progress: progress)
        }
        return try await PDFImporter.extract(data: data, filename: url.lastPathComponent, progress: progress)
    }
    static func reprocess(url: URL, book: Book, progress: @Sendable (Int, Int) async -> Void) async throws -> Book {
        if !book.isEpub { return try await PDFImporter.reprocess(url: url, book: book, progress: progress) }
        let converted = try await EPUBImporter.extract(data: Data(contentsOf: url), filename: book.originalFilename, progress: progress).book
        guard book.pages.map(\.sourceHref) == converted.pages.map(\.sourceHref) else {
            throw EPUBImporter.ImportError(message: "The EPUB sections changed. Import it as a new book to keep your current corrections and position.")
        }
        try Task.checkCancellation()
        return ReaderPipeline.reprocess(book, converted: converted)
    }
    static func restorePage(url: URL, book: Book, index: Int) async throws -> Book {
        if !book.isEpub {
            var restored = book
            restored.editText(on: index, to: try PDFImporter.extractedPage(url: url, index: index))
            return ReaderPipeline.update(from: book, to: restored)
        }
        let converted = try await EPUBImporter.extract(data: Data(contentsOf: url), filename: book.originalFilename).book
        guard let sourceIndex = converted.pages.firstIndex(where: { $0.sourceHref == book.pages[index].sourceHref }) else { throw RepositoryError.invalidBook }
        return ReaderPipeline.restoreSection(book, converted: converted, index: index, sourceIndex: sourceIndex)
    }
}
