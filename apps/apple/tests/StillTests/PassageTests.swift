import XCTest
@testable import Still

final class PassageLayoutTests: XCTestCase {
    func testWrappingPreservesWordsParagraphsPagesAndNarrowLayouts() {
        let tokens = Tokenizer.tokens(for: makeBook(["one two three four.\n\nfive six", "seven"]))
        let layout = PassageLayout(tokens: tokens, widths: Array(repeating: 40, count: tokens.count), availableWidth: 160)
        XCTAssertEqual(layout.rows.map(\.words), [0..<3, 3..<4, 4..<6, 6..<7])
        XCTAssertEqual(layout.rows.flatMap(\.words), Array(tokens.indices))
        XCTAssertTrue(PassageLayout(tokens: tokens, widths: Array(repeating: 900, count: tokens.count), availableWidth: 25).rows.allSatisfy { $0.words.count == 1 })
        XCTAssertTrue(PassageLayout(tokens: [], widths: [], availableWidth: 0).rows.isEmpty)
    }
    func testDocumentIndexesParagraphsPagesAndHeadings() {
        var b = makeBook(["one two.\n\nChapter One\nbody words", "another page"])
        b.chapters = [BookChapter(id: UUID(), title: "Chapter One", pageIndex: 0, level: 0, source: "Manual")]
        let tokens = Tokenizer.tokens(for: b), doc = PassageDocument(tokens: tokens, structure: ReadingStructure(book: b, tokens: tokens))
        XCTAssertEqual(doc.blocks.map(\.words), [0..<2, 2..<4, 4..<6, 6..<8])
        for index in tokens.indices { XCTAssertTrue(doc.blocks[doc.block(containing: index)].words.contains(index)) }
    }
    func testLargeBookAndTrimKeepSourcePositions() {
        let text = (1...200).map { "word\($0)" }.joined(separator: " ")
        var b = makeBook(Array(repeating: text, count: 300))
        let tokens = Tokenizer.tokens(for: b), doc = PassageDocument(tokens: tokens, structure: ReadingStructure(book: b, tokens: tokens))
        XCTAssertEqual(doc.blocks.count, 300)
        XCTAssertEqual(doc.blocks.flatMap(\.words), Array(tokens.indices))
        for index in [0, 19_999, 20_000, tokens.count - 1] { XCTAssertTrue(doc.blocks[doc.block(containing: index)].words.contains(index)) }
        b.setReadingStart(tokens[201].position)
        let kept = Tokenizer.tokens(for: b), trimmed = PassageDocument(tokens: kept, structure: ReadingStructure(book: b, tokens: kept))
        XCTAssertEqual(kept.first?.position, tokens[201].position)
        XCTAssertEqual(trimmed.blocks.flatMap(\.words), Array(kept.indices))
    }
    func testEmptyDocument() {
        let b = makeBook([""])
        XCTAssertTrue(PassageDocument(tokens: [], structure: ReadingStructure(book: b, tokens: [])).blocks.isEmpty)
    }
}

@MainActor final class PassagePlaybackTests: XCTestCase {
    func testManualScrollingCannotPlayAndModeSwitchPreservesExactWord() async throws {
        let engine = ReaderEngine(book: makeBook(["one two. three four"]))
        var writes: [ReadingPosition] = []
        engine.onCheckpoint = { writes.append($0) }
        engine.seek(1); engine.passageMode = true
        let before = writes.count
        engine.play(); try await Task.sleep(for: .milliseconds(120))
        XCTAssertFalse(engine.isPlaying); XCTAssertEqual(engine.index, 1)
        engine.browse(2); engine.browse(3)
        XCTAssertEqual(writes.count, before)
        engine.checkpoint(); XCTAssertEqual(writes.last, engine.tokens[3].position)
        engine.passageMode = false
        XCTAssertEqual(engine.index, 3)
        engine.browse(0); XCTAssertEqual(engine.index, 3)
        engine.seek(1); engine.wordsPerMinute = 600; engine.naturalPauses = true
        engine.play(); try await Task.sleep(for: .milliseconds(100)); engine.pause()
        XCTAssertEqual(engine.index, 1)
    }
    func testReviewAndSpeedPreferencesOnlyAffectSingleLinePlayback() async throws {
        var book = makeBook(["one.", "two"])
        for index in book.pages.indices { book.pages[index].isReviewed = false }
        let engine = ReaderEngine(book: book)
        engine.allowUnreviewed = false; engine.naturalPauses = true; engine.wordsPerMinute = 321
        engine.play(); XCTAssertEqual(engine.blockedPage, 0)
        engine.passageMode = true; engine.browse(1)
        XCTAssertNil(engine.blockedPage); XCTAssertEqual(engine.index, 1)
        engine.play(); try await Task.sleep(for: .milliseconds(100))
        XCTAssertFalse(engine.isPlaying); XCTAssertTrue(engine.naturalPauses)
        XCTAssertFalse(engine.allowUnreviewed); XCTAssertEqual(engine.wordsPerMinute, 321)
        engine.passageMode = false; engine.play(); XCTAssertEqual(engine.blockedPage, 1)
    }
}
