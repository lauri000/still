import SwiftUI
import UniformTypeIdentifiers

struct LibraryView: View {
    @EnvironmentObject private var library: LibraryStore
    @Environment(\.scenePhase) private var scenePhase
    @State private var selectedID: UUID?
    @State private var importing = false
    @State private var search = ""
    @State private var discovering = false

    private var filteredBooks: [Book] {
        library.books.filter { search.isEmpty || $0.title.localizedCaseInsensitiveContains(search) || $0.author.localizedCaseInsensitiveContains(search) }
    }

    var body: some View {
        Group {
            if let id = selectedID, library.book(id) != nil {
                BookDetailView(bookID: id, onBack: { selectedID = nil }).id(id)
            } else if discovering {
                CatalogView(onBack: { discovering = false }, onImported: { selectedID = $0; discovering = false })
            } else {
                libraryContent
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(Palette.paper).foregroundStyle(Palette.ink)
        .preferredColorScheme(.light)
        .fileImporter(isPresented: $importing, allowedContentTypes: [.pdf, .json, UTType(filenameExtension: "epub") ?? .data], allowsMultipleSelection: false) { result in
            switch result {
            case .success(let urls): if let url = urls.first { library.importFile(url) { selectedID = $0 } }
            case .failure(let error): library.errorMessage = error.localizedDescription
            }
        }
        .overlay { if library.isImporting { importOverlay } }
        .alert("Couldn’t complete that", isPresented: Binding(get: { library.errorMessage != nil }, set: { if !$0 { library.errorMessage = nil } })) {
            Button("OK", role: .cancel) { library.errorMessage = nil }
        } message: { Text(library.errorMessage ?? "") }
        .onChange(of: scenePhase) { _, phase in if phase != .active { library.flush() } }
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
                            Button { selectedID = book.id } label: {
                                HStack(spacing: 18) {
                                    if let data = book.coverData, let cover = platformImage(data) {
                                        cover.resizable().scaledToFit().frame(width: 42, height: 60)
                                    } else { Image(systemName: "doc.text").font(.system(size: 24)).foregroundStyle(Palette.muted) }
                                    VStack(alignment: .leading, spacing: 6) {
                                        Text(book.title).font(.system(size: 17, weight: .medium)).lineLimit(2)
                                        Text(bookStatus(book)).font(.system(size: 12)).foregroundStyle(Palette.muted)
                                    }
                                    Spacer()
                                    Image(systemName: "chevron.right").font(.system(size: 12)).foregroundStyle(Palette.muted)
                                }.padding(.vertical, 22).padding(.horizontal, 4).contentShape(Rectangle())
                            }.buttonStyle(.plain).accessibilityIdentifier("book-\(book.title)")
                            Divider().overlay(Palette.line)
                        }
                    }
                }
            }
            Spacer(minLength: 0)
        }.padding(28).frame(maxWidth: 900).frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
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
