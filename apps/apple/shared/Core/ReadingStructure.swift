import Foundation

/// Rebuilt from current text when opening the reader; no persisted or stale heading offsets.
struct ReadingStructure: Sendable {
    struct Heading: Equatable, Sendable {
        let title: String
        let words: Range<Int>
        let level: Int
        let pageIndex: Int
    }
    struct Section: Sendable {
        let title: String
        let pageIndex: Int
        let level: Int
        let index: Int
        let heading: Heading?
    }
    let sections: [Section]
    let headings: [Heading]
    let chapterEnds: Set<Int>
    private let headingWords: [Int: Heading]

    init(book: Book, tokens: [ReadingToken]) {
        var starts = Array(repeating: -1, count: book.pages.count)
        var ends = Array(repeating: 0, count: book.pages.count)
        for token in tokens {
            if starts[token.pageIndex] < 0 { starts[token.pageIndex] = token.id }
            ends[token.pageIndex] = token.id + 1
        }
        struct Line { let text: String; let range: NSRange }
        var lines: [Int: [Line]] = [:]
        let linePattern = try! NSRegularExpression(pattern: "[^\\r\\n]+")
        func match(_ title: String, page: Int, level: Int) -> Heading? {
            let normalized = Self.normalize(title)
            guard (3...240).contains(normalized.utf16.count), starts[page] >= 0 else { return nil }
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
            guard matches.count == 1, let span = matches.first else { return nil }
            let pageTokens = tokens[starts[page]..<ends[page]]
            guard let first = pageTokens.first(where: { $0.word.offset >= span.location }),
                  let last = pageTokens.last(where: { $0.word.offset + $0.word.length <= NSMaxRange(span) }), last.id >= first.id else { return nil }
            if let start = book.readingStart, start.pageID == first.pageID, start.utf16Offset > first.word.offset { return nil }
            let actual = tokens[first.id...last.id].map { $0.word.text }.joined(separator: " ")
            guard Self.normalize(actual) == normalized else { return nil }
            return Heading(title: actual, words: first.id..<(last.id + 1), level: level, pageIndex: page)
        }
        var seen: Set<String> = []
        var candidates: [Section] = []
        for chapter in book.chapters {
            guard book.pages.indices.contains(chapter.pageIndex), starts[chapter.pageIndex] >= 0 else { continue }
            guard seen.insert("\(chapter.pageIndex):\(chapter.level):\(Self.normalize(chapter.title))").inserted else { continue }
            let heading = match(chapter.title, page: chapter.pageIndex, level: chapter.level)
            candidates.append(Section(title: chapter.title, pageIndex: chapter.pageIndex, level: chapter.level,
                                      index: heading?.words.lowerBound ?? starts[chapter.pageIndex], heading: heading))
        }
        candidates.sort { $0.index == $1.index ? $0.level < $1.level : $0.index < $1.index }
        sections = candidates
        var mapped = candidates.compactMap(\.heading)
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
    }
    func heading(at index: Int) -> Heading? { headingWords[index] }
    func section(at index: Int) -> Section? {
        var low = 0, high = sections.count
        while low < high { let middle = (low + high) / 2; if sections[middle].index <= index { low = middle + 1 } else { high = middle } }
        return low > 0 ? sections[low - 1] : nil
    }
    func destination(title: String, page: Int) -> Int? {
        sections.first { $0.pageIndex == page && Self.normalize($0.title) == Self.normalize(title) }?.heading?.words.lowerBound
    }
    func duration(index: Int, base: Double, passage: Bool) -> Double {
        let heading = heading(at: index)
        let reading = !passage && heading != nil ? max(base, 1.2 / Double(heading!.words.count)) : base
        return chapterEnds.contains(index) ? max(reading, 0.8) : reading
    }
    static func normalize(_ text: String) -> String {
        text.trimmingCharacters(in: .whitespacesAndNewlines).replacingOccurrences(of: "\\s+", with: " ", options: .regularExpression).lowercased()
    }
}
