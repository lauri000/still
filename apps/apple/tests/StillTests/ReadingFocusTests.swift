import XCTest
@testable import Still

final class ReadingFocusTests: XCTestCase {
    func testFocusPreservesUnicodeAndIgnoresPunctuation() {
        for (word, expected) in [("a", "a"), ("word", "o"), ("reading", "a"),
                                  ("“reading,”", "a"), ("a\u{301}e\u{301}bc", "e\u{301}"),
                                  ("👨‍👩‍👧‍👦reading!", "a"), ("𐐀𐐁𐐂", "𐐁"),
                                  ("123456", "3"), ("extraordinarily", "a")] {
            let range = ReadingFocus.range(in: word)
            XCTAssertEqual(range.map { String(word[$0]) }, expected, word)
            XCTAssertEqual(String(FocusWord(word, enabled: true).text.characters), word)
        }
    }

    func testSymbolsAndDisabledFocusKeepPlainText() {
        for word in ["", "…", "👨‍👩‍👧‍👦", "—?!"] {
            XCTAssertNil(ReadingFocus.range(in: word))
            XCTAssertNil(FocusWord(word, enabled: true).anchor)
        }
        let word = FocusWord("reading", enabled: false)
        XCTAssertNil(word.anchor)
        XCTAssertEqual(String(word.text.characters), "reading")
        XCTAssertTrue(word.text.runs.allSatisfy { $0.foregroundColor == nil })
    }

    func testOnlyChosenCharacterHasColorAndAnchorIsInsideWord() {
        let word = FocusWord("“reading,”", enabled: true)
        let highlighted = word.text.runs.filter { $0.foregroundColor != nil }
        XCTAssertEqual(highlighted.count, 1)
        XCTAssertEqual(String(word.text[highlighted[0].range].characters), "a")
        XCTAssertGreaterThan(word.anchor ?? 0, 0)
        XCTAssertLessThan(word.anchor ?? 1, 0.5)
    }
}
