import Foundation

/// A transient view of the original PDF outline, independent of editable chapters.
struct PDFContentsEntry: Identifiable, Equatable, Sendable {
    let id: String
    let title: String
    let pageIndex: Int?
    let ancestors: [String]
    let hasChildren: Bool
    var utf16Offset: Int? = nil
    var level: Int { ancestors.count }
}

enum PDFContents {
    static func fromChapters(_ book: Book) -> [PDFContentsEntry] {
        var stack: [(Int, String)] = []
        return book.chapters.enumerated().map { index, chapter in
            while let last = stack.last, last.0 >= chapter.level { stack.removeLast() }
            let id = "chapter-" + chapter.id.uuidString
            let entry = PDFContentsEntry(id: id, title: chapter.title, pageIndex: chapter.pageIndex,
                ancestors: stack.map { $0.1 }, hasChildren: index + 1 < book.chapters.count && book.chapters[index + 1].level > chapter.level,
                utf16Offset: chapter.utf16Offset)
            stack.append((chapter.level, id))
            return entry
        }
    }

    static func current(in entries: [PDFContentsEntry], page: Int, selectedID: String?, utf16Offset: Int? = nil) -> PDFContentsEntry? {
        if let selected = entries.first(where: { entry in entry.id == selectedID && entry.pageIndex == page && (utf16Offset.map { (entry.utf16Offset ?? 0) <= $0 } ?? true) }) { return selected }
        return entries.enumerated().filter { item in item.element.pageIndex.map { destination in destination < page || destination == page && (utf16Offset.map { (item.element.utf16Offset ?? 0) <= $0 } ?? true) } ?? false }.max {
            let a = $0.element, b = $1.element
            if a.pageIndex != b.pageIndex { return a.pageIndex! < b.pageIndex! }
            if utf16Offset != nil, a.utf16Offset != b.utf16Offset { return (a.utf16Offset ?? 0) < (b.utf16Offset ?? 0) }
            if a.level != b.level { return a.level < b.level }
            return $0.offset < $1.offset
        }?.element
    }

    static func visible(_ entries: [PDFContentsEntry], collapsed: Set<String>) -> [PDFContentsEntry] {
        entries.filter { collapsed.isDisjoint(with: $0.ancestors) }
    }
}
