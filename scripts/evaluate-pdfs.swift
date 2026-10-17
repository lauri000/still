import Foundation

/// Compile with the production PDF importer; run from the repository root.
/// Output contains private book text. Use a directory outside the repository.
@main struct EvaluatePDFs {
    static func main() async throws {
        guard CommandLine.arguments.count >= 3 else {
            print("Usage: evaluate-pdfs OUTPUT_DIRECTORY PDF [PDF ...]")
            return
        }
        let output = URL(fileURLWithPath: CommandLine.arguments[1], isDirectory: true)
        try FileManager.default.createDirectory(at: output, withIntermediateDirectories: true)
        let words = Set(try String(contentsOfFile: "shared/resources/pdf-english-words.txt", encoding: .utf8)
            .split(separator: "\n").map(String.init))
        for path in CommandLine.arguments.dropFirst(2) {
            let url = URL(fileURLWithPath: path), start = Date()
            let result = try await PDFImporter.extract(url: url, vocabulary: words) { _, _ in }
            let seconds = Date().timeIntervalSince(start)
            let pages = result.book.pages.map { ["text": $0.text, "original": $0.extractedText] }
            let data = try JSONSerialization.data(withJSONObject: [
                "seconds": seconds, "version": PDFTextRepair.version, "pages": pages
            ])
            try data.write(to: output.appendingPathComponent(url.lastPathComponent + ".apple-repaired.json"), options: .atomic)
            print("\(url.lastPathComponent): \(pages.count) pages, \(String(format: "%.3f", seconds))s")
        }
    }
}
