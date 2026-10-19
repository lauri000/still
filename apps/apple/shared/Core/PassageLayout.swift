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
}

/// Index only paragraph/page/heading boundaries; measure text when its block appears.
struct PassageDocument {
    struct Block: Identifiable { let words: Range<Int>; var id: Int { words.lowerBound } }
    let blocks: [Block]
    init(tokens: [ReadingToken], structure: ReadingStructure) {
        var blocks: [Block] = [], start = 0
        for index in tokens.indices {
            if index > 0 && (tokens[index - 1].word.endsParagraph || tokens[index - 1].pageID != tokens[index].pageID ||
                structure.heading(at: index - 1) != structure.heading(at: index)) {
                blocks.append(Block(words: start..<index)); start = index
            }
        }
        if start < tokens.count { blocks.append(Block(words: start..<tokens.count)) }
        self.blocks = blocks
    }
    func block(containing index: Int) -> Int {
        var low = 0, high = blocks.count
        while low < high { let mid = (low + high) / 2; if blocks[mid].words.upperBound <= index { low = mid + 1 } else { high = mid } }
        return min(low, max(0, blocks.count - 1))
    }
}
