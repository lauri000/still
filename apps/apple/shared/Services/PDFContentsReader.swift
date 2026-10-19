import PDFKit

extension PDFContents {
    static func read(url: URL) throws -> [PDFContentsEntry] {
        guard let document = PDFDocument(url: url), !document.isLocked else { throw PDFImportError.invalidPDF }
        return read(document: document)
    }

    static func read(document: PDFDocument) -> [PDFContentsEntry] {
        var entries: [PDFContentsEntry] = []
        func collect(_ parent: PDFOutline, ancestors: [String]) {
            // Bound malformed or unusually large outlines; never sort by destination.
            guard ancestors.count < 64 else { return }
            for index in 0..<parent.numberOfChildren {
                guard entries.count < 10_000 else { return }
                guard let child = parent.child(at: index) else { continue }
                let id = (ancestors.last.map { $0 + "." } ?? "") + String(index)
                let destination = child.destination ?? (child.action as? PDFActionGoTo)?.destination
                let page = destination?.page.map { document.index(for: $0) }
                let validPage = page.flatMap { (0..<document.pageCount).contains($0) ? $0 : nil }
                let title = child.label?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
                entries.append(PDFContentsEntry(id: id, title: title.isEmpty ? "Untitled section" : title,
                    pageIndex: validPage, ancestors: ancestors, hasChildren: child.numberOfChildren > 0))
                collect(child, ancestors: ancestors + [id])
            }
        }
        if let root = document.outlineRoot { collect(root, ancestors: []) }
        return entries
    }
}
