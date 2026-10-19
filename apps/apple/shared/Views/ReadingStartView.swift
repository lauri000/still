import SwiftUI

struct ReadingStartView: View {
    @Environment(\.dismiss) private var dismiss
    let book: Book
    let pdfURL: URL
    let onSave: (ReadingPosition?) -> Void
    @State private var pageIndex: Int
    @State private var selectedOffset: Int

    init(book: Book, pdfURL: URL, initialPage: Int, onSave: @escaping (ReadingPosition?) -> Void) {
        self.book = book
        self.pdfURL = pdfURL
        self.onSave = onSave
        let index = min(max(0, initialPage), book.pages.count - 1)
        _pageIndex = State(initialValue: index)
        _selectedOffset = State(initialValue: book.readingStart?.pageID == book.pages[index].id ? book.readingStart!.utf16Offset : 0)
    }

    private var words: [TextWord] { Tokenizer.words(in: book.pages[pageIndex].text) }
    private var selectedWord: TextWord? { words.first { $0.offset + $0.length > selectedOffset } }

    var body: some View {
        VStack(alignment: .leading, spacing: 18) {
            HStack(alignment: .top) {
                VStack(alignment: .leading, spacing: 8) {
                    Text("Starting word").font(.system(size: 24, weight: .semibold))
                    Text("Tap the first word to keep.")
                        .font(.system(size: 12)).foregroundStyle(Palette.muted)
                }
                Spacer()
                Button { dismiss() } label: { Image(systemName: "xmark").frame(width: 40, height: 40) }
                    .buttonStyle(.plain).accessibilityLabel("Cancel trimming")
            }
            HStack(spacing: 12) {
                Button { changePage(pageIndex - 1) } label: { Image(systemName: "chevron.left").frame(width: 36, height: 40) }
                    .disabled(pageIndex == 0).accessibilityLabel("Previous start page")
                Text("PDF page").font(.system(size: 12))
                TextField("Page number", value: Binding(get: { pageIndex + 1 }, set: { changePage($0 - 1) }), format: .number.grouping(.never))
                    .textFieldStyle(.roundedBorder).frame(width: 65).accessibilityIdentifier("startPageNumber")
                Text("of \(book.pages.count)").font(.system(size: 12)).foregroundStyle(Palette.muted)
                Button { changePage(pageIndex + 1) } label: { Image(systemName: "chevron.right").frame(width: 36, height: 40) }
                    .disabled(pageIndex == book.pages.count - 1).accessibilityLabel("Next start page")
                Spacer()
                if !book.chapters.isEmpty {
                    Menu("Jump to chapter") {
                        ForEach(book.chapters.sorted { $0.pageIndex < $1.pageIndex }) { chapter in
                            Button(chapter.title) { changePage(chapter.pageIndex) }
                        }
                    }.fixedSize()
                }
            }.buttonStyle(.plain)
            GeometryReader { geometry in
                if geometry.size.width > 650 {
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
                Text("Start at “\(word.text)” on PDF page \(pageIndex + 1)\(book.pages[pageIndex].label != String(pageIndex + 1) ? " (printed \(book.pages[pageIndex].label))" : "").")
                    .font(.system(size: 12, weight: .medium)).foregroundStyle(Palette.forest).lineLimit(2)
            }
            HStack {
                if book.readingStart != nil {
                    Button("Restore beginning") { onSave(nil); dismiss() }.buttonStyle(SecondaryButton()).accessibilityIdentifier("restoreBeginning")
                }
                Spacer()
                Button("Start book here") {
                    guard let word = selectedWord else { return }
                    onSave(ReadingPosition(pageID: book.pages[pageIndex].id, utf16Offset: word.offset))
                    dismiss()
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
            ScrollView {
                if pageWords.isEmpty {
                    ContentUnavailableView("No text on this page", systemImage: "doc.text", description: Text("Choose another page, or add text in the editor first."))
                } else {
                    FlowLayout(spacing: 5) {
                        ForEach(pageWords, id: \.offset) { word in
                            Button { selectedOffset = word.offset } label: {
                                Text(word.text).font(.custom("Georgia", size: 17)).padding(.horizontal, 3).padding(.vertical, 6)
                                    .foregroundStyle(word.offset == activeOffset ? Palette.surface : (word.offset < selectedOffset ? Palette.muted.opacity(0.5) : Palette.ink))
                                    .background(word.offset == activeOffset ? Palette.forest : .clear, in: RoundedRectangle(cornerRadius: 4))
                                    .strikethrough(word.offset < selectedOffset)
                            }.buttonStyle(.plain).accessibilityIdentifier("startWord-\(word.offset)")
                                .accessibilityLabel("Start at \(word.text)")
                        }
                    }.padding(16)
                }
            }.background(Palette.surface, in: RoundedRectangle(cornerRadius: 10))
                .overlay(RoundedRectangle(cornerRadius: 10).stroke(Palette.line))
        }
    }

    private func changePage(_ index: Int) {
        pageIndex = min(max(0, index), book.pages.count - 1)
        selectedOffset = 0
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
