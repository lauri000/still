import SwiftUI

struct PDFContentsPreview: View {
    let book: Book
    let url: URL
    let page: Int
    let choosePage: (Int) -> Void
    @State private var entries: [PDFContentsEntry] = []
    @State private var loading = true
    @State private var failed = false
    @State private var panelOpen: Bool?
    @State private var sheetOpen = false
    @State private var collapsed: Set<String> = []
    @State private var selectedID: String?

    private var current: PDFContentsEntry? { PDFContents.current(in: entries, page: page, selectedID: selectedID) }

    var body: some View {
        // Observe loading state here even when the compact layout has no inline list.
        // Otherwise a sheet can capture the initial, still-loading view value.
        let hasOutline = !entries.isEmpty
        let isLoading = loading
        return GeometryReader { geometry in
            let wide = geometry.size.width >= 760
            let visible = wide && (panelOpen ?? hasOutline)
            VStack(alignment: .leading, spacing: 8) {
                Button {
                    if wide { panelOpen = !visible } else { sheetOpen = true }
                } label: { Label("Contents", systemImage: "list.bullet.indent").frame(minHeight: 36) }
                    .buttonStyle(.plain).foregroundStyle(Palette.forest)
                    .accessibilityIdentifier("pdfContents")
                    .accessibilityValue(isLoading ? "Loading" : (visible ? "Shown" : "Hidden"))
                HStack(spacing: 16) {
                    if visible { contents.frame(width: 280).accessibilityIdentifier("contentsSidebar") }
                    PDFPageView(url: url, pageIndex: page)
                        .clipShape(RoundedRectangle(cornerRadius: 8))
                        .overlay(RoundedRectangle(cornerRadius: 8).stroke(Palette.line))
                        .accessibilityLabel("PDF page \(page + 1) preview")
                }
            }
        }
        .sheet(isPresented: $sheetOpen) {
            VStack(spacing: 12) {
                HStack {
                    Text("Contents").font(.headline)
                    Spacer()
                    Button("Done") { sheetOpen = false }.accessibilityIdentifier("closeContents")
                }
                contents
            }.padding(20).background(Palette.paper)
                .presentationDetents([.large]).frame(idealWidth: 400, idealHeight: 600)
        }
        .task(id: url) {
            loading = true; failed = false; entries = []; collapsed = []; selectedID = nil
            let source = url
            do {
                let result = try await Task.detached(priority: .userInitiated) { try PDFContents.read(url: source) }.value
                guard !Task.isCancelled else { return }
                entries = result
            } catch { failed = true }
            loading = false
            revealCurrent()
        }
        .onChange(of: page) { _, _ in
            if !entries.contains(where: { $0.id == selectedID && $0.pageIndex == page }) { selectedID = nil }
            revealCurrent()
        }
    }

    private var contents: some View {
        ScrollViewReader { proxy in
            ScrollView {
                LazyVStack(alignment: .leading, spacing: 2) {
                    if loading { ProgressView("Loading contents…").padding() }
                    else if failed { message("Contents unavailable", "You can still choose a page using the page controls.") }
                    else if entries.isEmpty { message("No PDF contents", "This PDF has no embedded outline. Use the page controls to choose where to read.") }
                    else {
                        ForEach(PDFContents.visible(entries, collapsed: collapsed)) { entry in
                            row(entry).id(entry.id)
                        }
                    }
                }.padding(4)
            }
            .background(Palette.surface, in: RoundedRectangle(cornerRadius: 8))
            .onAppear { revealCurrent(); if let id = current?.id { proxy.scrollTo(id, anchor: .center) } }
            .onChange(of: current?.id) { _, id in
                if let id { proxy.scrollTo(id, anchor: .center) }
            }
        }
    }

    private func message(_ title: String, _ description: String) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(title).font(.headline)
            Text(description).font(.callout).foregroundStyle(Palette.muted)
        }.padding(16)
    }

    private func row(_ entry: PDFContentsEntry) -> some View {
        let selected = current?.id == entry.id
        return HStack(spacing: 0) {
            if entry.hasChildren {
                Button {
                    if !collapsed.insert(entry.id).inserted { collapsed.remove(entry.id) }
                } label: {
                    Image(systemName: collapsed.contains(entry.id) ? "chevron.right" : "chevron.down")
                        .font(.system(size: 11, weight: .semibold)).frame(width: 36, height: 44)
                }.buttonStyle(.plain)
                    .accessibilityLabel("\(collapsed.contains(entry.id) ? "Expand" : "Collapse") \(entry.title)")
                    .accessibilityIdentifier("contentsToggle-\(entry.id)")
            } else { Spacer().frame(width: 36) }
            Button {
                guard let destination = entry.pageIndex, book.pages.indices.contains(destination) else { return }
                selectedID = entry.id
                choosePage(destination)
                sheetOpen = false
            } label: {
                VStack(alignment: .leading, spacing: 4) {
                    Text(entry.title).font(.system(size: 14, weight: selected ? .semibold : .regular))
                        .fixedSize(horizontal: false, vertical: true)
                    Text(entry.pageIndex.flatMap { book.pages.indices.contains($0) ? "Page \(book.pages[$0].label)" : nil } ?? "No local page")
                        .font(.caption).foregroundStyle(Palette.muted)
                }.frame(maxWidth: .infinity, minHeight: 44, alignment: .leading).padding(.vertical, 6).padding(.trailing, 8)
                    .contentShape(Rectangle())
            }.buttonStyle(.plain).disabled(entry.pageIndex == nil)
                .accessibilityIdentifier("contentsEntry-\(entry.id)")
                .accessibilityAddTraits(selected ? [.isSelected] : [])
        }.padding(.leading, CGFloat(min(entry.level, 6)) * 12)
            .foregroundStyle(Palette.ink)
            .background(selected ? Palette.forest.opacity(0.1) : .clear, in: RoundedRectangle(cornerRadius: 6))
    }

    private func revealCurrent() {
        if let current { collapsed.subtract(current.ancestors) }
    }
}
