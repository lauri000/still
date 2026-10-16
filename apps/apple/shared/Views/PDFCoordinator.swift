import SwiftUI
import PDFKit

final class PDFCoordinator {
    private var url: URL?
    private var document: PDFDocument?
    private var pageIndex = -1
    func makeView() -> PDFView {
        let view = PDFView()
        view.autoScales = true
        view.displayMode = .singlePage
        view.displaysPageBreaks = false
        #if os(iOS)
        view.backgroundColor = UIColor(Palette.sidebar)
        #else
        view.backgroundColor = NSColor(Palette.sidebar)
        #endif
        return view
    }
    func update(_ view: PDFView, url: URL, page: Int) {
        if self.url != url { self.url = url; document = PDFDocument(url: url); pageIndex = -1 }
        guard page != pageIndex else { return }
        pageIndex = page
        // Isolate the page so PDF gestures cannot put the reference and editor out of sync.
        let singlePage = PDFDocument()
        if let copy = document?.page(at: page)?.copy() as? PDFPage { singlePage.insert(copy, at: 0) }
        view.document = singlePage
        view.autoScales = true
    }
}
