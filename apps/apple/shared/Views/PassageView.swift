import SwiftUI
#if os(iOS)
import UIKit
#else
import AppKit
#endif

/// Wrap once for the viewport, then move the whole column along the playback clock.
struct PassageView: View {
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @ObservedObject var engine: ReaderEngine
    let focusLetter: Bool
    let activeWordSizePercent: Int
    @ScaledMetric private var baseSize: CGFloat = 24
    @State private var layout: PassageLayout?

    var body: some View {
        GeometryReader { geometry in
            let fontSize = geometry.size.height < 240 ? baseSize * 20 / 24 : baseSize
            let selectedSize = fontSize * CGFloat(activeWordSizePercent) / 100
            let rowHeight = max(geometry.size.height < 240 ? 44 : 56, selectedSize * 1.4 + 12)
            TimelineView(.animation(minimumInterval: 1.0 / 60, paused: !engine.isPlaying || reduceMotion)) { _ in
                let position = reduceMotion ? Double(layout?.row(containing: engine.index) ?? 0) + 0.5
                    : layout?.position(index: engine.index, wordProgress: engine.wordProgress) ?? 0
                let radius = Int(ceil(geometry.size.height / rowHeight / 2)) + 1
                let first = max(0, Int(position) - radius)
                let end = min(layout?.rows.count ?? 0, Int(position) + radius + 1)
                ZStack(alignment: .topLeading) {
                    if let layout, first < end {
                        ForEach(first..<end, id: \.self) { row in
                            HStack(spacing: layout.spacing) {
                                ForEach(layout.rows[row].words, id: \.self) { index in
                                    word(index, width: layout.widths[index], size: selectedSize, height: rowHeight - 8)
                                }
                                Spacer(minLength: 0)
                            }
                            .frame(width: geometry.size.width, height: rowHeight)
                            .position(x: geometry.size.width / 2, y: geometry.size.height / 2 + (Double(row) + 0.5 - position) * rowHeight)
                        }
                    } else if engine.tokens.isEmpty {
                        Text("Ready?").font(.custom("Georgia", size: fontSize))
                            .frame(width: geometry.size.width, height: geometry.size.height)
                            .accessibilityIdentifier("currentWord")
                    }
                }
                .frame(width: geometry.size.width, height: geometry.size.height)
                .clipped()
                .mask {
                    LinearGradient(stops: [.init(color: .clear, location: 0), .init(color: .black, location: 0.06),
                                           .init(color: .black, location: 0.94), .init(color: .clear, location: 1)],
                                   startPoint: .top, endPoint: .bottom)
                }
            }
            .onChange(of: geometry.size.width, initial: true) { _, width in arrange(width: width, size: selectedSize) }
            .onChange(of: selectedSize) { _, size in arrange(width: geometry.size.width, size: size) }
            .onChange(of: engine.naturalPauses) { _, _ in arrange(width: geometry.size.width, size: selectedSize) }
        }
        .padding(.horizontal, 24)
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("nearbyPassage")
    }

    private func word(_ index: Int, width: CGFloat, size: CGFloat, height: CGFloat) -> some View {
        let selected = index == engine.index
        let text = FocusWord(engine.tokens[index].word.text, enabled: selected && focusLetter).text
        return Text(text)
            .font(.custom("Georgia", fixedSize: size))
            .lineLimit(1).minimumScaleFactor(0.01)
            .foregroundStyle(selected ? Palette.ink : Palette.muted)
            // Selection changes the highlight, so the text keeps its natural spacing.
            .frame(width: width, height: height)
            .accessibilityIdentifier(selected ? "currentWord" : "passageWord-\(index)")
            .accessibilityValue("Word \(index + 1) of \(engine.tokens.count)")
            .accessibilityAdjustableAction { direction in
                switch direction {
                case .increment: engine.seek(engine.index + 1)
                case .decrement: engine.seek(engine.index - 1)
                @unknown default: break
                }
            }
    }

    private func arrange(width: CGFloat, size: CGFloat) {
        #if os(iOS)
        let font = UIFont(name: "Georgia", size: size) ?? UIFont.systemFont(ofSize: size)
        #else
        let font = NSFont(name: "Georgia", size: size) ?? NSFont.systemFont(ofSize: size)
        #endif
        // Use a normal typographic space rather than extra layout padding between words.
        let spacing = (" " as NSString).size(withAttributes: [.font: font]).width
        var measured: [String: Double] = [:]
        let widths = engine.tokens.map { token in
            if let cached = measured[token.word.text] { return cached }
            let value = ceil((token.word.text as NSString).size(withAttributes: [.font: font]).width)
            measured[token.word.text] = value
            return value
        }
        layout = PassageLayout(tokens: engine.tokens, widths: widths, availableWidth: width, spacing: spacing, naturalPauses: engine.naturalPauses)
    }
}
