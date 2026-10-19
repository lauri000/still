import SwiftUI

struct BookDetailView: View {
    @EnvironmentObject private var library: LibraryStore
    let bookID: UUID
    var onBack: () -> Void
    @State private var pageIndex = 0
    @State private var section = "Read"
    @State private var startPage = 0
    @State private var startPageChanged = false
    @State private var initialized = false
    @State private var compactPanel = "Text"
    @State private var readingBook: Book?
    @State private var editingMetadata = false
    @State private var addingChapter = false
    @State private var trimmingBeginning = false
    @State private var confirmingDelete = false
    @State private var confirmingReset = false
    @State private var backup: BookBackup?
    @State private var exporting = false
    @State private var titleDraft = ""
    @State private var authorDraft = ""
    @State private var chapterDraft = ""

    private var book: Book? { library.book(bookID) }

    var body: some View {
        Group {
            if let book {
                VStack(alignment: .leading, spacing: 0) {
                    header(book).padding(.horizontal, 24).padding(.vertical, 18)
                    Divider().overlay(Palette.line)
                    if section == "Read" {
                        readingSetup(book)
                    } else {
                        HStack {
                            Text(section).font(.headline)
                            Spacer()
                            Text(library.savedMessage).font(.caption).foregroundStyle(Palette.muted)
                            Button("Done") { library.flush(); section = "Read" }
                                .buttonStyle(SecondaryButton()).accessibilityIdentifier("closeBookTools")
                            Button("Read") { openReader() }.buttonStyle(PrimaryButton()).accessibilityIdentifier("startReading")
                        }.padding(.horizontal, 24).padding(.vertical, 12)
                        if section == "Chapters" { chaptersView(book) } else { reviewView(book) }
                    }
                }
                .background(Palette.paper)
                .readerPresentation(item: $readingBook) { snapshot in
                    ReaderView(book: snapshot, onReviewPage: { page in pageIndex = page; section = "Edit text" }) { position in
                        library.update(bookID, saveImmediately: true) { $0.position = position }
                        if let index = library.book(bookID)?.pages.firstIndex(where: { $0.id == position.pageID }) { startPage = index }
                        startPageChanged = false
                    }
                }
                .sheet(isPresented: $editingMetadata) { metadataSheet(book) }
                .sheet(isPresented: $addingChapter) { chapterSheet(book) }
                .sheet(isPresented: $trimmingBeginning) {
                    ReadingStartView(book: book, pdfURL: library.repository.pdfURL(for: bookID), initialPage: pageIndex) { start in
                        library.update(bookID, saveImmediately: true) { $0.setReadingStart(start) }
                        pageIndex = library.book(bookID)?.readingStartPageIndex ?? 0
                        startPage = pageIndex
                        startPageChanged = false
                    }
                }
                .fileExporter(isPresented: $exporting, document: backup, contentType: .json, defaultFilename: "\(book.title) — Still backup") { result in
                    if case .failure(let error) = result { library.errorMessage = error.localizedDescription }
                }
                .confirmationDialog("Delete “\(book.title)” from this device?", isPresented: $confirmingDelete, titleVisibility: .visible) {
                    Button("Delete book and corrections", role: .destructive) { library.delete(bookID); onBack() }
                    Button("Cancel", role: .cancel) {}
                } message: { Text("This removes the imported PDF, corrections, and reading progress. Export a backup first if you want to keep them.") }
                .confirmationDialog("Restore the extracted text on this page?", isPresented: $confirmingReset, titleVisibility: .visible) {
                    Button("Restore original text", role: .destructive) {
                        library.editPage(bookID: bookID, pageID: book.pages[pageIndex].id, text: book.pages[pageIndex].extractedText)
                    }
                    Button("Cancel", role: .cancel) {}
                } message: { Text("Your corrections on this page will be replaced. The PDF stays unchanged.") }
            }
        }
        .onAppear {
            guard !initialized, let book else { return }
            initialized = true
            startPage = book.position.flatMap { position in book.pages.firstIndex { $0.id == position.pageID } } ?? book.readingStartPageIndex
            pageIndex = startPage
        }
        .onDisappear { library.flush() }
    }

    private func header(_ book: Book) -> some View {
        HStack(spacing: 16) {
            Button(action: onBack) { Image(systemName: "chevron.left").frame(width: 40, height: 44) }
                .buttonStyle(.plain).accessibilityLabel("Back to books")
            Text(book.title).font(.system(size: 18, weight: .semibold)).lineLimit(1)
            Spacer()
            Menu {
                Button("Edit text", systemImage: "pencil") { library.flush(); pageIndex = startPage; section = "Edit text" }
                    .accessibilityIdentifier("editBookText")
                Button("Chapters", systemImage: "list.bullet") { section = "Chapters" }
                Button("Improve text", systemImage: "text.badge.checkmark") { library.improveText(bookID) }
                    .disabled(!book.pages.contains { PDFTextRepair.eligible($0) && $0.isIncluded })
                    .accessibilityIdentifier("improveBookText")
                if library.undoImprovementID == bookID {
                    Button("Undo text improvement", systemImage: "arrow.uturn.backward") { library.undoTextImprovement(bookID) }
                }
                Button("Choose starting word…", systemImage: "text.cursor") { library.flush(); pageIndex = startPage; trimmingBeginning = true }
                    .accessibilityIdentifier("trimBeginning")
                Divider()
                Button("Edit title and author", systemImage: "info.circle") { titleDraft = book.title; authorDraft = book.author; editingMetadata = true }
                Button("Export book backup…", systemImage: "square.and.arrow.up") {
                    do {
                        library.flush()
                        backup = BookBackup(book: book, pdfData: try Data(contentsOf: library.repository.pdfURL(for: book.id)))
                        exporting = true
                    } catch { library.errorMessage = error.localizedDescription }
                }
                Divider()
                Button("Delete book…", systemImage: "trash", role: .destructive) { confirmingDelete = true }
            } label: { Image(systemName: "ellipsis").frame(width: 44, height: 44) }
                .menuStyle(.borderlessButton).fixedSize().accessibilityLabel("Book options").accessibilityIdentifier("bookOptions")
        }
    }

    private func readingSetup(_ book: Book) -> some View {
        VStack(spacing: 20) {
            HStack(spacing: 12) {
                Text(book.position != nil && !startPageChanged ? "Page" : "Start page").font(.system(size: 14, weight: .medium))
                TextField("Page number", value: Binding(get: { startPage + 1 }, set: { choosePage($0 - 1) }), format: .number.grouping(.never))
                    .textFieldStyle(.roundedBorder).frame(width: 70).accessibilityIdentifier("readingPageNumber")
                Text("of \(book.pages.count)").font(.system(size: 13)).foregroundStyle(Palette.muted)
                Spacer()
                Button { choosePage(startPage - 1) } label: { Image(systemName: "chevron.left").frame(width: 44, height: 44) }
                    .disabled(startPage == 0).accessibilityLabel("Previous starting page")
                Button { choosePage(startPage + 1) } label: { Image(systemName: "chevron.right").frame(width: 44, height: 44) }
                    .disabled(startPage == book.pages.count - 1).accessibilityLabel("Next starting page")
            }.buttonStyle(.plain)
            PDFContentsPreview(book: book, url: library.repository.pdfURL(for: bookID), page: startPage, choosePage: choosePage)
            HStack {
                if book.pages[startPage].hasNoText {
                    Text("No selectable text on this page. Choose another page or use Edit text.")
                        .font(.caption).foregroundStyle(Palette.amber)
                }
                Spacer()
                Button {
                    openReader()
                } label: {
                    Label(book.position != nil && !startPageChanged ? (book.position?.finished == true ? "Read again" : "Continue reading") : "Read", systemImage: "play.fill")
                }
                .buttonStyle(PrimaryButton()).accessibilityIdentifier("startReading")
                .disabled(book.pages[startPage].hasNoText && (startPageChanged || book.position == nil))
            }
        }.padding(24).frame(maxWidth: 1200).frame(maxWidth: .infinity)
    }

    private func choosePage(_ index: Int) {
        guard let book else { return }
        startPage = min(max(0, index), book.pages.count - 1)
        startPageChanged = true
    }

    private func openReader() {
        guard let book else { return }
        if startPageChanged || book.position == nil {
            library.update(bookID, saveImmediately: true) { book in
                book.setReadingStart(ReadingPosition(pageID: book.pages[startPage].id, utf16Offset: 0))
            }
            startPageChanged = false
        }
        library.flush()
        readingBook = library.book(bookID)
    }

    private func reviewView(_ book: Book) -> some View {
        let page = book.pages[min(pageIndex, book.pages.count - 1)]
        return VStack(spacing: 0) {
            HStack {
                Button { movePage(-1) } label: { Image(systemName: "chevron.left").frame(width: 40, height: 42) }.buttonStyle(.plain).disabled(pageIndex == 0).accessibilityLabel("Previous page")
                Menu {
                    ForEach(Array(book.pages.enumerated()), id: \.element.id) { index, item in
                        Button("\(item.isReviewed ? "✓ " : "")Page \(item.label)\(book.includesPage(at: index) ? "" : " · skipped")") { library.flush(); pageIndex = index }
                    }
                } label: {
                    HStack(spacing: 9) { Text("Page \(page.label)").font(.system(size: 13, weight: .semibold)); Image(systemName: "chevron.down").font(.system(size: 9)) }
                }.menuStyle(.borderlessButton).fixedSize()
                Text("of \(book.pages.count)").font(.system(size: 11)).foregroundStyle(Palette.muted)
                Button { movePage(1) } label: { Image(systemName: "chevron.right").frame(width: 40, height: 42) }.buttonStyle(.plain).disabled(pageIndex == book.pages.count - 1).accessibilityLabel("Next page")
                Spacer()
                if page.isReviewed { Label("Reviewed", systemImage: "checkmark.seal.fill").font(.system(size: 11)).foregroundStyle(Palette.forest) }
                else { Label(page.hasNoText ? "No text" : "Unchecked", systemImage: "circle.dotted").font(.system(size: 11)).foregroundStyle(Palette.muted) }
            }.padding(.horizontal, 20).padding(.vertical, 8)
            GeometryReader { geometry in
                if geometry.size.width > 720 {
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
            PDFPageView(url: library.repository.pdfURL(for: bookID), pageIndex: pageIndex)
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
                Button { confirmingReset = true } label: { Image(systemName: "arrow.counterclockwise").font(.system(size: 12)) }.buttonStyle(.plain).disabled(page.text == page.extractedText).accessibilityLabel("Restore extracted text")
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
                        Text("From PDF bookmarks or added manually.").font(.system(size: 12)).foregroundStyle(Palette.muted)
                    }
                    Spacer()
                    Button { chapterDraft = ""; addingChapter = true } label: { Label("Add chapter", systemImage: "plus") }.buttonStyle(SecondaryButton())
                }.padding(.bottom, 12)
                if book.chapters.isEmpty {
                    ContentUnavailableView("No PDF bookmarks found", systemImage: "list.bullet.indent", description: Text("Add a chapter and its starting page."))
                }
                ForEach(book.chapters.sorted { $0.pageIndex < $1.pageIndex }) { chapter in
                    HStack(spacing: 18) {
                        Text(String(format: "%02d", chapter.pageIndex + 1)).font(.system(size: 12, design: .monospaced)).foregroundStyle(Palette.muted)
                        Button { pageIndex = chapter.pageIndex; section = "Edit text" } label: {
                            VStack(alignment: .leading, spacing: 5) {
                                Text(chapter.title).font(.custom("Georgia", size: 18))
                                Text("Page \(book.pages[chapter.pageIndex].label) · \(chapter.source)").font(.system(size: 10)).foregroundStyle(Palette.muted)
                            }.padding(.leading, CGFloat(min(chapter.level, 4)) * 12)
                        }.buttonStyle(.plain)
                        Spacer()
                        Button(role: .destructive) { library.update(bookID, saveImmediately: true) { $0.chapters.removeAll { $0.id == chapter.id } } } label: { Image(systemName: "minus.circle").frame(width: 44, height: 44) }.buttonStyle(.plain).foregroundStyle(Palette.muted).accessibilityLabel("Remove chapter \(chapter.title)")
                    }.padding(15).background(Palette.surface, in: RoundedRectangle(cornerRadius: 12))
                }
            }.padding(28)
        }
    }

    private func metadataSheet(_ book: Book) -> some View {
        VStack(alignment: .leading, spacing: 20) {
            Text("Book details").font(.custom("Georgia", size: 27))
            TextField("Title", text: $titleDraft).textFieldStyle(.roundedBorder)
            TextField("Author", text: $authorDraft).textFieldStyle(.roundedBorder)
            HStack {
                Button("Cancel") { editingMetadata = false }.buttonStyle(SecondaryButton())
                Spacer()
                Button("Save details") {
                    library.update(bookID, saveImmediately: true) { $0.title = titleDraft.trimmingCharacters(in: .whitespacesAndNewlines); $0.author = authorDraft }
                    editingMetadata = false
                }.buttonStyle(PrimaryButton()).disabled(titleDraft.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
            }
        }.padding(30).frame(idealWidth: 440).background(Palette.paper)
    }
    private func chapterSheet(_ book: Book) -> some View {
        VStack(alignment: .leading, spacing: 20) {
            Text("A new chapter").font(.custom("Georgia", size: 27))
            TextField("Chapter title", text: $chapterDraft).textFieldStyle(.roundedBorder).accessibilityIdentifier("chapterTitle")
            Picker("Starting page", selection: $pageIndex) {
                ForEach(Array(book.pages.enumerated()), id: \.element.id) { index, page in Text("Page \(page.label)").tag(index) }
            }
            HStack {
                Button("Cancel") { addingChapter = false }.buttonStyle(SecondaryButton())
                Spacer()
                Button("Add chapter") {
                    library.update(bookID, saveImmediately: true) { $0.chapters.append(BookChapter(id: UUID(), title: chapterDraft.trimmingCharacters(in: .whitespacesAndNewlines), pageIndex: pageIndex, level: 0, source: "Added by you")) }
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
