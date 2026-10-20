import Foundation
import ZIPFoundation

/// A bounded, non-rendering EPUB reader. Sources remain in the archive; book scripts are never run.
enum EPUBImporter {
    static let entities: [String: Int] = {
        guard let url = Bundle.main.url(forResource: "xhtml-entities", withExtension: "json"),
              let data = try? Data(contentsOf: url) else { return [:] }
        return (try? JSONDecoder().decode([String: Int].self, from: data)) ?? [:]
    }()
    private static let maxXML = 16 * 1024 * 1024
    private struct Item { let id: String; let path: String; let type: String; let properties: Set<String> }
    private struct Link { let title: String; let path: String; let fragment: String?; let level: Int }
    private struct Content { let text: String; let anchors: [String: Int]; let headings: [TextHeading] }
    struct ImportError: LocalizedError {
        let message: String
        var errorDescription: String? { message }
    }
    private static func require(_ condition: Bool, _ message: String) throws {
        if !condition { throw ImportError(message: message) }
    }

    static func extract(data: Data, filename: String, entities: [String: Int] = Self.entities, progress: @Sendable (Int, Int) async -> Void = { _, _ in }) async throws -> ImportedBook {
        try require(data.count <= 250 * 1024 * 1024, "This EPUB is larger than 250 MB.")
        let archive = try Archive(data: data, accessMode: .read, pathEncoding: nil)
        let entries = Array(archive.prefix(10_001))
        try require(entries.count <= 10_000 && Set(entries.map(\.path)).count == entries.count, "This EPUB has too many or duplicate archive entries.")
        try require(entries.allSatisfy { $0.uncompressedSize <= 512 * 1024 * 1024 } && entries.reduce(UInt64(0)) { $0 + $1.uncompressedSize } <= 512 * 1024 * 1024, "This EPUB expands beyond the supported size.")
        func read(_ path: String) throws -> Data {
            try Task.checkCancellation()
            guard let entry = archive[path] else { throw ImportError(message: "The EPUB is missing a referenced file: \(path)") }
            try require(entry.uncompressedSize <= maxXML, "An EPUB text section is too large.")
            var result = Data()
            let checksum = try archive.extract(entry) { chunk in
                try Task.checkCancellation()
                try require(result.count + chunk.count <= maxXML, "An EPUB text section is too large.")
                result.append(chunk)
            }
            try require(checksum == entry.checksum, "An EPUB section is damaged.")
            return result
        }
        try require(String(data: read("mimetype"), encoding: .utf8)?.trimmingCharacters(in: .whitespacesAndNewlines) == "application/epub+zip", "This file is not an EPUB book.")
        func xml(_ data: Data) throws -> XMLNode { try Self.xml(data, entities: entities) }
        let container = try xml(read("META-INF/container.xml"))
        guard let rawPackage = container.descendants("rootfile").first?.attributes["full-path"], let packagePath = resolve(base: "", href: rawPackage)?.0 else {
            throw ImportError(message: "The EPUB has no package document.")
        }
        let pkg = try xml(read(packagePath))
        try require(pkg.name == "package" && ["2.0", "3.0"].contains(pkg.attributes["version"] ?? ""), "This EPUB version is not supported.")
        guard let metadata = pkg.child("metadata") else { throw ImportError(message: "The EPUB has no metadata.") }
        try require(!metadata.children.contains { $0.name == "meta" && $0.attributes["property"] == "rendition:layout" && $0.text.trimmingCharacters(in: .whitespacesAndNewlines) == "pre-paginated" },
                    "This is a fixed-layout EPUB. Still reads reflowable text books; illustrated page layouts are not supported yet.")
        let manifest: [Item] = try pkg.child("manifest")?.children.filter { $0.name == "item" }.map { node in
            guard let path = resolve(base: packagePath, href: node.attributes["href"] ?? "")?.0 else { throw ImportError(message: "The EPUB refers to an unsupported external resource.") }
            return Item(id: node.attributes["id"] ?? "", path: path, type: node.attributes["media-type"] ?? "", properties: Set((node.attributes["properties"] ?? "").split(separator: " ").map(String.init)))
        } ?? []
        try require(Set(manifest.map(\.id)).count == manifest.count, "The EPUB has duplicate resource identifiers.")
        let items = Dictionary(uniqueKeysWithValues: manifest.map { ($0.id, $0) })
        if archive["META-INF/encryption.xml"] != nil {
            let encryption = try xml(read("META-INF/encryption.xml"))
            for encrypted in encryption.descendants("EncryptedData") {
                let algorithm = encrypted.descendants("EncryptionMethod").first?.attributes["Algorithm"] ?? ""
                let path = encrypted.descendants("CipherReference").first?.attributes["URI"].flatMap { resolve(base: "", href: $0)?.0 }
                let font = manifest.contains { $0.path == path && ($0.type.hasPrefix("font/") || ["application/vnd.ms-opentype", "application/font-sfnt", "application/font-woff", "application/x-font-ttf"].contains($0.type)) }
                try require(font && ["http://www.idpf.org/2008/embedding", "http://ns.adobe.com/pdf/enc#RC"].contains(algorithm), "This EPUB contains encrypted content. Import a DRM-free copy.")
            }
        }
        guard let spine = pkg.child("spine") else { throw ImportError(message: "The EPUB has no reading order.") }
        let spineItems: [Item] = try spine.children.filter { $0.name == "itemref" && $0.attributes["linear"] != "no" }.map { ref in
            try require(!(ref.attributes["properties"] ?? "").contains("rendition:layout-pre-paginated"), "Fixed-layout EPUB sections are not supported yet.")
            guard let item = items[ref.attributes["idref"] ?? ""] else { throw ImportError(message: "The EPUB reading order references a missing resource.") }
            return item
        }
        try require(!spineItems.isEmpty && Set(spineItems.map(\.path)).count == spineItems.count, "The EPUB has no usable reading order or repeats a content file.")
        var links: [Link] = []
        if let nav = manifest.first(where: { $0.properties.contains("nav") }) {
            let toc = try xml(read(nav.path)).descendants("nav").first { $0.semanticTypes.contains("toc") }
            func collect(_ list: XMLNode, level: Int) {
                for li in list.children where li.name == "li" {
                    if let label = li.children.first(where: { ["a", "span"].contains($0.name) }), label.name == "a",
                       let destination = resolve(base: nav.path, href: label.attributes["href"] ?? "") {
                        links.append(Link(title: compact(label.text), path: destination.0, fragment: destination.1, level: level))
                    }
                    for list in li.children where list.name == "ol" { collect(list, level: level + 1) }
                }
            }
            if let list = toc?.child("ol") { collect(list, level: 0) }
        }
        if links.isEmpty, let ncx = items[spine.attributes["toc"] ?? ""] ?? manifest.first(where: { $0.type == "application/x-dtbncx+xml" }) {
            func collect(_ node: XMLNode, level: Int) {
                for point in node.children where point.name == "navPoint" {
                    if let source = point.child("content")?.attributes["src"], let destination = resolve(base: ncx.path, href: source) {
                        links.append(Link(title: compact(point.child("navLabel")?.text ?? ""), path: destination.0, fragment: destination.1, level: level))
                    }
                    collect(point, level: level + 1)
                }
            }
            if let map = try xml(read(ncx.path)).descendants("navMap").first { collect(map, level: 0) }
        }
        var pages: [BookPage] = [], contents: [String: (Int, Content)] = [:], total = 0
        for (index, item) in spineItems.enumerated() {
            try Task.checkCancellation()
            try require(item.type == "application/xhtml+xml", "This EPUB uses an unsupported content format. Still currently reads XHTML text sections.")
            let content = try text(xml(read(item.path)))
            if !content.text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                total += content.text.utf16.count
                try require(total <= 10_000_000, "This EPUB contains too much text to import.")
                contents[item.path] = (pages.count, content)
                pages.append(BookPage(id: UUID(), number: pages.count + 1, label: String(pages.count + 1), extractedText: content.text,
                    text: content.text, isReviewed: true, isIncluded: true, edited: false, sourceHref: item.path, headings: content.headings))
            }
            await progress(index + 1, spineItems.count)
        }
        try require(!pages.isEmpty, "This EPUB has no readable text. Image-only books are not supported yet.")
        var chapters = links.compactMap { link -> BookChapter? in
            guard !link.title.isEmpty, let (page, content) = contents[link.path] else { return nil }
            let offset: Int
            if let fragment = link.fragment, !fragment.isEmpty { guard let found = content.anchors[fragment] else { return nil }; offset = found }
            else { offset = 0 }
            return BookChapter(id: UUID(), title: link.title, pageIndex: page, level: link.level, source: "EPUB navigation", utf16Offset: offset, sourceAnchor: link.path + "#" + (link.fragment ?? ""))
        }
        if chapters.isEmpty {
            chapters = pages.enumerated().flatMap { index, page in (page.headings ?? []).enumerated().map { ordinal, h in
                BookChapter(id: UUID(), title: (page.text as NSString).substring(with: NSRange(location: h.start, length: h.end - h.start)),
                            pageIndex: index, level: h.level, source: "EPUB heading", utf16Offset: h.start, sourceAnchor: (page.sourceHref ?? "") + "#heading-\(ordinal)")
            } }
        }
        let title = compact(metadata.child("title")?.text ?? "")
        let book = Book(id: UUID(), title: title.isEmpty ? (filename as NSString).deletingPathExtension : title,
            author: metadata.children.filter { $0.name == "creator" }.map { compact($0.text) }.joined(separator: ", "),
            originalFilename: filename, importedAt: Date(), pages: pages, chapters: chapters, sourceFormat: "epub")
        try Task.checkCancellation()
        return ImportedBook(book: ReaderPipeline.prepare(book), sourceData: data)
    }

    static func isEPUB(_ data: Data) -> Bool {
        guard let archive = try? Archive(data: data, accessMode: .read, pathEncoding: nil), let entry = archive["mimetype"], entry.uncompressedSize == 20 else { return false }
        var mime = Data()
        do {
            _ = try archive.extract(entry) { chunk in
                try require(mime.count + chunk.count <= 20, "Invalid EPUB mimetype.")
                mime.append(chunk)
            }
            return mime == Data("application/epub+zip".utf8)
        } catch { return false }
    }

    private static func compact(_ text: String) -> String { text.split(whereSeparator: \.isWhitespace).joined(separator: " ") }
    private static func resolve(base: String, href: String) -> (String, String?)? {
        if href.range(of: "^[A-Za-z][A-Za-z0-9+.-]*:", options: .regularExpression) != nil || href.hasPrefix("/") || href.contains("\\") { return nil }
        let split = href.split(separator: "#", maxSplits: 1, omittingEmptySubsequences: false)
        guard let path = String(split[0].split(separator: "?", maxSplits: 1, omittingEmptySubsequences: false)[0]).removingPercentEncoding else { return nil }
        let parent = base.contains("/") ? String(base.prefix(upTo: base.lastIndex(of: "/")!)) : ""
        let joined = path.isEmpty ? base : (parent.isEmpty ? path : parent + "/" + path)
        var parts: [String] = []
        for part in joined.split(separator: "/", omittingEmptySubsequences: false) {
            if part.isEmpty || part == "." { continue }
            if part == ".." { guard !parts.isEmpty else { return nil }; parts.removeLast() }
            else {
                guard !part.contains("\0"), !part.contains("\\"), !part.contains(":") else { return nil }
                parts.append(String(part))
            }
        }
        let fragment = split.count > 1 ? String(split[1]).removingPercentEncoding : nil
        return (parts.joined(separator: "/"), fragment)
    }

    private static func xml(_ data: Data, entities: [String: Int]) throws -> XMLNode {
        let utf16 = data.starts(with: [0xff, 0xfe]) || data.starts(with: [0xfe, 0xff])
        guard var source = String(data: data, encoding: utf16 ? .utf16 : .utf8) else { throw ImportError(message: "An EPUB section has unsupported text encoding.") }
        try require(!source.contains("<!ENTITY") && source.range(of: "<!DOCTYPE[^>]*\\[", options: .regularExpression) == nil, "The EPUB contains unsupported XML entity declarations.")
        source = source.replacingOccurrences(of: "<!DOCTYPE[^>]*>", with: "", options: .regularExpression)
            .replacingOccurrences(of: "<\\?xml[^?]*\\?>", with: "", options: .regularExpression)
        let pattern = try! NSRegularExpression(pattern: "&([A-Za-z][A-Za-z0-9]+);")
        let mutable = NSMutableString(string: source)
        for match in pattern.matches(in: source, range: NSRange(location: 0, length: mutable.length)).reversed() {
            if let value = entities[mutable.substring(with: match.range(at: 1))] { mutable.replaceCharacters(in: match.range, with: "&#\(value);") }
        }
        source = mutable as String
        let parser = XMLParser(data: Data(source.utf8)), delegate = XMLTree()
        parser.shouldResolveExternalEntities = false
        parser.delegate = delegate
        guard parser.parse(), let root = delegate.root else { throw ImportError(message: "An EPUB section contains invalid XML.") }
        return root
    }

    private static func text(_ root: XMLNode) throws -> Content {
        guard let body = root.descendants("body").first else { throw ImportError(message: "An EPUB section has no body.") }
        let out = NSMutableString(); var anchors: [String: Int] = [:], headings: [TextHeading] = [], breaks = 0, space = false
        let blocks: Set<String> = ["p", "div", "section", "article", "header", "footer", "blockquote", "li", "dt", "dd", "tr", "pre", "h1", "h2", "h3", "h4", "h5", "h6"]
        func boundary(_ count: Int) { breaks = max(breaks, count); space = false }
        func append(_ value: String) {
            for scalar in value.unicodeScalars {
                if CharacterSet.whitespacesAndNewlines.contains(scalar) { if breaks == 0 { space = true } }
                else {
                    if out.length > 0 { if breaks > 0 { out.append(String(repeating: "\n", count: breaks)) } else if space { out.append(" ") } }
                    breaks = 0; space = false; out.append(String(scalar))
                }
            }
        }
        func walk(_ node: XMLNode, depth: Int) throws {
            try require(depth <= 128, "The EPUB markup is nested too deeply.")
            try Task.checkCancellation()
            let tag = node.name, style = (node.attributes["style"] ?? "").lowercased().replacingOccurrences(of: " ", with: "")
            if ["script", "style", "head", "nav", "svg", "rt", "rp", "img", "audio", "video"].contains(tag) || node.attributes["hidden"] != nil || node.attributes["aria-hidden"] == "true" || style.contains("display:none") || style.contains("visibility:hidden") { return }
            if node.semanticTypes.contains("pagebreak") { return }
            if tag == "br" { boundary(1); return }
            if blocks.contains(tag) { boundary(2) }
            let predicted = out.length + (out.length == 0 ? 0 : (breaks > 0 ? breaks : (space ? 1 : 0)))
            if let id = node.attributes["id"], !id.isEmpty, anchors[id] == nil { anchors[id] = predicted }
            let startBefore = out.length
            for item in node.content {
                switch item { case .text(let value): append(value); case .node(let child): try walk(child, depth: depth + 1) }
            }
            if ["h1", "h2", "h3", "h4", "h5", "h6"].contains(tag) {
                var start = startBefore
                while start < out.length, let scalar = UnicodeScalar(out.character(at: start)), CharacterSet.whitespacesAndNewlines.contains(scalar) { start += 1 }
                if start < out.length { headings.append(TextHeading(start: start, end: out.length, level: Int(tag.suffix(1))! - 1)) }
            }
            if blocks.contains(tag) { boundary(2) } else if ["td", "th"].contains(tag) { space = true }
        }
        try walk(body, depth: 0)
        return Content(text: out as String, anchors: anchors.mapValues { min($0, out.length) }, headings: headings)
    }

    private final class XMLNode {
        enum Content { case text(String), node(XMLNode) }
        let name: String, attributes: [String: String]
        var content: [Content] = []
        init(name: String, attributes: [String: String]) { self.name = name; self.attributes = attributes }
        var children: [XMLNode] { content.compactMap { if case .node(let node) = $0 { return node }; return nil } }
        var text: String { content.map { switch $0 { case .text(let s): s; case .node(let n): n.text } }.joined() }
        var semanticTypes: Set<String> { Set((attributes.first { $0.key.hasSuffix(":type") }?.value ?? "").split(whereSeparator: \.isWhitespace).map(String.init)) }
        func child(_ name: String) -> XMLNode? { children.first { $0.name == name } }
        func descendants(_ name: String) -> [XMLNode] { children.flatMap { ($0.name == name ? [$0] : []) + $0.descendants(name) } }
    }
    private final class XMLTree: NSObject, XMLParserDelegate {
        var root: XMLNode?, stack: [XMLNode] = []
        func parser(_ parser: XMLParser, didStartElement elementName: String, namespaceURI: String?, qualifiedName qName: String?, attributes: [String: String]) {
            guard stack.count < 128 else { parser.abortParsing(); return }
            let node = XMLNode(name: elementName.components(separatedBy: ":").last!, attributes: attributes)
            if let parent = stack.last { parent.content.append(.node(node)) } else { root = node }
            stack.append(node)
        }
        func parser(_ parser: XMLParser, didEndElement elementName: String, namespaceURI: String?, qualifiedName qName: String?) { if !stack.isEmpty { stack.removeLast() } }
        func parser(_ parser: XMLParser, foundCharacters string: String) { stack.last?.content.append(.text(string)) }
        func parser(_ parser: XMLParser, foundCDATA data: Data) { if let text = String(data: data, encoding: .utf8) { stack.last?.content.append(.text(text)) } }
        func parser(_ parser: XMLParser, foundInternalEntityDeclarationWithName name: String, value: String?) { parser.abortParsing() }
    }
}
