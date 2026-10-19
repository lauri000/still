import PDFKit
#if os(macOS)
import AppKit
private typealias PDFFont = NSFont
#else
import UIKit
private typealias PDFFont = UIFont
#endif

struct PDFTextGeometry {
    let page: PDFPage
    let raw: String
    private func selection(_ offset: Int) -> PDFSelection? {
        guard offset >= 0, offset < raw.utf16.count else { return nil }
        return page.selection(for: NSRange(location: offset, length: 1))
    }
    private func scale(_ selection: PDFSelection) -> Double {
        guard let attributed = selection.attributedString, attributed.length > 0,
              let font = attributed.attribute(.font, at: 0, effectiveRange: nil) as? PDFFont else {
            return selection.bounds(for: page).height
        }
        return font.pointSize
    }
    private func sameLine(_ a: CGRect, _ b: CGRect, _ size: Double) -> Bool {
        b.minX >= a.minX && (abs(a.minY - b.minY) < size * 0.35 ||
            a.height > b.height * 1.6 && abs(a.maxY - b.maxY) < size * 0.2)
    }
    func input() -> PDFTextRepair.Input {
        var removed = Set<Int>()
        let pattern = try! NSRegularExpression(pattern: "(?:ffi|ffl|ff|fi|fl|ft|st) (?=\\p{Ll})")
        for match in pattern.matches(in: raw, range: NSRange(location: 0, length: raw.utf16.count)) {
            let i = NSMaxRange(match.range) - 1
            guard let left = selection(i - 1), let right = selection(i + 1),
                  let text = left.string, ["ff", "fi", "fl", "ffi", "ffl", "ft", "st"].contains(text) else { continue }
            // Selection ranges follow page.string even when characterBounds(at:) does not
            // (newlines and expanded ligatures can shift that API's character indices).
            let a = left.bounds(for: page), b = right.bounds(for: page), size = scale(left)
            let gap = b.minX - a.maxX
            guard size > 0, sameLine(a, b, size) else { continue }
            // Broken font advances can describe an entire fi glyph as a tiny fraction of
            // an em. The extractor then invents a space inside the visible word.
            if a.width < size * 0.12 && gap >= 0 && gap < size * 0.65 {
                removed.insert(i)
            }
        }
        let source = raw as NSString
        let first = source.rangeOfCharacter(from: .whitespacesAndNewlines.inverted).location
        let last = source.rangeOfCharacter(from: .whitespacesAndNewlines.inverted, options: .backwards).location
        let bounds = page.bounds(for: .mediaBox)
        let a = first == NSNotFound ? nil : selection(first)?.bounds(for: page)
        let b = last == NSNotFound ? nil : selection(last)?.bounds(for: page)
        return PDFTextRepair.Input(text: raw, ligatureSpaces: removed,
            topMargin: a.map { $0.minY > bounds.minY + bounds.height * 0.88 } ?? false,
            bottomMargin: b.map { $0.maxY < bounds.minY + bounds.height * 0.12 } ?? false)
    }
    func boundary(offset: Int, split: Bool) -> Bool {
        let source = raw as NSString
        var rightOffset = offset
        if !split {
            while rightOffset < source.length, let scalar = Unicode.Scalar(source.character(at: rightOffset)),
                  CharacterSet.whitespacesAndNewlines.contains(scalar) { rightOffset += 1 }
        }
        guard let left = selection(offset - 1), let right = selection(rightOffset) else { return false }
        let a = left.bounds(for: page), b = right.bounds(for: page), size = max(scale(left), scale(right))
        guard size > 0, sameLine(a, b, size) else { return false }
        let gap = b.minX - a.maxX
        if split {
            // PDFKit assigns the whitespace after a decorative initial to the initial's
            // selection bounds. A font-size transition can supply the missing evidence,
            // but only for a standalone initial and a lexically supported split.
            if offset >= 1, ["I", "A", "a"].contains(source.substring(with: NSRange(location: offset - 1, length: 1))),
               scale(left) > scale(right) * 1.6,
               offset == 1 || source.substring(with: NSRange(location: offset - 2, length: 1)).trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                return true
            }
            let previous = selection(offset - 2)?.bounds(for: page)
            let following = selection(offset + 1)?.bounds(for: page)
            let neighboringGap = max(previous.map { a.minX - $0.maxX } ?? 0, following.map { $0.minX - b.maxX } ?? 0)
            return gap > max(0.8, min(scale(left), scale(right)) * 0.16) && gap > neighboringGap * 2.5
        }
        guard let space = selection(offset)?.bounds(for: page) else { return false }
        return space.width < size * 0.08 && gap < size * 0.12
    }
}
