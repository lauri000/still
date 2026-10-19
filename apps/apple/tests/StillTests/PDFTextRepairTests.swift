import XCTest
@testable import Still

final class PDFTextRepairTests: XCTestCase {
    func testNativeImportLoadsDictionaryAndKeepsOriginal() async throws {
        XCTAssertTrue(PDFVocabulary.words.contains("stubble"))
        XCTAssertTrue(PDFVocabulary.words.contains("altering"))
        let result = try await PDFImporter.extract(data: SampleBook.pdfData(), filename: "sample.pdf") { _, _ in }
        XCTAssertTrue(result.book.pages.allSatisfy { $0.processedText == $0.text && !$0.isEdited })
        XCTAssertTrue(result.book.pages.allSatisfy { $0.textProcessingVersion == PDFTextRepair.version })
    }

    func testRepairKeepsManualPagesAndMapsBoundaryWord() {
        let texts = ["An extraordi-", "nary beginning.", "Manual page"]
        let pages = texts.enumerated().map { index, text in
            BookPage(id: UUID(), number: index + 1, label: "\(index + 1)", extractedText: text,
                     text: index == 2 ? "My correction" : text, isReviewed: true, isIncluded: true)
        }
        var book = Book(id: UUID(), title: "Test", author: "", originalFilename: "test.pdf", importedAt: Date(), pages: pages, chapters: [])
        book.position = ReadingPosition(pageID: pages[1].id, utf16Offset: 0)
        let enabled = pages.map(PDFTextRepair.eligible)
        let result = PDFTextRepair.repair(pages.enumerated().map { .init(text: $0.element.text, enabled: enabled[$0.offset]) })
        let repaired = PDFTextRepair.apply(to: book, result: result, enabled: enabled)
        let tokens = Tokenizer.tokens(for: repaired)
        XCTAssertEqual(tokens[Tokenizer.index(for: repaired.position, in: tokens)].word.text, "extraordinary")
        XCTAssertEqual(repaired.pages[2].text, "My correction")
        XCTAssertEqual(repaired.pages[0].extractedText, texts[0])
    }

    @MainActor func testImproveExistingBookIsReversible() async throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: root) }
        let imported = try await PDFImporter.extract(data: SampleBook.pdfData(), filename: "sample.pdf") { _, _ in }
        var old = imported.book
        for index in old.pages.indices {
            old.pages[index].text = old.pages[index].extractedText
            old.pages[index].processedText = nil; old.pages[index].textProcessingVersion = nil
        }
        old.pages[1].text = "My manual correction"
        let repository = BookRepository(root: root)
        try repository.insert(old, pdfData: imported.pdfData)
        let library = LibraryStore(repository: repository)
        await library.load()
        library.improveText(old.id)
        while library.isImporting { try await Task.sleep(for: .milliseconds(10)) }
        XCTAssertNil(library.errorMessage)
        XCTAssertEqual(library.undoImprovementID, old.id)
        XCTAssertEqual(library.book(old.id)?.pages[1].text, "My manual correction")
        XCTAssertEqual(library.book(old.id)?.pages[0].textProcessingVersion, PDFTextRepair.version)
        library.undoTextImprovement(old.id)
        XCTAssertNil(library.undoImprovementID)
        XCTAssertEqual(library.book(old.id)?.pages.map(\.text), old.pages.map(\.text))
        XCTAssertEqual(try repository.load().books.first?.pages.map(\.text), old.pages.map(\.text))
    }
}
