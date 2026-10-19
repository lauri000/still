import Foundation

/// Plain nearby rows, independent of single-line reading preferences.
struct PassageLayout {
    struct Row {
        let words: Range<Int>
        let text: String
        let width: Double
        let top: Double
        let height: Double
        let headingID: Int?
        var isHeading: Bool { headingID != nil }
    }
    let rows: [Row]
    private let wordRows: [Int]

    init(tokens: [ReadingToken], widths: [Double], availableWidth: Double, spacing: Double = 8, headingIDs: [Int: Int] = [:]) {
        var rows: [Row] = [], wordRows: [Int] = []
        var start = 0, used = 0.0
        func append(_ end: Int) {
            let heading = headingIDs[tokens[start].id]
            let previous = rows.last
            let gap = heading != previous?.headingID && (heading != nil || previous?.isHeading == true) ? 0.45 : 0
            rows.append(Row(words: start..<end, text: tokens[start..<end].map { $0.word.text }.joined(separator: " "), width: used,
                            top: (previous.map { $0.top + $0.height } ?? 0) + gap, height: heading != nil ? 1.2 : 1, headingID: heading))
        }
        for index in tokens.indices {
            let gap = spacing * (headingIDs[tokens[index].id] != nil ? 1.2 : 1)
            let newParagraph = index > 0 && (headingIDs[tokens[index - 1].id] != headingIDs[tokens[index].id] || tokens[index - 1].word.endsParagraph || tokens[index - 1].pageID != tokens[index].pageID)
            if index > start && (newParagraph || used + gap + widths[index] > max(1, availableWidth)) {
                append(index); start = index; used = 0
            }
            wordRows.append(rows.count)
            used += (index == start ? 0 : gap) + max(1, widths[index])
        }
        if start < tokens.count { append(tokens.count) }
        self.rows = rows; self.wordRows = wordRows
    }
    func row(containing index: Int) -> Int { wordRows.indices.contains(index) ? wordRows[index] : 0 }
    func position(index: Int, wordProgress: Double) -> Double {
        guard wordRows.indices.contains(index) else { return 0 }
        let row = wordRows[index], words = rows[wordRows[index]].words
        let distance = row + 1 < rows.count ? rows[row + 1].top - rows[row].top : rows[row].height
        return rows[row].top + distance * (Double(index - words.lowerBound) + min(1, max(0, wordProgress))) / Double(words.count)
    }

    /// Real paragraph/page boundaries keep overlapping windows wrapped identically.
    static func window(tokens: [ReadingToken], index: Int, contextWords: Int = 256) -> Range<Int> {
        guard !tokens.isEmpty else { return 0..<0 }
        let current = min(tokens.count - 1, max(0, index))
        var start = current, end = current + 1
        while start > 0 && tokens[start - 1].pageID == tokens[current].pageID { start -= 1 }
        while end < tokens.count && tokens[end].pageID == tokens[current].pageID { end += 1 }
        start = max(0, start - contextWords); end = min(tokens.count, end + contextWords)
        func boundary(_ at: Int) -> Bool { tokens[at - 1].word.endsParagraph || tokens[at - 1].pageID != tokens[at].pageID }
        while start > 0 && !boundary(start) { start -= 1 }
        while end < tokens.count && !boundary(end) { end += 1 }
        return start..<end
    }
}
