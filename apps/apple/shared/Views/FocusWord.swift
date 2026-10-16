import SwiftUI
import CoreText

struct FocusWord {
    let text: AttributedString
    let anchor: CGFloat?

    init(_ word: String, enabled: Bool) {
        guard enabled, let range = ReadingFocus.range(in: word) else {
            text = AttributedString(word)
            anchor = nil
            return
        }
        var styled = AttributedString(String(word[..<range.lowerBound]))
        var letter = AttributedString(String(word[range]))
        letter.foregroundColor = Palette.focusLetter
        styled += letter
        styled += AttributedString(String(word[range.upperBound...]))
        text = styled

        // Measure the shaped word, including kerning and composed characters.
        // This fraction follows the same scaling as the displayed SwiftUI Text.
        let font = CTFontCreateWithName("Georgia" as CFString, 68, nil)
        let line = CTLineCreateWithAttributedString(NSAttributedString(string: word,
            attributes: [NSAttributedString.Key(kCTFontAttributeName as String): font]))
        let width = CTLineGetTypographicBounds(line, nil, nil, nil)
        let utf16 = NSRange(range, in: word)
        let start = CTLineGetOffsetForStringIndex(line, utf16.location, nil)
        let end = CTLineGetOffsetForStringIndex(line, NSMaxRange(utf16), nil)
        anchor = width > 0 ? min(1, max(0, (start + end) / 2 / width)) : nil
    }
}
