import SwiftUI
#if os(iOS)
import UIKit
#endif

struct ReaderView: View {
    @Environment(\.dismiss) private var dismiss
    @Environment(\.scenePhase) private var scenePhase
    @StateObject private var engine: ReaderEngine
    @AppStorage("readingWPM") private var savedWPM = 250.0
    @AppStorage("naturalPauses") private var savedNaturalPauses = true
    @AppStorage("pauseAtUncheckedPages") private var pauseAtUncheckedPages = false
    @AppStorage("focusLetter") private var focusLetter = false
    @State private var showContext = false
    @State private var showingSettings = false
    @GestureState private var dragInProgress = false
    var onReviewPage: (Int) -> Void
    var onCheckpoint: (ReadingPosition) -> Void

    init(book: Book, onReviewPage: @escaping (Int) -> Void, onCheckpoint: @escaping (ReadingPosition) -> Void) {
        _engine = StateObject(wrappedValue: ReaderEngine(book: book))
        self.onReviewPage = onReviewPage
        self.onCheckpoint = onCheckpoint
    }

    var body: some View {
        VStack(spacing: 0) {
            header.padding(.horizontal, 24).padding(.vertical, 16)
            Rectangle().fill(Palette.line).frame(height: 1)
            focusStage.frame(maxWidth: 850).frame(maxWidth: .infinity, maxHeight: .infinity)
            if let blocked = engine.blockedPage { reviewNotice(blocked).padding(.horizontal, 24).padding(.bottom, 22) }
            #if os(macOS)
            controls
            speedControl.padding(.top, 25)
            #endif
            progressFooter.padding(.horizontal, 24).padding(.vertical, 18)
        }.background(Palette.paper).foregroundStyle(Palette.ink)
        .onAppear {
            engine.wordsPerMinute = savedWPM
            engine.naturalPauses = savedNaturalPauses
            engine.allowUnreviewed = !pauseAtUncheckedPages
            engine.onCheckpoint = onCheckpoint
            if engine.tokens.isEmpty { engine.play() }
        }
        .onDisappear { engine.pause(); setAwake(false) }
        .onChange(of: engine.isPlaying) { _, playing in setAwake(playing) }
        .onChange(of: engine.wordsPerMinute) { _, value in savedWPM = value }
        .onChange(of: dragInProgress) { _, dragging in if !dragging { engine.endScrubbing() } }
        .onChange(of: scenePhase) { _, phase in if phase != .active { engine.pause() } }
        .sheet(isPresented: $showingSettings) { optionsSheet }
        .preferredColorScheme(.light)
    }

    private var header: some View {
        HStack(spacing: 14) {
            Button { engine.pause(); dismiss() } label: { Image(systemName: "chevron.left").frame(width: 40, height: 44) }
                .buttonStyle(.plain).accessibilityLabel("Close reader").keyboardShortcut(.escape, modifiers: [])
            Text(engine.book.title).font(.system(size: 14, weight: .medium)).lineLimit(1)
            Spacer()
            Button { engine.pause(); showContext.toggle() } label: { Image(systemName: "text.alignleft").frame(width: 44, height: 44) }
                .buttonStyle(.plain).accessibilityLabel(showContext ? "Single-line view" : "Passage view").accessibilityIdentifier("toggleContext")
            Button { engine.pause(); showingSettings = true } label: { Image(systemName: "ellipsis").frame(width: 44, height: 44) }
                .buttonStyle(.plain).accessibilityLabel("Reading options").accessibilityIdentifier("readingOptions")
        }
    }
    private var focusStage: some View {
        GeometryReader { geometry in
            wordRibbon(height: geometry.size.height).frame(maxWidth: .infinity, maxHeight: .infinity)
        }
        .contentShape(Rectangle())
        .simultaneousGesture(scrubGesture)
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("readingSurface")
        .accessibilityValue(showContext ? "Passage view" : "Single-line view")
    }
    private func wordRibbon(height: CGFloat) -> some View {
        let compact = showContext && height < 240
        let fontSize: CGFloat = compact ? 48 : 68
        let focused = FocusWord(engine.current?.word.text ?? "", enabled: focusLetter)
        let radius = showContext ? 80 : 6
        let start = max(0, engine.index - radius)
        let tokens = Array(engine.tokens[start..<min(engine.tokens.count, engine.index + radius + 1)])
        let breaks = Set(tokens.indices.dropLast().filter {
            tokens[$0].word.endsParagraph || tokens[$0].pageIndex != tokens[$0 + 1].pageIndex
        })
        return CenteredWordLayout(selected: engine.index - start, focusAnchor: focused.anchor,
                                  expanded: showContext, breaks: breaks) {
            if engine.tokens.isEmpty {
                Text("Ready?").font(.custom("Georgia", size: fontSize)).accessibilityIdentifier("currentWord")
            } else {
                ForEach(tokens) { token in
                    if token.id == engine.index {
                        Text(focused.text)
                            .font(focused.anchor == nil ? .custom("Georgia", size: fontSize) : .custom("Georgia", fixedSize: fontSize))
                            .lineLimit(1).minimumScaleFactor(focused.anchor == nil ? 0.12 : 0.01)
                            .padding(.horizontal, focused.anchor == nil ? 8 : 0)
                            .accessibilityIdentifier("currentWord")
                            .accessibilityValue("Word \(engine.index + 1) of \(engine.tokens.count)")
                            .accessibilityHint("Swipe left for later words or right for earlier words.")
                            .accessibilityAdjustableAction { direction in
                                switch direction {
                                case .increment: engine.seek(engine.index + 1)
                                case .decrement: engine.seek(engine.index - 1)
                                @unknown default: break
                                }
                            }
                    } else {
                        Button { engine.seek(token.id) } label: {
                            Text(token.word.text).font(.custom("Georgia", size: compact ? 20 : 22))
                                .foregroundStyle(Palette.muted).lineLimit(1).minimumScaleFactor(0.1).frame(minHeight: compact ? 32 : 44)
                        }
                        .buttonStyle(.plain)
                        .accessibilityLabel("Read from \(token.word.text)")
                        .accessibilityIdentifier(showContext ? "passageWord-\(token.id)" : "nearbyWord-\(token.id)")
                    }
                }
            }
        }
        .frame(height: showContext ? nil : 102).frame(maxWidth: .infinity, maxHeight: showContext ? .infinity : nil)
        .contentShape(Rectangle())
        .clipped()
        .mask {
            LinearGradient(stops: [.init(color: .clear, location: 0), .init(color: .black, location: 0.06),
                                   .init(color: .black, location: 0.94), .init(color: .clear, location: 1)],
                           startPoint: showContext ? .top : .leading, endPoint: showContext ? .bottom : .trailing)
        }
    }
    private var scrubGesture: some Gesture {
        DragGesture(minimumDistance: 12)
            .updating($dragInProgress) { _, dragging, _ in dragging = true }
            .onChanged { value in
                let translation = value.translation
                // Only horizontal movement changes the selected word.
                guard engine.isScrubbing || abs(translation.width) > abs(translation.height) * 1.2 else { return }
                engine.beginScrubbing()
                engine.scrub(by: -Int((translation.width / 32).rounded(.towardZero)))
            }
            .onEnded { _ in engine.endScrubbing() }
    }
    #if os(macOS)
    private var controls: some View {
        HStack(spacing: 25) {
            Button { engine.seek(engine.index - 1) } label: { Image(systemName: "backward.end.fill").frame(width: 40, height: 44) }.keyboardShortcut(.leftArrow, modifiers: []).accessibilityLabel("Previous word")
            Button { engine.toggle() } label: {
                Image(systemName: engine.isPlaying ? "pause.fill" : (engine.isFinished ? "arrow.counterclockwise" : "play.fill"))
                    .font(.system(size: 22, weight: .medium)).foregroundStyle(Palette.paper)
                    .frame(width: 76, height: 76).background(Palette.forest, in: Circle())
            }.keyboardShortcut(.space, modifiers: []).accessibilityLabel(engine.isPlaying ? "Pause reading" : "Play reading").accessibilityIdentifier("playPause")
            Button { engine.seek(engine.index + 1) } label: { Image(systemName: "forward.end.fill").frame(width: 40, height: 44) }.keyboardShortcut(.rightArrow, modifiers: []).accessibilityLabel("Next word")
        }.buttonStyle(.plain).foregroundStyle(Palette.forest).disabled(engine.tokens.isEmpty)
    }
    private var speedControl: some View {
        VStack(spacing: 10) {
            HStack(alignment: .firstTextBaseline, spacing: 5) {
                Text("\(Int(engine.wordsPerMinute))").font(.system(size: 23, weight: .medium, design: .rounded)).monospacedDigit()
                Text("words / min").font(.system(size: 11)).foregroundStyle(Palette.muted)
            }
            HStack(spacing: 16) {
                Slider(value: $engine.wordsPerMinute, in: 60...900, step: 10).tint(Palette.forest).accessibilityLabel("Words per minute")
            }.font(.system(size: 10)).foregroundStyle(Palette.muted).frame(maxWidth: 330)
        }
    }
    #endif
    private var progressFooter: some View {
        VStack(spacing: 13) {
            ProgressView(value: engine.progress).tint(Palette.forest)
            HStack {
                Menu {
                    ForEach(Array(engine.book.pages.enumerated()), id: \.element.id) { index, page in
                        if engine.book.includesPage(at: index) { Button("Page \(page.label)") { engine.seekPage(index) } }
                    }
                } label: {
                    Text("Page \(engine.current.map { engine.book.pages[$0.pageIndex].label } ?? "—") of \(engine.book.pages.count)")
                }.menuStyle(.borderlessButton).fixedSize()
                Spacer()
                #if os(iOS)
                holdCorner
                #else
                Text(engine.isFinished ? "Finished" : "\(Int(engine.progress * 100))%")
                #endif
            }.font(.system(size: 10)).foregroundStyle(Palette.muted)
            #if os(iOS)
            if engine.isFinished { Text("Finished").font(.system(size: 12)).foregroundStyle(Palette.muted) }
            #endif
        }
    }
    #if os(iOS)
    private var holdCorner: some View {
        ZStack {
            RoundedRectangle(cornerRadius: 18).fill(engine.isPlaying ? Palette.surface : Palette.paper)
            VStack(spacing: 5) {
                Text("\(Int(engine.wordsPerMinute)) wpm")
                    .font(.system(size: 22, weight: .medium, design: .rounded)).monospacedDigit().foregroundStyle(Palette.forest)
                Text(engine.isPlaying ? "Release to pause" : "Hold to read").font(.system(size: 12))
                Text("← slower · faster →").font(.system(size: 10))
            }.allowsHitTesting(false)
            HoldReadingTouchSurface(enabled: scenePhase == .active && !showingSettings,
                onBegin: { engine.play() },
                onMove: { delta in engine.wordsPerMinute = min(900, max(60, engine.wordsPerMinute + Double(delta) * 2)) },
                onEnd: { engine.pause() })
        }
        .frame(width: 176, height: 96)
        .accessibilityElement(children: .ignore)
        .accessibilityIdentifier("holdToRead")
        .accessibilityLabel("Hold to read")
        .accessibilityValue("\(Int(engine.wordsPerMinute)) words per minute, \(engine.isPlaying ? "reading" : "paused")")
        .accessibilityHint("Hold to read. Move right to read faster, left to read slower. Release to pause.")
        .accessibilityAction { engine.toggle() }
        .accessibilityAdjustableAction { direction in
            switch direction {
            case .increment: engine.wordsPerMinute = min(900, engine.wordsPerMinute + 10)
            case .decrement: engine.wordsPerMinute = max(60, engine.wordsPerMinute - 10)
            @unknown default: break
            }
        }
    }
    #endif
    private func reviewNotice(_ index: Int) -> some View {
        VStack(spacing: 10) {
            Text("Page \(engine.book.pages[index].label) needs a check.").font(.system(size: 14, weight: .medium))
            Text("Compare its text with the PDF, then mark it reviewed. Blank or scanned pages can be entered manually or excluded.")
                .font(.system(size: 12)).foregroundStyle(Palette.muted).multilineTextAlignment(.center)
            Button("Review this page") { engine.pause(); onReviewPage(index); dismiss() }.buttonStyle(SecondaryButton())
        }.padding(18).frame(maxWidth: 500).background(Palette.surface, in: RoundedRectangle(cornerRadius: 12))
    }
    private var optionsSheet: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 22) {
                Text("Reading options").font(.system(size: 22, weight: .semibold))
                Toggle("Focus letter", isOn: $focusLetter).accessibilityIdentifier("focusLetter")
                Text("Highlight one letter and keep it centered as words advance.").font(.system(size: 12)).foregroundStyle(Palette.muted)
                Divider()
                Toggle("Natural pauses", isOn: Binding(get: { engine.naturalPauses }, set: { engine.naturalPauses = $0; savedNaturalPauses = $0 }))
                Text("Slow down slightly at punctuation and paragraph breaks.").font(.system(size: 12)).foregroundStyle(Palette.muted)
                Divider()
                Toggle("Pause at unchecked pages", isOn: Binding(get: { pauseAtUncheckedPages }, set: {
                    pauseAtUncheckedPages = $0
                    engine.allowUnreviewed = !$0
                })).accessibilityIdentifier("pauseAtUncheckedPages")
                Text("Require page approval before playback.").font(.system(size: 12)).foregroundStyle(Palette.muted)
                if engine.book.emptyReadingPageCount > 0 {
                    Text("\(engine.book.emptyReadingPageCount) included page(s) have no selectable text and are skipped when this option is off. You can inspect them in Edit text.").font(.system(size: 12)).foregroundStyle(Palette.amber)
                }
                Button("Done") { showingSettings = false }.buttonStyle(PrimaryButton())
            }.padding(30)
        }.frame(idealWidth: 460, idealHeight: 520).background(Palette.paper)
    }
    private func setAwake(_ awake: Bool) {
        #if os(iOS)
        UIApplication.shared.isIdleTimerDisabled = awake
        #endif
    }
}

#if os(iOS)
/// Owns one touch from down to release, even when it leaves the corner. Keeping
/// this outside the text scroll view prevents speed movement from scrubbing words.
private struct HoldReadingTouchSurface: UIViewRepresentable {
    var enabled: Bool
    var onBegin: () -> Void
    var onMove: (CGFloat) -> Void
    var onEnd: () -> Void

    func makeUIView(context: Context) -> HoldTouchView { HoldTouchView() }
    func updateUIView(_ view: HoldTouchView, context: Context) {
        view.onBegin = onBegin
        view.onMove = onMove
        view.onEnd = onEnd
        view.isUserInteractionEnabled = enabled
    }

    final class HoldTouchView: UIView {
        var onBegin: (() -> Void)?
        var onMove: ((CGFloat) -> Void)?
        var onEnd: (() -> Void)?
        private var tracked: UITouch?
        private var lastX: CGFloat = 0
        private var lastSize = CGSize.zero

        override init(frame: CGRect) {
            super.init(frame: frame)
            isMultipleTouchEnabled = true
            isAccessibilityElement = false
            backgroundColor = .clear
        }
        required init?(coder: NSCoder) { fatalError("init(coder:) is not supported") }

        override func touchesBegan(_ touches: Set<UITouch>, with event: UIEvent?) {
            guard tracked == nil, let touch = touches.first else { return }
            tracked = touch
            lastX = touch.location(in: window).x
            onBegin?()
        }
        override func touchesMoved(_ touches: Set<UITouch>, with event: UIEvent?) {
            guard let tracked, touches.contains(tracked) else { return }
            let x = tracked.location(in: window).x
            onMove?(x - lastX)
            lastX = x
        }
        override func touchesEnded(_ touches: Set<UITouch>, with event: UIEvent?) {
            if let tracked, touches.contains(tracked) { finish() }
        }
        override func touchesCancelled(_ touches: Set<UITouch>, with event: UIEvent?) { finish() }
        override func layoutSubviews() {
            super.layoutSubviews()
            if bounds.size != lastSize { finish(); lastSize = bounds.size }
        }
        override func didMoveToWindow() {
            super.didMoveToWindow()
            if window == nil { finish() }
        }
        private func finish() {
            guard tracked != nil else { return }
            tracked = nil
            onEnd?()
        }
    }
}
#endif

/// Anchors the selected word (or its focus letter) as its neighbors change length.
private struct CenteredWordLayout: Layout {
    var selected: Int
    var focusAnchor: CGFloat?
    var expanded: Bool
    var breaks: Set<Int>
    private var spacing: CGFloat { expanded ? 10 : 18 }

    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        CGSize(width: proposal.width ?? 600, height: expanded ? (proposal.height ?? 360) : 102)
    }

    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
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
        let leading = bounds.midX - currentWidth * anchor
        current.place(at: CGPoint(x: leading, y: bounds.midY), anchor: .leading,
                      proposal: ProposedViewSize(width: currentWidth, height: currentSize.height))
        let inset = expanded ? min(24, bounds.width / 8) : 0
        let neighborProposal = ProposedViewSize(width: expanded ? bounds.width - inset * 2 : nil, height: nil)
        let sizes = subviews.map { $0.sizeThatFits(neighborProposal) }
        let compact = expanded && bounds.height < 240
        let rowHeight = max(compact ? 32 : 44, sizes.enumerated().filter { $0.offset != selected }.map { $0.element.height }.max() ?? 0)
        let firstRow = (currentSize.height + rowHeight) / 2 + (compact ? 4 : 12)
        let rowStep = rowHeight + (compact ? 4 : 8)
        func rowY(_ row: Int) -> CGFloat {
            row == 0 ? bounds.midY : bounds.midY + (row > 0 ? 1 : -1) * (firstRow + CGFloat(abs(row) - 1) * rowStep)
        }
        func place(_ index: Int, x: CGFloat, row: Int) {
            let y = rowY(row)
            let visible = y - sizes[index].height / 2 >= bounds.minY && y + sizes[index].height / 2 <= bounds.maxY
            subviews[index].place(at: CGPoint(x: x, y: visible ? y : bounds.maxY + 10000), anchor: .leading,
                                  proposal: ProposedViewSize(sizes[index]))
        }
        // Wrap outward from the fixed middle word while keeping source order.
        var leadingEdge = leading - spacing
        var row = 0
        for index in (0..<selected).reversed() {
            if expanded && (leadingEdge - sizes[index].width < bounds.minX + inset || breaks.contains(index)) {
                row -= 1; leadingEdge = bounds.maxX - inset
            }
            leadingEdge -= sizes[index].width
            place(index, x: leadingEdge, row: row)
            leadingEdge -= spacing
        }
        var trailingEdge = leading + currentWidth + spacing
        row = 0
        for index in (selected + 1)..<subviews.count {
            if expanded && (trailingEdge + sizes[index].width > bounds.maxX - inset || breaks.contains(index - 1)) {
                row += 1; trailingEdge = bounds.minX + inset
            }
            place(index, x: trailingEdge, row: row)
            trailingEdge += sizes[index].width + spacing
        }
    }
}

struct FlowLayout: Layout {
    var spacing: CGFloat
    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        arrange(subviews, width: proposal.width ?? 600).size
    }
    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        let layout = arrange(subviews, width: bounds.width)
        for (index, point) in layout.positions.enumerated() {
            subviews[index].place(at: CGPoint(x: bounds.minX + point.x, y: bounds.minY + point.y), proposal: .unspecified)
        }
    }
    private func arrange(_ subviews: Subviews, width: CGFloat) -> (size: CGSize, positions: [CGPoint]) {
        var x: CGFloat = 0, y: CGFloat = 0, rowHeight: CGFloat = 0
        var positions: [CGPoint] = []
        for view in subviews {
            let size = view.sizeThatFits(.unspecified)
            if x > 0 && x + size.width > width { x = 0; y += rowHeight + spacing; rowHeight = 0 }
            positions.append(CGPoint(x: x, y: y))
            x += size.width + spacing
            rowHeight = max(rowHeight, size.height)
        }
        return (CGSize(width: width, height: y + rowHeight), positions)
    }
}
