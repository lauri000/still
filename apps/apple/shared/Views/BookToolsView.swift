import SwiftUI

struct BookToolsView: View {
    @EnvironmentObject private var library: LibraryStore
    let bookID: UUID
    @State var section = "Edit text"
    var initialPage: Int?
    var onBack: () -> Void
    var onRead: () -> Void
    @State private var pageIndex = 0
    @State private var initialized = false
    @State private var compactPanel = "Text"
    @State private var addingChapter = false
    @State private var confirmingReset = false
    @State private var titleDraft = ""
    @State private var authorDraft = ""
    @State private var chapterDraft = ""
    private var book: Book? { library.book(bookID) }

    var body: some View {
        Group {
            if let book {
                VStack(alignment: .leading, spacing: 0) {
                    HStack {
                        Button { library.flush(); onBack() } label: { Image(systemName: "chevron.left").frame(width: 40, height: 44) }
                            .buttonStyle(.plain).accessibilityLabel("Back to books").accessibilityIdentifier("closeBookTools")
                        Text(section).font(.headline)
                        Spacer()
                        if section == "Edit text" || section == "Chapters" {
                            Button("Read", action: onRead).buttonStyle(PrimaryButton()).accessibilityIdentifier("startReading")
                        }
                    }.padding(.horizontal, 20).padding(.vertical, 12)
                    Divider().overlay(Palette.line)
                    if section == "Chapters" { chaptersView(book) }
                    else if section == "Book details" { metadataSheet(book); Spacer() }
                    else if section == "Choose starting word" {
                        ReadingStartView(book: book, pdfURL: library.repository.sourceURL(for: book), initialPage: initialPage ?? book.position.flatMap { position in book.pages.firstIndex { $0.id == position.pageID } } ?? book.readingStartPageIndex, onCancel: onBack) { start in
                            library.update(bookID, saveImmediately: true) { $0.setReadingStart(start) }
                            onBack()
                        }
                    } else { reviewView(book) }
                }
                .background(Palette.paper)
                .sheet(isPresented: $addingChapter) { chapterSheet(book) }
                .confirmationDialog("Restore the extracted text?", isPresented: $confirmingReset, titleVisibility: .visible) {
                    Button("Restore original text", role: .destructive) { library.restoreExtractedText(bookID, pageIndex: pageIndex) }
                    Button("Cancel", role: .cancel) {}
                } message: { Text("Your corrections here will be replaced. The original book stays unchanged.") }
            }
        }
        .onAppear {
            guard !initialized, let book else { return }
            initialized = true
            pageIndex = initialPage ?? book.position.flatMap { position in book.pages.firstIndex { $0.id == position.pageID } } ?? book.readingStartPageIndex
            titleDraft = book.title; authorDraft = book.author
        }
        .onDisappear { library.flush() }
    }

    private func reviewView(_ book: Book) -> some View {
        let page = book.pages[min(pageIndex, book.pages.count - 1)]
        return VStack(spacing: 0) {
            HStack {
                Button { movePage(-1) } label: { Image(systemName: "chevron.left").frame(width: 40, height: 42) }.buttonStyle(.plain).disabled(pageIndex == 0).accessibilityLabel("Previous page")
                Menu {
                    ForEach(Array(book.pages.enumerated()), id: \.element.id) { index, item in
                        Button("\(item.isReviewed ? "✓ " : "")\(book.unitName) \(item.label)\(book.includesPage(at: index) ? "" : " · skipped")") { library.flush(); pageIndex = index }
                    }
                } label: {
                    HStack(spacing: 9) { Text("\(book.unitName) \(page.label)").font(.system(size: 13, weight: .semibold)); Image(systemName: "chevron.down").font(.system(size: 9)) }
                }.menuStyle(.borderlessButton).fixedSize()
                Text("of \(book.pages.count)").font(.system(size: 11)).foregroundStyle(Palette.muted)
                Button { movePage(1) } label: { Image(systemName: "chevron.right").frame(width: 40, height: 42) }.buttonStyle(.plain).disabled(pageIndex == book.pages.count - 1).accessibilityLabel("Next page")
                Spacer()
                if page.isReviewed { Label("Reviewed", systemImage: "checkmark.seal.fill").font(.system(size: 11)).foregroundStyle(Palette.forest) }
                else { Label(page.hasNoText ? "No text" : "Unchecked", systemImage: "circle.dotted").font(.system(size: 11)).foregroundStyle(Palette.muted) }
            }.padding(.horizontal, 20).padding(.vertical, 8)
            GeometryReader { geometry in
                if book.isEpub { textPanel(book, page: page) }
                else if geometry.size.width > 720 {
                    HStack(spacing: 18) {
                        originalPanel(book).frame(maxWidth: .infinity)
                        textPanel(book, page: page).frame(maxWidth: .infinity)
                    }
                } else {
                    VStack(spacing: 12) {
                        Picker("Page view", selection: $compactPanel) { Text("Original PDF").tag("PDF"); Text("Editable text").tag("Text") }.pickerStyle(.segmented)
                        if compactPanel == "PDF" { originalPanel(book) } else { textPanel(book, page: page) }
                    }
                }
            }.padding(.horizontal, 24)
            footer(book, page: page).padding(24)
        }
    }

    private func originalPanel(_ book: Book) -> some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack { Eyebrow(text: "Original PDF"); Spacer(); Image(systemName: "lock").font(.system(size: 10)).foregroundStyle(Palette.muted) }
            PDFPageView(url: library.repository.sourceURL(for: book), pageIndex: pageIndex)
                .clipShape(RoundedRectangle(cornerRadius: 10))
                .overlay(RoundedRectangle(cornerRadius: 10).stroke(Palette.line))
        }
    }

    private func textPanel(_ book: Book, page: BookPage) -> some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack {
                Eyebrow(text: "Your reading text")
                Spacer()
                if page.isEdited { Text("Edited").font(.system(size: 10)).foregroundStyle(Palette.muted) }
                Button { confirmingReset = true } label: { Image(systemName: "arrow.counterclockwise").font(.system(size: 12)) }.buttonStyle(.plain).accessibilityLabel("Restore extracted text")
            }
            VStack(alignment: .leading, spacing: 0) {
                if !page.warnings.isEmpty {
                    HStack(alignment: .top, spacing: 9) {
                        Image(systemName: "exclamationmark.circle")
                        Text(page.warnings.joined(separator: "\n")).fixedSize(horizontal: false, vertical: true)
                    }.font(.system(size: 11)).foregroundStyle(Palette.amber).padding(14).frame(maxWidth: .infinity, alignment: .leading).background(Palette.amber.opacity(0.06))
                }
                TextEditor(text: Binding(get: { library.book(bookID)?.pages[pageIndex].text ?? "" }, set: { library.editPage(bookID: bookID, pageID: page.id, text: $0) }))
                    .font(.custom("Georgia", size: 16)).lineSpacing(7)
                    .scrollContentBackground(.hidden).padding(12).foregroundStyle(Palette.ink)
                    .accessibilityIdentifier("pageTextEditor")
                HStack {
                    Text("\(Tokenizer.words(in: page.text).count) words")
                    Spacer()
                    Text("Edits reset review status")
                }.font(.system(size: 9)).foregroundStyle(Palette.muted).padding(14)
            }.background(Palette.surface, in: RoundedRectangle(cornerRadius: 10))
                .overlay(RoundedRectangle(cornerRadius: 10).stroke(Palette.line))
        }
    }

    private func footer(_ book: Book, page: BookPage) -> some View {
        ViewThatFits(in: .horizontal) {
            HStack { includeToggle(page); Spacer(); approveButton(book, page: page) }
            VStack(alignment: .leading, spacing: 14) { includeToggle(page); approveButton(book, page: page) }
        }
    }
    private func includeToggle(_ page: BookPage) -> some View {
        Toggle(isOn: Binding(get: { book?.includesPage(at: pageIndex) ?? false }, set: { value in
            library.update(bookID, saveImmediately: true) { book in book.pages[pageIndex].isIncluded = value; book.position?.finished = false }
        })) { Text(pageIndex < (book?.readingStartPageIndex ?? 0) ? "Before reading start" : "Include in reading").font(.system(size: 12)) }
            .toggleStyle(.switch).fixedSize().controlSize(.small).disabled(pageIndex < (book?.readingStartPageIndex ?? 0))
    }
    private func approveButton(_ book: Book, page: BookPage) -> some View {
        Button {
            library.update(bookID, saveImmediately: true) { $0.pages[pageIndex].isReviewed = true }
            if pageIndex < book.pages.count - 1 { pageIndex += 1 }
        } label: { Label(pageIndex == book.pages.count - 1 ? "Mark reviewed" : "Approve & next", systemImage: "checkmark") }
            .buttonStyle(PrimaryButton()).disabled(page.hasNoText || !page.isIncluded)
            .accessibilityIdentifier("approvePage")
    }
    private func movePage(_ amount: Int) {
        library.flush()
        pageIndex = max(0, min((book?.pages.count ?? 1) - 1, pageIndex + amount))
    }

    private func chaptersView(_ book: Book) -> some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 20) {
                HStack {
                    VStack(alignment: .leading, spacing: 6) {
                        Text("Chapters").font(.system(size: 22, weight: .semibold))
                        Text(book.isEpub ? "From EPUB contents or added manually." : "From PDF bookmarks or added manually.").font(.system(size: 12)).foregroundStyle(Palette.muted)
                    }
                    Spacer()
                    Button { chapterDraft = ""; addingChapter = true } label: { Label("Add chapter", systemImage: "plus") }.buttonStyle(SecondaryButton())
                }.padding(.bottom, 12)
                if book.chapters.isEmpty {
                    ContentUnavailableView("No chapters found", systemImage: "list.bullet.indent", description: Text("Add a chapter and its starting \(book.unitName.lowercased())."))
                }
                ForEach(book.chapters.sorted { $0.pageIndex < $1.pageIndex }) { chapter in
                    HStack(spacing: 18) {
                        Text(String(format: "%02d", chapter.pageIndex + 1)).font(.system(size: 12, design: .monospaced)).foregroundStyle(Palette.muted)
                        Button { pageIndex = chapter.pageIndex; section = "Edit text" } label: {
                            VStack(alignment: .leading, spacing: 5) {
                                Text(chapter.title).font(.custom("Georgia", size: 18))
                                Text("\(book.unitName) \(book.pages[chapter.pageIndex].label) · \(chapter.source)").font(.system(size: 10)).foregroundStyle(Palette.muted)
                            }.padding(.leading, CGFloat(min(chapter.level, 4)) * 12)
                        }.buttonStyle(.plain)
                        Spacer()
                        Button(role: .destructive) { library.update(bookID, saveImmediately: true) { $0.chapters.removeAll { $0.id == chapter.id }; $0.chaptersCustomized = true } } label: { Image(systemName: "minus.circle").frame(width: 44, height: 44) }.buttonStyle(.plain).foregroundStyle(Palette.muted).accessibilityLabel("Remove chapter \(chapter.title)")
                    }.padding(15).background(Palette.surface, in: RoundedRectangle(cornerRadius: 12))
                }
            }.padding(28)
        }
    }

    private func metadataSheet(_ book: Book) -> some View {
        VStack(alignment: .leading, spacing: 20) {
            TextField("Title", text: $titleDraft).textFieldStyle(.roundedBorder)
            TextField("Author", text: $authorDraft).textFieldStyle(.roundedBorder)
            HStack {
                Button("Cancel") { onBack() }.buttonStyle(SecondaryButton())
                Spacer()
                Button("Save details") {
                    library.update(bookID, saveImmediately: true) { $0.title = titleDraft.trimmingCharacters(in: .whitespacesAndNewlines); $0.author = authorDraft }
                    onBack()
                }.buttonStyle(PrimaryButton()).disabled(titleDraft.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
            }
        }.padding(30).frame(idealWidth: 440).background(Palette.paper)
    }
    private func chapterSheet(_ book: Book) -> some View {
        VStack(alignment: .leading, spacing: 20) {
            Text("A new chapter").font(.custom("Georgia", size: 27))
            TextField("Chapter title", text: $chapterDraft).textFieldStyle(.roundedBorder).accessibilityIdentifier("chapterTitle")
            Picker("Starting page", selection: $pageIndex) {
                ForEach(Array(book.pages.enumerated()), id: \.element.id) { index, page in Text("\(book.unitName) \(page.label)").tag(index) }
            }
            HStack {
                Button("Cancel") { addingChapter = false }.buttonStyle(SecondaryButton())
                Spacer()
                Button("Add chapter") {
                    library.update(bookID, saveImmediately: true) { $0.chapters.append(BookChapter(id: UUID(), title: chapterDraft.trimmingCharacters(in: .whitespacesAndNewlines), pageIndex: pageIndex, level: 0, source: "Added by you")); $0.chaptersCustomized = true }
                    addingChapter = false
                }.buttonStyle(PrimaryButton()).disabled(chapterDraft.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
            }
        }.padding(30).frame(idealWidth: 440).background(Palette.paper)
    }
}

extension View {
    @ViewBuilder
    func readerPresentation<Content: View>(item: Binding<Book?>, @ViewBuilder content: @escaping (Book) -> Content) -> some View {
        #if os(iOS)
        fullScreenCover(item: item, content: content)
        #else
        sheet(item: item) { book in content(book).frame(minWidth: 760, idealWidth: 1000, minHeight: 680, idealHeight: 800) }
        #endif
    }
}
