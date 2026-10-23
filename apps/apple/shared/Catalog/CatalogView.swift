import SwiftUI
import ImageIO

@MainActor
final class CatalogStore: ObservableObject {
    @Published var page: CatalogPage?
    @Published var loading = false
    @Published var error: String?
    @Published var query = ""
    @Published var language = ""
    @Published var provider: any CatalogProvider = GutendexProvider()
    private var task: Task<Void, Never>?
    private var generation = UUID()
    private var lastURL: URL?
    func load(_ url: URL? = nil) {
        task?.cancel(); let token = UUID(); generation = token
        lastURL = url; loading = true; error = nil
        let provider = provider, query = query, language = language
        task = Task {
            do {
                let result = try await provider.search(query: query, language: language, page: url)
                guard !Task.isCancelled, token == generation else { return }
                page = result; loading = false
            } catch {
                guard !Task.isCancelled, token == generation else { return }
                self.error = error.localizedDescription; loading = false
            }
        }
    }
    func retry() { load(lastURL) }
    func configure(_ provider: any CatalogProvider) { self.provider = provider; query = ""; page = nil; load() }
    func cancel() { task?.cancel() }
}
struct CatalogView: View {
    @EnvironmentObject private var library: LibraryStore
    @StateObject private var catalog = CatalogStore()
    @State private var selected: CatalogBook?
    @State private var settings = false
    let onBack: () -> Void
    let onImported: (UUID) -> Void
    var body: some View {
        VStack(spacing: 20) {
            HStack {
                Button { if selected != nil { selected = nil } else { onBack() } } label: { Label(selected == nil ? "Books" : "Discover", systemImage: "chevron.left") }.buttonStyle(.plain)
                Spacer()
                Text("Discover").font(.headline)
                Spacer()
                Button { settings = true } label: { Image(systemName: "slider.horizontal.3").accessibilityLabel("Catalog settings") }.buttonStyle(.plain)
            }
            if let selected { detail(selected) } else { browsing }
        }.padding(28).frame(maxWidth: 900).frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
        .task { if catalog.page == nil { catalog.load() } }
        .onDisappear { catalog.cancel() }
        .sheet(isPresented: $settings) { CatalogSettings { catalog.configure($0); selected = nil } }
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
            if catalog.provider is OPDSProvider { Text("Language filters apply to each page. Browse further for more matches.").font(.caption).foregroundStyle(Palette.muted) }
            if catalog.loading { ProgressView("Loading books…").frame(maxWidth: .infinity, maxHeight: .infinity) }
            else if let error = catalog.error {
                VStack(spacing: 16) { Text(error); Button("Try again") { catalog.retry() }.buttonStyle(SecondaryButton()) }.frame(maxWidth: .infinity, maxHeight: .infinity)
            } else if let page = catalog.page {
                ScrollView {
                    LazyVStack(alignment: .leading, spacing: 0) {
                        ForEach(page.navigation) { link in Button { catalog.query = ""; catalog.load(link.url) } label: { HStack { Text(link.title); Spacer(); Image(systemName: "chevron.right") }.padding(.vertical, 18) }.buttonStyle(.plain); Divider() }
                        if page.books.isEmpty { Text(page.navigation.isEmpty ? "No books on this page match your search. Try another search or the next page." : "Choose a collection to browse books.").foregroundStyle(Palette.muted).padding(.vertical, 30) }
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
                    if let count = page.total { Text("\(count) books").font(.caption).foregroundStyle(Palette.muted) }
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
                if book.epubURL == nil { Text("This edition has no direct EPUB download. Borrowing, purchases, and DRM are not supported.").font(.caption) }
                if !book.summary.isEmpty { Text(book.summary).textSelection(.enabled) }
                if !book.subjects.isEmpty { Text(book.subjects.joined(separator: " · ")).font(.callout).foregroundStyle(Palette.muted) }
                if let url = book.webURL { Link("View at provider", destination: url) }
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
private struct CatalogSettings: View {
    @Environment(\.dismiss) private var dismiss
    @State private var kind = "gutenberg"
    @State private var customURL = UserDefaults.standard.string(forKey: "catalog.customURL") ?? ""
    @State private var username = ""
    @State private var password = ""
    @State private var error: String?
    let connect: (any CatalogProvider) -> Void
    var body: some View {
        VStack(alignment: .leading, spacing: 20) {
            Text("Catalog settings").font(.title2)
            Picker("Provider", selection: $kind) {
                Text("Project Gutenberg").tag("gutenberg")
                Text("Standard Ebooks").tag("standard")
                Text("Custom OPDS catalog").tag("custom")
            }
            if kind == "custom" { TextField("https://example.org/opds", text: $customURL).textFieldStyle(.roundedBorder) }
            if kind != "gutenberg" {
                if kind == "standard" {
                    Text("Standard Ebooks requires authorized feed access. Use your Patrons Circle email as the username; leave the password blank unless instructed by the provider.").font(.callout)
                    Link("Access requirements", destination: URL(string: "https://standardebooks.org/help/how-to-add-an-opds-feed-to-your-ereader")!)
                }
                TextField("Username (optional)", text: $username).textFieldStyle(.roundedBorder)
                SecureField("Password (optional)", text: $password).textFieldStyle(.roundedBorder)
                Text("Credentials are kept only while this catalog is open and sent only to its HTTPS origin.").font(.caption).foregroundStyle(Palette.muted)
            }
            if let error { Text(error).foregroundStyle(.red) }
            HStack {
                Button("Cancel") { dismiss() }.buttonStyle(SecondaryButton())
                Spacer()
                Button("Connect") {
                    if kind == "gutenberg" { connect(GutendexProvider()); dismiss(); return }
                    guard let url = catalogURL(kind == "standard" ? "https://standardebooks.org/feeds/opds" : customURL.trimmingCharacters(in: .whitespacesAndNewlines)) else { error = "Enter a valid HTTPS catalog URL without embedded credentials."; return }
                    if kind == "custom" { UserDefaults.standard.set(url.absoluteString, forKey: "catalog.customURL") }
                    let client = CatalogClient(credentials: CatalogCredentials(origin: url, username: username, password: password))
                    connect(OPDSProvider(id: url.absoluteString, name: kind == "standard" ? "Standard Ebooks" : url.host ?? "OPDS", endpoint: url, client: client)); dismiss()
                }.buttonStyle(PrimaryButton())
            }
        }.padding(28).frame(minWidth: 300, idealWidth: 450)
    }
}
