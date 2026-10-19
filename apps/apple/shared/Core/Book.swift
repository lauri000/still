import Foundation

struct Book: Codable, Identifiable, Equatable, Sendable {
    var schemaVersion = 1
    var id: UUID
    var title: String
    var author: String
    var originalFilename: String
    var importedAt: Date
    var pages: [BookPage]
    var chapters: [BookChapter]
    var position: ReadingPosition?
    var isSample = false
    // Optional so libraries and backups from the first version still decode.
    var readingStart: ReadingPosition? = nil

    var readingStartPageIndex: Int { readingStart.flatMap { start in pages.firstIndex { $0.id == start.pageID } } ?? 0 }
    func includesPage(at index: Int) -> Bool { pages.indices.contains(index) && index >= readingStartPageIndex && pages[index].isIncluded }
    var includedPages: [BookPage] { pages.enumerated().filter { includesPage(at: $0.offset) }.map(\.element) }
    var wordCount: Int { pages.indices.reduce(0) { $0 + readingWords(on: $1).count } }
    var canRead: Bool { pages.indices.contains { !readingWords(on: $0).isEmpty } }
    var emptyReadingPageCount: Int { includedPages.filter(\.hasNoText).count }

    func readingWords(on index: Int) -> [TextWord] {
        guard includesPage(at: index) else { return [] }
        let words = Tokenizer.words(in: pages[index].text)
        guard let start = readingStart, start.pageID == pages[index].id else { return words }
        return words.filter { $0.offset + $0.length > start.utf16Offset }
    }

    mutating func setReadingStart(_ start: ReadingPosition?) {
        if let start {
            guard let index = pages.firstIndex(where: { $0.id == start.pageID }), start.utf16Offset >= 0 else { return }
            pages[index].isIncluded = true
        }
        readingStart = start
        position = start
    }

    mutating func editText(on index: Int, to text: String) {
        guard pages.indices.contains(index), pages[index].text != text else { return }
        if var start = readingStart, start.pageID == pages[index].id {
            start.utf16Offset = Tokenizer.relocatedOffset(start.utf16Offset, from: pages[index].text, to: text)
            readingStart = start
        }
        pages[index].text = text
        pages[index].isReviewed = false
        position?.finished = false
    }

    var hasValidStructure: Bool {
        schemaVersion == 1 && !pages.isEmpty && Set(pages.map(\.id)).count == pages.count &&
            chapters.allSatisfy { pages.indices.contains($0.pageIndex) }
    }
}

struct BookPage: Codable, Identifiable, Equatable, Sendable {
    var id: UUID
    var number: Int
    var label: String
    let extractedText: String
    var text: String
    var isReviewed: Bool
    var isIncluded: Bool
    var processedText: String? = nil
    var textProcessingVersion: Int? = nil

    var hasNoText: Bool { text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty }
    var isEdited: Bool { text != (processedText ?? extractedText) }
    var warnings: [String] {
        var result: [String] = []
        if extractedText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            result.append("No selectable text found. This may be a scanned or blank page. Enter its text or exclude the page.")
        }
        if text.contains("\u{FFFD}") { result.append("Some characters could not be decoded. Compare them with the PDF.") }
        if text.range(of: "[\\p{L}]-[\\t ]*\\n[\\p{L}]", options: .regularExpression) != nil {
            result.append("Words may be split across lines. Check the hyphens against the original.")
        }
        return result
    }
}

struct BookChapter: Codable, Identifiable, Equatable, Sendable {
    var id: UUID
    var title: String
    var pageIndex: Int
    var level: Int
    var source: String
}

struct ReadingPosition: Codable, Equatable, Sendable {
    var pageID: UUID
    var utf16Offset: Int
    var finished: Bool = false
}

struct TextWord: Equatable, Sendable {
    var text: String
    var offset: Int
    var length: Int
    var endsParagraph: Bool
}

struct ReadingToken: Identifiable, Equatable, Sendable {
    var id: Int
    var pageID: UUID
    var pageIndex: Int
    var word: TextWord
    var position: ReadingPosition { ReadingPosition(pageID: pageID, utf16Offset: word.offset) }
}

enum Tokenizer {
    // Preserve punctuation and exact UTF-16 source offsets. Never rewrite the reviewed text.
    static func words(in text: String) -> [TextWord] {
        let source = text as NSString
        let matches = try! NSRegularExpression(pattern: "\\S+").matches(in: text, range: NSRange(location: 0, length: source.length))
        return matches.enumerated().map { index, match in
            let end = NSMaxRange(match.range)
            let next = index + 1 < matches.count ? matches[index + 1].range.location : source.length
            let gap = source.substring(with: NSRange(location: end, length: next - end))
            return TextWord(text: source.substring(with: match.range), offset: match.range.location,
                            length: match.range.length, endsParagraph: gap.contains("\n\n") || gap.contains("\r\n\r\n"))
        }
    }

    static func tokens(for book: Book) -> [ReadingToken] {
        var result: [ReadingToken] = []
        for (index, page) in book.pages.enumerated() {
            for word in book.readingWords(on: index) {
                result.append(ReadingToken(id: result.count, pageID: page.id, pageIndex: index, word: word))
            }
        }
        return result
    }

    // Move the start marker with a text edit, including edits before the chosen word.
    static func relocatedOffset(_ offset: Int, from old: String, to new: String) -> Int {
        let before = Array(old.utf16), after = Array(new.utf16)
        var prefix = 0, suffix = 0
        while prefix < min(before.count, after.count), before[prefix] == after[prefix] { prefix += 1 }
        while suffix < min(before.count, after.count) - prefix,
              before[before.count - suffix - 1] == after[after.count - suffix - 1] { suffix += 1 }
        let mapped: Int
        if offset < prefix { mapped = offset }
        else if offset >= before.count - suffix { mapped = offset + after.count - before.count }
        else { mapped = prefix }
        return min(after.count, max(0, mapped))
    }

    static func index(for position: ReadingPosition?, in tokens: [ReadingToken]) -> Int {
        guard let position, !tokens.isEmpty else { return 0 }
        let pageTokens = tokens.filter { $0.pageID == position.pageID }
        return pageTokens.last(where: { $0.word.offset <= position.utf16Offset })?.id ?? pageTokens.first?.id ?? 0
    }

    static func duration(for word: TextWord, wordsPerMinute: Double, naturalPauses: Bool) -> Double {
        let base = 60 / min(900, max(60, wordsPerMinute))
        guard naturalPauses else { return base }
        let stripped = word.text.trimmingCharacters(in: CharacterSet(charactersIn: "\"'”’)]}"))
        let punctuation: Double = stripped.last.map { ".!?…".contains($0) ? 1.9 : (",;:—".contains($0) ? 1.35 : 1) } ?? 1
        let length: Double = word.text.count > 12 ? 1.25 : 1
        return base * max(punctuation, length, word.endsParagraph ? 2.1 : 1)
    }

    static func blockingPage(in book: Book, from startPage: Int, through endPage: Int) -> Int? {
        guard !book.pages.isEmpty, startPage <= endPage, endPage >= 0, startPage < book.pages.count else { return nil }
        return (max(0, startPage)...min(endPage, book.pages.count - 1)).first {
            let page = book.pages[$0]
            return book.includesPage(at: $0) && (!page.isReviewed || page.hasNoText)
        }
    }
}
