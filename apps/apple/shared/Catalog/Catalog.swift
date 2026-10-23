import Foundation

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
struct CatalogLink: Identifiable, Sendable {
    var id: String { url.absoluteString }
    let title: String
    let url: URL
}
struct CatalogPage: Sendable {
    var books: [CatalogBook]
    var next: URL?
    var previous: URL?
    var navigation: [CatalogLink] = []
    var searchTemplate: String?
    var total: Int?
    var searchDescription: URL?
}
struct CatalogCredentials: Sendable {
    let origin: URL
    let username: String
    let password: String
    func header(for url: URL) -> String? {
        guard sameOrigin(origin, url), !username.isEmpty else { return nil }
        return "Basic " + Data("\(username):\(password)".utf8).base64EncodedString()
    }
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

// Credentials are memory-only, origin-scoped, and never forwarded to another host.
private final class CatalogRedirects: NSObject, URLSessionTaskDelegate, @unchecked Sendable {
    func urlSession(_ session: URLSession, task: URLSessionTask, willPerformHTTPRedirection response: HTTPURLResponse,
                    newRequest request: URLRequest, completionHandler: @escaping (URLRequest?) -> Void) {
        guard let url = request.url, catalogURL(url.absoluteString) != nil else { completionHandler(nil); return }
        var safe = request
        if let original = task.originalRequest?.url, !sameOrigin(original, url) { safe.setValue(nil, forHTTPHeaderField: "Authorization") }
        completionHandler(safe)
    }
}
actor CatalogClient {
    private let credentials: CatalogCredentials?
    private let session: URLSession
    private var cooldown: [String: Date] = [:]
    private var cache: [URL: (Date, Data)] = [:]
    private var cacheBytes = 0
    private let redirects = CatalogRedirects()
    init(credentials: CatalogCredentials? = nil, session: URLSession? = nil) {
        self.credentials = credentials
        let config = URLSessionConfiguration.ephemeral
        config.timeoutIntervalForRequest = 30
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
        request.setValue("application/opds+json, application/atom+xml, application/json, application/epub+zip, image/*", forHTTPHeaderField: "Accept")
        request.setValue("Still/0.1 (personal ebook reader)", forHTTPHeaderField: "User-Agent")
        request.setValue(credentials?.header(for: url), forHTTPHeaderField: "Authorization")
        let (bytes, response) = try await session.bytes(for: request, delegate: redirects)
        guard let http = response as? HTTPURLResponse else { throw CatalogError.invalid }
        if http.statusCode == 429 || http.statusCode == 503 {
            let retry = http.value(forHTTPHeaderField: "Retry-After") ?? "60"
            let formatter = DateFormatter(); formatter.locale = Locale(identifier: "en_US_POSIX"); formatter.timeZone = TimeZone(secondsFromGMT: 0); formatter.dateFormat = "EEE, dd MMM yyyy HH:mm:ss zzz"
            cooldown[host] = Double(retry).map { Date().addingTimeInterval(max(1, $0)) } ?? formatter.date(from: retry) ?? Date().addingTimeInterval(60)
            throw CatalogError.message("This provider is busy or has reached its request limit. Please wait before retrying.")
        }
        if http.statusCode == 401 || http.statusCode == 403 { throw CatalogError.message("Catalog access requires valid credentials or membership. Check Catalog settings and your provider’s access requirements.") }
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
    var endpoint = URL(string: "https://gutendex.com/books")!
    let client: CatalogClient
    init(client: CatalogClient = CatalogClient()) { self.client = client }
    func requestURL(query: String, language: String, page: URL?) throws -> URL {
        if let page { guard sameOrigin(page, endpoint) else { throw CatalogError.invalid }; return page }
        var url = URLComponents(url: endpoint, resolvingAgainstBaseURL: true)!
        url.queryItems = [URLQueryItem(name: "mime_type", value: "application/epub+zip"), URLQueryItem(name: "copyright", value: "false")]
        if !query.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty { url.queryItems?.append(URLQueryItem(name: "search", value: query.trimmingCharacters(in: .whitespacesAndNewlines))) }
        if !language.isEmpty { url.queryItems?.append(URLQueryItem(name: "languages", value: language)) }
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

struct OPDSProvider: CatalogProvider {
    let id: String
    let name: String
    let endpoint: URL
    let client: CatalogClient
    func search(query: String, language: String, page: URL?) async throws -> CatalogPage {
        var url = page ?? endpoint
        var result = try Self.parse(try await client.get(url), base: url, providerID: id)
        if page == nil && !query.isEmpty {
            var advertised = result.searchTemplate
            if advertised == nil, let description = result.searchDescription {
                let document = try CatalogXML.parse(try await client.get(description))
                advertised = document.descendants("Url").first { ($0.attributes["type"] ?? "").contains("atom+xml") || ($0.attributes["type"] ?? "").contains("opds+json") }?.attributes["template"]
                url = description
            }
            guard let template = advertised else { throw CatalogError.message("This catalog does not advertise search. Browse its categories instead.") }
            url = try Self.searchURL(template, query: query, base: url)
            result = try Self.parse(try await client.get(url), base: url, providerID: id)
        }
        // OPDS has no mandatory language query. Filter the current page, keeping its pagination.
        if !language.isEmpty { result.books = result.books.filter { $0.languages.contains { $0.lowercased().split(separator: "-").first.map(String.init) == language.lowercased() } }; result.total = nil }
        return result
    }
    static func searchURL(_ template: String, query: String, base: URL) throws -> URL {
        var value = template
        let escaped = query.addingPercentEncoding(withAllowedCharacters: .alphanumerics) ?? ""
        let regex = try NSRegularExpression(pattern: "\\{([^}]+)\\}")
        let matches = regex.matches(in: value, range: NSRange(value.startIndex..., in: value))
        var hasQuery = false
        for match in matches.reversed() {
            let expression = (value as NSString).substring(with: match.range(at: 1))
            let prefix = expression.first == "?" ? "?" : "&"
            let fields = expression.drop(while: { $0 == "?" || $0 == "&" }).split(separator: ",").map(String.init)
            let replacement: String
            if expression == "query" || expression == "searchTerms" { replacement = escaped; hasQuery = true }
            else if fields.contains("query") || fields.contains("searchTerms") { replacement = prefix + (fields.contains("query") ? "query=" : "searchTerms=") + escaped; hasQuery = true }
            else { replacement = "" }
            value = (value as NSString).replacingCharacters(in: match.range, with: replacement)
        }
        guard hasQuery, let url = catalogURL(value, relativeTo: base) else { throw CatalogError.message("This catalog uses an unsupported search template.") }
        return url
    }
    static func parse(_ data: Data, base: URL, providerID: String) throws -> CatalogPage {
        if data.first(where: { ![9, 10, 13, 32].contains($0) }) == 60 { return try parseAtom(data, base: base, providerID: providerID) }
        guard let root = try JSONSerialization.jsonObject(with: data) as? [String: Any], root["metadata"] is [String: Any], root["publications"] != nil || root["navigation"] != nil || root["groups"] != nil else { throw CatalogError.invalid }
        func strings(_ value: Any?) -> [String] { if let s = value as? String { return [s] }; return value as? [String] ?? [] }
        func names(_ value: Any?) -> [String] { if let s = value as? String { return [s] }; if let o = value as? [String: Any] { return strings(o["name"]) }; return (value as? [Any] ?? []).flatMap { names($0) } }
        func links(_ item: [String: Any]) -> [[String: Any]] { item["links"] as? [[String: Any]] ?? [] }
        func relation(_ link: [String: Any], _ rel: String) -> Bool { strings(link["rel"]).contains(rel) }
        let groups = root["groups"] as? [[String: Any]] ?? []
        let publications = ([root] + groups).flatMap { $0["publications"] as? [[String: Any]] ?? [] }
        var seen = Set<String>()
        let books = publications.compactMap { item -> CatalogBook? in
            guard let meta = item["metadata"] as? [String: Any], let title = meta["title"] as? String, !title.isEmpty else { return nil }
            let download = links(item).first { link in
                (link["type"] as? String)?.split(separator: ";").first == "application/epub+zip" && strings(link["rel"]).contains { ["download", "acquisition", "http://opds-spec.org/acquisition", "http://opds-spec.org/acquisition/open-access"].contains($0) }
            }
            let epub = catalogURL(download?["href"] as? String, relativeTo: base)
            guard let identity = meta["identifier"] as? String ?? epub?.absoluteString, !identity.isEmpty, seen.insert(identity).inserted else { return nil }
            let images = item["images"] as? [[String: Any]] ?? []
            return CatalogBook(id: "\(providerID):\(identity)", title: title, authors: names(meta["author"]), languages: strings(meta["language"]), summary: plainCatalogText(meta["description"] as? String ?? ""), subjects: names(meta["subject"]), coverURL: catalogURL(images.first?["href"] as? String, relativeTo: base), epubURL: epub)
        }
        let navigation = ([root] + groups).flatMap { $0["navigation"] as? [[String: Any]] ?? [] }.compactMap { link -> CatalogLink? in
            guard let url = catalogURL(link["href"] as? String, relativeTo: base), (link["type"] as? String ?? "").contains("opds") else { return nil }
            return CatalogLink(title: link["title"] as? String ?? "Browse", url: url)
        }
        let allLinks = links(root)
        return CatalogPage(books: books, next: catalogURL(allLinks.first { relation($0, "next") }?["href"] as? String, relativeTo: base), previous: catalogURL(allLinks.first { relation($0, "previous") || relation($0, "prev") }?["href"] as? String, relativeTo: base), navigation: navigation,
            searchTemplate: allLinks.first { relation($0, "search") && ($0["type"] as? String ?? "").contains("opds") }?["href"] as? String,
            total: (root["metadata"] as? [String: Any])?["numberOfItems"] as? Int)
    }
    // Standard Ebooks also serves OPDS 1.x Atom. Keep this compatibility at the provider boundary.
    static func parseAtom(_ data: Data, base: URL, providerID: String) throws -> CatalogPage {
        let root = try CatalogXML.parse(data)
        guard root.name == "feed" else { throw CatalogError.invalid }
        func url(_ node: CatalogXMLNode?) -> URL? { catalogURL(node?.attributes["href"], relativeTo: base) }
        let links = root.children.filter { $0.name == "link" }
        var books: [CatalogBook] = []; var navigation: [CatalogLink] = []; var seen = Set<String>()
        for entry in root.children.filter({ $0.name == "entry" }) {
            let entryLinks = entry.children.filter { $0.name == "link" }
            let epub = url(entryLinks.first { $0.attributes["type"] == "application/epub+zip" && ["http://opds-spec.org/acquisition", "http://opds-spec.org/acquisition/open-access"].contains($0.attributes["rel"] ?? "") })
            let title = entry.first("title")?.text.trimmingCharacters(in: .whitespacesAndNewlines) ?? "Untitled"
            if let epub {
                let identity = entry.first("id")?.text ?? epub.absoluteString
                guard seen.insert(identity).inserted else { continue }
                books.append(CatalogBook(id: "\(providerID):\(identity)", title: title, authors: entry.children.filter { $0.name == "author" }.compactMap { $0.first("name")?.text }, languages: entry.first("language").map { [$0.text] } ?? [], summary: plainCatalogText(entry.first("summary")?.text ?? entry.first("content")?.text ?? ""), coverURL: url(entryLinks.first { ($0.attributes["rel"] ?? "").contains("image") }), epubURL: epub))
            } else if let link = entryLinks.first(where: { ($0.attributes["type"] ?? "").contains("atom+xml") }), let href = url(link) { navigation.append(CatalogLink(title: title, url: href)) }
        }
        return CatalogPage(books: books, next: url(links.first { $0.attributes["rel"] == "next" }), previous: url(links.first { ["previous", "prev"].contains($0.attributes["rel"] ?? "") }), navigation: navigation, searchTemplate: links.first { $0.attributes["rel"] == "search" && ($0.attributes["href"] ?? "").contains("{") }?.attributes["href"], searchDescription: url(links.first { $0.attributes["rel"] == "search" && ($0.attributes["type"] ?? "").contains("opensearchdescription") }))
    }
}
func plainCatalogText(_ value: String) -> String {
    value.replacingOccurrences(of: "<[^>]+>", with: " ", options: .regularExpression).replacingOccurrences(of: "&amp;", with: "&").replacingOccurrences(of: "&nbsp;", with: " ").trimmingCharacters(in: .whitespacesAndNewlines)
}
