import XCTest

final class StillUITests: XCTestCase {
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
        let play = app.buttons["playPause"]
        XCTAssertTrue(play.waitForExistence(timeout: 5))
        XCTAssertEqual(app.staticTexts["currentWord"].label, "A") // First word of page two.
        app.buttons["Previous word"].tap()
        XCTAssertEqual(app.staticTexts["currentWord"].label, "A") // Earlier pages remain skipped.
        app.buttons["Next word"].tap()
        XCTAssertEqual(app.staticTexts["currentWord"].label, "book")
        attachScreenshot("Simple reader")
        play.tap()
        let changed = NSPredicate(format: "label != %@", "book")
        expectation(for: changed, evaluatedWith: app.staticTexts["currentWord"])
        waitForExpectations(timeout: 5)
        play.tap()
        let paused = app.staticTexts["currentWord"].label
        app.buttons["Close reader"].tap()
        app.buttons["startReading"].tap()
        XCTAssertTrue(play.waitForExistence(timeout: 5))
        XCTAssertEqual(app.staticTexts["currentWord"].label, paused)
        app.buttons["toggleContext"].tap()
        XCTAssertTrue(app.otherElements["nearbyPassage"].exists)
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
    func testEmptyLibraryStartsWithImport() throws {
        let app = XCUIApplication()
        app.launchArguments = ["--ui-testing", "--reset-library", "--empty-library"]
        app.launch()
        XCTAssertTrue(app.buttons["importPDF"].waitForExistence(timeout: 10))
        XCTAssertTrue(app.staticTexts["No PDFs yet"].exists)
        XCTAssertFalse(app.buttons["book-The art of attention"].exists)
        attachScreenshot("Empty library")
        app.buttons["importPDF"].tap()
        XCTAssertTrue(app.buttons["Cancel"].waitForExistence(timeout: 5))
        app.buttons["Cancel"].tap()
        XCTAssertTrue(app.buttons["importPDF"].exists)
    }

    @MainActor
    func testSwipeNearbyWordsPauseTapAndPersistSelection() throws {
        let app = XCUIApplication()
        app.launchArguments = ["--ui-testing", "--reset-library"]
        XCUIDevice.shared.orientation = .portrait
        app.launch()
        XCTAssertTrue(app.buttons["book-The art of attention"].waitForExistence(timeout: 15))
        app.buttons["book-The art of attention"].tap()
        app.buttons["startReading"].tap()
        let play = app.buttons["playPause"]
        XCTAssertTrue(play.waitForExistence(timeout: 5))
        app.buttons["Next word"].tap()
        app.buttons["Next word"].tap()
        let word = app.staticTexts["currentWord"]
        let surface = app.otherElements["readingSurface"]
        XCTAssertTrue(surface.exists)
        let originalOrdinal = wordOrdinal(in: app)
        let right = surface.coordinate(withNormalizedOffset: CGVector(dx: 0.75, dy: 0.5))
        let left = surface.coordinate(withNormalizedOffset: CGVector(dx: 0.25, dy: 0.5))
        right.press(forDuration: 0.05, thenDragTo: left)
        XCTAssertGreaterThan(wordOrdinal(in: app), originalOrdinal)
        XCTAssertEqual(play.label, "Play reading")
        left.press(forDuration: 0.05, thenDragTo: right)
        XCTAssertEqual(wordOrdinal(in: app), originalOrdinal)
        attachScreenshot("Swipe reader with surrounding words")

        // Dragging during playback pauses immediately and stays paused after release.
        play.tap()
        right.press(forDuration: 0.05, thenDragTo: left)
        XCTAssertEqual(play.label, "Play reading")
        let selected = word.value as? String
        let stable = NSPredicate { _, _ in word.value as? String != selected }
        XCTAssertEqual(XCTWaiter.wait(for: [XCTNSPredicateExpectation(predicate: stable, object: nil)], timeout: 0.8), .timedOut)

        // The next word in the visible ribbon is a direct jump target.
        let next = app.buttons["nearbyWord-\(wordOrdinal(in: app))"]
        XCTAssertTrue(next.isHittable)
        let nextText = next.label.replacingOccurrences(of: "Read from ", with: "")
        next.tap()
        XCTAssertEqual(word.label, nextText)
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
        play.tap()
        XCTAssertEqual(play.label, "Pause reading")
        let advanced = NSPredicate { _, _ in self.wordOrdinal(in: app) > chosenOrdinal }
        XCTAssertEqual(XCTWaiter.wait(for: [XCTNSPredicateExpectation(predicate: advanced, object: nil)], timeout: 5), .completed)
        play.tap()
        XCUIDevice.shared.orientation = .landscapeLeft
        waitForRotation(in: app, landscape: true, controlID: "playPause")
        attachScreenshot("Swipe reader in landscape")
        XCUIDevice.shared.orientation = .portrait
        waitForRotation(in: app, landscape: false, controlID: "playPause")
        app.buttons["toggleContext"].tap()
        app.scrollViews.firstMatch.swipeUp()
        let passage = app.otherElements["nearbyPassage"]
        XCTAssertTrue(passage.isHittable)
        let beforePassageSwipe = wordOrdinal(in: app)
        passage.coordinate(withNormalizedOffset: CGVector(dx: 0.8, dy: 0.5))
            .press(forDuration: 0.05, thenDragTo: passage.coordinate(withNormalizedOffset: CGVector(dx: 0.2, dy: 0.5)))
        XCTAssertGreaterThan(wordOrdinal(in: app), beforePassageSwipe)
        XCTAssertEqual(play.label, "Play reading")
        attachScreenshot("Swipe through the expanded passage")
    }

    @MainActor private func wordOrdinal(in app: XCUIApplication) -> Int {
        let value = app.staticTexts["currentWord"].value as? String ?? ""
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
        app.buttons["playPause"].tap()
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
        let play = app.buttons["playPause"]
        XCTAssertTrue(play.waitForExistence(timeout: 5))
        play.tap()
        XCTAssertEqual(play.label, "Pause reading")
        XCTAssertFalse(app.buttons["Review this page"].exists)
        play.tap()
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
            let button = app.buttons[controlID]
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
