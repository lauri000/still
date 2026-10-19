import XCTest
@testable import Still

final class PassageLayoutTests: XCTestCase {
    func testWrappingPreservesEveryWordAndParagraphAndPageBoundaries() {
        let tokens = Tokenizer.tokens(for: makeBook(["one two three four.\n\nfive six", "seven"]))
        let layout = PassageLayout(tokens: tokens, widths: Array(repeating: 40, count: tokens.count), availableWidth: 160, naturalPauses: true)
        XCTAssertEqual(layout.rows.map(\.words), [0..<3, 3..<4, 4..<6, 6..<7])
        XCTAssertEqual(layout.rows.flatMap(\.words), Array(tokens.indices))
        for index in 0..<(tokens.count - 1) {
            XCTAssertEqual(layout.position(index: index, wordProgress: 1), layout.position(index: index + 1, wordProgress: 0), accuracy: 0.000001)
        }
    }

    func testNaturalPausesKeepMotionLinearWithinARow() {
        let tokens = Tokenizer.tokens(for: makeBook(["one two. three"]))
        let layout = PassageLayout(tokens: tokens, widths: [40, 40, 40], availableWidth: 500, naturalPauses: true)
        let plainMovement = layout.position(index: 0, wordProgress: 0.1) - layout.position(index: 0, wordProgress: 0)
        let punctuationMovement = layout.position(index: 1, wordProgress: 0.1 / 1.9) - layout.position(index: 1, wordProgress: 0)
        XCTAssertEqual(plainMovement, punctuationMovement, accuracy: 0.000001)
        XCTAssertEqual(layout.position(index: 2, wordProgress: 1), 1)
    }

    func testNarrowWidthsAndEmptyBooksRemainValid() {
        let tokens = Tokenizer.tokens(for: makeBook(["oversized word"]))
        let layout = PassageLayout(tokens: tokens, widths: [900, 40], availableWidth: 25, naturalPauses: false)
        XCTAssertEqual(layout.widths, [25, 25])
        XCTAssertEqual(layout.rows.map(\.words), [0..<1, 1..<2])
        let empty = PassageLayout(tokens: [], widths: [], availableWidth: 0, naturalPauses: true)
        XCTAssertTrue(empty.rows.isEmpty)
        XCTAssertEqual(empty.position(index: 0, wordProgress: 0), 0)
    }
}

@MainActor
final class PassagePlaybackTests: XCTestCase {
    func testPauseSpeedChangeSeekAndModeSwitchPreservePosition() async throws {
        let engine = ReaderEngine(book: makeBook(["one two three"]))
        engine.passageMode = true
        engine.wordsPerMinute = 60
        engine.naturalPauses = false
        engine.play()
        try await Task.sleep(for: .milliseconds(100))
        engine.pause()
        let frozen = engine.wordProgress
        XCTAssertGreaterThan(frozen, 0.05)
        XCTAssertLessThan(frozen, 0.5)
        try await Task.sleep(for: .milliseconds(80))
        XCTAssertEqual(engine.wordProgress, frozen)
        engine.wordsPerMinute = 120
        engine.play()
        try await Task.sleep(for: .milliseconds(60))
        engine.pause()
        XCTAssertGreaterThan(engine.wordProgress, frozen + 0.08)
        engine.seek(1)
        XCTAssertEqual(engine.wordProgress, 0)
        XCTAssertEqual(engine.current?.word.text, "two")
        engine.passageMode = false
        engine.play()
        try await Task.sleep(for: .milliseconds(50))
        engine.pause()
        XCTAssertEqual(engine.wordProgress, 0) // Single-line resume still gives the whole word its full duration.
    }
}
