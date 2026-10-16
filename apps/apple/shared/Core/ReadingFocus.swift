import Foundation

enum ReadingFocus {
    /// A simple length-based focus cue, counting letters/numbers rather than punctuation.
    /// Character iteration keeps accents and other composed characters intact.
    static func range(in word: String) -> Range<String.Index>? {
        let letters = word.indices.filter { index in
            word[index].unicodeScalars.contains {
                switch $0.properties.generalCategory {
                case .uppercaseLetter, .lowercaseLetter, .titlecaseLetter, .modifierLetter, .otherLetter, .decimalNumber: return true
                default: return false
                }
            }
        }
        guard !letters.isEmpty else { return nil }
        let position: Int
        switch letters.count {
        case 1: position = 0
        case 2...5: position = 1
        case 6...9: position = 2
        case 10...13: position = 3
        default: position = 4
        }
        let start = letters[position]
        return start..<word.index(after: start)
    }
}
