import XCTest
@testable import Still

final class BookProgressTests: XCTestCase {
    func testChaptersWithinOneEpubFileHaveDistinctProgressAndSurviveResume() async throws {
        let url = try XCTUnwrap(Bundle(for: Self.self).url(forResource: "structured", withExtension: "epub", subdirectory: "epub"))
        var book = try await EPUBImporter.extract(data: Data(contentsOf: url), filename: "structured.epub").book
        let progress = BookProgress(book), first = book.chapters[1], second = book.chapters[2]
        XCTAssertEqual(first.pageIndex, second.pageIndex)
        XCTAssertLessThan(try XCTUnwrap(progress.percent(page: first.pageIndex, offset: first.utf16Offset!)), try XCTUnwrap(progress.percent(page: second.pageIndex, offset: second.utf16Offset!)))
        book.position = ReadingPosition(pageID: book.pages[second.pageIndex].id, utf16Offset: second.utf16Offset!)
        XCTAssertEqual(progress.chapterTitle(page: second.pageIndex, offset: second.utf16Offset!), "Repeat")
        XCTAssertEqual(BookProgress(book).savedStatus(), "Repeat · \(progress.percent(page: second.pageIndex, offset: second.utf16Offset!)!)% read")
        XCTAssertFalse(progress.textLabel(page: 0).contains("Section"))
    }
    func testProgressCountsWordsRatherThanUnevenFilesAndMatchesReader() {
        var book = makeBook(["one two three four five six seven eight nine", "ten"])
        book.sourceFormat = "epub"; book = ReaderPipeline.prepare(book)
        let progress = BookProgress(book), tokens = book.readerCache.get(book).tokens
        XCTAssertEqual(progress.percent(page: 1), 90)
        for token in tokens { XCTAssertEqual(progress.percent(page: token.pageIndex, offset: token.word.offset), token.id * 100 / tokens.count) }
    }
    func testTrimsAndExcludedTextKeepProgressAndCurrentChapterAccurate() {
        var book = makeBook(["one two three four", "ignored words", "five six"])
        book.sourceFormat = "epub"
        book.chapters = [BookChapter(id: UUID(), title: "Opening", pageIndex: 0, level: 0, source: "EPUB navigation", utf16Offset: 0)]
        book = ReaderPipeline.prepare(book)
        book.setReadingStart(ReadingPosition(pageID: book.pages[0].id, utf16Offset: 5))
        book.pages[1].isIncluded = false
        let progress = BookProgress(book), tokens = book.readerCache.get(book).tokens
        XCTAssertEqual(progress.wordCount, tokens.count)
        for token in tokens { XCTAssertEqual(progress.percent(page: token.pageIndex, offset: token.word.offset), token.id * 100 / tokens.count) }
        XCTAssertEqual(progress.chapterTitle(page: 0, offset: 8), "Opening")
        XCTAssertEqual(progress.percent(page: 0, offset: 5), 0)
    }
    func testUnfinishedLastWordAndMissingIndexesDoNotClaimCompletion() {
        var book = ReaderPipeline.prepare(makeBook(["one two"]))
        XCTAssertEqual(BookProgress(book).savedStatus(), "Not started")
        book.position = ReadingPosition(pageID: book.pages[0].id, utf16Offset: 4)
        XCTAssertEqual(BookProgress(book).savedStatus(), "50% read")
        book.position?.finished = true
        XCTAssertEqual(BookProgress(book).savedStatus(), "Finished · 100% read")
        book.pages[0].wordIndex = nil
        XCTAssertNil(BookProgress(book).percent(page: 0))
        XCTAssertNil(BookProgress(ReaderPipeline.prepare(makeBook([""]))).percent(page: 0))
        XCTAssertNil(BookProgress(book).percent(page: -1))
    }
}
