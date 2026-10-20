import Foundation

/// Run after native Android tests and a macOS build (ZIPFoundation is linked by the command in docs/epub.md).
@main enum EPUBContracts {
    struct Backup: Codable { var format = "still-book"; var version = 2; let book: Book; let sourceData: Data }
    static func main() async throws {
        let root = URL(fileURLWithPath: FileManager.default.currentDirectoryPath)
        let output = root.appendingPathComponent("apps/android/app/build/contract-output")
        let entities = try JSONDecoder().decode([String: Int].self, from: Data(contentsOf: root.appendingPathComponent("shared/resources/xhtml-entities.json")))
        let names = ["structured", "hume-enquiry-epub2", "hume-enquiry-epub3", "alice-epub3", "moby-dick", "childrens-literature", "internallinks", "israelsailing"]
        for name in names {
            let epub = root.appendingPathComponent("shared/fixtures/epub/\(name).epub")
            if !FileManager.default.fileExists(atPath: epub.path) { print("Skipped optional corpus: \(name)"); continue }
            let result = try await EPUBImporter.extract(data: Data(contentsOf: epub), filename: name + ".epub", entities: entities)
            let kotlin = try JSONDecoder().decode(Book.self, from: Data(contentsOf: output.appendingPathComponent(name == "structured" ? "android-epub.json" : "epub/\(name).epub.json")))
            let swift = result.book
            precondition(swift.title == kotlin.title && swift.author == kotlin.author, name + " metadata differs")
            precondition(swift.pages.map(\.text) == kotlin.pages.map(\.text), name + " text differs")
            precondition(swift.pages.map(\.sourceHref) == kotlin.pages.map(\.sourceHref), name + " section paths differ")
            precondition(swift.pages.map(\.headings) == kotlin.pages.map(\.headings), name + " headings differ")
            precondition(swift.pages.map(\.wordIndex) == kotlin.pages.map(\.wordIndex), name + " word indexes differ")
            precondition(swift.chapters.count == kotlin.chapters.count, name + " chapter count differs")
            for (a, b) in zip(swift.chapters, kotlin.chapters) {
                precondition(a.title == b.title && a.pageIndex == b.pageIndex && a.level == b.level && a.utf16Offset == b.utf16Offset && a.sourceAnchor == b.sourceAnchor, name + " chapter destination differs")
            }
            precondition(!ReaderPipeline.loaded(kotlin).needsReprocessing)
            print("\(name): \(swift.pages.count) sections, \(swift.chapters.count) chapters; identical native text, headings, indexes and destinations")
            if name == "structured" {
                let backup = try JSONDecoder().decode(Backup.self, from: Data(contentsOf: output.appendingPathComponent("android-epub-backup.json")))
                precondition(backup.sourceData == result.sourceData && backup.book.hasValidStructure && backup.version == 2)
                try JSONEncoder().encode(Backup(book: swift, sourceData: result.sourceData)).write(to: output.appendingPathComponent("swift-epub-backup.json"))
            }
        }
    }
}
