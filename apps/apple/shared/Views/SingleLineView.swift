import SwiftUI

/// Single-line presentation owns its ribbon and animation; passage scrolling is independent.
struct SingleLineView: View {
    @ObservedObject var engine: ReaderEngine
    @Environment(\.scenePhase) private var scenePhase
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    let focusLetter: Bool
    let activeWordSizePercent: Int
    let animatedScrolling: Bool

    private var scrolling: Bool { animatedScrolling && !reduceMotion && engine.isPlaying && scenePhase == .active }

    var body: some View {
        Group {
            if let heading = engine.structure.heading(at: engine.index) {
                Text(heading.title).font(.custom("Georgia", size: 28)).foregroundStyle(Palette.ink)
                    .lineLimit(8).minimumScaleFactor(0.5)
                    .multilineTextAlignment(.center).padding(32).frame(maxWidth: 700)
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                    .accessibilityAddTraits(.isHeader).accessibilityIdentifier("currentHeading")
                    .accessibilityValue("Word \(engine.index + 1) of \(engine.tokens.count)")
                    .accessibilityAdjustableAction(adjustWord)
            } else {
                TimelineView(.animation(minimumInterval: 1.0 / 60, paused: !scrolling)) { timeline in
                    ribbon(time: timeline.date.timeIntervalSinceReferenceDate)
                        .frame(maxWidth: .infinity, maxHeight: .infinity)
                }
            }
        }
    }

    private func ribbon(time: Double) -> some View {
        let fontSize = 22 * CGFloat(activeWordSizePercent) / 100
        let focused = FocusWord(engine.current?.word.text ?? "", enabled: focusLetter)
        var start = min(engine.index, engine.tokens.count), end = min(engine.tokens.count, engine.index + 1)
        while start > max(0, engine.index - 6), engine.structure.heading(at: start - 1) == nil { start -= 1 }
        while end < min(engine.tokens.count, engine.index + 7), engine.structure.heading(at: end) == nil { end += 1 }
        return CenteredWordLayout(selected: engine.index - start, focusAnchor: focused.anchor, firstTokenID: start,
                                  scrolling: scrolling, time: time,
                                  duration: WordMotion.duration(wordsPerMinute: engine.wordsPerMinute)) {
            if engine.tokens.isEmpty {
                Text("Ready?").font(.custom("Georgia", size: fontSize)).accessibilityIdentifier("currentWord")
            } else {
                ForEach(engine.tokens[start..<end]) { token in
                    if token.id == engine.index {
                        Text(focused.text)
                            .font(focused.anchor == nil ? .custom("Georgia", size: fontSize) : .custom("Georgia", fixedSize: fontSize))
                            .lineLimit(1).minimumScaleFactor(focused.anchor == nil ? 0.12 : 0.01)
                            .foregroundStyle(Palette.ink)
                            .background(Palette.paper).zIndex(1)
                            .accessibilityIdentifier("currentWord")
                            .accessibilityValue("Word \(engine.index + 1) of \(engine.tokens.count)")
                            .accessibilityHint("Swipe left for later words or right for earlier words.")
                            .accessibilityAdjustableAction(adjustWord)
                    } else {
                        Text(token.word.text).font(.custom("Georgia", size: 22))
                            .foregroundStyle(Palette.muted).lineLimit(1).minimumScaleFactor(0.1)
                            .frame(minHeight: 44).accessibilityIdentifier("nearbyWord-\(token.id)")
                    }
                }
            }
        }
        .id("\(focusLetter)-\(activeWordSizePercent)")
        .frame(height: 102).frame(maxWidth: .infinity)
        .contentShape(Rectangle())
        .clipped()
        .mask {
            LinearGradient(stops: [.init(color: .clear, location: 0), .init(color: .black, location: 0.06),
                                   .init(color: .black, location: 0.94), .init(color: .clear, location: 1)],
                           startPoint: .leading, endPoint: .trailing)
        }
    }

    private func adjustWord(_ direction: AccessibilityAdjustmentDirection) {
        switch direction {
        case .increment: engine.seek(engine.index + 1)
        case .decrement: engine.seek(engine.index - 1)
        @unknown default: break
        }
    }
}

/// Anchors the selected word (or its focus letter) as its neighbors change length.
struct CenteredWordLayout: Layout {
    var selected: Int
    var focusAnchor: CGFloat?
    var firstTokenID: Int
    var scrolling: Bool
    var time: Double
    var duration: Double
    private let spacing: CGFloat = 18

    struct Cache { var motion = WordMotion() }
    func makeCache(subviews: Subviews) -> Cache { Cache() }
    // Token-window changes retain the positions of words with the same source ID.
    func updateCache(_ cache: inout Cache, subviews: Subviews) {}

    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout Cache) -> CGSize {
        CGSize(width: proposal.width ?? 600, height: 102)
    }

    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout Cache) {
        guard subviews.indices.contains(selected) else {
            subviews.first?.place(at: CGPoint(x: bounds.midX, y: bounds.midY), anchor: .center, proposal: proposal)
            return
        }
        let current = subviews[selected]
        let anchor = focusAnchor ?? 0.5
        let availableWidth = focusAnchor == nil ? bounds.width * 0.68
            : min(bounds.width * 0.68, bounds.width * 0.44 / max(anchor, 1 - anchor))
        let currentWidth = min(current.sizeThatFits(.unspecified).width, availableWidth)
        let currentSize = current.sizeThatFits(ProposedViewSize(width: currentWidth, height: nil))
        // Cache local positions so layout probes at different origins cannot affect motion.
        let leading = bounds.width / 2 - currentWidth * anchor
        current.place(at: CGPoint(x: bounds.minX + leading, y: bounds.midY), anchor: .leading,
                      proposal: ProposedViewSize(width: currentWidth, height: currentSize.height))
        let sizes = subviews.map { $0.sizeThatFits(.unspecified) }
        var targets = [firstTokenID + selected: leading]
        var leadingEdge = leading - spacing
        for index in (0..<selected).reversed() {
            leadingEdge -= sizes[index].width
            targets[firstTokenID + index] = leadingEdge
            leadingEdge -= spacing
        }
        var trailingEdge = leading + currentWidth + spacing
        for index in (selected + 1)..<subviews.count {
            targets[firstTokenID + index] = trailingEdge
            trailingEdge += sizes[index].width + spacing
        }
        let displayed = cache.motion.positions(index: firstTokenID + selected, targets: targets,
            viewportWidth: bounds.width, enabled: scrolling, time: time, duration: duration)
        for index in subviews.indices where index != selected {
            guard let x = displayed[firstTokenID + index] else { continue }
            subviews[index].place(at: CGPoint(x: bounds.minX + x, y: bounds.midY),
                                  anchor: .leading, proposal: ProposedViewSize(sizes[index]))
        }
    }
}
