import XCTest
import PDFKit
@testable import Still

final class ModelTests: XCTestCase {
    func testTokenizationPreservesUnicodePunctuationAndSourceOffsets() {
        let text = "Hello,  naïve 🌿\n\n世界!"
        let words = Tokenizer.words(in: text)
        XCTAssertEqual(words.map(\.text), ["Hello,", "naïve", "🌿", "世界!"])
        for word in words {
            XCTAssertEqual((text as NSString).substring(with: NSRange(location: word.offset, length: word.length)), word.text)
        }
        XCTAssertTrue(words[2].endsParagraph)
        XCTAssertFalse(words[0].endsParagraph)
    }

    func testPageBoundariesAndExclusionsSurviveTokenization() {
        var book = makeBook(["First page.", "A cover", "Last page."])
        book.pages[1].isIncluded = false
        let tokens = Tokenizer.tokens(for: book)
        XCTAssertEqual(tokens.map(\.word.text), ["First", "page.", "Last", "page."])
        XCTAssertEqual(tokens.map(\.pageIndex), [0, 0, 2, 2])
        XCTAssertEqual(tokens.map(\.id), [0, 1, 2, 3])
    }

    func testSavedPositionUsesPageAndOffsetAfterPreviousPageEdit() {
        var book = makeBook(["One two", "Three four five"])
        let position = ReadingPosition(pageID: book.pages[1].id, utf16Offset: 6)
        book.pages[0].text = "An entirely different number of words on this page"
        let tokens = Tokenizer.tokens(for: book)
        let index = Tokenizer.index(for: position, in: tokens)
        XCTAssertEqual(tokens[index].word.text, "four")
        XCTAssertEqual(tokens[index].pageID, book.pages[1].id)
    }

    func testPositionClampsToEndOfShortenedPage() {
        let book = makeBook(["One two"])
        let position = ReadingPosition(pageID: book.pages[0].id, utf16Offset: 500)
        XCTAssertEqual(Tokenizer.index(for: position, in: Tokenizer.tokens(for: book)), 1)
    }

    func testReviewGateDoesNotSilentlySkipBlankIncludedPages() {
        let book = makeBook(["Before", "", "After"])
        XCTAssertEqual(Tokenizer.blockingPage(in: book, from: 0, through: 2), 1)
    }

    func testExcludedUnreviewedPagesDoNotBlock() {
        var book = makeBook(["Before", "", "After"])
        book.pages[1].isIncluded = false
        XCTAssertNil(Tokenizer.blockingPage(in: book, from: 0, through: 2))
    }

    func testNaturalPausesAndClampedSpeed() {
        let plain = TextWord(text: "word", offset: 0, length: 4, endsParagraph: false)
        let sentence = TextWord(text: "word.”", offset: 0, length: 6, endsParagraph: false)
        XCTAssertEqual(Tokenizer.duration(for: plain, wordsPerMinute: 300, naturalPauses: false), 0.2, accuracy: 0.001)
        XCTAssertGreaterThan(Tokenizer.duration(for: sentence, wordsPerMinute: 300, naturalPauses: true), 0.2)
        XCTAssertEqual(Tokenizer.duration(for: plain, wordsPerMinute: 0, naturalPauses: false), 1)
    }

    func testChapterAtPageUsesDeepestMatchingBookmark() {
        var book = makeBook(["one", "two", "three"])
        book.chapters = [BookChapter(id: UUID(), title: "Chapter", pageIndex: 0, level: 0, source: "PDF bookmark"), BookChapter(id: UUID(), title: "Section", pageIndex: 1, level: 1, source: "PDF bookmark")]
        XCTAssertEqual(book.chapter(at: 2)?.title, "Section")
    }
}

final class ImportAndPersistenceTests: XCTestCase {
    func testRealPDFImportPreservesPagesMetadataAndBookmarks() async throws {
        let result = try await PDFImporter.extract(data: SampleBook.pdfData(), filename: "sample.pdf") { _, _ in }
        XCTAssertEqual(result.book.pages.count, 3)
        XCTAssertEqual(result.book.title, "The art of attention")
        XCTAssertEqual(result.book.chapters.map(\.pageIndex), [0, 1, 2])
        XCTAssertTrue(result.book.pages[0].extractedText.contains("particular kind of quiet"))
        XCTAssertTrue(result.book.pages.allSatisfy { !$0.isReviewed && $0.text == $0.processedText && !$0.isEdited })
    }

    func testInvalidPDFReportsError() async {
        do {
            _ = try await PDFImporter.extract(data: Data("not a PDF".utf8), filename: "bad.pdf") { _, _ in }
            XCTFail("Expected invalid PDF error")
        } catch { XCTAssertTrue(error is PDFImportError) }
    }

    func testCancelledImportDoesNotProducePartialBook() async {
        let data = SampleBook.pdfData()
        let task = Task { try await PDFImporter.extract(data: data, filename: "test.pdf") { _, _ in } }
        task.cancel()
        do { _ = try await task.value; XCTFail("Expected cancellation") }
        catch { XCTAssertTrue(error is CancellationError) }
    }

    func testDiskRoundTripPreservesOriginalCorrectionsReviewAndPosition() throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: root) }
        let repository = BookRepository(root: root)
        var book = makeBook(["Original words"])
        book.pages[0].text = "Corrected words"
        book.position = ReadingPosition(pageID: book.pages[0].id, utf16Offset: 10)
        let pdf = SampleBook.pdfData()
        try repository.insert(book, pdfData: pdf)
        let loaded = try XCTUnwrap(repository.load().books.first)
        XCTAssertEqual(loaded.pages[0].extractedText, "Original words")
        XCTAssertEqual(loaded.pages[0].text, "Corrected words")
        XCTAssertTrue(loaded.pages[0].isReviewed)
        XCTAssertEqual(loaded.position, book.position)
        XCTAssertEqual(try Data(contentsOf: repository.pdfURL(for: book.id)), pdf)
    }

    func testUnreadableBookIsReportedAndPreserved() throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: root) }
        let repository = BookRepository(root: root)
        let folder = repository.directory(for: UUID())
        try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        let broken = folder.appendingPathComponent("book.json")
        try Data("broken".utf8).write(to: broken)
        let result = try repository.load()
        XCTAssertEqual(result.unreadableCount, 1)
        XCTAssertTrue(FileManager.default.fileExists(atPath: broken.path))
    }

    func testBackupRoundTripAndInvalidChapterRejection() throws {
        var backup = BookBackup(book: makeBook(["one"]), pdfData: SampleBook.pdfData())
        let decoded = try JSONDecoder().decode(BookBackup.self, from: JSONEncoder().encode(backup))
        try decoded.validate()
        XCTAssertEqual(decoded.book.pages.first?.text, "one")
        backup.book.chapters = [BookChapter(id: UUID(), title: "Bad", pageIndex: 10, level: 0, source: "test")]
        XCTAssertThrowsError(try backup.validate())
    }
}

@MainActor
final class PlaybackTests: XCTestCase {
    func testContinuousSpeedChangesDoNotPostponeAdvancement() async throws {
        let engine = ReaderEngine(book: makeBook([(0...30).map(String.init).joined(separator: " ")]))
        engine.wordsPerMinute = 600
        engine.naturalPauses = false
        engine.play()
        for step in 0..<20 {
            try await Task.sleep(for: .milliseconds(25))
            engine.wordsPerMinute = 600 + Double(step)
        }
        engine.pause()
        XCTAssertGreaterThanOrEqual(engine.index, 3)
    }

    func testPlayPauseAndCheckpoint() async throws {
        let engine = ReaderEngine(book: makeBook(["one two three four five"]))
        engine.wordsPerMinute = 900
        engine.naturalPauses = false
        var saved: ReadingPosition?
        engine.onCheckpoint = { saved = $0 }
        engine.play()
        try await Task.sleep(for: .milliseconds(170))
        engine.pause()
        let stopped = engine.index
        XCTAssertGreaterThan(stopped, 0)
        try await Task.sleep(for: .milliseconds(130))
        XCTAssertEqual(engine.index, stopped)
        XCTAssertNotNil(saved)
    }

    func testPlaybackBlocksAtUnreviewedPage() async throws {
        var book = makeBook(["one", "two"])
        book.pages[1].isReviewed = false
        let engine = ReaderEngine(book: book)
        engine.wordsPerMinute = 900
        engine.allowUnreviewed = false
        engine.play()
        try await Task.sleep(for: .milliseconds(160))
        XCTAssertFalse(engine.isPlaying)
        XCTAssertEqual(engine.blockedPage, 1)
        XCTAssertEqual(engine.index, 0)
    }

    func testPlaybackFinishesAfterShowingLastWord() async throws {
        let engine = ReaderEngine(book: makeBook(["one"]))
        engine.wordsPerMinute = 900
        engine.play()
        XCTAssertFalse(engine.isFinished)
        try await Task.sleep(for: .milliseconds(170))
        XCTAssertTrue(engine.isFinished)
        XCTAssertFalse(engine.isPlaying)
        XCTAssertEqual(engine.progress, 1)
    }

    func testBlockedBlankPageIsStillBlockedAfterReopening() async throws {
        var book = makeBook(["before", "", "after"])
        let engine = ReaderEngine(book: book)
        engine.wordsPerMinute = 900
        engine.onCheckpoint = { book.position = $0 }
        engine.allowUnreviewed = false
        engine.play()
        try await Task.sleep(for: .milliseconds(160))
        XCTAssertEqual(engine.blockedPage, 1)
        XCTAssertEqual(book.position?.pageID, book.pages[1].id)
        let reopened = ReaderEngine(book: book)
        reopened.allowUnreviewed = false
        reopened.play()
        XCTAssertFalse(reopened.isPlaying)
        XCTAssertEqual(reopened.blockedPage, 1)
    }

    func testTrailingBlankPageIsStillBlockedAfterReopening() async throws {
        var book = makeBook(["before", ""])
        let engine = ReaderEngine(book: book)
        engine.wordsPerMinute = 900
        engine.onCheckpoint = { book.position = $0 }
        engine.allowUnreviewed = false
        engine.play()
        try await Task.sleep(for: .milliseconds(160))
        XCTAssertEqual(engine.blockedPage, 1)
        let reopened = ReaderEngine(book: book)
        reopened.allowUnreviewed = false
        reopened.play()
        XCTAssertFalse(reopened.isPlaying)
        XCTAssertEqual(reopened.blockedPage, 1)
    }

    func testEmptyLeadingPageRequiresReview() {
        let engine = ReaderEngine(book: makeBook(["", "one"]))
        engine.allowUnreviewed = false
        engine.play()
        XCTAssertEqual(engine.blockedPage, 0)
        XCTAssertFalse(engine.isPlaying)
    }

    func testEditingInvalidatesApprovalWithoutChangingExtractedText() async {
        let store = LibraryStore(repository: BookRepository(root: FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)))
        let book = makeBook(["before"])
        store.books = [book]
        store.editPage(bookID: book.id, pageID: book.pages[0].id, text: "after")
        XCTAssertFalse(store.books[0].pages[0].isReviewed)
        XCTAssertEqual(store.books[0].pages[0].extractedText, "before")
        store.flush()
        try? FileManager.default.removeItem(at: store.repository.root)
    }
}

func makeBook(_ texts: [String]) -> Book {
    Book(id: UUID(), title: "Test book", author: "", originalFilename: "test.pdf", importedAt: Date(),
         pages: texts.enumerated().map { index, text in BookPage(id: UUID(), number: index + 1, label: String(index + 1), extractedText: text, text: text, isReviewed: true, isIncluded: true) }, chapters: [])
}

final class ReadingStartTests: XCTestCase {
    func testTrimmingWholePagesAndPartialPagePreservesOriginalData() throws {
        var book = makeBook(["Cover and contents", "Heading 🌿 Begin the story", "Next chapter"])
        let original = book.pages
        let offset = (book.pages[1].text as NSString).range(of: "Begin").location
        book.setReadingStart(ReadingPosition(pageID: book.pages[1].id, utf16Offset: offset))
        XCTAssertEqual(Tokenizer.tokens(for: book).map(\.word.text), ["Begin", "the", "story", "Next", "chapter"])
        XCTAssertEqual(book.wordCount, 5)
        XCTAssertEqual(book.includedPages.count, 2)
        XCTAssertEqual(book.pages.map(\.text), original.map(\.text))
        XCTAssertEqual(book.pages.map(\.extractedText), original.map(\.extractedText))
        XCTAssertEqual(book.pages.map(\.isReviewed), original.map(\.isReviewed))
        let restored = try JSONDecoder().decode(Book.self, from: JSONEncoder().encode(book))
        XCTAssertEqual(restored.readingStart, book.readingStart)
        XCTAssertEqual(Tokenizer.tokens(for: restored).first?.word.text, "Begin")
    }

    func testOldLibraryWithoutReadingStartStillDecodes() throws {
        let book = makeBook(["Existing imported book"])
        var json = try XCTUnwrap(JSONSerialization.jsonObject(with: JSONEncoder().encode(book)) as? [String: Any])
        json.removeValue(forKey: "readingStart")
        let decoded = try JSONDecoder().decode(Book.self, from: JSONSerialization.data(withJSONObject: json))
        XCTAssertNil(decoded.readingStart)
        XCTAssertEqual(decoded.pages[0].text, "Existing imported book")
        XCTAssertEqual(decoded.wordCount, 3)
    }

    func testRestoreBeginningKeepsManualExclusions() {
        var book = makeBook(["Cover", "Contents", "Story begins"])
        book.pages[1].isIncluded = false
        book.setReadingStart(ReadingPosition(pageID: book.pages[2].id, utf16Offset: 0))
        book.setReadingStart(nil)
        XCTAssertEqual(Tokenizer.tokens(for: book).map(\.word.text), ["Cover", "Story", "begins"])
        XCTAssertNil(book.position)
        XCTAssertFalse(book.pages[1].isIncluded)
    }

    func testStartFollowsEditsBeforeItsWord() {
        var book = makeBook(["Heading 🌿 Begin the story"])
        let start = (book.pages[0].text as NSString).range(of: "Begin").location
        book.setReadingStart(ReadingPosition(pageID: book.pages[0].id, utf16Offset: start))
        book.editText(on: 0, to: "An expanded heading 🌿 Begin the story")
        XCTAssertEqual(Tokenizer.tokens(for: book).first?.word.text, "Begin")
        book.editText(on: 0, to: "Begin the story")
        XCTAssertEqual(Tokenizer.tokens(for: book).first?.word.text, "Begin")
        XCTAssertFalse(book.pages[0].isReviewed)
        XCTAssertEqual(book.pages[0].extractedText, "Heading 🌿 Begin the story")
    }

    func testExcludingAllTextIsNotReadyToRead() {
        var book = makeBook(["Only page"])
        book.pages[0].isIncluded = false
        XCTAssertFalse(book.canRead)
        XCTAssertEqual(book.wordCount, 0)
    }
}

@MainActor
final class ScrubbingTests: XCTestCase {
    func testScrubbingPausesPlaybackAndSavesOnlyOnRelease() async throws {
        var book = makeBook(["zero one two three four five six"])
        let engine = ReaderEngine(book: book)
        engine.wordsPerMinute = 900
        engine.naturalPauses = false
        var checkpoints = 0
        engine.onCheckpoint = { book.position = $0; checkpoints += 1 }
        engine.play()
        engine.beginScrubbing()
        engine.scrub(by: 4)
        engine.beginScrubbing() // Repeated gesture updates must not move the origin.
        engine.scrub(by: 2)
        XCTAssertFalse(engine.isPlaying)
        XCTAssertEqual(engine.current?.word.text, "two")
        XCTAssertEqual(checkpoints, 0)
        try await Task.sleep(for: .milliseconds(160))
        XCTAssertEqual(engine.current?.word.text, "two")
        engine.endScrubbing()
        engine.endScrubbing()
        XCTAssertEqual(checkpoints, 1)
        let reopened = ReaderEngine(book: book)
        XCTAssertEqual(reopened.current?.word.text, "two")
        // Resume gives the chosen word a full interval before advancing.
        engine.wordsPerMinute = 60
        engine.play()
        XCTAssertEqual(engine.current?.word.text, "two")
        try await Task.sleep(for: .milliseconds(100))
        XCTAssertEqual(engine.current?.word.text, "two")
        XCTAssertTrue(engine.isPlaying)
        engine.pause()
    }

    func testScrubbingAcrossPagesRespectsTrimExclusionsAndUnicodeOffsets() {
        var book = makeBook(["Cover", "Heading 🌿 Start here", "Excluded", "Later words"])
        book.pages[2].isIncluded = false
        let offset = (book.pages[1].text as NSString).range(of: "Start").location
        book.setReadingStart(ReadingPosition(pageID: book.pages[1].id, utf16Offset: offset))
        let engine = ReaderEngine(book: book)
        engine.onCheckpoint = { book.position = $0 }
        engine.beginScrubbing()
        engine.scrub(by: -100)
        XCTAssertEqual(engine.current?.word.text, "Start")
        engine.scrub(by: 100)
        XCTAssertEqual(engine.current?.word.text, "words")
        XCTAssertEqual(engine.current?.pageIndex, 3)
        engine.scrub(by: 1)
        XCTAssertEqual(engine.current?.word.text, "here")
        engine.endScrubbing()
        XCTAssertEqual(book.position?.pageID, book.pages[1].id)
        XCTAssertEqual(book.position?.utf16Offset, (book.pages[1].text as NSString).range(of: "here").location)
        XCTAssertEqual(ReaderEngine(book: book).index, engine.index)
    }

    func testPauseDuringScrubSavesSelectionAndClearsGestureOrigin() {
        var book = makeBook(["one two three four"])
        let engine = ReaderEngine(book: book)
        engine.onCheckpoint = { book.position = $0 }
        engine.beginScrubbing()
        engine.scrub(by: 2)
        engine.pause() // The scene can become inactive before the drag ends.
        XCTAssertFalse(engine.isScrubbing)
        XCTAssertEqual(ReaderEngine(book: book).current?.word.text, "three")
        engine.scrub(by: -2)
        XCTAssertEqual(engine.current?.word.text, "three")
        engine.beginScrubbing()
        engine.scrub(by: -1)
        engine.endScrubbing()
        XCTAssertEqual(engine.current?.word.text, "two")
    }

    func testScrubbingFromFinishedBookResumesSelectionInsteadOfRestarting() async throws {
        let engine = ReaderEngine(book: makeBook(["first second last"]))
        engine.wordsPerMinute = 900
        engine.naturalPauses = false
        engine.seek(2)
        engine.play()
        try await Task.sleep(for: .milliseconds(180))
        XCTAssertTrue(engine.isFinished)
        engine.beginScrubbing()
        engine.scrub(by: -1)
        engine.endScrubbing()
        XCTAssertFalse(engine.isFinished)
        engine.play()
        XCTAssertEqual(engine.current?.word.text, "second")
        engine.pause()
    }

    func testScrubbingStillHonorsReviewPreferenceOnResume() {
        var book = makeBook(["Reviewed", "Needs review"])
        book.pages[1].isReviewed = false
        let engine = ReaderEngine(book: book)
        engine.allowUnreviewed = false
        engine.beginScrubbing()
        engine.scrub(by: 1)
        engine.endScrubbing()
        engine.play()
        XCTAssertFalse(engine.isPlaying)
        XCTAssertEqual(engine.blockedPage, 1)
    }

    func testEmptyBookCannotStartScrubbing() {
        let engine = ReaderEngine(book: makeBook([""]))
        engine.beginScrubbing()
        engine.scrub(by: 100)
        engine.endScrubbing()
        XCTAssertFalse(engine.isScrubbing)
        XCTAssertNil(engine.current)
        XCTAssertTrue(engine.context.isEmpty)
    }
}

@MainActor
final class ImmediateReadingTests: XCTestCase {
    func testUncheckedPagesReadByDefaultWithoutFalselyApprovingThem() async throws {
        var book = makeBook(["First", "", "Last"])
        for index in book.pages.indices { book.pages[index].isReviewed = false }
        let engine = ReaderEngine(book: book)
        engine.wordsPerMinute = 900
        engine.naturalPauses = false
        engine.play()
        XCTAssertTrue(engine.isPlaying)
        try await Task.sleep(for: .milliseconds(250))
        XCTAssertTrue(engine.isFinished)
        XCTAssertNil(engine.blockedPage)
        XCTAssertTrue(engine.book.pages.allSatisfy { !$0.isReviewed })
    }

    func testReplayAndSeekingCannotReintroduceTrimmedBeginning() async throws {
        var book = makeBook(["Do not read", "Heading Start here"])
        book.pages[0].isReviewed = false
        book.setReadingStart(ReadingPosition(pageID: book.pages[1].id, utf16Offset: 8))
        let engine = ReaderEngine(book: book)
        engine.allowUnreviewed = false
        engine.wordsPerMinute = 900
        engine.naturalPauses = false
        engine.seekPage(0)
        XCTAssertEqual(engine.current?.word.text, "Start")
        engine.play()
        XCTAssertTrue(engine.isPlaying)
        try await Task.sleep(for: .milliseconds(250))
        XCTAssertTrue(engine.isFinished)
        engine.play()
        XCTAssertEqual(engine.current?.word.text, "Start")
        XCTAssertNil(engine.blockedPage)
        engine.pause()
    }
}
