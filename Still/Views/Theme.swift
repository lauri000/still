import SwiftUI

enum Palette {
    static let paper = Color(red: 0.966, green: 0.960, blue: 0.937)
    static let surface = Color(red: 0.994, green: 0.989, blue: 0.973)
    static let sidebar = Color(red: 0.928, green: 0.930, blue: 0.900)
    static let forest = Color(red: 0.19, green: 0.30, blue: 0.23)
    static let ink = Color(red: 0.16, green: 0.20, blue: 0.17)
    static let muted = Color(red: 0.44, green: 0.47, blue: 0.42)
    static let line = Color(red: 0.84, green: 0.85, blue: 0.80)
    static let amber = Color(red: 0.57, green: 0.38, blue: 0.17)
}

struct PrimaryButton: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label.font(.system(size: 14, weight: .semibold))
            .padding(.horizontal, 20).frame(minHeight: 46)
            .foregroundStyle(.white)
            .background(Palette.forest.opacity(configuration.isPressed ? 0.8 : 1), in: RoundedRectangle(cornerRadius: 12))
    }
}
struct SecondaryButton: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label.font(.system(size: 13, weight: .medium))
            .padding(.horizontal, 15).frame(minHeight: 44)
            .foregroundStyle(Palette.forest)
            .background(configuration.isPressed ? Palette.line : Palette.surface, in: RoundedRectangle(cornerRadius: 10))
            .overlay(RoundedRectangle(cornerRadius: 10).stroke(Palette.line, lineWidth: 1))
    }
}
struct Eyebrow: View {
    var text: String
    var body: some View { Text(text.uppercased()).font(.system(size: 10, weight: .semibold)).tracking(2).foregroundStyle(Palette.muted) }
}
