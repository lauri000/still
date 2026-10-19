import Foundation

struct HeadingMatch: Codable, Equatable, Sendable {
    let title: String
    let pageIndex: Int
    let level: Int
    let start: Int
    let end: Int
}

struct ReaderDocument: Sendable {
    let tokens: [ReadingToken]
    let structure: ReadingStructure
    let passage: PassageDocument
    private let pageRanges: [UUID: Range<Int>]
    init(book: Book) {
        tokens = Tokenizer.tokens(for: book)
        structure = ReadingStructure(book: book, tokens: tokens)
        passage = PassageDocument(tokens: tokens, structure: structure)
        var ranges: [UUID: Range<Int>] = [:], start = 0
        while start < tokens.count {
            var end = start + 1
            while end < tokens.count && tokens[end].pageID == tokens[start].pageID { end += 1 }
            ranges[tokens[start].pageID] = start..<end; start = end
        }
        pageRanges = ranges
    }
    func hasPage(_ id: UUID) -> Bool { pageRanges[id] != nil }
    func index(_ position: ReadingPosition?) -> Int {
        guard let position, let range = pageRanges[position.pageID] else { return 0 }
        var low = range.lowerBound, high = range.upperBound
        while low < high {
            let middle = (low + high) / 2
            if tokens[middle].word.offset <= position.utf16Offset { low = middle + 1 } else { high = middle }
        }
        return max(range.lowerBound, low - 1)
    }
}

/// Shared by progress-only value copies. No PDF or text analysis occurs when restoring it.
final class ReaderCache: @unchecked Sendable, Equatable {
    // Runtime cache contents do not participate in persisted Book value equality.
    static func == (lhs: ReaderCache, rhs: ReaderCache) -> Bool { true }
    private let lock = NSLock()
    private var source: Book?
    private var document: ReaderDocument?
    func clear() { lock.lock(); defer { lock.unlock() }; source = nil; document = nil }
    func get(_ book: Book) -> ReaderDocument {
        lock.lock(); defer { lock.unlock() }
        if source?.pages != book.pages || source?.chapters != book.chapters || source?.title != book.title || source?.readingStart != book.readingStart {
            document = ReaderDocument(book: book)
            var snapshot = book; snapshot.readerCache = ReaderCache()
            source = snapshot
        }
        return document!
    }
}

enum ReaderPipeline {
    // Bump for extraction, repair, tokenization, or heading-recognition changes.
    static let version = 1
    static func prepare(_ book: Book, reuseIndexes: Bool = false) -> Book {
        var updated = book
        for index in updated.pages.indices {
            let page = updated.pages[index]
            updated.pages[index].edited = page.isEdited
            updated.pages[index].wordIndex = (reuseIndexes ? page.wordIndex : nil) ?? Tokenizer.words(in: page.text).flatMap { [$0.offset, $0.length, $0.endsParagraph ? 1 : 0] }
        }
        var full = updated
        for index in full.pages.indices { full.pages[index].isIncluded = true }
        full.readingStart = nil; full.headingMatches = nil
        updated.headingMatches = ReadingStructure(book: full, tokens: Tokenizer.tokens(for: full)).matches
        updated.schemaVersion = 2
        updated.pipelineVersion = version
        updated.readerCache = ReaderCache()
        _ = updated.readerCache.get(updated)
        return updated
    }

    static func update(from old: Book, to book: Book) -> Book {
        guard !old.needsReprocessing, book.pipelineVersion == version else { return book }
        let changed = old.pages.indices.filter { old.pages[$0].text != book.pages[$0].text }
        guard !changed.isEmpty || old.title != book.title || old.chapters != book.chapters else { return book }
        var updated = book
        for index in changed { updated.pages[index].wordIndex = nil; updated.pages[index].edited = true }
        updated.chaptersCustomized = old.chaptersCustomized == true || old.chapters != book.chapters
        return prepare(updated, reuseIndexes: true)
    }

    private static func relocateOffset(_ offset: Int, old: String, new: String) -> Int {
        if old == new { return min(max(0, offset), new.utf16.count) }
        let before = Tokenizer.words(in: old), after = Tokenizer.words(in: new)
        guard let target = before.lastIndex(where: { $0.offset <= offset }) else { return 0 }
        let approximate = Double(offset) * Double(new.utf16.count) / Double(max(1, old.utf16.count))
        let candidates = after.indices.filter { after[$0].text == before[target].text }
        func score(_ candidate: Int) -> Int {
            (-2...2).filter { delta in
                before.indices.contains(target + delta) && after.indices.contains(candidate + delta) && before[target + delta].text == after[candidate + delta].text
            }.count
        }
        let best = candidates.max { a, b in
            score(a) == score(b) ? abs(Double(after[a].offset) - approximate) > abs(Double(after[b].offset) - approximate) : score(a) < score(b)
        }
        guard let best else { return Tokenizer.relocatedOffset(offset, from: old, to: new) }
        return after[best].offset + min(max(0, offset - before[target].offset), after[best].length - 1)
    }

    static func reprocess(_ original: Book, converted: Book) -> Book {
        precondition(original.pages.count == converted.pages.count)
        var updated = original
        for index in original.pages.indices {
            let old = original.pages[index]
            var page = converted.pages[index]
            page.id = old.id; page.label = old.label
            if old.isEdited { page.text = old.text }
            page.wordIndex = nil; page.edited = old.isEdited
            page.isIncluded = old.isIncluded
            page.isReviewed = old.isReviewed && old.text == page.text
            updated.pages[index] = page
        }
        func relocate(_ position: ReadingPosition?) -> ReadingPosition? {
            guard var position, let index = original.pages.firstIndex(where: { $0.id == position.pageID }) else { return nil }
            position.utf16Offset = relocateOffset(position.utf16Offset, old: original.pages[index].text, new: updated.pages[index].text)
            return position
        }
        updated.position = relocate(original.position); updated.readingStart = relocate(original.readingStart)
        let keepChapters = original.chaptersCustomized == true || original.pipelineVersion == nil || original.chapters.contains { $0.source != "PDF bookmark" }
        updated.chapters = keepChapters ? original.chapters : converted.chapters
        updated.chaptersCustomized = keepChapters
        return prepare(updated)
    }

    static func loaded(_ book: Book) -> Book {
        var book = book
        let valid = book.pages.allSatisfy { page in
            guard let words = page.wordIndex, words.count % 3 == 0 else { return false }
            var end = 0
            let textLength = page.text.utf16.count
            return stride(from: 0, to: words.count, by: 3).allSatisfy { index in
                let offset = words[index], length = words[index + 1]
                guard offset >= end, length > 0, length <= textLength,
                      offset <= textLength - length, (0...1).contains(words[index + 2]) else { return false }
                end = offset + length
                return true
            }
        } && book.headingMatches?.allSatisfy { match in
            book.pages.indices.contains(match.pageIndex) && match.start >= 0 && match.end > match.start && match.end <= book.pages[match.pageIndex].text.utf16.count
        } == true
        if !valid {
            book.pipelineVersion = nil; book.headingMatches = nil
            for index in book.pages.indices { book.pages[index].wordIndex = nil }
        }
        return book
    }
}
