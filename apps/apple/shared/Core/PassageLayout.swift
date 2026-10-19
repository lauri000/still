import Foundation

/// Plain nearby rows, independent of single-line reading preferences.
struct PassageLayout {
    struct Row {
        let words: Range<Int>
        let text: String
        let width: Double
    }
    let rows: [Row]
    private let wordRows: [Int]

    init(tokens: [ReadingToken], widths: [Double], availableWidth: Double, spacing: Double = 8) {
        var rows: [Row] = [], wordRows: [Int] = []
        var start = 0, used = 0.0
        func append(_ end: Int) {
            rows.append(Row(words: start..<end, text: tokens[start..<end].map { $0.word.text }.joined(separator: " "), width: used))
        }
        for index in tokens.indices {
            let newParagraph = index > 0 && (tokens[index - 1].word.endsParagraph || tokens[index - 1].pageID != tokens[index].pageID)
            if index > start && (newParagraph || used + spacing + widths[index] > max(1, availableWidth)) {
                append(index); start = index; used = 0
            }
            wordRows.append(rows.count)
            used += (index == start ? 0 : spacing) + max(1, widths[index])
        }
        if start < tokens.count { append(tokens.count) }
        self.rows = rows; self.wordRows = wordRows
    }
    func row(containing index: Int) -> Int { wordRows.indices.contains(index) ? wordRows[index] : 0 }
    func position(index: Int, wordProgress: Double) -> Double {
        guard wordRows.indices.contains(index) else { return 0 }
        let row = wordRows[index], words = rows[wordRows[index]].words
        return Double(row) + (Double(index - words.lowerBound) + min(1, max(0, wordProgress))) / Double(words.count)
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
