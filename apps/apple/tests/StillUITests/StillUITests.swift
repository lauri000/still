import XCTest
import UIKit

final class StillUITests: XCTestCase {
    @MainActor
    func testDiscoveryIsParallelToFileImport() throws {
        let app = XCUIApplication()
        app.launchArguments = ["--ui-testing", "--reset-library", "--empty-library"]
        app.launch()
        XCTAssertTrue(app.buttons["discoverBooks"].waitForExistence(timeout: 10))
        XCTAssertTrue(app.buttons["importPDF"].exists)
        app.buttons["discoverBooks"].tap()
        XCTAssertTrue(app.textFields["catalogSearch"].waitForExistence(timeout: 5))
        XCTAssertTrue(app.buttons["Catalog settings"].exists)
        app.buttons["Books"].tap()
        XCTAssertTrue(app.buttons["importPDF"].waitForExistence(timeout: 5))
    }

    @MainActor
    func testReaderUsesFullWidthAcrossRotation() throws {
        let app = XCUIApplication()
        app.launchArguments = ["--ui-testing", "--reset-library"]
        XCUIDevice.shared.orientation = .portrait
        defer { XCUIDevice.shared.orientation = .portrait }
        app.launch()
        XCTAssertTrue(app.buttons["book-The art of attention"].waitForExistence(timeout: 15))
        app.buttons["book-The art of attention"].tap()
        openTextEditor(in: app)
        let editor = app.textViews["pageTextEditor"]
        XCTAssertTrue(editor.waitForExistence(timeout: 5))
        editor.tap()
        editor.typeText(String(repeating: "i ", count: 120))
        app.buttons["startReading"].tap()
        let surface = app.otherElements["readingSurface"]
        XCTAssertTrue(surface.waitForExistence(timeout: 5))
        for _ in 0..<10 where wordOrdinal(in: app) < 40 {
            surface.coordinate(withNormalizedOffset: CGVector(dx: 0.9, dy: 0.3))
                .press(forDuration: 0.05, thenDragTo: surface.coordinate(withNormalizedOffset: CGVector(dx: 0.1, dy: 0.3)))
        }
        XCTAssertEqual(app.staticTexts["currentWord"].label, "i")
        let ordinal = wordOrdinal(in: app)
        for orientation in [UIDeviceOrientation.portrait, .landscapeLeft, .landscapeRight] {
            XCUIDevice.shared.orientation = orientation
            waitForRotation(in: app, landscape: orientation != .portrait, controlID: "toggleContext")
            let window = app.windows.firstMatch.frame
            XCTAssertEqual(surface.frame.minX, window.minX, accuracy: 1)
            XCTAssertEqual(surface.frame.maxX, window.maxX, accuracy: 1)
            XCTAssertEqual(app.staticTexts["currentWord"].frame.midX, window.midX, accuracy: 1)
            let neighbors = app.staticTexts.matching(NSPredicate(format: "identifier BEGINSWITH %@", "nearbyWord-")).allElementsBoundByIndex
                .map(\.frame).filter { $0.intersects(window) }
            XCTAssertLessThanOrEqual(neighbors.map(\.minX).min() ?? .infinity, window.minX + 19)
            XCTAssertGreaterThanOrEqual(neighbors.map(\.maxX).max() ?? 0, window.maxX - 19)
            XCTAssertEqual(wordOrdinal(in: app), ordinal)
            attachScreenshot("Full width reader \(orientation.rawValue)")
        }
        app.buttons["toggleContext"].tap()
        // The reader surface identifier overrides the nested passage scroll identifier.
        let passage = app.scrollViews["readingSurface"]
        XCTAssertTrue(passage.waitForExistence(timeout: 5))
        XCTAssertGreaterThan(passage.frame.width, app.windows.firstMatch.frame.width * 0.8)
    }

    @MainActor
    func testBookTypographyAndReaderContentsPreservePositionAndTrim() throws {
        let app = XCUIApplication()
        app.launchArguments = ["--ui-testing", "--reset-library", "--with-headings"]
        XCUIDevice.shared.orientation = .portrait
        app.launch()
        XCTAssertTrue(app.buttons["book-The art of attention"].waitForExistence(timeout: 15))
        app.buttons["book-The art of attention"].tap()
        app.buttons["startReading"].tap()
        let heading = app.staticTexts["currentHeading"]
        XCTAssertTrue(heading.waitForExistence(timeout: 5))
        XCTAssertEqual(heading.label, "A place to begin")
        XCTAssertFalse(app.staticTexts["currentWord"].exists)
        corner(in: app).press(forDuration: 0.4)
        XCTAssertEqual(heading.label, "A place to begin")
        let ordinal = wordOrdinal(in: app)
        app.buttons["toggleContext"].tap()
        XCTAssertEqual(wordOrdinal(in: app), ordinal)
        XCTAssertTrue(app.staticTexts["currentPassageRow"].label.contains("place"))
        attachScreenshot("Book typography multiline")
        app.buttons["readerPage"].tap()
        let next = app.buttons["contentsEntry-1"]
        XCTAssertTrue(next.waitForExistence(timeout: 5)); next.tap()
        XCTAssertTrue(app.staticTexts["currentPassageRow"].label.contains("Keep the meaning"))
        XCTAssertFalse(corner(in: app).exists)
        app.buttons["toggleContext"].tap()
        XCTAssertEqual(heading.label, "Keep the meaning")
        attachScreenshot("Book typography single line")
        let selected = wordOrdinal(in: app)
        app.buttons["Close reader"].tap(); app.buttons["startReading"].tap()
        XCTAssertTrue(heading.waitForExistence(timeout: 5)); XCTAssertEqual(wordOrdinal(in: app), selected)
        // Reader contents seeks; it must not replace the book's starting trim.
        app.buttons["readerPage"].tap()
        let earlier = app.buttons["contentsEntry-0"]
        XCTAssertTrue(earlier.waitForExistence(timeout: 5)); XCTAssertTrue(earlier.isEnabled); earlier.tap()
        XCTAssertEqual(heading.label, "A place to begin")
        corner(in: app).press(forDuration: 1.4)
        XCTAssertTrue(app.staticTexts["currentWord"].exists)
        XCTAssertFalse(heading.exists)
        XCUIDevice.shared.orientation = .landscapeLeft
        waitForRotation(in: app, landscape: true, controlID: "holdToRead")
        app.buttons["readerPage"].tap()
        XCTAssertTrue(app.buttons["contentsEntry-1"].waitForExistence(timeout: 5)); app.buttons["contentsEntry-1"].tap()
        XCTAssertEqual(heading.label, "Keep the meaning")
        XCTAssertTrue(app.descendants(matching: .any).matching(identifier: "readingSurface").firstMatch.frame.contains(heading.frame))
        attachScreenshot("Book typography landscape")
        XCUIDevice.shared.orientation = .portrait
    }

    @MainActor
    func testChooseStartingPageReadAndResume() throws {
        let app = XCUIApplication()
        app.launchArguments = ["--ui-testing", "--reset-library"]
        XCUIDevice.shared.orientation = .portrait
        app.launch()
        let open = app.buttons["book-The art of attention"]
        XCTAssertTrue(open.waitForExistence(timeout: 15))
        attachScreenshot("Simple library")
        open.tap()
        XCTAssertTrue(app.textFields["readingPageNumber"].waitForExistence(timeout: 5))
        XCTAssertFalse(app.textViews["pageTextEditor"].exists)
        app.buttons["Next starting page"].tap()
        XCTAssertEqual(app.textFields["readingPageNumber"].value as? String, "2")
        attachScreenshot("Choose starting page")
        XCUIDevice.shared.orientation = .landscapeLeft
        waitForRotation(in: app, landscape: true)
        attachScreenshot("Starting page in landscape")
        XCUIDevice.shared.orientation = .portrait
        waitForRotation(in: app, landscape: false)
        app.buttons["startReading"].tap()
        let play = corner(in: app)
        XCTAssertTrue(play.waitForExistence(timeout: 5))
        XCTAssertEqual(app.staticTexts["currentWord"].label, "A") // First word of page two.
        app.descendants(matching: .any).matching(identifier: "readingSurface").firstMatch.swipeRight()
        XCTAssertEqual(app.staticTexts["currentWord"].label, "A") // Earlier pages remain skipped.
        stepForward(in: app)
        XCTAssertEqual(app.staticTexts["currentWord"].label, "book")
        attachScreenshot("Simple reader")
        play.press(forDuration: 0.8)
        XCTAssertNotEqual(app.staticTexts["currentWord"].label, "book")
        XCTAssertTrue((play.value as? String ?? "").contains("paused"))
        let paused = app.staticTexts["currentWord"].label
        app.buttons["Close reader"].tap()
        app.buttons["startReading"].tap()
        XCTAssertTrue(play.waitForExistence(timeout: 5))
        XCTAssertEqual(app.staticTexts["currentWord"].label, paused)
        app.buttons["toggleContext"].tap()
        XCTAssertEqual(app.descendants(matching: .any).matching(identifier: "readingSurface").firstMatch.value as? String, "Passage view")
        app.buttons["Close reader"].tap()
        app.terminate()
        app.launchArguments = ["--ui-testing"]
        app.launch()
        XCTAssertTrue(open.waitForExistence(timeout: 15))
        open.tap()
        XCTAssertEqual(app.textFields["readingPageNumber"].value as? String, "2")
        app.buttons["startReading"].tap()
        XCTAssertTrue(play.waitForExistence(timeout: 5))
        XCTAssertEqual(app.staticTexts["currentWord"].label, paused)
        app.buttons["Close reader"].tap()
        app.buttons["Previous starting page"].tap()
        openTextEditor(in: app)
        app.buttons["startReading"].tap()
        XCTAssertTrue(play.waitForExistence(timeout: 5))
        XCTAssertEqual(app.staticTexts["currentWord"].label, "There")
    }

    @MainActor
    func testContentsChoosesPreviewAndPreservesExactResume() throws {
        let app = XCUIApplication()
        app.launchArguments = ["--ui-testing", "--reset-library"]
        XCUIDevice.shared.orientation = .portrait
        app.launch()
        XCTAssertTrue(app.buttons["book-The art of attention"].waitForExistence(timeout: 15))
        app.buttons["book-The art of attention"].tap()
        let contents = app.buttons["pdfContents"]
        XCTAssertTrue(contents.waitForExistence(timeout: 5))
        // iPhone presents a sheet; wide iPad/Mac layouts already show the sidebar.
        if !app.buttons["contentsEntry-1"].waitForExistence(timeout: 2) { contents.tap() }
        let chapter = app.buttons["contentsEntry-1"]
        XCTAssertTrue(chapter.waitForExistence(timeout: 5), app.debugDescription)
        attachScreenshot("PDF contents")
        chapter.tap()
        XCTAssertEqual(app.textFields["readingPageNumber"].value as? String, "2")
        attachScreenshot("PDF contents selection")
        app.buttons["startReading"].tap()
        XCTAssertTrue(app.staticTexts["currentWord"].waitForExistence(timeout: 5))
        XCTAssertEqual(app.staticTexts["currentWord"].label, "A")
        stepForward(in: app)
        let word = app.staticTexts["currentWord"].label
        let position = app.staticTexts["currentWord"].value as? String
        app.buttons["Close reader"].tap()
        // Opening/closing contents must not turn Continue reading into a new start.
        contents.tap()
        if app.buttons["closeContents"].waitForExistence(timeout: 1) { app.buttons["closeContents"].tap() }
        app.buttons["startReading"].tap()
        XCTAssertTrue(app.staticTexts["currentWord"].waitForExistence(timeout: 5))
        XCTAssertEqual(app.staticTexts["currentWord"].label, word)
        XCTAssertEqual(app.staticTexts["currentWord"].value as? String, position)
        app.buttons["Close reader"].tap()
        XCUIDevice.shared.orientation = .landscapeLeft
        waitForRotation(in: app, landscape: true)
        attachScreenshot("PDF contents in landscape")
    }

    @MainActor
    func testEmptyLibraryStartsWithImport() throws {
        let app = XCUIApplication()
        app.launchArguments = ["--ui-testing", "--reset-library", "--empty-library"]
        app.launch()
        XCTAssertTrue(app.buttons["importPDF"].waitForExistence(timeout: 10))
        XCTAssertTrue(app.staticTexts["No books yet"].exists)
        XCTAssertFalse(app.buttons["book-The art of attention"].exists)
        attachScreenshot("Empty library")
        app.buttons["importPDF"].tap()
        XCTAssertTrue(app.buttons["Cancel"].waitForExistence(timeout: 5))
        app.buttons["Cancel"].tap()
        XCTAssertTrue(app.buttons["importPDF"].exists)
    }

    @MainActor
    func testSwipeNearbyWordsPauseAndPersistSelection() throws {
        let app = XCUIApplication()
        app.launchArguments = ["--ui-testing", "--reset-library"]
        XCUIDevice.shared.orientation = .portrait
        app.launch()
        XCTAssertTrue(app.buttons["book-The art of attention"].waitForExistence(timeout: 15))
        app.buttons["book-The art of attention"].tap()
        app.buttons["startReading"].tap()
        let play = corner(in: app)
        XCTAssertTrue(play.waitForExistence(timeout: 5))
        stepForward(in: app)
        stepForward(in: app)
        let word = app.staticTexts["currentWord"]
        let surface = app.descendants(matching: .any).matching(identifier: "readingSurface").firstMatch
        XCTAssertTrue(surface.exists)
        let originalOrdinal = wordOrdinal(in: app)
        let right = surface.coordinate(withNormalizedOffset: CGVector(dx: 0.75, dy: 0.3))
        let left = surface.coordinate(withNormalizedOffset: CGVector(dx: 0.25, dy: 0.3))
        right.press(forDuration: 0.05, thenDragTo: left)
        XCTAssertGreaterThan(wordOrdinal(in: app), originalOrdinal)
        XCTAssertTrue((play.value as? String ?? "").contains("paused"))
        left.press(forDuration: 0.05, thenDragTo: right)
        XCTAssertEqual(wordOrdinal(in: app), originalOrdinal)
        attachScreenshot("Swipe reader with surrounding words")

        // Holding reads; text gestures afterward remain paused and only select words.
        play.press(forDuration: 0.5)
        right.press(forDuration: 0.05, thenDragTo: left)
        XCTAssertTrue((play.value as? String ?? "").contains("paused"))
        let selected = word.value as? String
        let stable = NSPredicate { _, _ in word.value as? String != selected }
        XCTAssertEqual(XCTWaiter.wait(for: [XCTNSPredicateExpectation(predicate: stable, object: nil)], timeout: 0.8), .timedOut)

        // One more scrub step selects the exact word to resume later.
        stepForward(in: app)
        let nextText = word.label
        let chosenPosition = word.value as? String
        let chosenOrdinal = wordOrdinal(in: app)
        app.buttons["Close reader"].tap()
        app.terminate()
        app.launchArguments = ["--ui-testing"]
        app.launch()
        XCTAssertTrue(app.buttons["book-The art of attention"].waitForExistence(timeout: 15))
        app.buttons["book-The art of attention"].tap()
        app.buttons["startReading"].tap()
        XCTAssertTrue(play.waitForExistence(timeout: 5))
        XCTAssertEqual(word.label, nextText)
        XCTAssertEqual(word.value as? String, chosenPosition)
        play.press(forDuration: 0.8)
        XCTAssertGreaterThan(wordOrdinal(in: app), chosenOrdinal)
        XCTAssertTrue((play.value as? String ?? "").contains("paused"))
        XCUIDevice.shared.orientation = .landscapeLeft
        waitForRotation(in: app, landscape: true, controlID: "holdToRead")
        attachScreenshot("Swipe reader in landscape")
        XCUIDevice.shared.orientation = .portrait
        waitForRotation(in: app, landscape: false, controlID: "holdToRead")
        app.buttons["toggleContext"].tap()
        let passage = app.descendants(matching: .any).matching(identifier: "readingSurface").firstMatch
        XCTAssertTrue(passage.isHittable)
        let beforePassageSwipe = wordOrdinal(in: app)
        passage.swipeUp()
        XCTAssertGreaterThan(wordOrdinal(in: app), beforePassageSwipe)
        XCTAssertFalse(corner(in: app).exists)
        attachScreenshot("Swipe through the expanded passage")
    }

    @MainActor
    func testManualPassageScrollAndWordTapPreservePosition() throws {
        let app = XCUIApplication()
        app.launchArguments = ["--ui-testing", "--reset-library"]
        XCUIDevice.shared.orientation = .portrait
        app.launch()
        XCTAssertTrue(app.buttons["book-The art of attention"].waitForExistence(timeout: 15))
        app.buttons["book-The art of attention"].tap()
        app.buttons["Next starting page"].tap()
        app.buttons["startReading"].tap()
        XCTAssertTrue(corner(in: app).waitForExistence(timeout: 5))
        app.buttons["readingOptions"].tap()
        let animation = app.switches["animatedScrolling"]
        XCTAssertTrue(animation.waitForExistence(timeout: 5))
        animation.coordinate(withNormalizedOffset: CGVector(dx: 0.95, dy: 0.5)).tap()
        app.buttons["Done"].tap()
        let ordinal = wordOrdinal(in: app)
        app.buttons["toggleContext"].tap()
        let surface = app.descendants(matching: .any).matching(identifier: "readingSurface").firstMatch
        let row = app.staticTexts["currentPassageRow"]
        XCTAssertTrue(row.waitForExistence(timeout: 5))
        XCTAssertFalse(app.staticTexts["currentWord"].exists)
        XCTAssertFalse(app.buttons["readingOptions"].exists)
        XCTAssertFalse(corner(in: app).exists)
        XCTAssertEqual(wordOrdinal(in: app), ordinal)
        assertPassageRowVisible(in: app)
        surface.swipeUp()
        XCTAssertGreaterThan(wordOrdinal(in: app), ordinal)
        let scrolled = wordOrdinal(in: app)
        XCUIDevice.shared.orientation = .landscapeLeft
        waitForRotation(in: app, landscape: true, controlID: "currentPassageRow")
        XCTAssertEqual(wordOrdinal(in: app), scrolled)
        XCUIDevice.shared.orientation = .portrait
        waitForRotation(in: app, landscape: false, controlID: "currentPassageRow")
        XCTAssertEqual(wordOrdinal(in: app), scrolled)
        app.buttons["toggleContext"].tap()
        XCTAssertEqual(wordOrdinal(in: app), scrolled)
        app.buttons["toggleContext"].tap()
        XCTAssertEqual(wordOrdinal(in: app), scrolled)
        assertPassageRowVisible(in: app)
        // Select the second word by its actual font metrics, preserving punctuation.
        let words = row.label.split(separator: " ").map(String.init)
        XCTAssertGreaterThan(words.count, 1)
        let font = UIFont(name: "Georgia", size: 24)!
        let prefix = (words[0] + " " as NSString).size(withAttributes: [.font: font]).width
        let chosenWidth = (words[1] as NSString).size(withAttributes: [.font: font]).width
        row.coordinate(withNormalizedOffset: .zero).withOffset(CGVector(dx: prefix + chosenWidth / 2, dy: row.frame.height / 2)).tap()
        XCTAssertTrue(corner(in: app).waitForExistence(timeout: 5))
        XCTAssertEqual(app.staticTexts["currentWord"].label, words[1])
        XCTAssertEqual(wordOrdinal(in: app), scrolled + 1)
        let selected = wordOrdinal(in: app)
        app.buttons["toggleContext"].tap()
        XCTAssertEqual(wordOrdinal(in: app), selected)
        XCUIDevice.shared.orientation = .landscapeLeft
        waitForRotation(in: app, landscape: true, controlID: "currentPassageRow")
        XCTAssertEqual(wordOrdinal(in: app), selected)
        assertPassageRowVisible(in: app)
        attachScreenshot("Manual scrolling passage")
        app.buttons["toggleContext"].tap()
        XCTAssertEqual(wordOrdinal(in: app), selected)
        XCTAssertEqual(app.staticTexts["currentWord"].label, words[1])
        app.buttons["readingOptions"].tap()
        XCTAssertTrue(animation.waitForExistence(timeout: 5))
        XCTAssertEqual(animation.value as? String, "1")
        app.buttons["Done"].tap()
        app.buttons["Close reader"].tap(); app.buttons["startReading"].tap()
        XCTAssertTrue(corner(in: app).waitForExistence(timeout: 5))
        XCTAssertEqual(wordOrdinal(in: app), selected)
        XCUIDevice.shared.orientation = .portrait
    }

    @MainActor
    func testActiveWordSizeOnlyAffectsSingleLineAndPersists() throws {
        let app = XCUIApplication()
        app.launchArguments = ["--ui-testing", "--reset-library"]
        XCUIDevice.shared.orientation = .portrait
        app.launch()
        XCTAssertTrue(app.buttons["book-The art of attention"].waitForExistence(timeout: 15))
        app.buttons["book-The art of attention"].tap()
        app.buttons["startReading"].tap()
        XCTAssertTrue(corner(in: app).waitForExistence(timeout: 5))
        let word = app.staticTexts["currentWord"]
        let ordinal = wordOrdinal(in: app)
        let baseWidth = word.frame.width
        func assertCentered() {
            let surface = app.descendants(matching: .any).matching(identifier: "readingSurface").firstMatch
            if surface.value as? String == "Passage view" {
                assertPassageRowVisible(in: app)
            } else {
                XCTAssertEqual(word.frame.midX, surface.frame.midX, accuracy: 1)
                XCTAssertEqual(word.frame.midY, surface.frame.midY, accuracy: 1)
            }
            XCTAssertEqual(wordOrdinal(in: app), ordinal)
            XCTAssertTrue((corner(in: app).value as? String ?? "").contains("paused"))
        }
        func changeSize(_ fraction: CGFloat, expected: String) {
            app.buttons["readingOptions"].tap()
            let slider = app.sliders["activeWordSize"]
            XCTAssertTrue(slider.waitForExistence(timeout: 5))
            slider.adjust(toNormalizedSliderPosition: fraction)
            // XCTest's normalized adjustment stops slightly short of native slider endpoints.
            slider.coordinate(withNormalizedOffset: CGVector(dx: fraction == 1 ? 0.95 : 0.05, dy: 0.5))
                .press(forDuration: 0.1, thenDragTo: slider.coordinate(withNormalizedOffset: CGVector(dx: fraction == 1 ? 1.2 : -0.2, dy: 0.5)))
            XCTAssertEqual(app.staticTexts["activeWordSizeValue"].label, expected)
            app.buttons["Done"].tap()
        }
        assertCentered()
        changeSize(1, expected: "300%")
        XCTAssertEqual(word.frame.width / baseWidth, 3, accuracy: 0.08)
        assertCentered()
        attachScreenshot("Single-line active word at 300 percent")
        app.buttons["toggleContext"].tap()
        let passageRow = app.staticTexts["currentPassageRow"]
        let passageFrame = passageRow.frame
        let passageText = passageRow.label
        XCTAssertFalse(word.exists)
        XCTAssertFalse(app.buttons["readingOptions"].exists)
        app.buttons["toggleContext"].tap()
        changeSize(0, expected: "100%")
        app.buttons["toggleContext"].tap()
        XCTAssertEqual(passageRow.frame, passageFrame)
        XCTAssertEqual(passageRow.label, passageText)
        XCTAssertEqual(wordOrdinal(in: app), ordinal)
        app.buttons["toggleContext"].tap()
        changeSize(1, expected: "300%")
        XCUIDevice.shared.orientation = .landscapeLeft
        waitForRotation(in: app, landscape: true, controlID: "holdToRead")
        assertCentered()
        attachScreenshot("Large passage active word in landscape")
        app.buttons["Close reader"].tap()
        app.terminate()
        app.launchArguments = ["--ui-testing"]
        app.launch()
        XCTAssertTrue(app.buttons["book-The art of attention"].waitForExistence(timeout: 15))
        app.buttons["book-The art of attention"].tap()
        app.buttons["startReading"].tap()
        XCTAssertTrue(corner(in: app).waitForExistence(timeout: 5))
        XCTAssertEqual(word.frame.width / baseWidth, 3, accuracy: 0.08)
        assertCentered()
        app.buttons["readingOptions"].tap()
        XCTAssertEqual(app.staticTexts["activeWordSizeValue"].label, "300%")
        attachScreenshot("Saved active word size option")
        app.buttons["Done"].tap()
        XCUIDevice.shared.orientation = .portrait
    }

    @MainActor
    func testFocusLetterTogglePersistsWithoutMovingReadingPosition() throws {
        let app = XCUIApplication()
        app.launchArguments = ["--ui-testing", "--reset-library"]
        XCUIDevice.shared.orientation = .portrait
        app.launch()
        XCTAssertTrue(app.buttons["book-The art of attention"].waitForExistence(timeout: 15))
        app.buttons["book-The art of attention"].tap()
        app.buttons["startReading"].tap()
        XCTAssertTrue(corner(in: app).waitForExistence(timeout: 5))
        let word = app.staticTexts["currentWord"]
        // Choose a word whose focus is visibly left of its midpoint.
        for _ in 0..<20 where word.label.count < 6 { stepForward(in: app) }
        XCTAssertGreaterThanOrEqual(word.label.count, 6)
        let text = word.label
        let ordinal = wordOrdinal(in: app)
        let originalCenter = word.frame.midX
        app.buttons["readingOptions"].tap()
        let toggle = app.switches["focusLetter"]
        XCTAssertTrue(toggle.waitForExistence(timeout: 5))
        XCTAssertEqual(toggle.value as? String, "0")
        toggle.coordinate(withNormalizedOffset: CGVector(dx: 0.95, dy: 0.5)).tap()
        XCTAssertEqual(toggle.value as? String, "1")
        app.buttons["Done"].tap()
        XCTAssertEqual(word.label, text)
        XCTAssertEqual(wordOrdinal(in: app), ordinal)
        XCTAssertGreaterThan(word.frame.midX, originalCenter)
        app.buttons["toggleContext"].tap()
        XCTAssertFalse(word.exists)
        XCTAssertTrue(app.staticTexts["currentPassageRow"].label.contains(text))
        XCTAssertEqual(wordOrdinal(in: app), ordinal)
        assertPassageRowVisible(in: app)
        XCTAssertEqual(app.descendants(matching: .any).matching(identifier: "readingSurface").firstMatch.value as? String, "Passage view")
        attachScreenshot("Focus letter in portrait")
        XCUIDevice.shared.orientation = .landscapeLeft
        waitForRotation(in: app, landscape: true, controlID: "currentPassageRow")
        attachScreenshot("Focus letter in landscape")
        app.buttons["Close reader"].tap()
        app.terminate()
        app.launchArguments = ["--ui-testing"]
        app.launch()
        XCTAssertTrue(app.buttons["book-The art of attention"].waitForExistence(timeout: 15))
        app.buttons["book-The art of attention"].tap()
        app.buttons["startReading"].tap()
        XCTAssertTrue(corner(in: app).waitForExistence(timeout: 5))
        XCTAssertEqual(word.label, text)
        XCTAssertEqual(wordOrdinal(in: app), ordinal)
        app.buttons["readingOptions"].tap()
        XCTAssertTrue(toggle.waitForExistence(timeout: 5))
        XCTAssertEqual(toggle.value as? String, "1")
        toggle.coordinate(withNormalizedOffset: CGVector(dx: 0.95, dy: 0.5)).tap()
        XCTAssertEqual(toggle.value as? String, "0")
        app.buttons["Done"].tap()
        let surface = app.descendants(matching: .any).matching(identifier: "readingSurface").firstMatch
        XCTAssertEqual(word.frame.midX, surface.frame.midX, accuracy: 1)
        XCUIDevice.shared.orientation = .portrait
    }

    @MainActor private func assertPassageRowVisible(in app: XCUIApplication, file: StaticString = #filePath, line: UInt = #line) {
        let row = app.staticTexts["currentPassageRow"]
        XCTAssertTrue(row.waitForExistence(timeout: 5), file: file, line: line)
        let surface = app.descendants(matching: .any).matching(identifier: "readingSurface").firstMatch.frame
        XCTAssertGreaterThan(row.frame.maxY, surface.minY, file: file, line: line)
        XCTAssertLessThan(row.frame.minY, surface.maxY, file: file, line: line)
    }

    @MainActor private func corner(in app: XCUIApplication) -> XCUIElement {
        app.descendants(matching: .any).matching(identifier: "holdToRead").firstMatch
    }

    @MainActor private func stepForward(in app: XCUIApplication) {
        let start = app.descendants(matching: .any).matching(identifier: "readingSurface").firstMatch.coordinate(withNormalizedOffset: CGVector(dx: 0.6, dy: 0.25))
        start.press(forDuration: 0.05, thenDragTo: start.withOffset(CGVector(dx: -33, dy: 0)))
    }

    @MainActor private func speed(in app: XCUIApplication) -> Int {
        (corner(in: app).value as? String ?? "").split(separator: " ").first.flatMap { Int($0) } ?? -1
    }

    @MainActor
    func testHoldDragSpeedIsRelativeAndSeparateFromTextScrubbing() throws {
        let app = XCUIApplication()
        app.launchArguments = ["--ui-testing", "--reset-library"]
        XCUIDevice.shared.orientation = .portrait
        app.launch()
        XCTAssertTrue(app.buttons["book-The art of attention"].waitForExistence(timeout: 15))
        app.buttons["book-The art of attention"].tap()
        app.buttons["Next starting page"].tap()
        app.buttons["startReading"].tap()
        let hold = corner(in: app)
        XCTAssertTrue(hold.waitForExistence(timeout: 5))
        XCTAssertFalse(app.buttons["playPause"].exists)
        XCTAssertFalse(app.sliders["Words per minute"].exists)
        let initial = speed(in: app)
        let ordinal = wordOrdinal(in: app)
        hold.press(forDuration: 0.7)
        XCTAssertGreaterThan(wordOrdinal(in: app), ordinal)
        XCTAssertEqual(speed(in: app), initial)
        XCTAssertTrue((hold.value as? String ?? "").contains("paused"))
        let left = hold.coordinate(withNormalizedOffset: CGVector(dx: 0.25, dy: 0.3))
        let right = hold.coordinate(withNormalizedOffset: CGVector(dx: 0.65, dy: 0.5))
        left.press(forDuration: 0.15, thenDragTo: right)
        let faster = speed(in: app)
        XCTAssertGreaterThan(faster, initial)
        XCTAssertLessThanOrEqual(faster, 900)
        // Touching a new point preserves the current speed.
        right.press(forDuration: 0.3)
        XCTAssertEqual(speed(in: app), faster)
        right.press(forDuration: 0.15, thenDragTo: left)
        XCTAssertLessThan(speed(in: app), faster)
        let slowed = speed(in: app)
        let top = hold.coordinate(withNormalizedOffset: CGVector(dx: 0.25, dy: 0.1))
        left.press(forDuration: 0.15, thenDragTo: top)
        XCTAssertEqual(speed(in: app), slowed)
        app.descendants(matching: .any).matching(identifier: "readingSurface").firstMatch.swipeRight()
        XCTAssertEqual(speed(in: app), slowed)
        XCTAssertTrue((hold.value as? String ?? "").contains("paused"))
        attachScreenshot("Hold-to-read corner")
        app.buttons["Close reader"].tap()
        app.terminate()
        app.launchArguments = ["--ui-testing"]
        app.launch()
        XCTAssertTrue(app.buttons["book-The art of attention"].waitForExistence(timeout: 15))
        app.buttons["book-The art of attention"].tap()
        app.buttons["startReading"].tap()
        XCTAssertTrue(corner(in: app).waitForExistence(timeout: 5))
        XCTAssertEqual(speed(in: app), slowed)
    }

    @MainActor
    func testCompactHeaderFixedGestureZonesAndNoWordTaps() throws {
        let app = XCUIApplication()
        app.launchArguments = ["--ui-testing", "--reset-library"]
        XCUIDevice.shared.orientation = .portrait
        app.launch()
        XCTAssertTrue(app.buttons["book-The art of attention"].waitForExistence(timeout: 15))
        app.buttons["book-The art of attention"].tap()
        app.buttons["Next starting page"].tap()
        app.buttons["startReading"].tap()
        let hold = corner(in: app)
        XCTAssertTrue(hold.waitForExistence(timeout: 5))
        let surface = app.descendants(matching: .any).matching(identifier: "readingSurface").firstMatch
        let header = app.otherElements["readerHeader"]
        func assertLayout() {
            XCTAssertLessThanOrEqual(header.frame.height, 56)
            XCTAssertEqual(header.frame.maxY, surface.frame.minY, accuracy: 1)
            XCTAssertLessThan(app.windows.firstMatch.frame.maxY - surface.frame.maxY, 50)
            XCTAssertEqual(hold.frame.width, surface.frame.width / 2, accuracy: 1)
            XCTAssertEqual(hold.frame.height, surface.frame.height / 2, accuracy: 1)
            XCTAssertEqual(hold.frame.maxX, surface.frame.maxX, accuracy: 1)
            XCTAssertEqual(hold.frame.maxY, surface.frame.maxY, accuracy: 1)
            XCTAssertTrue(header.frame.contains(app.buttons["readerPage"].frame))
        }
        assertLayout()
        XCTAssertFalse(app.staticTexts["speedOverlay"].exists)
        hold.press(forDuration: 0.3)
        XCTAssertEqual(speed(in: app), 250)
        XCTAssertFalse(app.staticTexts["speedOverlay"].exists)
        do {
            let initial = wordOrdinal(in: app)
            let neighbor = app.staticTexts["nearbyWord-\(initial - 2)"]
            XCTAssertTrue(neighbor.exists)
            neighbor.tap()
            XCTAssertEqual(wordOrdinal(in: app), initial)
            // Starting outside the corner remains scrubbing after crossing into it.
            let left = surface.coordinate(withNormalizedOffset: CGVector(dx: 0.25, dy: 0.75))
            let right = surface.coordinate(withNormalizedOffset: CGVector(dx: 0.8, dy: 0.75))
            let savedSpeed = speed(in: app)
            left.press(forDuration: 0.05, thenDragTo: right)
            XCTAssertLessThan(wordOrdinal(in: app), initial)
            XCTAssertEqual(speed(in: app), savedSpeed)
            XCTAssertTrue((hold.value as? String ?? "").contains("paused"))
            // Starting in the corner keeps speed control after crossing out of it.
            let beforeHold = wordOrdinal(in: app)
            right.press(forDuration: 0.05, thenDragTo: left)
            XCTAssertGreaterThanOrEqual(wordOrdinal(in: app), beforeHold)
            XCTAssertLessThanOrEqual(speed(in: app), savedSpeed)
            XCTAssertTrue((hold.value as? String ?? "").contains("paused"))
            stepForward(in: app)
            stepForward(in: app)
        }
        app.buttons["readerPage"].tap()
        XCTAssertTrue(app.buttons["readerGoToPage"].waitForExistence(timeout: 5))
        app.buttons["readerGoToPage"].tap()
        XCTAssertTrue(app.buttons["Page 3"].waitForExistence(timeout: 5))
        app.buttons["Page 3"].tap()
        XCTAssertEqual(app.staticTexts["currentWord"].label, "Press")
        attachScreenshot("Compact Apple reader in portrait")
        XCUIDevice.shared.orientation = .landscapeLeft
        waitForRotation(in: app, landscape: true, controlID: "holdToRead")
        assertLayout()
        XCTAssertTrue((hold.value as? String ?? "").contains("paused"))
        attachScreenshot("Compact Apple reader in landscape")
        XCUIDevice.shared.orientation = .portrait
    }

    @MainActor private func wordOrdinal(in app: XCUIApplication) -> Int {
        let element = app.staticTexts["currentPassageRow"].exists ? app.staticTexts["currentPassageRow"] : (app.staticTexts["currentHeading"].exists ? app.staticTexts["currentHeading"] : app.staticTexts["currentWord"])
        let value = element.value as? String ?? ""
        return value.split(separator: " ").dropFirst().first.flatMap { Int($0) } ?? -1
    }

    @MainActor
    func testOptInReviewAndEditsSurviveRelaunch() throws {
        let app = XCUIApplication()
        app.launchArguments = ["--ui-testing", "--reset-library", "--require-review"]
        app.launch()
        XCTAssertTrue(app.buttons["book-The art of attention"].waitForExistence(timeout: 15))
        app.buttons["book-The art of attention"].tap()
        openTextEditor(in: app)
        let editor = app.textViews["pageTextEditor"]
        XCTAssertTrue(editor.waitForExistence(timeout: 5))
        editor.tap()
        editor.typeText("A corrected introduction. ")
        app.buttons["startReading"].tap()
        corner(in: app).press(forDuration: 0.3)
        XCTAssertTrue(app.buttons["Review this page"].waitForExistence(timeout: 5))
        app.buttons["Review this page"].tap()
        app.buttons["approvePage"].tap()
        app.buttons["Previous page"].tap()
        app.terminate()
        app.launchArguments = ["--ui-testing"]
        app.launch()
        XCTAssertTrue(app.buttons["book-The art of attention"].waitForExistence(timeout: 10))
        app.buttons["book-The art of attention"].tap()
        openTextEditor(in: app)
        XCTAssertTrue(app.textViews["pageTextEditor"].waitForExistence(timeout: 5))
        XCTAssertTrue((app.textViews["pageTextEditor"].value as? String ?? "").contains("A corrected introduction."))
    }

    @MainActor
    func testReadUncheckedTextAndTrimBeginningAcrossRelaunch() throws {
        let app = XCUIApplication()
        app.launchArguments = ["--ui-testing", "--reset-library"]
        XCUIDevice.shared.orientation = .portrait
        app.launch()
        XCTAssertTrue(app.buttons["book-The art of attention"].waitForExistence(timeout: 15))
        app.buttons["book-The art of attention"].tap()
        openTextEditor(in: app)
        let editor = app.textViews["pageTextEditor"]
        XCTAssertTrue(editor.waitForExistence(timeout: 5))
        editor.tap()
        editor.typeText("A correction. ")
        app.buttons["startReading"].tap()
        let play = corner(in: app)
        XCTAssertTrue(play.waitForExistence(timeout: 5))
        play.press(forDuration: 0.5)
        XCTAssertFalse(app.buttons["Review this page"].exists)
        XCTAssertTrue((play.value as? String ?? "").contains("paused"))
        app.buttons["Close reader"].tap()

        app.buttons["bookOptions"].tap()
        app.buttons["trimBeginning"].tap()
        XCTAssertTrue(app.buttons["Next start page"].waitForExistence(timeout: 5))
        app.buttons["Next start page"].tap()
        let startWord = app.buttons["startWord-2"] // "book" in "A book is more..."
        XCTAssertTrue(startWord.waitForExistence(timeout: 5))
        startWord.tap()
        attachScreenshot("Choose the book beginning")
        app.buttons["saveReadingStart"].tap()
        app.buttons["startReading"].tap()
        XCTAssertTrue(play.waitForExistence(timeout: 5))
        XCTAssertEqual(app.staticTexts["currentWord"].label, "book")
        app.buttons["Close reader"].tap()

        app.terminate()
        app.launchArguments = ["--ui-testing"]
        app.launch()
        XCTAssertTrue(app.buttons["book-The art of attention"].waitForExistence(timeout: 15))
        app.buttons["book-The art of attention"].tap()
        app.buttons["startReading"].tap()
        XCTAssertTrue(play.waitForExistence(timeout: 5))
        XCTAssertEqual(app.staticTexts["currentWord"].label, "book")
        app.buttons["Close reader"].tap()
        app.buttons["bookOptions"].tap()
        app.buttons["trimBeginning"].tap()
        XCTAssertTrue(app.buttons["restoreBeginning"].waitForExistence(timeout: 5))
        app.buttons["restoreBeginning"].tap()
        app.buttons["startReading"].tap()
        XCTAssertTrue(play.waitForExistence(timeout: 5))
        XCTAssertNotEqual(app.staticTexts["currentWord"].label, "book")
    }

    @MainActor private func openTextEditor(in app: XCUIApplication) {
        app.buttons["bookOptions"].tap()
        app.buttons["editBookText"].tap()
    }

    @MainActor private func waitForRotation(in app: XCUIApplication, landscape: Bool, controlID: String = "startReading") {
        var previousFrame = CGRect.zero
        var stableChecks = 0
        let settled = NSPredicate { _, _ in
            let window = app.windows.firstMatch.frame
            let button = app.descendants(matching: .any).matching(identifier: controlID).firstMatch
            let frame = button.frame
            let orientationMatches = landscape ? window.width > window.height : window.height > window.width
            stableChecks = orientationMatches && frame == previousFrame && button.isHittable ? stableChecks + 1 : 0
            previousFrame = frame
            return stableChecks >= 2
        }
        XCTAssertEqual(XCTWaiter.wait(for: [XCTNSPredicateExpectation(predicate: settled, object: nil)], timeout: 10), .completed)
    }

    @MainActor private func attachScreenshot(_ name: String) {
        let attachment = XCTAttachment(screenshot: XCUIScreen.main.screenshot())
        attachment.name = name
        attachment.lifetime = .keepAlways
        add(attachment)
    }
}
