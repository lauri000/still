import XCTest
@testable import Still

final class ReadingStructureTests: XCTestCase {
    func source() -> Book {
        var b = makeBook(["Chapter One\nfirst body.\nSmall section\nmore body.", "Chapter Two\nnext body."])
        b.chapters = [chapter("Chapter One", 0), chapter("Small section", 0, 1), chapter("Chapter Two", 1)]
        return b
    }
    func chapter(_ title: String, _ page: Int, _ level: Int = 0) -> BookChapter {
        BookChapter(id: UUID(), title: title, pageIndex: page, level: level, source: "PDF bookmark")
    }
    func structure(_ book: Book) -> ReadingStructure { ReadingStructure(book: book, tokens: Tokenizer.tokens(for: book)) }

    func testMappedHeadingsKeepOrderAndOnlyMajorSectionsPause() {
        let s = structure(source())
        XCTAssertEqual(s.headings.map(\.title), ["Chapter One", "Small section", "Chapter Two"])
        XCTAssertEqual(s.section(at: 0)?.title, "Chapter One")
        XCTAssertEqual(s.section(at: 7)?.title, "Small section")
        XCTAssertEqual(s.chapterEnds, [7])
        XCTAssertEqual(s.destination(title: "chapter TWO", page: 1), 8)
        XCTAssertEqual(s.duration(index: 7, base: 0.1, passage: true), 0.8)
        XCTAssertEqual(s.duration(index: 0, base: 0.1, passage: false), 0.6)
        XCTAssertEqual(s.duration(index: 0, base: 0.1, passage: true), 0.1)
    }
    func testUnicodeWrappedTitlesRejectProseAndDuplicateMatches() {
        var b = makeBook(["Café ☕\nA quieter\nbeginning\nThe word Ambiguous is in prose.\nRepeated\nbody\nRepeated"])
        b.chapters = [chapter("CAFÉ ☕", 0), chapter("A quieter beginning", 0), chapter("Ambiguous", 0), chapter("Repeated", 0)]
        let s = structure(b), tokens = Tokenizer.tokens(for: b)
        XCTAssertEqual(s.headings.map(\.title), ["Café ☕", "A quieter beginning"])
        XCTAssertNil(s.destination(title: "Repeated", page: 0)); XCTAssertNil(s.destination(title: "Ambiguous", page: 0))
        for h in s.headings { XCTAssertEqual(h.title, tokens[h.words].map { $0.word.text }.joined(separator: " ")) }
    }
    func testTrimsExclusionsAndEditsCannotInventAChapterEnding() {
        var midWord = source(); midWord.setReadingStart(ReadingPosition(pageID: midWord.pages[0].id, utf16Offset: 1))
        XCTAssertNil(structure(midWord).destination(title: "Chapter One", page: 0))
        var trimmed = source(); trimmed.setReadingStart(ReadingPosition(pageID: trimmed.pages[0].id, utf16Offset: 8))
        XCTAssertNil(structure(trimmed).destination(title: "Chapter One", page: 0)); XCTAssertTrue(structure(trimmed).chapterEnds.isEmpty)
        var changed = source(); changed.editText(on: 0, to: changed.pages[0].text.replacingOccurrences(of: "Chapter One", with: "Different title"))
        XCTAssertNil(structure(changed).destination(title: "Chapter One", page: 0)); XCTAssertTrue(structure(changed).chapterEnds.isEmpty)
        var omitted = source(); omitted.pages[1].isIncluded = false
        XCTAssertTrue(structure(omitted).chapterEnds.isEmpty)
        var missing = makeBook(["Chapter One\nbody", "", "Chapter Two\nbody"])
        missing.chapters = [chapter("Chapter One", 0), chapter("Chapter Two", 2)]
        XCTAssertTrue(structure(missing).chapterEnds.isEmpty)
    }
    func testBookTitleAndRepeatedRunningTitles() {
        var unique = makeBook(["My Book\nby the author", "ordinary body"]); unique.title = "My Book"
        XCTAssertEqual(structure(unique).headings.map(\.title), ["My Book"])
        XCTAssertTrue(structure(unique).chapterEnds.isEmpty)
        var repeated = makeBook(["My Book\nfirst body", "My Book\nsecond body"]); repeated.title = "My Book"
        XCTAssertTrue(structure(repeated).headings.isEmpty)
    }
    func testHeadingRowsPreserveWordsAndContinuousSpacing() {
        let b = source(), tokens = Tokenizer.tokens(for: b), s = structure(b)
        let ids = Dictionary(uniqueKeysWithValues: s.headings.flatMap { h in h.words.map { ($0, h.words.lowerBound) } })
        let layout = PassageLayout(tokens: tokens, widths: Array(repeating: 50, count: tokens.count), availableWidth: 170, headingIDs: ids)
        XCTAssertEqual(layout.rows.flatMap(\.words), Array(tokens.indices))
        XCTAssertTrue(layout.rows.filter(\.isHeading).allSatisfy { row in row.words.allSatisfy { ids[$0] != nil } })
        XCTAssertTrue(layout.rows.filter { !$0.isHeading }.allSatisfy { row in row.words.allSatisfy { ids[$0] == nil } })
        for index in 0..<(tokens.count - 1) { XCTAssertEqual(layout.position(index: index, wordProgress: 1), layout.position(index: index + 1, wordProgress: 0), accuracy: 0.000001) }
        XCTAssertTrue(layout.rows.contains { $0.height > 1 })
    }
    @MainActor func testLiftingDuringChapterPauseStopsPlaybackAndFreezesTheColumn() async throws {
        let engine = ReaderEngine(book: source())
        engine.wordsPerMinute = 900; engine.passageMode = true
        engine.seek(7); engine.play()
        try await Task.sleep(for: .milliseconds(160)); engine.pause()
        XCTAssertEqual(engine.index, 7); XCTAssertEqual(engine.motionProgress, 1)
        let frozen = engine.wordProgress
        try await Task.sleep(for: .milliseconds(120))
        XCTAssertFalse(engine.isPlaying); XCTAssertEqual(engine.index, 7); XCTAssertEqual(engine.wordProgress, frozen)
        engine.play(); try await Task.sleep(for: .milliseconds(60)); engine.pause()
        XCTAssertGreaterThan(engine.wordProgress, frozen)
        engine.seek(0); XCTAssertEqual(engine.wordProgress, 0)
    }
}
