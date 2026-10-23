import Foundation

final class CatalogXMLNode {
    let name: String
    let attributes: [String: String]
    var children: [CatalogXMLNode] = []
    var text = ""
    init(_ name: String, _ attributes: [String: String]) { self.name = name; self.attributes = attributes }
    func first(_ name: String) -> CatalogXMLNode? { descendants(name).first }
    func descendants(_ name: String) -> [CatalogXMLNode] { children.flatMap { ($0.name == name ? [$0] : []) + $0.descendants(name) } }
}
final class CatalogXML: NSObject, XMLParserDelegate {
    private var stack: [CatalogXMLNode] = []
    private var root: CatalogXMLNode?
    static func parse(_ data: Data) throws -> CatalogXMLNode {
        guard data.count <= 8 * 1_024 * 1_024, let source = String(data: data, encoding: .utf8), !source.contains("<!ENTITY") else { throw CatalogError.invalid }
        let parser = XMLParser(data: data)
        parser.shouldProcessNamespaces = true; parser.shouldResolveExternalEntities = false
        let delegate = CatalogXML(); parser.delegate = delegate
        guard parser.parse(), let root = delegate.root else { throw CatalogError.invalid }
        return root
    }
    func parser(_ parser: XMLParser, didStartElement name: String, namespaceURI: String?, qualifiedName: String?, attributes: [String: String]) {
        guard stack.count < 128 else { parser.abortParsing(); return }
        let node = CatalogXMLNode(name, attributes)
        if let parent = stack.last { parent.children.append(node) } else { root = node }
        stack.append(node)
        if ["p", "div", "section", "br", "li", "h1", "h2", "h3", "tr"].contains(name) { stack.dropLast().forEach { $0.text += "\n\n" } }
    }
    func parser(_ parser: XMLParser, foundCharacters string: String) {
        guard !stack.contains(where: { ["script", "style", "head"].contains($0.name) }) else { return }
        stack.forEach { $0.text += string }
    }
    func parser(_ parser: XMLParser, foundCDATA CDATABlock: Data) { self.parser(parser, foundCharacters: String(decoding: CDATABlock, as: UTF8.self)) }
    func parser(_ parser: XMLParser, didEndElement name: String, namespaceURI: String?, qualifiedName: String?) {
        stack.removeLast()
        if ["p", "div", "section", "li", "h1", "h2", "h3", "tr"].contains(name) { stack.forEach { $0.text += "\n\n" } }
    }
}
