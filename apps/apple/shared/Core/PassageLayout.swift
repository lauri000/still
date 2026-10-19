import Foundation

/// A stable layout of the retained book. Playback never changes the wrapping.
struct PassageLayout {
    struct Row {
        let words: Range<Int>
        let duration: Double
    }
    let rows: [Row]
    let widths: [Double]
    let spacing: Double
    private let wordRows: [Int]
    private let elapsed: [Double]
    private let durations: [Double]

    init(tokens: [ReadingToken], widths: [Double], availableWidth: Double, spacing: Double = 8, naturalPauses: Bool) {
        self.spacing = spacing
        let width = max(1, availableWidth)
        self.widths = widths.map { min(width, max(1, $0)) }
        durations = tokens.map { Tokenizer.duration(for: $0.word, wordsPerMinute: 60, naturalPauses: naturalPauses) }
        var rows: [Row] = [], wordRows: [Int] = [], elapsed: [Double] = []
        var start = 0, used = 0.0, duration = 0.0
        for index in tokens.indices {
            let newParagraph = index > 0 && (tokens[index - 1].word.endsParagraph || tokens[index - 1].pageID != tokens[index].pageID)
            if index > start && (newParagraph || used + spacing + self.widths[index] > width) {
                rows.append(Row(words: start..<index, duration: duration))
                start = index; used = 0; duration = 0
            }
            wordRows.append(rows.count)
            elapsed.append(duration)
            used += (index == start ? 0 : spacing) + self.widths[index]
            duration += durations[index]
        }
        if start < tokens.count { rows.append(Row(words: start..<tokens.count, duration: duration)) }
        self.rows = rows; self.wordRows = wordRows; self.elapsed = elapsed
    }

    func row(containing index: Int) -> Int { wordRows.indices.contains(index) ? wordRows[index] : 0 }

    /// Moves one row over that row's total reading time, continuously across word/row boundaries.
    func position(index: Int, wordProgress: Double) -> Double {
        guard wordRows.indices.contains(index) else { return 0 }
        let row = wordRows[index]
        return Double(row) + (elapsed[index] + durations[index] * min(1, max(0, wordProgress))) / rows[row].duration
    }
}
