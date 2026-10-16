import SwiftUI
import PDFKit

#if os(iOS)
struct PDFPageView: UIViewRepresentable {
    var url: URL
    var pageIndex: Int
    func makeCoordinator() -> PDFCoordinator { PDFCoordinator() }
    func makeUIView(context: Context) -> PDFView { context.coordinator.makeView() }
    func updateUIView(_ view: PDFView, context: Context) { context.coordinator.update(view, url: url, page: pageIndex) }
}
#else
struct PDFPageView: NSViewRepresentable {
    var url: URL
    var pageIndex: Int
    func makeCoordinator() -> PDFCoordinator { PDFCoordinator() }
    func makeNSView(context: Context) -> PDFView { context.coordinator.makeView() }
    func updateNSView(_ view: PDFView, context: Context) { context.coordinator.update(view, url: url, page: pageIndex) }
}
#endif

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
