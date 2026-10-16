import Foundation
import CoreGraphics
import CoreText
import PDFKit

// Original sample prose. The PDF goes through the same extraction pipeline as imported books.
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

    static func pdfData() -> Data {
        let data = NSMutableData()
        let consumer = CGDataConsumer(data: data)!
        var bounds = CGRect(x: 0, y: 0, width: 612, height: 792)
        let context = CGContext(consumer: consumer, mediaBox: &bounds, nil)!
        for (index, section) in sections.enumerated() {
            context.beginPDFPage(nil)
            context.setFillColor(CGColor(red: 0.985, green: 0.978, blue: 0.956, alpha: 1))
            context.fill(bounds)
            draw("S T I L L   /   A   S H O R T   I N T R O D U C T I O N", font: "Helvetica", size: 10, rect: CGRect(x: 64, y: 693, width: 484, height: 25), in: context)
            draw(section.0, font: "Georgia", size: 29, rect: CGRect(x: 64, y: 605, width: 484, height: 60), in: context)
            draw(section.1, font: "Georgia", size: 16, rect: CGRect(x: 64, y: 150, width: 484, height: 440), in: context)
            draw("\(index + 1)", font: "Helvetica", size: 11, rect: CGRect(x: 294, y: 46, width: 40, height: 24), in: context)
            context.endPDFPage()
        }
        context.closePDF()
        let document = PDFDocument(data: data as Data)!
        document.documentAttributes = [PDFDocumentAttribute.titleAttribute: "The art of attention", PDFDocumentAttribute.authorAttribute: "A Still introduction"]
        let outline = PDFOutline()
        for (index, section) in sections.enumerated() {
            let child = PDFOutline()
            child.label = section.0
            child.destination = PDFDestination(page: document.page(at: index)!, at: CGPoint(x: 0, y: 792))
            outline.insertChild(child, at: index)
        }
        document.outlineRoot = outline
        return document.dataRepresentation()!
    }

    private static func draw(_ string: String, font: String, size: CGFloat, rect: CGRect, in context: CGContext) {
        let attributes: [NSAttributedString.Key: Any] = [
            NSAttributedString.Key(kCTFontAttributeName as String): CTFontCreateWithName(font as CFString, size, nil),
            NSAttributedString.Key(kCTForegroundColorAttributeName as String): CGColor(gray: 0.14, alpha: 1)
        ]
        let text = NSAttributedString(string: string, attributes: attributes)
        let setter = CTFramesetterCreateWithAttributedString(text)
        let frame = CTFramesetterCreateFrame(setter, CFRange(location: 0, length: text.length), CGPath(rect: rect, transform: nil), nil)
        CTFrameDraw(frame, context)
    }
}
