import Foundation

/// Lightweight presentation of saved word indexes; never reparses a book or changes its structure.
struct BookProgress {
    private let book: Book
    private var starts: [Int]
    private var firsts: [Int]
    private var counts: [Int]
    private let chapters: [BookChapter]
    let wordCount: Int?

    init(_ book: Book, readingOnly: Bool = true) {
        self.book = book
        starts = Array(repeating: 0, count: book.pages.count)
        firsts = starts; counts = starts
        chapters = book.chapters.filter { book.pages.indices.contains($0.pageIndex) }.sorted {
            if $0.pageIndex != $1.pageIndex { return $0.pageIndex < $1.pageIndex }
            if ($0.utf16Offset ?? 0) != ($1.utf16Offset ?? 0) { return ($0.utf16Offset ?? 0) < ($1.utf16Offset ?? 0) }
            return $0.level < $1.level
        }
        var total = 0, prepared = true
        for (i, page) in book.pages.enumerated() {
            starts[i] = total
            if !readingOnly || book.includesPage(at: i) {
                guard let words = page.wordIndex else { prepared = false; continue }
                let trim = readingOnly && book.readingStart?.pageID == page.id ? book.readingStart!.utf16Offset : 0
                var low = 0, high = words.count / 3
                while low < high {
                    let mid = (low + high) / 2
                    if words[mid * 3] + words[mid * 3 + 1] <= trim { low = mid + 1 } else { high = mid }
                }
                firsts[i] = low
                counts[i] = words.count / 3 - low
                total += counts[i]
            }
        }
        wordCount = prepared ? total : nil
    }

    func percent(page: Int, offset: Int = 0) -> Int? {
        guard let total = wordCount, total > 0, book.pages.indices.contains(page), let words = book.pages[page].wordIndex else { return nil }
        var low = firsts[page], high = firsts[page] + counts[page]
        while low < high {
            let mid = (low + high) / 2
            if words[mid * 3] <= offset { low = mid + 1 } else { high = mid }
        }
        let index = starts[page] + max(0, low - 1 - firsts[page])
        return min(99, max(0, 100 * min(index, total - 1) / total))
    }

    func chapterTitle(page: Int, offset: Int = 0) -> String? {
        chapters.last { $0.pageIndex < page || $0.pageIndex == page && ($0.utf16Offset ?? 0) <= offset }?.title
    }

    func textLabel(page: Int) -> String {
        if !book.isEpub { return "Page \(book.pages[page].label)" }
        let local = chapters.filter { $0.pageIndex == page }
        let first = chapterTitle(page: page) ?? local.first?.title ?? "Beginning"
        let last = local.last?.title ?? first
        let title = first == last ? first : "\(first) – \(last)"
        return [title, percent(page: page).map { "\($0)% of book" }].compactMap { $0 }.joined(separator: " · ")
    }

    func savedStatus() -> String {
        guard let position = book.position else { return "Not started" }
        if position.finished { return "Finished · 100% read" }
        guard let page = book.pages.firstIndex(where: { $0.id == position.pageID }) else { return "Not started" }
        let status = [chapterTitle(page: page, offset: position.utf16Offset), percent(page: page, offset: position.utf16Offset).map { "\($0)% read" }]
            .compactMap { $0 }.joined(separator: " · ")
        return status.isEmpty ? "Ready to read" : status
    }
}
