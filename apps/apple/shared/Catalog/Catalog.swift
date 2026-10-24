import Foundation

// One request gets enough time for a cold Gutendex search; the UI enforces the same deadline.
let catalogRequestTimeout: TimeInterval = 60

struct CatalogBook: Identifiable, Hashable, Sendable {
    let id: String
    let title: String
    var authors: [String] = []
    var languages: [String] = []
    var summary = ""
    var subjects: [String] = []
    var coverURL: URL?
    var epubURL: URL?
    var webURL: URL?
}
struct CatalogPage: Sendable {
    var books: [CatalogBook]
    var next: URL?
    var previous: URL?
    var total: Int?
}
func sameOrigin(_ a: URL, _ b: URL) -> Bool {
    a.scheme == b.scheme && a.host?.lowercased() == b.host?.lowercased() && (a.port ?? 443) == (b.port ?? 443)
}
func catalogURL(_ value: String?, relativeTo base: URL? = nil) -> URL? {
    guard let value, let url = URL(string: value, relativeTo: base)?.absoluteURL,
          url.scheme == "https", url.host != nil, url.user == nil, url.password == nil else { return nil }
    return url
}
enum CatalogError: LocalizedError {
    case message(String)
    var errorDescription: String? { if case .message(let message) = self { return message }; return nil }
    static let invalid = CatalogError.message("This catalog returned an invalid or unsupported response.")
}
protocol CatalogProvider: Sendable {
    var id: String { get }
    var name: String { get }
    var client: CatalogClient { get }
    func search(query: String, language: String, page: URL?) async throws -> CatalogPage
    func download(_ book: CatalogBook, progress: @escaping @Sendable (Double?) async -> Void) async throws -> Data
}
extension CatalogProvider {
    func download(_ book: CatalogBook, progress: @escaping @Sendable (Double?) async -> Void) async throws -> Data {
        guard let url = book.epubURL else { throw CatalogError.message("This edition has no supported EPUB download.") }
        let data = try await client.get(url, limit: 80 * 1_024 * 1_024, progress: progress)
        guard data.starts(with: [0x50, 0x4b, 0x03, 0x04]) else { throw CatalogError.message("The provider did not return an EPUB file.") }
        return data
    }
}

// Redirects must retain HTTPS and may not embed credentials in the URL.
private final class CatalogRedirects: NSObject, URLSessionTaskDelegate, @unchecked Sendable {
    func urlSession(_ session: URLSession, task: URLSessionTask, willPerformHTTPRedirection response: HTTPURLResponse,
                    newRequest request: URLRequest, completionHandler: @escaping (URLRequest?) -> Void) {
        guard let url = request.url, catalogURL(url.absoluteString) != nil else { completionHandler(nil); return }
        completionHandler(request)
    }
}
actor CatalogClient {
    private let session: URLSession
    private var cooldown: [String: Date] = [:]
    private var cache: [URL: (Date, Data)] = [:]
    private var cacheBytes = 0
    private let redirects = CatalogRedirects()
    init(session: URLSession? = nil) {
        let config = URLSessionConfiguration.ephemeral
        config.timeoutIntervalForRequest = catalogRequestTimeout
        config.timeoutIntervalForResource = 180
        config.httpMaximumConnectionsPerHost = 3
        config.urlCredentialStorage = nil
        self.session = session ?? URLSession(configuration: config)
    }
    func get(_ url: URL, limit: Int = 8 * 1_024 * 1_024,
             progress: @escaping @Sendable (Double?) async -> Void = { _ in }) async throws -> Data {
        guard catalogURL(url.absoluteString) != nil else { throw CatalogError.invalid }
        let host = url.host ?? ""
        if let until = cooldown[host], until > Date() { throw CatalogError.message("This provider asked us to wait. Try again in \(Int(until.timeIntervalSinceNow) + 1) seconds.") }
        if let (date, data) = cache[url], Date().timeIntervalSince(date) < 300, data.count <= limit { return data }
        var request = URLRequest(url: url)
        request.setValue("application/json, application/epub+zip, image/*", forHTTPHeaderField: "Accept")
        request.setValue("Still/0.1 (personal ebook reader)", forHTTPHeaderField: "User-Agent")
        let (bytes, response) = try await session.bytes(for: request, delegate: redirects)
        guard let http = response as? HTTPURLResponse else { throw CatalogError.invalid }
        if http.statusCode == 429 || http.statusCode == 503 {
            let retry = http.value(forHTTPHeaderField: "Retry-After") ?? "60"
            let formatter = DateFormatter(); formatter.locale = Locale(identifier: "en_US_POSIX"); formatter.timeZone = TimeZone(secondsFromGMT: 0); formatter.dateFormat = "EEE, dd MMM yyyy HH:mm:ss zzz"
            cooldown[host] = Double(retry).map { Date().addingTimeInterval(max(1, $0)) } ?? formatter.date(from: retry) ?? Date().addingTimeInterval(60)
            throw CatalogError.message("This provider is busy or has reached its request limit. Please wait before retrying.")
        }
        guard (200...299).contains(http.statusCode) else { throw CatalogError.message("The provider could not complete this request (HTTP \(http.statusCode)). Try again later.") }
        let expected = response.expectedContentLength
        guard expected <= limit else { throw CatalogError.message("This download is too large.") }
        var data = Data(); var chunk = Data(); chunk.reserveCapacity(65_536)
        for try await byte in bytes {
            chunk.append(byte)
            if chunk.count == 65_536 {
                try Task.checkCancellation()
                data.append(chunk); chunk.removeAll(keepingCapacity: true)
                guard data.count <= limit else { throw CatalogError.message("This download is too large.") }
                await progress(expected > 0 ? min(1, Double(data.count) / Double(expected)) : nil)
            }
        }
        data.append(chunk)
        guard data.count <= limit else { throw CatalogError.message("This download is too large.") }
        try Task.checkCancellation()
        await progress(1)
        if data.count <= 2 * 1_024 * 1_024 {
            if cacheBytes + data.count > 16 * 1_024 * 1_024 { cache.removeAll(); cacheBytes = 0 }
            if let old = cache.removeValue(forKey: url) { cacheBytes -= old.1.count }
            cache[url] = (Date(), data); cacheBytes += data.count
        }
        return data
    }
}

struct GutendexProvider: CatalogProvider {
    let id = "gutenberg"
    let name = "Project Gutenberg"
    var endpoint = URL(string: "https://gutendex.com/books/")!
    let client: CatalogClient
    init(client: CatalogClient = CatalogClient()) { self.client = client }
    func requestURL(query: String, language: String, page: URL?) throws -> URL {
        if let page { guard sameOrigin(page, endpoint) else { throw CatalogError.invalid }; return page }
        var url = URLComponents(url: endpoint, resolvingAgainstBaseURL: true)!
        // Keep public-domain and EPUB validation in parse; redundant server filters stall public queries.
        url.queryItems = []
        // Share cache entries for case-insensitive searches without changing the user's text.
        let normalized = query.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        if !normalized.isEmpty { url.queryItems?.append(URLQueryItem(name: "search", value: normalized)) }
        if !language.isEmpty { url.queryItems?.append(URLQueryItem(name: "languages", value: language)) }
        if url.queryItems?.isEmpty == true { url.queryItems = nil }
        return url.url!
    }
    func search(query: String, language: String, page: URL?) async throws -> CatalogPage {
        let url = try requestURL(query: query, language: language, page: page)
        return try Self.parse(try await client.get(url), base: url)
    }
    static func parse(_ data: Data, base: URL) throws -> CatalogPage {
        guard let root = try JSONSerialization.jsonObject(with: data) as? [String: Any], let results = root["results"] as? [[String: Any]], let count = root["count"] as? Int else { throw CatalogError.invalid }
        let books = results.compactMap { item -> CatalogBook? in
            guard let number = item["id"] as? Int, let title = item["title"] as? String, !title.isEmpty,
                  item["copyright"] as? Bool == false, let formats = item["formats"] as? [String: String],
                  let epub = catalogURL(formats["application/epub+zip"]) else { return nil }
            return CatalogBook(id: "gutenberg:\(number)", title: title,
                authors: (item["authors"] as? [[String: Any]] ?? []).compactMap { $0["name"] as? String },
                languages: item["languages"] as? [String] ?? [], summary: (item["summaries"] as? [String] ?? []).joined(separator: "\n\n"),
                subjects: item["subjects"] as? [String] ?? [], coverURL: catalogURL(formats["image/jpeg"]), epubURL: epub,
                webURL: URL(string: "https://www.gutenberg.org/ebooks/\(number)"))
        }
        return CatalogPage(books: books, next: catalogURL(root["next"] as? String, relativeTo: base), previous: catalogURL(root["previous"] as? String, relativeTo: base), total: count)
    }
}
