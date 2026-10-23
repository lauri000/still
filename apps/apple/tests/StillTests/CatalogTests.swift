import XCTest
import PDFKit
@testable import Still

private func catalogFixture(_ name: String, _ ext: String) throws -> Data {
    let url = try XCTUnwrap(Bundle(for: CatalogTests.self).url(forResource: name, withExtension: ext))
    return try Data(contentsOf: url)
}
final class CatalogTests: XCTestCase {
    func testGutendexMetadataPublicDomainFilterAndPagination() throws {
        let result = try GutendexProvider.parse(catalogFixture("catalog-gutendex", "json"), base: URL(string: "https://gutendex.com/books")!)
        XCTAssertEqual(result.total, 3); XCTAssertEqual(result.books.count, 1)
        XCTAssertEqual(result.books[0].id, "gutenberg:1"); XCTAssertEqual(result.books[0].authors, ["Reader, Still"])
        XCTAssertEqual(result.next?.absoluteString, "https://gutendex.com/books?page=2"); XCTAssertNil(result.previous)
    }
    func testSearchEncodingLanguageAndNextPage() throws {
        let provider = GutendexProvider()
        let url = try provider.requestURL(query: "Austen & Brontë", language: "en,fr", page: nil)
        let query = URLComponents(url: url, resolvingAgainstBaseURL: false)!.queryItems!
        XCTAssertEqual(query.first { $0.name == "search" }?.value, "Austen & Brontë")
        XCTAssertEqual(query.first { $0.name == "languages" }?.value, "en,fr")
        XCTAssertNil(query.first { $0.name == "copyright" }); XCTAssertNil(query.first { $0.name == "mime_type" })
        XCTAssertEqual(try provider.requestURL(query: "", language: "", page: nil).absoluteString, "https://gutendex.com/books/")
        let next = URL(string: "https://gutendex.com/books?page=2&languages=en")!
        XCTAssertEqual(try provider.requestURL(query: "different", language: "fr", page: next), next)
        XCTAssertThrowsError(try provider.requestURL(query: "", language: "", page: URL(string: "https://other.example/feed")!))
    }
    func testInvalidResponsesAndUnsafeURLsAreRejected() throws {
        for response in ["{}", "<html/>", #"{"count":1,"results":{}}"#] {
            XCTAssertThrowsError(try GutendexProvider.parse(Data(response.utf8), base: URL(string: "https://gutendex.com/books/")!))
        }
        for url in ["https://user:pass@test.example/feed", "file:///tmp/book", "http://test.example/book"] { XCTAssertNil(catalogURL(url)) }
    }
    func testEPUBImportPaginationTextMetadataCoverAndPersistence() async throws {
        let data = try catalogFixture("sample", "epub")
        let imported = try await EPUBImporter.extract(data: data, filename: "sample.epub") { _, _ in }
        XCTAssertEqual(imported.book.title, "A quiet test"); XCTAssertEqual(imported.book.author, "Still Reader")
        XCTAssertEqual(imported.book.epubIdentifier, "urn:still:epub-fixture"); XCTAssertEqual(imported.book.languageCodes, ["en"])
        XCTAssertEqual(imported.book.chapters.map(\.title), ["A beginning", "A conclusion"])
        XCTAssertEqual(imported.book.pages.count, 2)
        let text = imported.book.pages.map(\.text).joined()
        XCTAssertTrue(text.contains("Café, naïve, and 世界")); XCTAssertFalse(text.contains("Hidden"))
        XCTAssertTrue(text.hasSuffix("Last words & peaceful thoughts."))
        XCTAssertNotNil(imported.book.coverData); XCTAssertNotNil(imported.book.contentHash)
        XCTAssertFalse(imported.book.needsReprocessing)
        let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: root) }
        let repository = BookRepository(root: root)
        try repository.insert(imported.book, sourceData: imported.sourceData)
        let loaded = try XCTUnwrap(repository.load().books.first)
        XCTAssertEqual(loaded.contentHash, imported.book.contentHash); XCTAssertEqual(loaded.coverData, imported.book.coverData)
        XCTAssertEqual(try Data(contentsOf: repository.directory(for: loaded.id).appendingPathComponent("original.epub")), data)
        let backup = BookBackup(book: loaded, pdfData: imported.sourceData)
        let decoded = try JSONDecoder().decode(BookBackup.self, from: JSONEncoder().encode(backup))
        try decoded.validate(); XCTAssertEqual(decoded.originalData, data)
        XCTAssertFalse(Tokenizer.tokens(for: loaded).isEmpty)
    }
    @MainActor
    func testDownloadedEPUBUsesLibraryAndPreventsDuplicates() async throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: root) }
        let store = LibraryStore(repository: BookRepository(root: root))
        await store.load()
        let provider = FixtureProvider(data: try catalogFixture("sample", "epub"))
        let entry = CatalogBook(id: "test:one", title: "Catalog title", authors: ["Catalog author"], epubURL: URL(string: "https://test.example/book.epub")!)
        let first = expectation(description: "download imported")
        store.download(entry, provider: provider) { _ in first.fulfill() }
        await fulfillment(of: [first], timeout: 15)
        XCTAssertNil(store.errorMessage); XCTAssertEqual(store.books.count, 1)
        XCTAssertEqual(store.books.first?.title, "Catalog title"); XCTAssertEqual(store.books.first?.catalogID, entry.id)
        let second = expectation(description: "duplicate opens existing")
        store.download(entry, provider: provider) { _ in second.fulfill() }
        await fulfillment(of: [second], timeout: 2)
        XCTAssertEqual(store.books.count, 1)
        // A different catalog identity for the same EPUB is also deduplicated by content/identifier.
        let third = expectation(description: "same EPUB")
        var other = entry
        other = CatalogBook(id: "other:one", title: other.title, epubURL: other.epubURL)
        store.download(other, provider: provider) { _ in third.fulfill() }
        await fulfillment(of: [third], timeout: 15)
        XCTAssertEqual(store.books.count, 1)
        let saved = store.books[0]
        let backup = BookBackup(book: saved, pdfData: provider.data)
        let backupURL = root.appendingPathComponent("restore.json")
        try JSONEncoder().encode(backup).write(to: backupURL)
        let restored = expectation(description: "explicit backup creates separate copy")
        store.importFile(backupURL) { _ in restored.fulfill() }
        await fulfillment(of: [restored], timeout: 5)
        XCTAssertEqual(store.books.count, 2)
    }
    @MainActor
    func testCancelledDownloadDoesNotImport() async throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: root) }
        let store = LibraryStore(repository: BookRepository(root: root)); await store.load()
        store.download(CatalogBook(id: "test:cancel", title: "Cancel"), provider: FixtureProvider(data: Data(), delay: true)) { _ in XCTFail("Cancelled download imported") }
        store.cancelImport()
        try await Task.sleep(for: .milliseconds(50))
        XCTAssertFalse(store.isImporting); XCTAssertTrue(store.books.isEmpty); XCTAssertNil(store.errorMessage)
    }
    func testHTTPFailuresLimitsAndRateLimitCooldown() async throws {
        let config = URLSessionConfiguration.ephemeral; config.protocolClasses = [CatalogURLProtocol.self]
        let client = CatalogClient(session: URLSession(configuration: config))
        let url = URL(string: "https://test.example/feed")!
        for status in [401, 403, 500] {
            CatalogURLProtocol.status = status; CatalogURLProtocol.body = Data()
            do { _ = try await client.get(url); XCTFail("HTTP \(status) accepted") } catch { }
        }
        CatalogURLProtocol.status = 200; CatalogURLProtocol.body = Data(repeating: 1, count: 100)
        do { _ = try await client.get(url, limit: 10); XCTFail("Oversized response accepted") } catch { }
        CatalogURLProtocol.status = 429
        do { _ = try await client.get(url); XCTFail("Rate limit ignored") } catch { }
        CatalogURLProtocol.status = 200
        let count = CatalogURLProtocol.requests
        do { _ = try await client.get(url); XCTFail("Cooldown ignored") } catch { }
        XCTAssertEqual(CatalogURLProtocol.requests, count)
    }
}
private struct FixtureProvider: CatalogProvider {
    let id = "test", name = "Test"
    let client = CatalogClient()
    let data: Data
    var delay = false
    func search(query: String, language: String, page: URL?) async throws -> CatalogPage { CatalogPage(books: []) }
    func download(_ book: CatalogBook, progress: @escaping @Sendable (Double?) async -> Void) async throws -> Data {
        if delay { try await Task.sleep(for: .seconds(5)) }
        await progress(0.5); await progress(1); return data
    }
}
private final class CatalogURLProtocol: URLProtocol {
    static var status = 200
    static var body = Data()
    static var requests = 0
    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
    override func startLoading() {
        Self.requests += 1
        let response = HTTPURLResponse(url: request.url!, statusCode: Self.status, httpVersion: nil, headerFields: ["Content-Length": String(Self.body.count), "Retry-After": "60"])!
        client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
        client?.urlProtocol(self, didLoad: Self.body)
        client?.urlProtocolDidFinishLoading(self)
    }
    override func stopLoading() { }
}
