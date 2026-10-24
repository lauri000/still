import XCTest
@testable import Still

final class CatalogStoreTests: XCTestCase {
    @MainActor
    private func eventually(_ condition: () async -> Bool) async throws {
        for _ in 0..<200 {
            if await condition() { return }
            try await Task.sleep(for: .milliseconds(5))
        }
        XCTFail("Catalog state did not settle")
    }
    @MainActor
    func testColdSearchCanFinishAfterFortySecondsWithoutRestarting() async throws {
        let provider = PendingSearchProvider()
        // Use the production deadline; this reproduces a server that sends no headers for 40s.
        let slow = CatalogStore(provider: provider)
        slow.query = "anarchy"; slow.load()
        try await eventually { await provider.count == 1 }
        try await Task.sleep(for: .seconds(40))
        XCTAssertTrue(slow.loading); XCTAssertTrue(slow.waiting); XCTAssertNil(slow.error)
        let count = await provider.count
        XCTAssertEqual(count, 1)
        await provider.finish(0, .success(CatalogPage(books: [CatalogBook(id: "gutenberg:4212", title: "Culture and Anarchy")])))
        try await eventually { !slow.loading }
        XCTAssertEqual(slow.page?.books.first?.title, "Culture and Anarchy")
        XCTAssertFalse(slow.waiting); XCTAssertNil(slow.error)
        slow.cancel()
    }

    @MainActor
    func testDeadlineEndsSpinnerAndRetryIgnoresOldResponse() async throws {
        let provider = PendingSearchProvider()
        let store = CatalogStore(provider: provider, requestTimeoutNanoseconds: 100_000_000)
        let next = URL(string: "https://gutendex.com/books/?page=2")!
        store.load(next)
        try await eventually { await provider.count == 1 }
        XCTAssertTrue(store.loading)
        try await eventually { !store.loading }
        XCTAssertTrue(store.error?.contains("took too long") == true); XCTAssertNil(store.page)
        store.retry()
        try await eventually { await provider.count == 2 }
        let pages = await provider.pages
        XCTAssertEqual(pages, [next, next])
        await provider.finish(1, .success(CatalogPage(books: [CatalogBook(id: "gutenberg:2", title: "New result")])))
        try await eventually { !store.loading }
        await provider.finish(0, .success(CatalogPage(books: [CatalogBook(id: "gutenberg:1", title: "Obsolete")])))
        try await Task.sleep(for: .milliseconds(150))
        XCTAssertEqual(store.page?.books.first?.title, "New result"); XCTAssertNil(store.error)
        store.cancel()
    }
    @MainActor
    func testCancelAndReplacementIgnoreLateFailure() async throws {
        let provider = PendingSearchProvider()
        let store = CatalogStore(provider: provider)
        store.query = "old"; store.load()
        try await eventually { await provider.count == 1 }
        store.stopLoading()
        XCTAssertFalse(store.loading); XCTAssertTrue(store.error?.contains("Loading stopped") == true)
        store.query = "Austen"; store.language = "en"; store.load()
        try await eventually { await provider.count == 2 }
        await provider.finish(0, .failure(CatalogError.message("Obsolete error")))
        await provider.finish(1, .success(CatalogPage(books: [])))
        try await eventually { !store.loading }
        let searches = await provider.searches
        XCTAssertEqual(searches.map { $0.0 }, ["old", "Austen"])
        XCTAssertEqual(searches.map { $0.1 }, ["", "en"])
        XCTAssertTrue(store.page?.books.isEmpty == true); XCTAssertNil(store.error)
        store.cancel()
    }
    @MainActor
    func testFailureStopsLoadingAndRetryClearsError() async throws {
        let provider = PendingSearchProvider()
        let store = CatalogStore(provider: provider)
        store.load()
        try await eventually { await provider.count == 1 }
        await provider.finish(0, .failure(CatalogError.message("Offline")))
        try await eventually { !store.loading }
        XCTAssertEqual(store.error, "Offline")
        store.retry()
        try await eventually { await provider.count == 2 }
        XCTAssertNil(store.error)
        await provider.finish(1, .success(CatalogPage(books: [])))
        try await eventually { !store.loading }
        XCTAssertNil(store.error); XCTAssertNotNil(store.page)
        store.cancel()
    }
}
private actor PendingSearchProvider: CatalogProvider {
    let id = "test", name = "Project Gutenberg"
    let client = CatalogClient()
    var pages: [URL?] = []
    var searches: [(String, String)] = []
    private var pending: [CheckedContinuation<CatalogPage, Error>] = []
    var count: Int { pending.count }
    func search(query: String, language: String, page: URL?) async throws -> CatalogPage {
        pages.append(page); searches.append((query, language))
        // Simulate a network operation that keeps waiting after cancellation.
        return try await withCheckedThrowingContinuation { pending.append($0) }
    }
    func finish(_ index: Int, _ result: Result<CatalogPage, Error>) { pending[index].resume(with: result) }
}
