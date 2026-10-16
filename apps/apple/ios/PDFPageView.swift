import SwiftUI
import PDFKit

struct PDFPageView: UIViewRepresentable {
    var url: URL
    var pageIndex: Int
    func makeCoordinator() -> PDFCoordinator { PDFCoordinator() }
    func makeUIView(context: Context) -> PDFView { context.coordinator.makeView() }
    func updateUIView(_ view: PDFView, context: Context) { context.coordinator.update(view, url: url, page: pageIndex) }
}
