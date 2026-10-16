import Foundation

// Compile with Still/Core/Book.swift and Still/Services/SampleBook.swift.
// These fixtures are consumed by Android JVM/device tests and this Swift checker.
@main
enum ContractFixtures {
    struct Word: Codable, Equatable {
        var text: String
        var offset: Int
        var length: Int
        var endsParagraph: Bool
        var duration: Double
    }
    struct Case: Codable {
        var name: String
        var text: String
        var words: [Word]
    }
    struct Backup: Codable {
        var format = "still-book"
        var version = 1
        var book: Book
        var pdfData: Data
    }
    static func words(_ text: String) -> [Word] {
        Tokenizer.words(in: text).map { Word(text: $0.text, offset: $0.offset, length: $0.length,
            endsParagraph: $0.endsParagraph, duration: Tokenizer.duration(for: $0, wordsPerMinute: 300, naturalPauses: true)) }
    }
    static func main() throws {
        let root = URL(fileURLWithPath: FileManager.default.currentDirectoryPath).appendingPathComponent("shared/fixtures")
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.prettyPrinted, .sortedKeys]
        if CommandLine.arguments.contains("--write") {
            try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
            let cases: [(String, String)] = [
                ("punctuation", "Hello, world! “Wait.” a-long-word-here\n\nAgain…"),
                ("unicode", "Heading 🌿 Begin cafe\u{301} 👨‍👩‍👧‍👦 story"),
                ("whitespace", "one\u{00a0}two\u{2003}three\t four\r\n\r\nfive\u{2028}six"),
                ("graphemes", "👨‍👩‍👧‍👦👨‍👩‍👧‍👦 abcdefghijklm e\u{301}e\u{301}e\u{301}e\u{301}e\u{301}e\u{301}e\u{301}e\u{301}e\u{301}e\u{301}e\u{301}e\u{301}"),
                ("blank", " \t\r\n"),
                ("line-breaks", "hyphen-\nated text\nnext\n\nparagraph"),
            ]
            try encoder.encode(cases.map { Case(name: $0.0, text: $0.1, words: words($0.1)) })
                .write(to: root.appendingPathComponent("tokenization.json"))
            let texts = ["Cover and contents", "Heading 🌿 Begin the story.\n\nKeep the meaning.", "Excluded page"]
            let pages = texts.enumerated().map { index, text in
                BookPage(id: UUID(uuidString: String(format: "00000000-0000-4000-8000-%012d", index + 1))!,
                    number: index + 1, label: ["i", "1", "2"][index], extractedText: text,
                    text: index == 1 ? text.replacingOccurrences(of: "meaning", with: "original meaning") : text,
                    isReviewed: index == 1, isIncluded: index != 2)
            }
            var book = Book(id: UUID(uuidString: "AAAAAAAA-AAAA-4AAA-8AAA-AAAAAAAAAAAA")!, title: "The art of attention", author: "Still",
                originalFilename: "sample.pdf", importedAt: Date(timeIntervalSinceReferenceDate: 800_000_000), pages: pages,
                chapters: [BookChapter(id: UUID(uuidString: "BBBBBBBB-BBBB-4BBB-8BBB-BBBBBBBBBBBB")!, title: "Begin", pageIndex: 1, level: 0, source: "Manual")])
            book.setReadingStart(ReadingPosition(pageID: pages[1].id, utf16Offset: 11))
            book.position = ReadingPosition(pageID: pages[1].id, utf16Offset: 17)
            let pdf = SampleBook.pdfData()
            try pdf.write(to: root.appendingPathComponent("sample.pdf"))
            try encoder.encode(Backup(book: book, pdfData: pdf)).write(to: root.appendingPathComponent("apple-backup.json"))
        }
        let cases = try JSONDecoder().decode([Case].self, from: Data(contentsOf: root.appendingPathComponent("tokenization.json")))
        for item in cases { precondition(words(item.text) == item.words, "Tokenization changed: \(item.name)") }
        let file = CommandLine.arguments.first(where: { $0.hasPrefix("--backup=") }).map { String($0.dropFirst(9)) }
        let url = file.map { URL(fileURLWithPath: $0) } ?? root.appendingPathComponent("apple-backup.json")
        let backup = try JSONDecoder().decode(Backup.self, from: Data(contentsOf: url))
        precondition(backup.format == "still-book" && backup.version == 1 && backup.book.schemaVersion == 1)
        precondition(backup.pdfData.starts(with: Array("%PDF-".utf8)))
        precondition(backup.book.pages[1].label == "1")
        precondition(backup.book.pages[1].text.contains("original meaning"))
        precondition(Tokenizer.tokens(for: backup.book).first?.word.text == "Begin")
        let tokens = Tokenizer.tokens(for: backup.book)
        precondition(tokens[Tokenizer.index(for: backup.book.position, in: tokens)].word.text == "the")
        precondition(backup.book.importedAt.timeIntervalSinceReferenceDate == 800_000_000)
        print("Swift: \(cases.count) tokenizer contracts and portable backup passed.")
    }
}
