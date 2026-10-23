import SwiftUI
import ImageIO

@MainActor
final class CatalogStore: ObservableObject {
    @Published var page: CatalogPage?
    @Published var loading = false
    @Published var error: String?
    @Published var query = ""
    @Published var language = ""
    let provider: any CatalogProvider
    private var task: Task<Void, Never>?
    private var deadline: Task<Void, Never>?
    private let requestTimeoutNanoseconds: UInt64
    private var generation = UUID()
    private var lastURL: URL?
    init(provider: any CatalogProvider = GutendexProvider(), requestTimeoutNanoseconds: UInt64 = 20_000_000_000) {
        self.provider = provider
        self.requestTimeoutNanoseconds = requestTimeoutNanoseconds
    }
    func load(_ url: URL? = nil) {
        cancel(); let token = generation
        lastURL = url; loading = true; error = nil
        let provider = provider, query = query, language = language
        deadline = Task {
            do { try await Task.sleep(nanoseconds: requestTimeoutNanoseconds) } catch { return }
            guard generation == token, loading else { return }
            cancel()
            error = "\(provider.name) took too long to respond. Please try again."
        }
        task = Task {
            defer { if token == generation { loading = false; deadline?.cancel(); deadline = nil } }
            do {
                let result = try await provider.search(query: query, language: language, page: url)
                guard !Task.isCancelled, token == generation else { return }
                page = result
            } catch {
                guard !Task.isCancelled, token == generation else { return }
                self.error = error.localizedDescription
            }
        }
    }
    func retry() { load(lastURL) }
    func cancel() { generation = UUID(); deadline?.cancel(); deadline = nil; task?.cancel(); task = nil; loading = false }
    func stopLoading() { cancel(); if page == nil { error = "Loading stopped. Tap Try again when you’re ready." } }

}
struct CatalogView: View {
    @EnvironmentObject private var library: LibraryStore
    @StateObject private var catalog = CatalogStore()
    @State private var selected: CatalogBook?
    let onBack: () -> Void
    let onImported: (UUID) -> Void
    var body: some View {
        VStack(spacing: 20) {
            HStack {
                Button { if selected != nil { selected = nil } else { onBack() } } label: { Label(selected == nil ? "Books" : "Discover", systemImage: "chevron.left") }.buttonStyle(.plain)
                Spacer()
                Text("Discover").font(.headline)
                Spacer()
            }
            if let selected { detail(selected) } else { browsing }
        }.padding(28).frame(maxWidth: 900).frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
        .task { if catalog.page == nil { catalog.load() } }
        .onDisappear { catalog.cancel() }
    }
    private var browsing: some View {
        VStack(alignment: .leading, spacing: 16) {
            Text(catalog.provider.name).font(.title2)
            HStack {
                TextField("Title or author", text: $catalog.query).textFieldStyle(.roundedBorder).onSubmit { catalog.load() }.accessibilityIdentifier("catalogSearch")
                Button("Search") { catalog.load() }.buttonStyle(SecondaryButton()).disabled(catalog.loading)
            }
            Picker("Language", selection: $catalog.language) {
                Text("All languages").tag("")
                ForEach(["en", "fi", "sv", "fr", "de", "es", "it", "pt", "nl", "ru", "zh", "ja"], id: \.self) { code in Text(Locale.current.localizedString(forLanguageCode: code) ?? code).tag(code) }
            }.onChange(of: catalog.language) { _, _ in catalog.load() }
            if catalog.loading {
                VStack(spacing: 16) {
                    ProgressView("Loading books…")
                    Button("Cancel") { catalog.stopLoading() }.buttonStyle(SecondaryButton())
                }.frame(maxWidth: .infinity, maxHeight: .infinity)
            }
            else if let error = catalog.error {
                VStack(spacing: 16) { Text(error); Button("Try again") { catalog.retry() }.buttonStyle(SecondaryButton()) }.frame(maxWidth: .infinity, maxHeight: .infinity)
            } else if let page = catalog.page {
                ScrollView {
                    LazyVStack(alignment: .leading, spacing: 0) {
                        if page.books.isEmpty { Text("No books on this page match your search. Try another search or the next page.").foregroundStyle(Palette.muted).padding(.vertical, 30) }
                        ForEach(page.books) { book in
                            Button { selected = book } label: {
                                HStack(spacing: 18) {
                                    CatalogCover(url: book.coverURL, client: catalog.provider.client).frame(width: 54, height: 78)
                                    VStack(alignment: .leading, spacing: 6) {
                                        Text(book.title).font(.system(size: 17, weight: .medium)).lineLimit(2)
                                        Text(book.authors.joined(separator: ", ")).font(.callout).foregroundStyle(Palette.muted)
                                        if library.books.contains(where: { $0.catalogID == book.id }) { Text("In your library").font(.caption) }
                                    }
                                    Spacer()
                                    Image(systemName: "chevron.right").foregroundStyle(Palette.muted)
                                }.padding(.vertical, 16).contentShape(Rectangle())
                            }.buttonStyle(.plain)
                            Divider().overlay(Palette.line)
                        }
                    }
                }
                HStack {
                    Button("Previous") { catalog.load(page.previous) }.disabled(page.previous == nil)
                    Spacer()
                    if let count = page.total { Text("\(count) catalog entries").font(.caption).foregroundStyle(Palette.muted) }
                    Spacer()
                    Button("Next") { catalog.load(page.next) }.disabled(page.next == nil)
                }.buttonStyle(SecondaryButton())
            }
            Text("Public-domain status varies by country. Check the provider’s terms for your location.").font(.caption).foregroundStyle(Palette.muted)
        }
    }
    private func detail(_ book: CatalogBook) -> some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 20) {
                CatalogCover(url: book.coverURL, client: catalog.provider.client).frame(width: 140, height: 200)
                Text(book.title).font(.title)
                Text(book.authors.joined(separator: ", ")).foregroundStyle(Palette.muted)
                if !book.languages.isEmpty { Text(book.languages.map { Locale.current.localizedString(forLanguageCode: $0) ?? $0 }.joined(separator: ", ")).font(.callout) }
                let existing = library.books.first { $0.catalogID == book.id }
                Button(existing == nil ? "Download EPUB" : "Open in library") {
                    if let existing { onImported(existing.id) }
                    else { library.download(book, provider: catalog.provider, completion: onImported) }
                }.buttonStyle(PrimaryButton()).disabled(library.isImporting || (book.epubURL == nil && existing == nil)).accessibilityIdentifier("downloadEPUB")
                if !book.summary.isEmpty { Text(book.summary).textSelection(.enabled) }
                if !book.subjects.isEmpty { Text(book.subjects.joined(separator: " · ")).font(.callout).foregroundStyle(Palette.muted) }
                if let url = book.webURL { Link("View at Project Gutenberg", destination: url) }
                Text("Saved on this device for offline reading.").font(.caption).foregroundStyle(Palette.muted)
            }.frame(maxWidth: .infinity, alignment: .leading)
        }
    }
}
struct CatalogCover: View {
    let url: URL?
    let client: CatalogClient
    @State private var data: Data?
    var body: some View {
        Group {
            if let data, let image = platformImage(data) { image.resizable().scaledToFit() }
            else { RoundedRectangle(cornerRadius: 4).fill(Palette.line).overlay { Image(systemName: "book.closed").foregroundStyle(Palette.muted) } }
        }.task(id: url) {
            data = nil
            if let url { data = try? await client.get(url, limit: 2 * 1_024 * 1_024) }
        }.accessibilityHidden(true)
    }
}
func platformImage(_ data: Data) -> Image? {
    guard let source = CGImageSourceCreateWithData(data as CFData, nil),
          let thumbnail = CGImageSourceCreateThumbnailAtIndex(source, 0, [
            kCGImageSourceCreateThumbnailFromImageAlways: true,
            kCGImageSourceThumbnailMaxPixelSize: 800,
            kCGImageSourceCreateThumbnailWithTransform: true
          ] as CFDictionary) else { return nil }
    return Image(decorative: thumbnail, scale: 1)
}
