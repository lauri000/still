import Foundation

@main enum PDFRepairContracts {
    struct Input: Decodable {
        var text: String
        var ligatureSpaces: Set<Int>?
        var topMargin: Bool?
        var bottomMargin: Bool?
        var enabled: Bool?
    }
    struct Case: Decodable { var name: String; var pages: [Input]; var expected: [String] }
    static func main() throws {
        let root = URL(fileURLWithPath: FileManager.default.currentDirectoryPath)
        let cases = try JSONDecoder().decode([Case].self, from: Data(contentsOf: root.appendingPathComponent("shared/fixtures/pdf-repair.json")))
        let words = Set(try String(contentsOf: root.appendingPathComponent("shared/resources/pdf-english-words.txt"), encoding: .utf8).split(separator: "\n").map(String.init))
        for item in cases {
            let result = PDFTextRepair.repair(item.pages.map {
                PDFTextRepair.Input(text: $0.text, ligatureSpaces: $0.ligatureSpaces ?? [],
                    topMargin: $0.topMargin ?? false, bottomMargin: $0.bottomMargin ?? false, enabled: $0.enabled ?? true)
            }, englishWords: words)
            precondition(result.pages.map(\.text) == item.expected, "Failed: \(item.name): \(result.pages.map(\.text))")
            for page in result.pages { precondition(page.origins.count == page.text.utf16.count) }
        }
        let raw = "abstract reasonings. abstract reasonings. abstractreasonings."
        let boundary = (raw as NSString).range(of: "abstractreasonings").location + 8
        precondition(PDFTextRepair.repair([.init(text: raw)]).pages[0].text == raw)
        let split = PDFTextRepair.repair([.init(text: raw)]) { _, offset, split in split && offset == boundary }
        precondition(split.pages[0].text == "abstract reasonings. abstract reasonings. abstract reasonings.")
        let spaced = "The next word. The next word. T\nhe ne xt word."
        precondition(PDFTextRepair.repair([.init(text: spaced)]).pages[0].text == spaced)
        let joined = PDFTextRepair.repair([.init(text: spaced)]) { _, offset, split in
            !split && [(spaced as NSString).range(of: "\n").location,
                       (spaced as NSString).range(of: "ne xt").location + 2].contains(offset)
        }
        precondition(joined.pages[0].text == "The next word. The next word. The next word.")
        let long = String(repeating: "a", count: 100_000)
        precondition(PDFTextRepair.repair([.init(text: long)]).pages[0].text == long)
        do {
            _ = try PDFTextRepair.repair([.init(text: long)], checkpoint: { throw CancellationError() })
            preconditionFailure("Cancellation was ignored")
        } catch is CancellationError { }
        let source = "🌿 An oﬃce pre-\npared for reading."
        let page = BookPage(id: UUID(), number: 1, label: "1", extractedText: source, text: source, isReviewed: true, isIncluded: true)
        var book = Book(id: UUID(), title: "Test", author: "", originalFilename: "test.pdf", importedAt: Date(), pages: [page], chapters: [])
        book.position = ReadingPosition(pageID: page.id, utf16Offset: (source as NSString).range(of: "reading").location)
        book.readingStart = ReadingPosition(pageID: page.id, utf16Offset: (source as NSString).range(of: "pared").location)
        let repaired = PDFTextRepair.apply(to: book, result: PDFTextRepair.repair([.init(text: source)]), enabled: [true])
        let tokens = Tokenizer.tokens(for: repaired)
        precondition(tokens[Tokenizer.index(for: repaired.position, in: tokens)].word.text == "reading.")
        precondition(tokens.first?.word.text == "prepared")
        precondition(!repaired.pages[0].isEdited && !PDFTextRepair.eligible(repaired.pages[0]))
        let decoded = try JSONDecoder().decode(Book.self, from: JSONEncoder().encode(repaired))
        precondition(decoded.pages[0].processedText == repaired.pages[0].text)
        precondition(decoded.pages[0].textProcessingVersion == PDFTextRepair.version)
        print("Swift: \(cases.count) shared PDF repair contracts, geometry gating, Unicode relocation, and versioned backup passed.")
    }
}
