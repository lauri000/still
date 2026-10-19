import Foundation

/// Import-time formatting repair. Source provenance and reader positions both use UTF-16.
enum PDFTextRepair {
    static let version = 1
    struct Input {
        var text: String
        var ligatureSpaces: Set<Int> = []
        var topMargin = false
        var bottomMargin = false
        var enabled = true
    }
    struct Edit { var start: Int; var end: Int; var replacement: String }
    struct Result {
        var pages: [MappedText]
        func relocate(page: Int, offset: Int) -> (page: Int, offset: Int) {
            var best = (page: page, offset: 0), distance = Int64.max
            for (p, text) in pages.enumerated() {
                for (i, origin) in text.origins.enumerated() where Int(origin >> 32) == page {
                    let delta = abs((origin & 0xffffffff) - Int64(offset))
                    if delta < distance { distance = delta; best = (p, i) }
                }
            }
            return best
        }
    }
    final class MappedText {
        var text: String
        var origins: [Int64]
        init(_ text: String, page: Int) {
            self.text = text
            origins = (0..<text.utf16.count).map { Int64(page) << 32 | Int64($0) }
        }
        func apply(_ edits: [Edit]) {
            guard !edits.isEmpty else { return }
            let source = text as NSString
            var result = "", mapped: [Int64] = [], cursor = 0
            mapped.reserveCapacity(origins.count)
            for edit in edits.sorted(by: { $0.start < $1.start }) {
                precondition(edit.start >= cursor && edit.end >= edit.start && edit.end <= source.length)
                result += source.substring(with: NSRange(location: cursor, length: edit.start - cursor))
                mapped.append(contentsOf: origins[cursor..<edit.start])
                result += edit.replacement
                let origin = edit.start < origins.count ? origins[edit.start] : (origins.last ?? 0)
                mapped.append(contentsOf: repeatElement(origin, count: edit.replacement.utf16.count))
                cursor = edit.end
            }
            result += source.substring(from: cursor)
            mapped.append(contentsOf: origins[cursor...])
            text = result; origins = mapped
        }
        func replace(_ pattern: String, _ transform: (String) -> String) {
            let source = text as NSString
            apply(PDFTextRepair.matches(pattern, text).map { match in
                Edit(start: match.range.location, end: NSMaxRange(match.range),
                     replacement: transform(source.substring(with: match.range)))
            })
        }
    }
    private static func matches(_ pattern: String, _ text: String) -> [NSTextCheckingResult] {
        (try! NSRegularExpression(pattern: pattern)).matches(in: text, range: NSRange(location: 0, length: text.utf16.count))
    }
    private static let letters = "[\\p{L}\\p{M}]+"
    private static let words = "[\\p{L}\\p{M}]+(?:[-’'][\\p{L}\\p{M}]+)*"
    private static let lineHyphen = "(?<![\\p{L}\\p{M}])([\\p{L}\\p{M}]+)[-‐][\\t ]*\\n[\\t ]*([\\p{L}\\p{M}]+)"
    private static let compoundPrefixes: Set<String> = ["self", "well", "ill", "all", "half", "quasi", "ex", "non"]
    private static let ligatures = ["ﬀ": "ff", "ﬁ": "fi", "ﬂ": "fl", "ﬃ": "ffi", "ﬄ": "ffl", "ﬅ": "st", "ﬆ": "st"]
    private static func lower(_ text: String) -> String { text.lowercased(with: Locale(identifier: "en_US_POSIX")) }

    static func eligible(_ page: BookPage) -> Bool { !page.isEdited && (page.textProcessingVersion ?? 0) < version }

    static func apply(to book: Book, result: Result, enabled: [Bool]) -> Book {
        precondition(book.pages.count == result.pages.count && enabled.count == book.pages.count)
        func relocate(_ position: ReadingPosition?) -> ReadingPosition? {
            guard var position, let index = book.pages.firstIndex(where: { $0.id == position.pageID }), enabled[index] else { return position }
            let mapped = result.relocate(page: index, offset: position.utf16Offset)
            position.pageID = book.pages[mapped.page].id; position.utf16Offset = mapped.offset
            return position
        }
        var updated = book
        updated.position = relocate(book.position); updated.readingStart = relocate(book.readingStart)
        for index in book.pages.indices where enabled[index] {
            updated.pages[index].text = result.pages[index].text
            updated.pages[index].processedText = result.pages[index].text
            updated.pages[index].textProcessingVersion = version
            updated.pages[index].wordIndex = nil; updated.pages[index].edited = false
            updated.pages[index].isReviewed = book.pages[index].isReviewed && book.pages[index].text == result.pages[index].text
        }
        return updated
    }

    /// Evidence is requested only for plausible boundaries, using original page offsets.
    static func repair(_ inputs: [Input], englishWords: Set<String> = [], evidence: (Int, Int, Bool) -> Bool = { _, _, _ in false }) -> Result {
        repair(inputs, englishWords: englishWords, checkpoint: {}, evidence: evidence)
    }

    static func repair(_ inputs: [Input], englishWords: Set<String> = [], checkpoint: () throws -> Void,
                       evidence: (Int, Int, Bool) -> Bool = { _, _, _ in false }) rethrows -> Result {
        let pages = try inputs.enumerated().map { index, input in
            try checkpoint()
            let page = MappedText(input.text, page: index)
            if input.enabled {
                let source = input.text as NSString
                page.apply(input.ligatureSpaces.filter { $0 >= 0 && $0 < source.length && source.character(at: $0) == 32 }
                    .map { Edit(start: $0, end: $0 + 1, replacement: "") })
                page.replace("\\u00AD[\\t ]*(?:\\r?\\n[\\t ]*)?") { _ in "" }
                page.replace("\\r\\n?|[ﬀ-ﬆ]|\\uFFFE|\\u0000|\\uFEFF|\\u00A0") {
                    switch $0 {
                    case "\r\n", "\r": return "\n"
                    case "\u{FFFE}": return "-\n"
                    case "\u{00AD}", "\u{0000}", "\u{FEFF}": return ""
                    case "\u{00A0}": return " "
                    default: return ligatures[$0] ?? $0
                    }
                }
            }
            return page
        }
        removeFurniture(inputs, pages)
        var vocabulary: [String: Int] = [:]
        let beforeFragment = try! NSRegularExpression(pattern: "[-‐]\\s*$")
        let afterFragment = try! NSRegularExpression(pattern: "^[-‐]\\s*\\n")
        for (pageIndex, page) in pages.enumerated() {
            try checkpoint()
            let source = page.text as NSString
            for match in matches(words, page.text) {
                let start = match.range.location, end = NSMaxRange(match.range)
                let before = source.substring(with: NSRange(location: max(0, start - 3), length: min(3, start)))
                let after = source.substring(with: NSRange(location: end, length: min(3, source.length - end)))
                if beforeFragment.firstMatch(in: before, range: NSRange(location: 0, length: before.utf16.count)) != nil ||
                    afterFragment.firstMatch(in: after, range: NSRange(location: 0, length: after.utf16.count)) != nil { continue }
                if start == 0 && pageIndex > 0 && pages[pageIndex - 1].text.trimmingCharacters(in: .whitespacesAndNewlines).hasSuffix("-") { continue }
                vocabulary[lower(source.substring(with: match.range)), default: 0] += 1
            }
        }
        func count(_ word: String) -> Int { vocabulary[lower(word), default: 0] }
        let commonEnglish = ["the", "and", "of", "to", "is", "that", "with"].reduce(0) { $0 + count($1) }
        let useEnglish = commonEnglish >= max(5, vocabulary.values.reduce(0, +) / 50)
        func known(_ word: String) -> Bool { count(word) > 0 || useEnglish && englishWords.contains(lower(word)) }
        func inflected(_ word: String) -> Bool {
            guard useEnglish else { return false }
            let value = lower(word)
            var roots = Set<String>()
            for suffix in ["ing", "ed", "ly", "ness", "ful", "less", "s"] where value.hasSuffix(suffix) && value.count > suffix.count + 3 {
                let stem = String(value.dropLast(suffix.count))
                roots.insert(stem)
                if suffix == "ing" || suffix == "ed" {
                    roots.insert(stem + "e")
                    if stem.count >= 2 && stem.last == stem.dropLast().last { roots.insert(String(stem.dropLast())) }
                }
            }
            return roots.contains(where: known)
        }
        func separator(_ left: String, _ right: String) -> String {
            if count(left + "-" + right) > 0 { return "-" }
            if count(left + right) > 0 { return "" }
            if known(left + "-" + right) { return "-" }
            if known(left + right) || inflected(left + right) { return "" }
            if compoundPrefixes.contains(lower(left)) || (right.first?.isUppercase == true && !left.allSatisfy(\.isUppercase)) { return "-" }
            if known(left) && known(right) { return "-" }
            return ""
        }
        for (index, page) in pages.enumerated() where inputs[index].enabled {
            try checkpoint()
            var splits: [Edit] = []
            let source = page.text as NSString
            for match in matches(letters, page.text) {
                let word = source.substring(with: match.range) as NSString
                guard (4...64).contains(word.length), count(word as String) <= 1,
                      !(useEnglish && englishWords.contains(lower(word as String))) else { continue }
                let supported = (1...word.length - 2).filter { split in
                    if split == 1 && !["I", "a", "A"].contains(word.substring(to: 1)) { return false }
                    guard count(word.substring(to: split)) >= 2, count(word.substring(from: split)) >= 2 else { return false }
                    let offset = Int(page.origins[match.range.location + split] & 0xffffffff)
                    return evidence(index, offset, true)
                }
                if supported.count == 1 {
                    let at = match.range.location + supported[0]
                    splits.append(Edit(start: at, end: at, replacement: " "))
                }
            }
            page.apply(splits)
            var joins: [Edit] = []
            let spaced = page.text as NSString, tokens = matches(letters, page.text)
            for (left, right) in zip(tokens, tokens.dropFirst()) {
                let start = NSMaxRange(left.range), end = right.range.location
                let a = spaced.substring(with: left.range), b = spaced.substring(with: right.range)
                let gap = spaced.substring(with: NSRange(location: start, length: end - start))
                let dropCap = gap == "\n" && a.count == 1 && a.first?.isUppercase == true
                guard (gap == " " || dropCap), count(a + b) >= 2 else { continue }
                if evidence(index, Int(page.origins[start] & 0xffffffff), false) {
                    joins.append(Edit(start: start, end: end, replacement: ""))
                }
            }
            page.apply(joins)
            while true {
                try checkpoint()
                let hyphenated = page.text as NSString
                let edits = matches(lineHyphen, page.text).map { match in
                    let left = match.range(at: 1), right = match.range(at: 2)
                    return Edit(start: NSMaxRange(left), end: right.location,
                        replacement: separator(hyphenated.substring(with: left), hyphenated.substring(with: right)))
                }
                if edits.isEmpty { break }
                page.apply(edits)
            }
            page.replace("(?<=\\p{N})[-‐][\\t ]*\\n[\\t ]*(?=\\p{L})") { _ in "-" }
        }
        if pages.count > 1 {
            for index in 0..<pages.count - 1 where inputs[index].enabled && inputs[index + 1].enabled {
                let previous = pages[index], next = pages[index + 1]
                guard let tail = matches("(?<![\\p{L}\\p{M}])([\\p{L}\\p{M}]+)[-‐][\\t ]*\\n?\\s*$", previous.text).first,
                      let head = matches("^\\s*([\\p{Ll}\\p{M}]+)", next.text).first else { continue }
                let left = tail.range(at: 1), right = head.range(at: 1)
                let a = (previous.text as NSString).substring(with: left), b = (next.text as NSString).substring(with: right)
                let suffixOrigins = Array(next.origins[right.location..<NSMaxRange(right)])
                previous.apply([Edit(start: NSMaxRange(left), end: previous.text.utf16.count, replacement: separator(a, b))])
                previous.text += b; previous.origins.append(contentsOf: suffixOrigins)
                next.apply([Edit(start: 0, end: NSMaxRange(right), replacement: "")])
            }
        }
        for (index, page) in pages.enumerated() where inputs[index].enabled {
            page.replace("[\\t ]*\\n[\\t ]*") { _ in "\n" }
            page.replace("^[\\s\\p{Z}]+|[\\s\\p{Z}]+$") { _ in "" }
        }
        return Result(pages: pages)
    }

    private static func removeFurniture(_ inputs: [Input], _ pages: [MappedText]) {
        func key(_ line: String) -> String? {
            let value = line.trimmingCharacters(in: .whitespaces)
            if value.count > 100 || !matches("(?i)^(chapter|part|book|section)\\s+[\\divxlcdm]+$", value).isEmpty { return nil }
            if !matches("^[0-9]+$", value).isEmpty { return "#" }
            if matches("^(?:[0-9]+|[ivxlcdm]+)\\s+.+|.+\\s+(?:[0-9]+|[ivxlcdm]+)$", value).isEmpty { return nil }
            return value.replacingOccurrences(of: "^(?:[0-9]+|[ivxlcdm]+)\\s+|\\s+(?:[0-9]+|[ivxlcdm]+)$", with: "#", options: .regularExpression)
        }
        var top: [String: Int] = [:], bottom: [String: Int] = [:]
        for (index, page) in pages.enumerated() where inputs[index].enabled {
            let lines = page.text.trimmingCharacters(in: .newlines).components(separatedBy: "\n")
            guard lines.count >= 5 else { continue }
            if inputs[index].topMargin, let k = key(lines[0]) { top[k, default: 0] += 1 }
            if inputs[index].bottomMargin, let k = key(lines.last!) { bottom[k, default: 0] += 1 }
        }
        for (index, page) in pages.enumerated() where inputs[index].enabled {
            guard page.text.filter({ $0 == "\n" }).count >= 4 else { continue }
            let source = page.text as NSString
            let firstEnd = source.range(of: "\n").location
            let first = source.substring(to: firstEnd)
            var edits: [Edit] = []
            if inputs[index].topMargin, let k = key(first), top[k, default: 0] >= 3 {
                edits.append(Edit(start: 0, end: firstEnd + 1, replacement: ""))
            }
            // Leading whitespace is retained in source offsets; only trim the trailing end here.
            let tailLength = matches("[\\s\\p{Z}]+$", page.text).first?.range.length ?? 0
            let end = source.length - tailLength
            let lastBreak = source.range(of: "\n", options: .backwards, range: NSRange(location: 0, length: end)).location
            let start = lastBreak == NSNotFound ? 0 : lastBreak + 1
            if inputs[index].bottomMargin, let k = key(source.substring(with: NSRange(location: start, length: end - start))), bottom[k, default: 0] >= 3 {
                edits.append(Edit(start: start, end: source.length, replacement: ""))
            }
            page.apply(edits)
        }
    }
}
