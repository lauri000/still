import SwiftUI
#if os(iOS)
import UIKit
#else
import AppKit
#endif

struct PassageView: View {
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @ObservedObject var engine: ReaderEngine
    @ScaledMetric private var baseSize: CGFloat = 24
    @State private var layout: PassageLayout?
    @State private var windowStart = 0

    var body: some View {
        GeometryReader { geometry in
            let fontSize = geometry.size.height < 240 ? baseSize * 20 / 24 : baseSize
            let rowHeight = max(geometry.size.height < 240 ? 44 : 56, fontSize * 1.4 + 12)
            TimelineView(.animation(minimumInterval: 1.0 / 60, paused: !engine.isPlaying || reduceMotion)) { _ in
                let index = engine.index - windowStart
                let currentRow = layout?.row(containing: index) ?? 0
                let row = layout.flatMap { $0.rows.indices.contains(currentRow) ? $0.rows[currentRow] : nil }
                let position = reduceMotion ? row.map { $0.top + $0.height / 2 } ?? 0
                    : layout?.position(index: index, wordProgress: engine.motionProgress) ?? 0
                let radius = Int(ceil(geometry.size.height / rowHeight / 2)) + 1
                let first = max(0, currentRow - radius)
                let end = min(layout?.rows.count ?? 0, currentRow + radius + 1)
                ZStack(alignment: .topLeading) {
                    if let layout, first < end {
                        ForEach(first..<end, id: \.self) { rowIndex in
                            let row = layout.rows[rowIndex]
                            Text(row.text).font(.custom("Georgia", fixedSize: fontSize * (row.isHeading ? 1.2 : 1))).foregroundStyle(Palette.ink)
                                .lineLimit(1).minimumScaleFactor(0.01)
                                .frame(width: geometry.size.width, height: rowHeight * row.height, alignment: .leading)
                                .position(x: geometry.size.width / 2, y: geometry.size.height / 2 + (row.top + row.height / 2 - position) * rowHeight)
                                .accessibilityAddTraits(row.isHeading ? .isHeader : [])
                                .accessibilityIdentifier(row.words.contains(index) ? "currentPassageRow" : "passageRow-\(windowStart + row.words.lowerBound)")
                                .accessibilityValue(row.words.contains(index) ? "Word \(engine.index + 1) of \(engine.tokens.count)" : "")
                                .accessibilityAdjustableAction { direction in
                                    switch direction {
                                    case .increment: engine.seek(engine.index + 1)
                                    case .decrement: engine.seek(engine.index - 1)
                                    @unknown default: break
                                    }
                                }
                        }
                    } else if engine.tokens.isEmpty {
                        Text("Ready?").font(.custom("Georgia", size: fontSize))
                            .frame(width: geometry.size.width, height: geometry.size.height)
                    }
                }
                .frame(width: geometry.size.width, height: geometry.size.height).clipped()
                .mask {
                    LinearGradient(stops: [.init(color: .clear, location: 0), .init(color: .black, location: 0.06),
                                           .init(color: .black, location: 0.94), .init(color: .clear, location: 1)],
                                   startPoint: .top, endPoint: .bottom)
                }
            }
            .onChange(of: geometry.size.width, initial: true) { _, width in arrange(width: width, size: fontSize) }
            .onChange(of: fontSize) { _, size in arrange(width: geometry.size.width, size: size) }
            .onChange(of: engine.current?.pageID) { _, _ in arrange(width: geometry.size.width, size: fontSize) }
        }
        .padding(.horizontal, 24)
        .accessibilityElement(children: .contain).accessibilityIdentifier("nearbyPassage")
    }

    private func arrange(width: CGFloat, size: CGFloat) {
        #if os(iOS)
        let font = UIFont(name: "Georgia", size: size) ?? UIFont.systemFont(ofSize: size)
        #else
        let font = NSFont(name: "Georgia", size: size) ?? NSFont.systemFont(ofSize: size)
        #endif
        let window = PassageLayout.window(tokens: engine.tokens, index: engine.index)
        let nearby = Array(engine.tokens[window])
        let spacing = (" " as NSString).size(withAttributes: [.font: font]).width
        let headingIDs = Dictionary(uniqueKeysWithValues: nearby.compactMap { token in engine.structure.heading(at: token.id).map { (token.id, $0.words.lowerBound) } })
        var measured: [String: Double] = [:]
        let widths = nearby.map { token in
            let isHeading = headingIDs[token.id] != nil
            let key = "\(isHeading):\(token.word.text)"
            if let cached = measured[key] { return cached }
            #if os(iOS)
            let face = isHeading ? font.withSize(size * 1.2) : font
            #else
            let face = isHeading ? NSFont(name: font.fontName, size: size * 1.2) ?? font : font
            #endif
            let value = ceil((token.word.text as NSString).size(withAttributes: [.font: face]).width)
            measured[key] = value
            return value
        }
        windowStart = window.lowerBound
        layout = PassageLayout(tokens: nearby, widths: widths, availableWidth: width, spacing: spacing, headingIDs: headingIDs)
    }
}
