import Foundation

enum PDFVocabulary {
    static let words: Set<String> = {
        guard let url = Bundle.main.url(forResource: "pdf-english-words", withExtension: "txt"),
              let contents = try? String(contentsOf: url, encoding: .utf8) else { return [] }
        return Set(contents.split(separator: "\n").map(String.init))
    }()
}
