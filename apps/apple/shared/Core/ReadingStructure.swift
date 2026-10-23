import Foundation

/// Applies saved heading matches to the current trim and page selection.
struct ReadingStructure: Sendable {
    struct Heading: Equatable, Sendable {
        let title: String
        let words: Range<Int>
        let level: Int
        let pageIndex: Int
    }
    struct Section: Sendable {
        let chapterID: UUID
        let title: String
        let pageIndex: Int
        let level: Int
        let index: Int
        let heading: Heading?
    }
    let matches: [HeadingMatch]
    let sections: [Section]
    let headings: [Heading]
    let chapterEnds: Set<Int>
    private let isEpub: Bool
    private let headingWords: [Int: Heading]

    init(book: Book, tokens: [ReadingToken]) {
        isEpub = book.isEpub
        var starts = Array(repeating: -1, count: book.pages.count)
        var ends = Array(repeating: 0, count: book.pages.count)
        for token in tokens {
            if starts[token.pageIndex] < 0 { starts[token.pageIndex] = token.id }
            ends[token.pageIndex] = token.id + 1
        }
        // EPUB sections can span a whole book; resolve persisted spans with binary searches.
        func lowerBound(page: Int, offset: Int, wordEnd: Bool = false) -> Int {
            var low = starts[page], high = ends[page]
            while low < high {
                let middle = (low + high) / 2, word = tokens[(low + high) / 2].word
                if word.offset + (wordEnd ? word.length : 0) < offset { low = middle + 1 } else { high = middle }
            }
            return low
        }
        struct Line { let text: String; let range: NSRange }
        var lines: [Int: [Line]] = [:]
        var savedMatches: [HeadingMatch] = []
        let linePattern = try! NSRegularExpression(pattern: "[^\\r\\n]+")
        func match(_ title: String, page: Int, level: Int, expectedStart: Int? = nil) -> Heading? {
            let normalized = Self.normalize(title)
            guard ((book.isEpub ? 1 : 3)...240).contains(normalized.utf16.count), starts[page] >= 0 else { return nil }
            let span: NSRange?
            if let prepared = book.headingMatches {
                span = prepared.first { $0.pageIndex == page && $0.title == normalized && $0.level == level && (expectedStart == nil || $0.start == expectedStart) }
                    .map { NSRange(location: $0.start, length: $0.end - $0.start) }
            } else {
                let source = book.pages[page].text as NSString
                let rows: [Line]
                if let cached = lines[page] { rows = cached }
                else {
                    rows = linePattern.matches(in: source as String, range: NSRange(location: 0, length: source.length)).map { Line(text: source.substring(with: $0.range), range: $0.range) }
                    lines[page] = rows
                }
                var matches: [NSRange] = []
                for first in rows.indices {
                    if Self.normalize(rows[first].text).isEmpty { continue }
                    var text = ""
                    for last in first..<min(first + 3, rows.count) {
                        if last > first {
                            let gap = source.substring(with: NSRange(location: NSMaxRange(rows[last - 1].range), length: rows[last].range.location - NSMaxRange(rows[last - 1].range)))
                            if gap.filter({ $0 == "\n" }).count > 1 { break }
                        }
                        text += " " + rows[last].text
                        let value = Self.normalize(text)
                        if value == normalized { matches.append(NSRange(location: rows[first].range.location, length: NSMaxRange(rows[last].range) - rows[first].range.location)) }
                        if value.utf16.count >= normalized.utf16.count { break }
                    }
                }
                span = matches.count == 1 ? matches.first : nil
            }
            guard let span else { return nil }
            savedMatches.append(HeadingMatch(title: normalized, pageIndex: page, level: level, start: span.location, end: NSMaxRange(span)))
            let firstIndex = lowerBound(page: page, offset: span.location)
            let lastIndex = lowerBound(page: page, offset: NSMaxRange(span) + 1, wordEnd: true) - 1
            guard firstIndex < ends[page], lastIndex >= firstIndex else { return nil }
            let first = tokens[firstIndex], last = tokens[lastIndex]
            if let start = book.readingStart, start.pageID == first.pageID, start.utf16Offset > first.word.offset { return nil }
            var actual = first.word.text
            for index in (first.id + 1)..<(last.id + 1) {
                actual += tokens[index - 1].separator(to: tokens[index]) + tokens[index].word.text
            }
            guard Self.normalize(actual) == normalized else { return nil }
            return Heading(title: actual, words: first.id..<(last.id + 1), level: level, pageIndex: page)
        }
        var seen: Set<String> = []
        var candidates: [Section] = []
        for chapter in book.chapters {
            guard book.pages.indices.contains(chapter.pageIndex), starts[chapter.pageIndex] >= 0 else { continue }
            if let offset = chapter.utf16Offset, let start = book.readingStart, start.pageID == book.pages[chapter.pageIndex].id, offset < start.utf16Offset { continue }
            guard seen.insert("\(chapter.pageIndex):\(chapter.level):\(Self.normalize(chapter.title)):\(chapter.utf16Offset ?? -1)").inserted else { continue }
            let explicit = chapter.utf16Offset.flatMap { offset in book.headingMatches?.first { $0.pageIndex == chapter.pageIndex && ($0.start..<$0.end).contains(offset) } }
            let heading = explicit.flatMap { match($0.title, page: $0.pageIndex, level: $0.level, expectedStart: $0.start) } ?? match(chapter.title, page: chapter.pageIndex, level: chapter.level)
            let destination = chapter.utf16Offset.flatMap { offset in min(lowerBound(page: chapter.pageIndex, offset: offset + 1, wordEnd: true), ends[chapter.pageIndex] - 1) }
            candidates.append(Section(chapterID: chapter.id, title: chapter.title, pageIndex: chapter.pageIndex, level: chapter.level,
                                      index: destination ?? heading?.words.lowerBound ?? starts[chapter.pageIndex], heading: heading))
        }
        candidates.sort { $0.index == $1.index ? $0.level < $1.level : $0.index < $1.index }
        sections = candidates
        var mapped = candidates.compactMap(\.heading)
        if book.isEpub {
            for h in book.headingMatches ?? [] { if let heading = match(h.title, page: h.pageIndex, level: h.level, expectedStart: h.start) { mapped.append(heading) } }
        }
        let titles = (0..<min(3, book.pages.count)).compactMap { match(book.title, page: $0, level: -1) }
        if titles.count == 1, let title = titles.first, !mapped.contains(where: { $0.words.overlaps(title.words) }) { mapped.append(title) }
        var accepted: [Heading] = []
        for heading in mapped.sorted(by: { $0.words.lowerBound < $1.words.lowerBound }) {
            if accepted.last.map({ $0.words.upperBound <= heading.words.lowerBound }) ?? true { accepted.append(heading) }
        }
        headings = accepted
        let wordMap = Dictionary(uniqueKeysWithValues: accepted.flatMap { h in h.words.map { ($0, h) } })
        headingWords = wordMap
        var nextPeer = Array(repeating: -1, count: candidates.count), stack: [Int] = []
        for index in candidates.indices.reversed() {
            while let last = stack.last, candidates[last].level > candidates[index].level { stack.removeLast() }
            nextPeer[index] = stack.last ?? -1
            stack.append(index)
        }
        var pauses: Set<Int> = []
        for (index, section) in candidates.enumerated() {
            guard let opening = section.heading, wordMap[opening.words.lowerBound] == opening,
                  section.level == 0 || section.title.range(of: "^chapter\\s+\\S+", options: [.regularExpression, .caseInsensitive]) != nil,
                  nextPeer[index] >= 0 else { continue }
            let next = candidates[nextPeer[index]]
            guard let nextHeading = next.heading, wordMap[nextHeading.words.lowerBound] == nextHeading else { continue }
            let end = nextHeading.words.lowerBound - 1
            guard end >= opening.words.upperBound else { continue }
            if (section.pageIndex...next.pageIndex).allSatisfy({ book.includesPage(at: $0) && !book.pages[$0].hasNoText }) { pauses.insert(end) }
        }
        chapterEnds = pauses
        matches = savedMatches
    }
    func heading(at index: Int) -> Heading? { headingWords[index] }
    func section(at index: Int) -> Section? {
        var low = 0, high = sections.count
        while low < high { let middle = (low + high) / 2; if sections[middle].index <= index { low = middle + 1 } else { high = middle } }
        return low > 0 ? sections[low - 1] : nil
    }
    func destination(title: String, page: Int) -> Int? {
        sections.first { $0.pageIndex == page && Self.normalize($0.title) == Self.normalize(title) }.flatMap { isEpub ? $0.index : $0.heading?.words.lowerBound }
    }
    func duration(index: Int, base: Double) -> Double {
        let heading = heading(at: index)
        let reading = heading != nil ? max(base, 1.2 / Double(heading!.words.count)) : base
        return chapterEnds.contains(index) ? max(reading, 0.8) : reading
    }
    static func normalize(_ text: String) -> String {
        text.trimmingCharacters(in: .whitespacesAndNewlines).replacingOccurrences(of: "\\s+", with: " ", options: .regularExpression).lowercased()
    }
}
