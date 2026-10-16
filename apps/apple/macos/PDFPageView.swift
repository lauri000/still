import SwiftUI
import PDFKit

struct PDFPageView: NSViewRepresentable {
    var url: URL
    var pageIndex: Int
    func makeCoordinator() -> PDFCoordinator { PDFCoordinator() }
    func makeNSView(context: Context) -> PDFView { context.coordinator.makeView() }
    func updateNSView(_ view: PDFView, context: Context) { context.coordinator.update(view, url: url, page: pageIndex) }
}
