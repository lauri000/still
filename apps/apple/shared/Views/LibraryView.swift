import SwiftUI
import UniformTypeIdentifiers

struct LibraryView: View {
    @EnvironmentObject private var library: LibraryStore
    @Environment(\.scenePhase) private var scenePhase
    @State private var selectedID: UUID?
    @State private var importing = false
    @State private var search = ""

    private var filteredBooks: [Book] {
        library.books.filter { search.isEmpty || $0.title.localizedCaseInsensitiveContains(search) || $0.author.localizedCaseInsensitiveContains(search) }
    }

    var body: some View {
        Group {
            if let id = selectedID, library.book(id) != nil {
                BookDetailView(bookID: id, onBack: { selectedID = nil }).id(id)
            } else {
                libraryContent
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(Palette.paper).foregroundStyle(Palette.ink)
        .preferredColorScheme(.light)
        .fileImporter(isPresented: $importing, allowedContentTypes: [.pdf, .json], allowsMultipleSelection: false) { result in
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
            HStack {
                Text("Books").font(.system(size: 28, weight: .semibold))
                Spacer()
                Button { importing = true } label: { Label("Import PDF", systemImage: "plus") }
                    .buttonStyle(PrimaryButton()).accessibilityIdentifier("importPDF")
            }
            if library.books.count > 5 {
                TextField("Search books", text: $search).textFieldStyle(.roundedBorder)
                    .accessibilityLabel("Search books")
            }
            if filteredBooks.isEmpty {
                ContentUnavailableView(search.isEmpty ? "No PDFs yet" : "No matching books", systemImage: "doc", description: Text(search.isEmpty ? "Import a PDF to start reading." : "Try another title."))
            } else {
                ScrollView {
                    LazyVStack(spacing: 0) {
                        ForEach(filteredBooks) { book in
                            Button { selectedID = book.id } label: {
                                HStack(spacing: 18) {
                                    Image(systemName: "doc.text").font(.system(size: 24)).foregroundStyle(Palette.muted)
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

    private func bookStatus(_ book: Book) -> String {
        if let position = book.position, let page = book.pages.firstIndex(where: { $0.id == position.pageID }) {
            return position.finished ? "Finished · \(book.pages.count) pages" : "Page \(page + 1) of \(book.pages.count)"
        }
        return "\(book.pages.count) pages"
    }

    private var importOverlay: some View {
        ZStack {
            Color.black.opacity(0.2).ignoresSafeArea()
            VStack(spacing: 18) {
                Text("Preparing book").font(.headline)
                Text(library.importMessage).font(.callout).foregroundStyle(Palette.muted)
                ProgressView(value: library.importProgress).frame(width: 250)
                Button("Cancel") { library.cancelImport() }.buttonStyle(SecondaryButton())
            }.padding(30).background(Palette.surface, in: RoundedRectangle(cornerRadius: 14))
        }
    }
}
