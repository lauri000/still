import SwiftUI
import UniformTypeIdentifiers

struct LibraryView: View {
    @EnvironmentObject private var library: LibraryStore
    @Environment(\.scenePhase) private var scenePhase
    @State private var selectedID: UUID?
    @State private var tool = "Edit text"
    @State private var toolPage: Int?
    @State private var readingBook: Book?
    @State private var openingID: UUID?
    @State private var openTask: Task<Void, Never>?
    @State private var preparationBook: Book?
    @State private var deletingBook: Book?
    @State private var backup: BookBackup?
    @State private var exportTitle = "Book"
    @State private var exporting = false
    @State private var importing = false
    @State private var search = ""
    @State private var discovering = false

    private var filteredBooks: [Book] {
        library.books.filter { search.isEmpty || $0.title.localizedCaseInsensitiveContains(search) || $0.author.localizedCaseInsensitiveContains(search) }
    }

    var body: some View {
        bookActions
        .fileExporter(isPresented: $exporting, document: backup, contentType: .json, defaultFilename: "\(exportTitle) — Still backup") { result in
            if case .failure(let error) = result { library.errorMessage = error.localizedDescription }
        }
        .alert("Couldn’t complete that", isPresented: Binding(get: { library.errorMessage != nil }, set: { if !$0 { library.errorMessage = nil } })) {
            Button("OK", role: .cancel) { library.errorMessage = nil }
        } message: { Text(library.errorMessage ?? "") }
        .onChange(of: scenePhase) { _, phase in if phase != .active { library.flush() } }
    }

    private var bookActions: some View {
        readerContent
        .overlay {
            if library.isImporting { importOverlay }
            else if openingID != nil {
                ZStack {
                    Color.black.opacity(0.2).ignoresSafeArea()
                    VStack(spacing: 18) {
                        ProgressView("Opening book…")
                        Button("Cancel") { openTask?.cancel(); openTask = nil; openingID = nil }.buttonStyle(SecondaryButton())
                    }.padding(30).background(Palette.surface, in: RoundedRectangle(cornerRadius: 14))
                }
            }
        }
        .alert("Prepare this book?", isPresented: Binding(get: { preparationBook != nil }, set: { if !$0 { preparationBook = nil } }), presenting: preparationBook) { book in
            Button("Reprocess and read") {
                preparationBook = nil
                library.reprocess(book.id) { openBook(book.id) }
            }.accessibilityIdentifier("prepareBook")
            Button("Cancel", role: .cancel) { preparationBook = nil }
        } message: { _ in Text("This older book needs to be reprocessed before reading. Your corrections and page choices are kept.") }
        .confirmationDialog("Delete “\(deletingBook?.title ?? "")” from this device?", isPresented: Binding(get: { deletingBook != nil }, set: { if !$0 { deletingBook = nil } }), titleVisibility: .visible, presenting: deletingBook) { book in
            Button("Delete book and corrections", role: .destructive) { library.delete(book.id); deletingBook = nil }
            Button("Cancel", role: .cancel) { deletingBook = nil }
        } message: { _ in Text("This removes the imported book, corrections, and reading progress from this device.") }
    }

    private var readerContent: some View {
        Group {
            if let id = selectedID, library.book(id) != nil {
                BookToolsView(bookID: id, section: tool, initialPage: toolPage, onBack: { selectedID = nil }, onRead: { selectedID = nil; openBook(id) }).id(id)
            } else if discovering {
                CatalogView(onBack: { discovering = false }, onImported: { discovering = false; openBook($0) })
            } else {
                libraryContent
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(Palette.paper).foregroundStyle(Palette.ink)
        .preferredColorScheme(.light)
        .fileImporter(isPresented: $importing, allowedContentTypes: [.pdf, .json, UTType(filenameExtension: "epub") ?? .data], allowsMultipleSelection: false) { result in
            switch result {
            case .success(let urls): if let url = urls.first { library.importFile(url) { openBook($0) } }
            case .failure(let error): library.errorMessage = error.localizedDescription
            }
        }
        .readerPresentation(item: $readingBook) { snapshot in
            ReaderView(book: snapshot, pdfURL: library.repository.sourceURL(for: snapshot), onReviewPage: { page in
                tool = "Edit text"; toolPage = page; selectedID = snapshot.id
            }) { position in
                library.update(snapshot.id, saveImmediately: true) { $0.position = position }
            }
        }
        .onChange(of: readingBook?.id) { old, current in
            if current == nil, let old { library.book(old)?.readerCache.clear() }
        }
    }

    private var libraryContent: some View {
        VStack(alignment: .leading, spacing: 24) {
            ViewThatFits(in: .horizontal) {
                HStack {
                    Text("Books").font(.system(size: 28, weight: .semibold)).fixedSize()
                    Spacer()
                    addBookActions
                }
                VStack(alignment: .leading, spacing: 18) {
                    Text("Books").font(.system(size: 28, weight: .semibold))
                    addBookActions
                }
            }
            if library.books.count > 5 {
                TextField("Search books", text: $search).textFieldStyle(.roundedBorder)
                    .accessibilityLabel("Search books")
            }
            if filteredBooks.isEmpty {
                ContentUnavailableView(search.isEmpty ? "No books yet" : "No matching books", systemImage: "doc", description: Text(search.isEmpty ? "Import a PDF or EPUB, or discover a book to read." : "Try another title."))
            } else {
                ScrollView {
                    LazyVStack(spacing: 0) {
                        ForEach(filteredBooks) { book in
                            HStack(spacing: 8) {
                                Button { openBook(book.id) } label: {
                                    HStack(spacing: 18) {
                                        if let data = book.coverData, let cover = platformImage(data) {
                                            cover.resizable().scaledToFit().frame(width: 42, height: 60)
                                        } else { Image(systemName: "doc.text").font(.system(size: 24)).foregroundStyle(Palette.muted) }
                                        VStack(alignment: .leading, spacing: 6) {
                                            Text(book.title).font(.system(size: 17, weight: .medium)).lineLimit(2)
                                            Text(bookStatus(book)).font(.system(size: 12)).foregroundStyle(Palette.muted)
                                        }
                                        Spacer()
                                    }.padding(.vertical, 22).padding(.horizontal, 4).contentShape(Rectangle())
                                }.buttonStyle(.plain).accessibilityIdentifier("book-\(book.title)")
                                bookOptions(book)
                            }
                            Divider().overlay(Palette.line)
                        }
                    }
                }
            }
            Spacer(minLength: 0)
        }.padding(28).frame(maxWidth: 900).frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
    }

    private func openBook(_ id: UUID) {
        openTask?.cancel()
        openingID = id
        openTask = Task {
            defer { if !Task.isCancelled && openingID == id { openingID = nil; openTask = nil } }
            await library.waitForSaves()
            guard !Task.isCancelled, let book = library.book(id) else { return }
            if book.needsReprocessing {
                preparationBook = book; openingID = nil; openTask = nil
                return
            }
            _ = await Task.detached { book.readerCache.get(book) }.value
            guard !Task.isCancelled, openingID == id else { return }
            openingID = nil; openTask = nil
            guard library.book(id) == book else { return }
            readingBook = book
        }
    }

    private func bookOptions(_ book: Book) -> some View {
        Menu {
            ForEach(["Edit text", "Chapters", "Choose starting word", "Book details"], id: \.self) { name in
                Button(name) { tool = name; toolPage = nil; selectedID = book.id }
            }
            Button("Reprocess book", systemImage: "arrow.clockwise") { library.reprocess(book.id) }
                .accessibilityIdentifier("reprocessBook")
            if library.undoReprocessID == book.id {
                Button("Undo reprocessing", systemImage: "arrow.uturn.backward") { library.undoReprocess(book.id) }
            }
            Button("Export book backup…", systemImage: "square.and.arrow.up") {
                Task {
                    await library.waitForSaves()
                    guard let current = library.book(book.id) else { return }
                    do {
                        backup = BookBackup(book: current, pdfData: try Data(contentsOf: library.repository.sourceURL(for: current)))
                        exportTitle = current.title; exporting = true
                    } catch { library.errorMessage = error.localizedDescription }
                }
            }
            Button("Delete book…", systemImage: "trash", role: .destructive) { deletingBook = book }
        } label: { Image(systemName: "ellipsis").frame(width: 44, height: 44) }
            .menuStyle(.borderlessButton).fixedSize()
            .accessibilityLabel("Book options for \(book.title)").accessibilityIdentifier("bookOptions-\(book.id)")
    }

    private var addBookActions: some View {
        HStack {
            Button { discovering = true } label: { Label("Discover", systemImage: "books.vertical") }
                .buttonStyle(SecondaryButton()).accessibilityIdentifier("discoverBooks")
            Button { importing = true } label: { Label("Import file", systemImage: "plus") }
                .buttonStyle(PrimaryButton()).accessibilityIdentifier("importPDF")
        }.fixedSize(horizontal: true, vertical: false)
    }

    private func bookStatus(_ book: Book) -> String {
        if let position = book.position, let page = book.pages.firstIndex(where: { $0.id == position.pageID }) {
            return position.finished ? "Finished · \(book.pages.count) \(book.unitPlural)" : "\(book.unitName) \(page + 1) of \(book.pages.count)"
        }
        return "\(book.pages.count) \(book.unitPlural)"
    }

    private var importOverlay: some View {
        ZStack {
            Color.black.opacity(0.2).ignoresSafeArea()
            VStack(spacing: 18) {
                Text("Adding book").font(.headline)
                Text(library.importMessage).font(.callout).foregroundStyle(Palette.muted)
                if library.importProgress < 0 { ProgressView().frame(width: 250) }
                else { ProgressView(value: library.importProgress).frame(width: 250) }
                Button("Cancel") { library.cancelImport() }.buttonStyle(SecondaryButton())
            }.padding(30).background(Palette.surface, in: RoundedRectangle(cornerRadius: 14))
        }
    }
}
