import Foundation

// Original sample prose; scripts/contract-fixtures.swift generates the bundled PDF.
enum SampleBook {
    static let sections: [(String, String)] = [
        ("A place to begin", """
        There is a particular kind of quiet that arrives when you open a book. The room stays the same, but your attention moves somewhere else. A sentence makes a small invitation. You accept it, and then another.

        Still is a place for that attention. Instead of moving your eyes across a page, let the words come to you. One word, in one place, at a pace you choose. There is no score to chase and no finish line to hurry toward.

        Start slowly. Find a speed that lets the meaning settle. You can pause at any moment, look at the surrounding text, and begin again. Reading is allowed to have a rhythm of its own.
        """),
        ("Keep the meaning", """
        A book is more than a stream of words. It has pages, chapters, and pauses. It has a shape. That shape should survive when the words move into a different kind of reader.

        When you import a PDF, Still keeps the original file. The text on each page becomes an editable companion to it. You can compare the two, repair a broken word, remove a running header, or leave a cover page out of your reading session.

        Nothing needs to be guessed away. Your corrections belong to the page they came from. Mark a page as reviewed when it looks right. If the PDF contains chapter bookmarks, those come along too. You can also add your own.
        """),
        ("Find your rhythm", """
        Press play and notice how the words arrive. A familiar phrase may pass easily. An unexpected idea might ask you to stop. Both are part of reading.

        The speed control changes how many words appear each minute. Natural pauses give punctuation and paragraph endings a little extra time. Turn them off if you prefer a steady beat.

        Your place is saved when you pause or close the reader. Tomorrow, the book will be waiting where you left it. There is always time to go back a sentence, reread a thought, or simply sit with what you have read.

        Take your time. Make it yours.
        """)
    ]

    static func pdfData() throws -> Data {
        guard let url = Bundle.main.url(forResource: "sample", withExtension: "pdf") else {
            throw CocoaError(.fileNoSuchFile)
        }
        return try Data(contentsOf: url)
    }
}
