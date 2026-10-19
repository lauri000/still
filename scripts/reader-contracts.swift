import Foundation

/// Both platforms consume the same prepared format, including UTF-16 and heading spans.
@main enum ReaderContracts {
    static func main() throws {
        let fixture = URL(fileURLWithPath: "shared/fixtures/prepared-book.json")
        if CommandLine.arguments.contains("--write") {
            let texts = ["Chapter One\nCafé 🌿 begin.\n\nNext paragraph.", "Chapter Two\nThe end."]
            let pages = texts.enumerated().map { index, text in
                BookPage(id: UUID(uuidString: String(format: "00000000-0000-4000-8000-%012d", index + 1))!, number: index + 1,
                    label: String(index + 1), extractedText: text, text: text, isReviewed: true, isIncluded: true)
            }
            var book = Book(id: UUID(uuidString: "AAAAAAAA-AAAA-4AAA-8AAA-AAAAAAAAAAAA")!, title: "Prepared book", author: "Still", originalFilename: "sample.pdf",
                importedAt: Date(timeIntervalSinceReferenceDate: 800_000_000), pages: pages,
                chapters: texts.indices.map { BookChapter(id: UUID(uuidString: String(format: "BBBBBBBB-BBBB-4BBB-8BBB-%012d", $0 + 1))!, title: "Chapter " + ($0 == 0 ? "One" : "Two"), pageIndex: $0, level: 0, source: "PDF bookmark") })
            book.position = ReadingPosition(pageID: pages[0].id, utf16Offset: 20)
            let encoder = JSONEncoder(); encoder.outputFormatting = [.prettyPrinted, .sortedKeys]
            try encoder.encode(ReaderPipeline.prepare(book)).write(to: fixture)
        }
        let path = CommandLine.arguments.first { $0.hasPrefix("--book=") }.map { String($0.dropFirst(7)) }
        let data = try Data(contentsOf: path.map { URL(fileURLWithPath: $0) } ?? fixture)
        let book = ReaderPipeline.loaded(try JSONDecoder().decode(Book.self, from: data))
        precondition(book.schemaVersion == 2 && !book.needsReprocessing)
        precondition(book.pages.allSatisfy { $0.extractedText.isEmpty && $0.processedText == nil })
        let restored = book.readerCache.get(book)
        precondition(restored.tokens[restored.index(book.position)].word.text == "begin.")
        precondition(restored.structure.headings.map(\.title) == ["Chapter One", "Chapter Two"])
        for page in book.pages { precondition(page.words == Tokenizer.words(in: page.text)) }
        var source = book; source.headingMatches = nil
        let expected = ReadingStructure(book: source, tokens: restored.tokens)
        precondition(restored.structure.headings == expected.headings && restored.structure.chapterEnds == expected.chapterEnds)
        print("Prepared-reader words, positions, headings and compact storage agree across platforms.")
    }
}
