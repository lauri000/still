import Foundation
import PDFKit

struct ImportedBook: Sendable {
    var book: Book
    var pdfData: Data
}

enum PDFImportError: LocalizedError {
    case invalidPDF, locked, tooLarge, noPages, copyingNotAllowed
    var errorDescription: String? {
        switch self {
        case .invalidPDF: "This file could not be opened as a PDF. Try exporting a new copy."
        case .locked: "This PDF is password protected. Unlock it and save a copy before importing."
        case .tooLarge: "This PDF is larger than 250 MB. Try splitting it into smaller files."
        case .noPages: "This PDF has no readable pages."
        case .copyingNotAllowed: "This PDF does not permit text extraction. Import a copy that allows copying text."
        }
    }
}

enum PDFImporter {
    static func extract(url: URL, vocabulary: Set<String> = PDFVocabulary.words, progress: @Sendable (Int, Int) async -> Void) async throws -> ImportedBook {
        let access = url.startAccessingSecurityScopedResource()
        defer { if access { url.stopAccessingSecurityScopedResource() } }
        let size = try url.resourceValues(forKeys: [.fileSizeKey]).fileSize ?? 0
        guard size <= 250 * 1_024 * 1_024 else { throw PDFImportError.tooLarge }
        let data = try Data(contentsOf: url)
        return try await extract(data: data, filename: url.lastPathComponent, vocabulary: vocabulary, progress: progress)
    }

    static func extract(data: Data, filename: String, prepareReader: Bool = true, preserving: Book? = nil, vocabulary: Set<String> = PDFVocabulary.words, progress: @Sendable (Int, Int) async -> Void) async throws -> ImportedBook {
        guard let document = PDFDocument(data: data) else { throw PDFImportError.invalidPDF }
        guard !document.isLocked else { throw PDFImportError.locked }
        guard document.allowsCopying else { throw PDFImportError.copyingNotAllowed }
        guard document.pageCount > 0 else { throw PDFImportError.noPages }
        guard preserving == nil || preserving?.pages.count == document.pageCount else { throw PDFImportError.invalidPDF }
        var pages: [BookPage] = []
        var inputs: [PDFTextRepair.Input] = []
        for index in 0..<document.pageCount {
            try Task.checkCancellation()
            guard let page = document.page(at: index) else { throw PDFImportError.noPages }
            let original = page.string ?? ""
            if let correction = preserving?.pages[index], correction.isEdited {
                inputs.append(PDFTextRepair.Input(text: correction.text, enabled: false))
            } else { inputs.append(PDFTextGeometry(page: page, raw: original).input()) }
            pages.append(BookPage(id: UUID(), number: index + 1, label: page.label ?? String(index + 1),
                                  extractedText: original, text: original, isReviewed: false, isIncluded: true))
            await progress(index + 1, document.pageCount)
        }
        let repaired = try PDFTextRepair.repair(inputs, englishWords: vocabulary, checkpoint: { try Task.checkCancellation() }) { index, offset, split in
            guard let page = document.page(at: index) else { return false }
            return PDFTextGeometry(page: page, raw: inputs[index].text).boundary(offset: offset, split: split)
        }
        try Task.checkCancellation()
        var chapters: [BookChapter] = []
        func collect(_ outline: PDFOutline, level: Int) {
            for index in 0..<outline.numberOfChildren {
                guard let child = outline.child(at: index) else { continue }
                let destination = child.destination ?? (child.action as? PDFActionGoTo)?.destination
                if let page = destination?.page {
                    let pageIndex = document.index(for: page)
                    if pageIndex != NSNotFound {
                        chapters.append(BookChapter(id: UUID(), title: child.label ?? "Untitled chapter", pageIndex: pageIndex, level: level, source: "PDF bookmark"))
                    }
                }
                collect(child, level: level + 1)
            }
        }
        if let outline = document.outlineRoot { collect(outline, level: 0) }
        let metadata = document.documentAttributes ?? [:]
        let title = (metadata[PDFDocumentAttribute.titleAttribute] as? String)?.trimmingCharacters(in: .whitespacesAndNewlines)
        let author = (metadata[PDFDocumentAttribute.authorAttribute] as? String) ?? ""
        let fallback = (filename as NSString).deletingPathExtension.replacingOccurrences(of: "_", with: " ")
        let book = Book(id: UUID(), title: (title?.isEmpty == false ? title! : fallback), author: author,
                        originalFilename: filename, importedAt: Date(), pages: pages,
                        chapters: chapters.sorted { $0.pageIndex < $1.pageIndex })
        let repairedBook = PDFTextRepair.apply(to: book, result: repaired, enabled: Array(repeating: true, count: pages.count))
        return ImportedBook(book: prepareReader ? ReaderPipeline.prepare(repairedBook) : repairedBook, pdfData: data)
    }

    static func reprocess(url: URL, book: Book, progress: @Sendable (Int, Int) async -> Void) async throws -> Book {
        let data = try Data(contentsOf: url)
        let converted = try await extract(data: data, filename: book.originalFilename, prepareReader: false, preserving: book, progress: progress)
        guard converted.book.pages.count == book.pages.count else { throw PDFImportError.invalidPDF }
        try Task.checkCancellation()
        return ReaderPipeline.reprocess(book, converted: converted.book)
    }

    static func extractedPage(url: URL, index: Int) throws -> String {
        guard let document = PDFDocument(url: url), let page = document.page(at: index) else { throw PDFImportError.invalidPDF }
        guard !document.isLocked else { throw PDFImportError.locked }
        guard document.allowsCopying else { throw PDFImportError.copyingNotAllowed }
        return page.string ?? ""
    }
}
