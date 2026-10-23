import SwiftUI

struct ReadingStartView: View {
    let book: Book
    let pdfURL: URL
    let onCancel: () -> Void
    let onSave: (ReadingPosition?) -> Void
    @State private var pageIndex: Int
    @State private var selectedOffset: Int
    @State private var words: [TextWord]
    @State private var scrollRequest = 0

    init(book: Book, pdfURL: URL, initialPage: Int, onCancel: @escaping () -> Void, onSave: @escaping (ReadingPosition?) -> Void) {
        self.book = book
        self.pdfURL = pdfURL
        self.onSave = onSave
        self.onCancel = onCancel
        let index = min(max(0, initialPage), book.pages.count - 1)
        _pageIndex = State(initialValue: index)
        _words = State(initialValue: book.pages[index].words)
        _selectedOffset = State(initialValue: book.readingStart?.pageID == book.pages[index].id ? book.readingStart!.utf16Offset : 0)
    }

    private var selectedWord: TextWord? { words.first { $0.offset + $0.length > selectedOffset } }

    var body: some View {
        let progress = BookProgress(book, readingOnly: false)
        VStack(alignment: .leading, spacing: 18) {
            HStack(alignment: .top) {
                VStack(alignment: .leading, spacing: 8) {
                    Text("Starting word").font(.system(size: 24, weight: .semibold))
                    Text("Tap the first word to keep.")
                        .font(.system(size: 12)).foregroundStyle(Palette.muted)
                }
                Spacer()
                Button { onCancel() } label: { Image(systemName: "xmark").frame(width: 40, height: 40) }
                    .buttonStyle(.plain).accessibilityLabel("Cancel trimming")
            }
            HStack(spacing: 12) {
                Button { changePage(pageIndex - 1) } label: { Image(systemName: "chevron.left").frame(width: 36, height: 40) }
                    .disabled(pageIndex == 0).accessibilityLabel(book.isEpub ? "Earlier text" : "Previous start page")
                if book.isEpub {
                    Menu {
                        ForEach(Array(book.pages.enumerated()), id: \.element.id) { index, _ in
                            Button(progress.textLabel(page: index)) { changePage(index) }
                        }
                    } label: { Text(progress.textLabel(page: pageIndex)).lineLimit(2) }
                    .accessibilityIdentifier("startTextLocation")
                } else {
                    Text(book.unitName).font(.system(size: 12))
                    TextField("\(book.unitName) number", value: Binding(get: { pageIndex + 1 }, set: { changePage($0 - 1) }), format: .number.grouping(.never))
                        .textFieldStyle(.roundedBorder).frame(width: 65).accessibilityIdentifier("startPageNumber")
                    Text("of \(book.pages.count)").font(.system(size: 12)).foregroundStyle(Palette.muted)
                }
                Button { changePage(pageIndex + 1) } label: { Image(systemName: "chevron.right").frame(width: 36, height: 40) }
                    .disabled(pageIndex == book.pages.count - 1).accessibilityLabel(book.isEpub ? "Later text" : "Next start page")
                Spacer()
                if !book.chapters.isEmpty {
                    Menu("Jump to chapter") {
                        ForEach(book.chapters.sorted { $0.pageIndex < $1.pageIndex }) { chapter in
                            Button(chapter.title) { changePage(chapter.pageIndex); selectedOffset = chapter.utf16Offset ?? 0; scrollRequest += 1 }
                        }
                    }.fixedSize()
                }
            }.buttonStyle(.plain)
            GeometryReader { geometry in
                if geometry.size.width > 650 && !book.isEpub {
                    HStack(spacing: 20) {
                        VStack(alignment: .leading, spacing: 10) {
                            Eyebrow(text: "Original PDF")
                            PDFPageView(url: pdfURL, pageIndex: pageIndex).clipShape(RoundedRectangle(cornerRadius: 10))
                        }.frame(maxWidth: .infinity)
                        wordPicker.frame(maxWidth: .infinity)
                    }
                } else { wordPicker }
            }
            if let word = selectedWord {
                Text(book.isEpub ? "Start at “\(word.text)”" : "Start at “\(word.text)” on page \(pageIndex + 1)\(book.pages[pageIndex].label != String(pageIndex + 1) ? " (printed \(book.pages[pageIndex].label))" : "").")
                    .font(.system(size: 12, weight: .medium)).foregroundStyle(Palette.forest).lineLimit(2)
            }
            HStack {
                if book.readingStart != nil {
                    Button("Restore beginning") { onSave(nil) }.buttonStyle(SecondaryButton()).accessibilityIdentifier("restoreBeginning")
                }
                Spacer()
                Button("Start book here") {
                    guard let word = selectedWord else { return }
                    onSave(ReadingPosition(pageID: book.pages[pageIndex].id, utf16Offset: word.offset))
                }.buttonStyle(PrimaryButton()).disabled(selectedWord == nil).accessibilityIdentifier("saveReadingStart")
            }
        }.padding(28).background(Palette.paper).foregroundStyle(Palette.ink)
            .frame(idealWidth: 960, idealHeight: 720)
            .presentationDetents([.large])
    }

    private var wordPicker: some View {
        let pageWords = words
        let activeOffset = pageWords.first { $0.offset + $0.length > selectedOffset }?.offset
        return VStack(alignment: .leading, spacing: 10) {
            Eyebrow(text: "Tap the first word to keep")
            ScrollViewReader { proxy in
                ScrollView {
                    if pageWords.isEmpty {
                        ContentUnavailableView("No text here", systemImage: "doc.text", description: Text("Choose another location, or add text in the editor first."))
                    } else {
                        // A spine document may contain a whole book; only nearby groups create word controls.
                        LazyVStack(alignment: .leading, spacing: 5) {
                            ForEach(Array(stride(from: 0, to: pageWords.count, by: 160)), id: \.self) { first in
                                FlowLayout(spacing: 5) {
                                    ForEach(pageWords[first..<min(first + 160, pageWords.count)], id: \.offset) { word in
                                        Button { selectedOffset = word.offset } label: {
                                            Text(word.text).font(.custom("Georgia", size: 17)).padding(.horizontal, 3).padding(.vertical, 6)
                                                .foregroundStyle(word.offset == activeOffset ? Palette.surface : (word.offset < selectedOffset ? Palette.muted.opacity(0.5) : Palette.ink))
                                                .background(word.offset == activeOffset ? Palette.forest : .clear, in: RoundedRectangle(cornerRadius: 4))
                                                .strikethrough(word.offset < selectedOffset)
                                        }.buttonStyle(.plain).accessibilityIdentifier("startWord-\(word.offset)")
                                            .accessibilityLabel("Start at \(word.text)")
                                    }
                                }.id(first)
                            }
                        }.padding(16)
                    }
                }.background(Palette.surface, in: RoundedRectangle(cornerRadius: 10))
                    .overlay(RoundedRectangle(cornerRadius: 10).stroke(Palette.line))
                    .onAppear { proxy.scrollTo((pageWords.firstIndex { $0.offset + $0.length > selectedOffset } ?? 0) / 160 * 160, anchor: .top) }
                    .onChange(of: scrollRequest) { _, _ in
                        proxy.scrollTo((pageWords.firstIndex { $0.offset + $0.length > selectedOffset } ?? 0) / 160 * 160, anchor: .top)
                    }
            }
        }
    }

    private func changePage(_ index: Int) {
        pageIndex = min(max(0, index), book.pages.count - 1)
        selectedOffset = 0
        words = book.pages[pageIndex].words
        scrollRequest += 1
    }
}

private struct FlowLayout: Layout {
    var spacing: CGFloat
    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        arrange(subviews, width: proposal.width ?? 600).size
    }
    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        let layout = arrange(subviews, width: bounds.width)
        for (index, point) in layout.positions.enumerated() {
            subviews[index].place(at: CGPoint(x: bounds.minX + point.x, y: bounds.minY + point.y), proposal: .unspecified)
        }
    }
    private func arrange(_ subviews: Subviews, width: CGFloat) -> (size: CGSize, positions: [CGPoint]) {
        var x: CGFloat = 0, y: CGFloat = 0, rowHeight: CGFloat = 0
        var positions: [CGPoint] = []
        for view in subviews {
            let size = view.sizeThatFits(.unspecified)
            if x > 0 && x + size.width > width { x = 0; y += rowHeight + spacing; rowHeight = 0 }
            positions.append(CGPoint(x: x, y: y))
            x += size.width + spacing
            rowHeight = max(rowHeight, size.height)
        }
        return (CGSize(width: width, height: y + rowHeight), positions)
    }
}
