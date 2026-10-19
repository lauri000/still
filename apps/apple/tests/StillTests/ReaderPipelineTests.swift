import XCTest
@testable import Still

final class ReaderPipelineTests: XCTestCase {
    private func source() -> Book {
        var book = makeBook(["Chapter One\nCafé 🌿 one two.\n\nNext paragraph.", "Chapter Two\nlast page"])
        book.chapters = [BookChapter(id: UUID(), title: "Chapter One", pageIndex: 0, level: 0, source: "Manual"),
                         BookChapter(id: UUID(), title: "Chapter Two", pageIndex: 1, level: 0, source: "Manual")]
        return book
    }
    func testPersistedOutputRestoresExactReaderAndOmitsIntermediateText() throws {
        let original = source(), prepared = ReaderPipeline.prepare(original)
        let data = try JSONEncoder().encode(prepared)
        let json = String(decoding: data, as: UTF8.self)
        XCTAssertFalse(json.contains("extractedText")); XCTAssertFalse(json.contains("processedText"))
        let restored = ReaderPipeline.loaded(try JSONDecoder().decode(Book.self, from: data))
        XCTAssertFalse(restored.needsReprocessing)
        let expected = ReaderDocument(book: original), actual = restored.readerCache.get(restored)
        XCTAssertEqual(expected.tokens, actual.tokens)
        XCTAssertEqual(expected.structure.headings, actual.structure.headings)
        XCTAssertEqual(expected.structure.chapterEnds, actual.structure.chapterEnds)
        XCTAssertEqual(expected.passage.blocks.map(\.words), actual.passage.blocks.map(\.words))
        var resumed = restored; resumed.position = actual.tokens.last?.position
        XCTAssertTrue(resumed.readerCache === restored.readerCache)
        XCTAssertEqual(resumed.readerCache.get(resumed).tokens, actual.tokens)
    }
    func testFullPipelineIgnoresIndexesFromEarlierText() {
        var changed = ReaderPipeline.prepare(source())
        for index in changed.pages.indices { changed.pages[index].text = "short" }
        let prepared = ReaderPipeline.prepare(changed)
        XCTAssertEqual(prepared.readerCache.get(prepared).tokens.map { $0.word.text }, ["short", "short"])
    }
    func testEditsTrimsAndExclusionsKeepIndexAndHeadingsConsistent() {
        let prepared = ReaderPipeline.prepare(source())
        var edited = prepared; edited.editText(on: 0, to: "Different heading\nCafé 🌿 one two.")
        edited = ReaderPipeline.update(from: prepared, to: edited)
        XCTAssertTrue(edited.pages[0].isEdited)
        XCTAssertNil(edited.readerCache.get(edited).structure.destination(title: "Chapter One", page: 0))
        XCTAssertEqual(edited.pages[1].wordIndex, prepared.pages[1].wordIndex)
        var trimmed = prepared; trimmed.setReadingStart(ReadingPosition(pageID: prepared.pages[0].id, utf16Offset: 9))
        XCTAssertNil(trimmed.readerCache.get(trimmed).structure.destination(title: "Chapter One", page: 0))
        trimmed.pages[1].isIncluded = false
        XCTAssertTrue(trimmed.readerCache.get(trimmed).structure.chapterEnds.isEmpty)
        XCTAssertTrue(trimmed.readerCache.get(trimmed).tokens.allSatisfy { $0.pageIndex == 0 })
    }
    func testOldFutureAndCorruptOutputRequireExplicitReprocessing() {
        XCTAssertTrue(ReaderPipeline.loaded(source()).needsReprocessing)
        var future = ReaderPipeline.prepare(source()); future.pipelineVersion = 999
        XCTAssertTrue(ReaderPipeline.loaded(future).needsReprocessing)
        var corrupt = ReaderPipeline.prepare(source()); corrupt.pages[0].wordIndex = [Int.max, 5, 0]
        XCTAssertTrue(ReaderPipeline.loaded(corrupt).needsReprocessing)
    }
    func testReprocessingKeepsSelectedWordAcrossSeveralTextChanges() {
        var old = makeBook(["Old header alpha beta target gamma old footer"])
        old.position = ReadingPosition(pageID: old.pages[0].id, utf16Offset: (old.pages[0].text as NSString).range(of: "target").location)
        let updated = ReaderPipeline.reprocess(old, converted: makeBook(["New longer heading alpha beta target gamma new ending"]))
        let document = updated.readerCache.get(updated)
        XCTAssertEqual(document.tokens[document.index(updated.position)].word.text, "target")
    }
    func testReprocessingPreservesManualStateAndUpdatesDerivedText() throws {
        var original = source(); original.editText(on: 1, to: "My correction 🌿")
        original.pages[0].isIncluded = false
        original.setReadingStart(ReadingPosition(pageID: original.pages[1].id, utf16Offset: 3))
        let updated = ReaderPipeline.reprocess(original, converted: makeBook(["New pipeline output", "Other derived text"]))
        XCTAssertEqual(original.id, updated.id); XCTAssertEqual(original.pages.map(\.id), updated.pages.map(\.id))
        XCTAssertEqual(updated.pages[0].text, "New pipeline output"); XCTAssertEqual(updated.pages[1].text, "My correction 🌿")
        XCTAssertEqual(original.position, updated.position); XCTAssertEqual(original.readingStart, updated.readingStart)
        XCTAssertFalse(updated.pages[0].isIncluded); XCTAssertFalse(updated.needsReprocessing)
        let restored = ReaderPipeline.loaded(try JSONDecoder().decode(Book.self, from: JSONEncoder().encode(updated)))
        XCTAssertTrue(restored.pages[1].isEdited); XCTAssertFalse(restored.pages[0].isEdited)
    }
}
