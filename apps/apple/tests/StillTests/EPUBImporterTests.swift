import XCTest
@testable import Still

final class EPUBImporterTests: XCTestCase {
    private func fixture(_ name: String) throws -> URL {
        try XCTUnwrap(Bundle(for: Self.self).url(forResource: name, withExtension: "epub", subdirectory: "epub"))
    }
    private func imported(_ name: String = "structured") async throws -> ImportedBook {
        try await EPUBImporter.extract(data: Data(contentsOf: fixture(name)), filename: name + ".epub")
    }
    func testSemanticTextSpineOrderAndExactAnchorsSurviveStorage() async throws {
        let book = try await imported().book
        XCTAssertEqual(book.title, "A Structured Book")
        XCTAssertEqual(book.sourceFormat, "epub")
        XCTAssertEqual(book.pages.map(\.sourceHref), ["OPS/first.xhtml", "OPS/café two.xhtml"])
        XCTAssertEqual(book.pages[0].text, "First heading\n\nCafé 🌿 something & more.\n\nAnother paragraph.\nA new line.\n\nRepeat\n\nFirst repeat body.\n\nRepeat\n\nSecond repeat body.")
        XCTAssertEqual(book.chapters.map(\.title), ["A different label", "Repeat", "Repeat", "The ending"])
        let first = try XCTUnwrap(book.chapters[1].utf16Offset), second = try XCTUnwrap(book.chapters[2].utf16Offset)
        let contents = PDFContents.fromChapters(book)
        XCTAssertEqual(PDFContents.current(in: contents, page: 0, selectedID: nil, utf16Offset: 0)?.id, contents[0].id)
        XCTAssertEqual(PDFContents.current(in: contents, page: 0, selectedID: nil, utf16Offset: second)?.id, contents[2].id)
        XCTAssertGreaterThan(second, first)
        XCTAssertTrue((book.pages[0].text as NSString).substring(from: second).hasPrefix("Repeat\n\nSecond"))
        let restored = ReaderPipeline.loaded(try JSONDecoder().decode(Book.self, from: JSONEncoder().encode(book)))
        XCTAssertFalse(restored.needsReprocessing)
        XCTAssertEqual(book.pages.map(\.text), restored.pages.map(\.text))
        let reader = restored.readerCache.get(restored)
        XCTAssertEqual(reader.structure.headings.map(\.title), ["First heading", "Repeat", "Repeat", "Last heading"])
        XCTAssertEqual(reader.tokens[reader.structure.headings[2].words.lowerBound].word.offset, second)
        var trimmed = restored; trimmed.setReadingStart(ReadingPosition(pageID: restored.pages[0].id, utf16Offset: second))
        XCTAssertEqual(trimmed.readerCache.get(trimmed).structure.sections.count, 2)
    }
    func testEPUB2NCXBOMAndStandardXHTMLEntities() async throws {
        let book = try await imported("legacy").book
        XCTAssertEqual(book.pages[0].text, "Legacy heading\n\nSome words — ©.")
        XCTAssertEqual(book.chapters.map(\.title), ["Legacy chapter"])
        XCTAssertEqual(book.chapters.first?.utf16Offset, 0)
    }
    func testRealCorpusAndSectionOrigins() async throws {
        let cases = ["hume-enquiry-epub2", "hume-enquiry-epub3", "alice-epub3", "moby-dick", "childrens-literature", "internallinks", "israelsailing"]
        try XCTSkipUnless(cases.allSatisfy { Bundle(for: Self.self).url(forResource: $0, withExtension: "epub", subdirectory: "epub") != nil }, "Run scripts/download-epub-fixtures.py for the optional real-book corpus.")
        for name in cases {
            let book = try await imported(name).book
            XCTAssertFalse(book.needsReprocessing, name)
            XCTAssertGreaterThan(book.pages.reduce(0) { $0 + $1.words.count }, 100, name)
            XCTAssertFalse(book.chapters.isEmpty, name)
            XCTAssertTrue(book.pages.allSatisfy { $0.sourceHref != nil }, name)
            XCTAssertEqual(book.pages.map(\.text), try JSONDecoder().decode(Book.self, from: JSONEncoder().encode(book)).pages.map(\.text), name)
            if name == "moby-dick" { XCTAssertGreaterThanOrEqual(book.chapters.count, 130) }
        }
    }
    func testMalformedEncryptedFixedAndImageOnlyFailClearly() async {
        for (name, message) in [("encrypted", "encrypted"), ("fixed-layout", "fixed-layout"), ("image-only", "no readable text"), ("unsafe-entity", "entity"), ("missing-spine", "missing"), ("path-escape", "package")] {
            do { _ = try await imported(name); XCTFail("Accepted \(name)") }
            catch { XCTAssertTrue(error.localizedDescription.localizedCaseInsensitiveContains(message), "\(name): \(error)") }
        }
    }
    func testFixedLayoutCorpusFailsClearly() async throws {
        try XCTSkipUnless(Bundle(for: Self.self).url(forResource: "haruko-html-jpeg", withExtension: "epub", subdirectory: "epub") != nil)
        do { _ = try await imported("haruko-html-jpeg"); XCTFail("Accepted fixed layout") }
        catch { XCTAssertTrue(error.localizedDescription.contains("fixed-layout"), "\(error)") }
    }
    func testReprocessingPreservesEditsExactPositionAndSectionIdentity() async throws {
        let book = try await imported().book
        var edited = book; edited.editText(on: 1, to: "My corrected ending.")
        edited = ReaderPipeline.update(from: book, to: edited)
        edited.position = ReadingPosition(pageID: book.pages[0].id, utf16Offset: (book.pages[0].text as NSString).range(of: "Second").location)
        let result = try await BookImporter.reprocess(url: fixture("structured"), book: edited) { _, _ in }
        XCTAssertEqual(result.pages.map(\.id), edited.pages.map(\.id))
        XCTAssertEqual(result.pages[1].text, "My corrected ending.")
        XCTAssertEqual(result.position, edited.position)
        XCTAssertFalse(result.needsReprocessing)
    }
    func testBackupRetainsOriginalBytesAndReopensPreparedOutput() async throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: root) }
        let repository = BookRepository(root: root), source = try await imported(), book = source.book
        try repository.insert(book, sourceData: source.sourceData)
        let backup = BookBackup(book: book, pdfData: source.sourceData)
        let restored = try JSONDecoder().decode(BookBackup.self, from: JSONEncoder().encode(backup))
        try restored.validate()
        XCTAssertEqual(restored.version, 2)
        XCTAssertEqual(restored.originalData, source.sourceData)
        XCTAssertEqual(try Data(contentsOf: repository.sourceURL(for: book)), source.sourceData)
        XCTAssertFalse(FileManager.default.fileExists(atPath: repository.pdfURL(for: book.id).path))
        XCTAssertEqual(try repository.load().books.first?.pages.map(\.text), book.pages.map(\.text))
        XCTAssertFalse(try XCTUnwrap(repository.load().books.first).needsReprocessing)
    }
    func testCancellationStopsImportBeforePreparing() async throws {
        let data = try Data(contentsOf: fixture("structured"))
        let task = Task { try await EPUBImporter.extract(data: data, filename: "structured.epub") }
        task.cancel()
        do { _ = try await task.value; XCTFail("Ignored cancellation") }
        catch { XCTAssertTrue(error is CancellationError) }
    }

    func testHardBreakSurvivesPersistenceAndPassageLayoutWithoutChangingPDFWrapping() async throws {
        let imported = try await imported().book
        let book = ReaderPipeline.loaded(try JSONDecoder().decode(Book.self, from: JSONEncoder().encode(imported)))
        let document = book.readerCache.get(book)
        let block = try XCTUnwrap(document.passage.blocks.first { document.tokens[$0.words].contains { $0.word.text == "Another" } })
        let words = Array(document.tokens[block.words])
        let layout = PassageLayout(tokens: words, widths: Array(repeating: 1, count: words.count), availableWidth: 1000)
        XCTAssertEqual(layout.rows.map { words[$0.words].map(\.word.text).joined(separator: " ") }, ["Another paragraph.", "A new line."])
        let pdf = ReaderPipeline.prepare(makeBook(["Another paragraph.\nA new line."]))
        let pdfWords = pdf.readerCache.get(pdf).tokens
        XCTAssertEqual(PassageLayout(tokens: pdfWords, widths: Array(repeating: 1, count: pdfWords.count), availableWidth: 1000).rows.count, 1)
    }

    func testTextCorrectionDoesNotCustomizeChaptersAndReprocessingRefreshesThem() async throws {
        let original = try await imported().book
        var edited = original
        edited.editText(on: 0, to: "Preface.\n\n" + original.pages[0].text)
        edited = ReaderPipeline.update(from: original, to: edited)
        XCTAssertNotEqual(edited.chapters[1].utf16Offset, original.chapters[1].utf16Offset)
        XCTAssertNotEqual(edited.chaptersCustomized, true)
        var converted = original
        converted.chapters[0].title = "Refreshed navigation"
        XCTAssertEqual(ReaderPipeline.reprocess(edited, converted: converted).chapters[0].title, "Refreshed navigation")
        var reverted = original
        reverted.editText(on: 0, to: "x" + original.pages[0].text)
        reverted.editText(on: 0, to: original.pages[0].text)
        XCTAssertFalse(ReaderPipeline.update(from: original, to: reverted).needsReprocessing)
    }

    func testRestoreRecoversHeadingsAndRepeatedChapterAnchors() async throws {
        let original = try await imported().book
        var edited = original
        edited.editText(on: 0, to: "Replacement content.")
        edited = ReaderPipeline.update(from: original, to: edited)
        XCTAssertTrue(edited.pages[0].headings?.isEmpty == true)
        let restored = try await BookImporter.restorePage(url: fixture("structured"), book: edited, index: 0)
        XCTAssertEqual(restored.pages[0].text, original.pages[0].text)
        XCTAssertEqual(restored.pages[0].headings, original.pages[0].headings)
        XCTAssertEqual(restored.chapters.map(\.utf16Offset), original.chapters.map(\.utf16Offset))
        XCTAssertFalse(restored.pages[0].isEdited)
        XCTAssertEqual(ReaderPipeline.reprocess(restored, converted: original).pages[0].headings, original.pages[0].headings)
    }

    func testRestoreKeepsChapterRemovalsAndManualEntries() async throws {
        let original = try await imported().book
        var edited = original
        edited.chapters.remove(at: 1) // Remove the first of two equally named chapters.
        let manual = BookChapter(id: UUID(), title: "My note", pageIndex: 0, level: 0, source: "Added by you", utf16Offset: 0)
        edited.chapters.append(manual); edited.chaptersCustomized = true
        edited.editText(on: 0, to: "Replacement content.")
        edited = ReaderPipeline.update(from: original, to: edited)
        let restored = try await BookImporter.restorePage(url: fixture("structured"), book: edited, index: 0)
        XCTAssertEqual(restored.chapters.map(\.id), edited.chapters.map(\.id))
        XCTAssertEqual(restored.chapters.first { $0.id == original.chapters[2].id }?.utf16Offset, original.chapters[2].utf16Offset)
        XCTAssertNil(restored.chapters.first { $0.id == original.chapters[1].id })
        XCTAssertTrue(restored.chapters.contains { $0.id == manual.id })
    }

    @MainActor func testRapidEditsAndFlushPersistLatestPreparedSnapshot() async throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: root) }
        let source = try await imported(), repository = BookRepository(root: root)
        try repository.insert(source.book, sourceData: source.sourceData)
        let store = LibraryStore(repository: repository)
        await store.load()
        let book = source.book
        store.editPage(bookID: book.id, pageID: book.pages[0].id, text: "First draft")
        store.flush()
        store.editPage(bookID: book.id, pageID: book.pages[0].id, text: "Final draft\nwith a line break")
        store.update(book.id) { $0.pages[0].isReviewed = true }
        await store.waitForSaves()
        let saved = try XCTUnwrap(repository.load().books.first)
        XCTAssertFalse(saved.needsReprocessing)
        XCTAssertFalse(store.isPreparing(book.id))
        XCTAssertEqual(saved.pages[0].text, "Final draft\nwith a line break")
        XCTAssertTrue(saved.pages[0].isReviewed)
        XCTAssertTrue(saved.pages[0].words.contains { $0.endsLine })
        XCTAssertEqual(saved.readerCache.get(saved).tokens, store.book(book.id)?.readerCache.get(store.book(book.id)!).tokens)
    }
}
