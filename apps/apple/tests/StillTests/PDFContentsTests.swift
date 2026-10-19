import XCTest
import PDFKit
@testable import Still

final class PDFContentsTests: XCTestCase {
    private func fixture(_ name: String) throws -> URL {
        try XCTUnwrap(Bundle(for: Self.self).url(forResource: name, withExtension: "pdf"))
    }

    func testOriginalOutlinePreservesHierarchyOrderAndLocalDestinations() throws {
        let entries = try PDFContents.read(url: fixture("outline"))
        XCTAssertEqual(entries.map(\.title), ["Part", "Later section", "Deep section", "Named section", "GoTo section", "Back to beginning", "Same page", "Untitled section", "Website", "Remote", "Broken named", "Out of range"])
        XCTAssertEqual(entries.map(\.pageIndex), [nil, 2, 2, 1, 3, 0, 0, 2, nil, nil, nil, nil])
        XCTAssertEqual(entries.map(\.level), [0, 1, 2, 1, 1, 0, 0, 0, 0, 0, 0, 0])
        XCTAssertEqual(entries[2].ancestors, ["0", "0.0"])
        XCTAssertTrue(entries[0].hasChildren)
        XCTAssertEqual(PDFContents.visible(entries, collapsed: ["0"]).count, 8)
        XCTAssertFalse(PDFContents.visible(entries, collapsed: ["0.0"]).contains { $0.title == "Deep section" })
    }

    func testCurrentSectionAndExplicitDuplicateSelection() throws {
        let entries = try PDFContents.read(url: fixture("outline"))
        XCTAssertEqual(PDFContents.current(in: entries, page: 0, selectedID: nil)?.title, "Same page")
        XCTAssertEqual(PDFContents.current(in: entries, page: 0, selectedID: "1")?.title, "Back to beginning")
        XCTAssertEqual(PDFContents.current(in: entries, page: 1, selectedID: "1")?.title, "Named section")
        XCTAssertEqual(PDFContents.current(in: entries, page: 2, selectedID: nil)?.title, "Deep section")
        XCTAssertEqual(PDFContents.current(in: entries, page: 3, selectedID: nil)?.title, "GoTo section")
        XCTAssertNil(PDFContents.current(in: entries, page: -1, selectedID: nil))
        XCTAssertEqual(PDFContents.current(in: entries, page: Int.max, selectedID: nil)?.title, "GoTo section")
    }

    func testEmptyOutlineAndUnreadablePDFDiffer() throws {
        XCTAssertTrue(try PDFContents.read(url: fixture("no-outline")).isEmpty)
        XCTAssertThrowsError(try PDFContents.read(url: URL(fileURLWithPath: "/missing-outline.pdf")))
    }

    func testOutlineBrowsingDoesNotMutateBookOrOriginalPDF() async throws {
        let url = try fixture("outline")
        let data = try Data(contentsOf: url)
        var imported = try await PDFImporter.extract(data: data, filename: "outline.pdf") { _, _ in }
        imported.book.setReadingStart(ReadingPosition(pageID: imported.book.pages[1].id, utf16Offset: 10))
        imported.book.position = ReadingPosition(pageID: imported.book.pages[2].id, utf16Offset: 15)
        let before = try JSONEncoder().encode(imported.book)
        let entries = try PDFContents.read(url: url)
        _ = PDFContents.current(in: entries, page: 0, selectedID: "1")
        // The outline has no mutation path into the portable model.
        XCTAssertEqual(imported.book.readingStart?.utf16Offset, 10)
        XCTAssertEqual(imported.book.position?.utf16Offset, 15)
        XCTAssertEqual(try JSONSerialization.jsonObject(with: before) as? NSDictionary,
                       try JSONSerialization.jsonObject(with: JSONEncoder().encode(imported.book)) as? NSDictionary)
        XCTAssertEqual(try Data(contentsOf: url), data)
        XCTAssertEqual(imported.pdfData, data)
    }
}
