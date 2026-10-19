import Foundation

/// A transient view of the original PDF outline, independent of editable chapters.
struct PDFContentsEntry: Identifiable, Equatable, Sendable {
    let id: String
    let title: String
    let pageIndex: Int?
    let ancestors: [String]
    let hasChildren: Bool
    var level: Int { ancestors.count }
}

enum PDFContents {
    static func current(in entries: [PDFContentsEntry], page: Int, selectedID: String?) -> PDFContentsEntry? {
        if let selected = entries.first(where: { $0.id == selectedID && $0.pageIndex == page }) { return selected }
        return entries.enumerated().filter { $0.element.pageIndex.map { $0 <= page } ?? false }.max {
            let a = $0.element, b = $1.element
            if a.pageIndex != b.pageIndex { return a.pageIndex! < b.pageIndex! }
            if a.level != b.level { return a.level < b.level }
            return $0.offset < $1.offset
        }?.element
    }

    static func visible(_ entries: [PDFContentsEntry], collapsed: Set<String>) -> [PDFContentsEntry] {
        entries.filter { collapsed.isDisjoint(with: $0.ancestors) }
    }
}
