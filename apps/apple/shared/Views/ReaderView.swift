import SwiftUI
#if os(iOS)
import UIKit
#endif

struct ReaderView: View {
    @Environment(\.dismiss) private var dismiss
    @Environment(\.scenePhase) private var scenePhase
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @StateObject private var engine: ReaderEngine
    @AppStorage("readingWPM") private var savedWPM = 250.0
    @AppStorage("naturalPauses") private var savedNaturalPauses = true
    @AppStorage("pauseAtUncheckedPages") private var pauseAtUncheckedPages = false
    @AppStorage("focusLetter") private var focusLetter = false
    @AppStorage("activeWordSizePercent") private var savedActiveWordSizePercent = 100
    private var activeWordSizePercent: Int { min(300, max(100, savedActiveWordSizePercent)) }
    @AppStorage("animatedScrolling") private var animatedScrolling = false
    @State private var showContext = false
    @State private var showingSettings = false
    @State private var showingContents = false
    private let pdfURL: URL?
    @GestureState private var dragInProgress = false
    @State private var speedFeedbackID = 0
    @State private var showSpeed = false
    var onReviewPage: (Int) -> Void
    var onCheckpoint: (ReadingPosition) -> Void

    init(book: Book, pdfURL: URL? = nil, onReviewPage: @escaping (Int) -> Void, onCheckpoint: @escaping (ReadingPosition) -> Void) {
        _engine = StateObject(wrappedValue: ReaderEngine(book: book))
        self.pdfURL = pdfURL
        self.onReviewPage = onReviewPage
        self.onCheckpoint = onCheckpoint
    }

    var body: some View {
        VStack(spacing: 0) {
            header
            focusStage
        }.background(Palette.paper).foregroundStyle(Palette.ink)
        .onAppear {
            engine.wordsPerMinute = savedWPM
            engine.naturalPauses = savedNaturalPauses
            engine.allowUnreviewed = !pauseAtUncheckedPages
            engine.onCheckpoint = onCheckpoint
            if engine.tokens.isEmpty { engine.play() }
        }
        .task(id: speedFeedbackID) {
            guard speedFeedbackID > 0 else { return }
            withAnimation(.easeOut(duration: 0.08)) { showSpeed = true }
            do { try await Task.sleep(for: .milliseconds(650)) } catch { return }
            withAnimation(.easeOut(duration: 0.18)) { showSpeed = false }
        }
        .onDisappear { engine.pause(); setAwake(false) }
        .onChange(of: showContext) { _, passage in engine.passageMode = passage }
        .onChange(of: engine.isPlaying) { _, playing in setAwake(playing) }
        .onChange(of: engine.wordsPerMinute) { _, value in savedWPM = value }
        .onChange(of: dragInProgress) { _, dragging in if !dragging { engine.endScrubbing() } }
        .onChange(of: scenePhase) { _, phase in if phase != .active { engine.pause(); showSpeed = false } }
        .sheet(isPresented: $showingSettings) { optionsSheet }
        .sheet(isPresented: $showingContents) { ReaderContentsSheet(engine: engine, url: pdfURL) }
        .preferredColorScheme(.light)
    }

    private var header: some View {
        VStack(spacing: 0) {
            HStack(spacing: 6) {
                Button { engine.pause(); dismiss() } label: { Image(systemName: "chevron.left").frame(width: 40, height: 44) }
                    .buttonStyle(.plain).accessibilityLabel("Close reader").keyboardShortcut(.escape, modifiers: [])
                pageMenu
                #if os(macOS)
                controls
                #endif
                Button { engine.pause(); showContext.toggle() } label: { Image(systemName: "text.alignleft").frame(width: 44, height: 44) }
                    .buttonStyle(.plain).accessibilityLabel(showContext ? "Single-line view" : "Passage view").accessibilityIdentifier("toggleContext")
                if !showContext {
                    Button { engine.pause(); showingSettings = true } label: { Image(systemName: "ellipsis").frame(width: 44, height: 44) }
                        .buttonStyle(.plain).accessibilityLabel("Reading options").accessibilityIdentifier("readingOptions")
                }
            }.padding(.horizontal, 12).padding(.vertical, 4)
            GeometryReader { geometry in
                Rectangle().fill(Palette.line)
                    .overlay(alignment: .leading) { Rectangle().fill(Palette.forest).frame(width: geometry.size.width * engine.progress) }
            }.frame(height: 2).accessibilityLabel("Reading progress")
                .accessibilityValue("\(Int(engine.progress * 100)) percent").accessibilityIdentifier("readingProgress")
        }.accessibilityElement(children: .contain).accessibilityIdentifier("readerHeader")
    }
    private var pageDescription: String {
        "\(engine.isFinished ? "Finished · " : "")Page \(engine.current.map { engine.book.pages[$0.pageIndex].label } ?? "—") of \(engine.book.pages.count)"
    }
    private var pageMenu: some View {
        Button { engine.pause(); showingContents = true } label: {
            VStack(alignment: .leading, spacing: 2) {
                Text(engine.book.title)
                    .font(.system(size: 14, weight: .medium)).foregroundStyle(Palette.ink)
                HStack(spacing: 8) {
                    if let section = engine.structure.section(at: engine.index) {
                        Text(section.title).lineLimit(1).truncationMode(.tail).accessibilityIdentifier("readerChapter")
                    }
                    Text(pageDescription).fixedSize(horizontal: true, vertical: false)
                }.font(.system(size: 11)).foregroundStyle(Palette.muted)
            }.lineLimit(1).frame(maxWidth: .infinity, minHeight: 44, alignment: .leading).contentShape(Rectangle())
        }.buttonStyle(.plain).accessibilityIdentifier("readerPage").accessibilityHint("Contents and pages")
    }
    private var focusStage: some View {
        GeometryReader { geometry in
            ZStack(alignment: .bottomTrailing) {
                if showContext {
                    PassageView(engine: engine)
                        .frame(maxWidth: 850).frame(maxWidth: .infinity, maxHeight: .infinity)
                } else if let heading = engine.structure.heading(at: engine.index) {
                    Text(heading.title).font(.custom("Georgia", size: 28)).foregroundStyle(Palette.ink)
                        .lineLimit(8).minimumScaleFactor(0.5)
                        .multilineTextAlignment(.center).padding(32).frame(maxWidth: 700)
                        .frame(maxWidth: .infinity, maxHeight: .infinity)
                        .accessibilityAddTraits(.isHeader).accessibilityIdentifier("currentHeading")
                        .accessibilityValue("Word \(engine.index + 1) of \(engine.tokens.count)")
                        .accessibilityAdjustableAction { direction in
                            switch direction {
                            case .increment: engine.seek(engine.index + 1)
                            case .decrement: engine.seek(engine.index - 1)
                            @unknown default: break
                            }
                        }
                } else {
                    TimelineView(.animation(minimumInterval: 1.0 / 60, paused: !scrolling)) { timeline in
                        wordRibbon(height: geometry.size.height, time: timeline.date.timeIntervalSinceReferenceDate)
                            .frame(maxWidth: .infinity, maxHeight: .infinity)
                    }
                }
                #if os(iOS)
                ReadingTouchSurface(enabled: scenePhase == .active && !showingSettings && !showingContents,
                    onBegin: { reading in if reading { engine.play() } else { engine.pause() } },
                    onMove: { reading, movement in
                        if reading { changeSpeed(by: Double(movement) * 2) }
                        else if abs(movement) > 12 || engine.isScrubbing {
                            engine.beginScrubbing()
                            engine.scrub(by: -Int(movement / 32))
                        }
                    },
                    onEnd: { if engine.isPlaying || engine.isScrubbing { engine.pause() } })
                holdCorner.frame(width: geometry.size.width / 2, height: geometry.size.height / 2)
                    .allowsHitTesting(false)
                #endif
            }
            .contentShape(Rectangle())
            #if os(macOS)
            .gesture(scrubGesture)
            #endif
            .overlay(alignment: .topTrailing) {
                if showSpeed {
                    Text("\(Int(engine.wordsPerMinute)) wpm").font(.system(size: 14)).monospacedDigit()
                        .foregroundStyle(Palette.forest).padding(.horizontal, 10).padding(.vertical, 6)
                        .background(Palette.paper.opacity(0.94), in: RoundedRectangle(cornerRadius: 8))
                        .padding(16).transition(.opacity).accessibilityIdentifier("speedOverlay").allowsHitTesting(false)
                }
            }
            .overlay(alignment: .bottom) {
                if let blocked = engine.blockedPage { reviewNotice(blocked).padding(16) }
            }
        }
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("readingSurface")
        .accessibilityValue(showContext ? "Passage view" : "Single-line view")
    }
    private func changeSpeed(by amount: Double) {
        let next = min(900, max(60, engine.wordsPerMinute + amount))
        if Int(next) != Int(engine.wordsPerMinute) { speedFeedbackID += 1 }
        engine.wordsPerMinute = next
    }
    private var scrolling: Bool { animatedScrolling && !reduceMotion && engine.isPlaying && scenePhase == .active }

    private func wordRibbon(height: CGFloat, time: Double) -> some View {
        let compact = showContext && height < 240
        let passageSize: CGFloat = compact ? 20 : 24
        let neighborSize: CGFloat = showContext ? passageSize : 22
        let fontSize = neighborSize * CGFloat(activeWordSizePercent) / 100
        let passageRowHeight: CGFloat = compact ? 36 : 48
        let focused = FocusWord(engine.current?.word.text ?? "", enabled: focusLetter)
        let radius = showContext ? 80 : 6
        var start = min(engine.index, engine.tokens.count), end = min(engine.tokens.count, engine.index + 1)
        while start > max(0, engine.index - radius), engine.structure.heading(at: start - 1) == nil { start -= 1 }
        while end < min(engine.tokens.count, engine.index + radius + 1), engine.structure.heading(at: end) == nil { end += 1 }
        let tokens = Array(engine.tokens[start..<end])
        let breaks = Set(tokens.indices.dropLast().filter {
            tokens[$0].word.endsParagraph || tokens[$0].pageIndex != tokens[$0 + 1].pageIndex
        })
        return CenteredWordLayout(selected: engine.index - start, focusAnchor: focused.anchor,
                                  expanded: showContext, breaks: breaks, firstTokenID: start,
                                  scrolling: scrolling, time: time,
                                  duration: WordMotion.duration(wordsPerMinute: engine.wordsPerMinute)) {
            if engine.tokens.isEmpty {
                Text("Ready?").font(.custom("Georgia", size: fontSize)).accessibilityIdentifier("currentWord")
            } else {
                ForEach(tokens) { token in
                    if token.id == engine.index {
                        Text(focused.text)
                            .font(focused.anchor == nil ? .custom("Georgia", size: fontSize) : .custom("Georgia", fixedSize: fontSize))
                            .lineLimit(1).minimumScaleFactor(focused.anchor == nil ? 0.12 : 0.01)
                            .frame(minHeight: showContext ? passageRowHeight : 0)
                            .foregroundStyle(Palette.ink)
                            .background(Palette.paper).zIndex(1)
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
                        Text(token.word.text)
                            .font(showContext && focusLetter ? .custom("Georgia", fixedSize: passageSize) : .custom("Georgia", size: neighborSize))
                            .foregroundStyle(Palette.muted).lineLimit(1).minimumScaleFactor(0.1)
                            .frame(minHeight: showContext ? passageRowHeight : 44)
                            .accessibilityIdentifier(showContext ? "passageWord-\(token.id)" : "nearbyWord-\(token.id)")
                    }
                }
            }
        }
        .id("\(showContext)-\(focusLetter)-\(activeWordSizePercent)")
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
        HStack(spacing: 2) {
            Button { engine.seek(engine.index - 1) } label: { Image(systemName: "backward.end.fill").frame(width: 32, height: 44) }
                .keyboardShortcut(.leftArrow, modifiers: []).accessibilityLabel("Previous word")
            Button { engine.toggle() } label: {
                Image(systemName: engine.isPlaying ? "pause.fill" : (engine.isFinished ? "arrow.counterclockwise" : "play.fill"))
                    .frame(width: 40, height: 44)
            }.keyboardShortcut(.space, modifiers: []).accessibilityLabel(engine.isPlaying ? "Pause reading" : "Play reading").accessibilityIdentifier("playPause")
            Button { engine.seek(engine.index + 1) } label: { Image(systemName: "forward.end.fill").frame(width: 32, height: 44) }
                .keyboardShortcut(.rightArrow, modifiers: []).accessibilityLabel("Next word")
            Button { changeSpeed(by: -10) } label: { Image(systemName: "minus").frame(width: 32, height: 44) }
                .accessibilityLabel("Read slower").accessibilityValue("\(Int(engine.wordsPerMinute)) words per minute").help("Read slower (−10 WPM)")
            Button { changeSpeed(by: 10) } label: { Image(systemName: "plus").frame(width: 32, height: 44) }
                .accessibilityLabel("Read faster").accessibilityValue("\(Int(engine.wordsPerMinute)) words per minute").help("Read faster (+10 WPM)")
        }.buttonStyle(.plain).foregroundStyle(Palette.forest).disabled(engine.tokens.isEmpty)
    }
    #endif
    #if os(iOS)
    private var holdCorner: some View {
        Color.clear
            .accessibilityElement(children: .ignore)
            .accessibilityAddTraits(.isButton)
            .accessibilityIdentifier("holdToRead")
            .accessibilityLabel("Hold to read")
            .accessibilityValue("\(Int(engine.wordsPerMinute)) words per minute, \(engine.isPlaying ? "reading" : "paused")")
            .accessibilityHint("Hold to read. Move right to read faster, left to read slower. Release to pause.")
            .accessibilityAction { engine.toggle() }
            .accessibilityAdjustableAction { direction in
                switch direction {
                case .increment: changeSpeed(by: 10)
                case .decrement: changeSpeed(by: -10)
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
                Text("Single-line options").font(.system(size: 22, weight: .semibold))
                VStack(alignment: .leading, spacing: 10) {
                    HStack {
                        Text("Active word size")
                        Spacer()
                        Text("\(activeWordSizePercent)%").monospacedDigit().foregroundStyle(Palette.muted)
                            .accessibilityIdentifier("activeWordSizeValue")
                    }
                    Slider(value: Binding(get: { Double(activeWordSizePercent) }, set: {
                        savedActiveWordSizePercent = min(300, max(100, Int($0.rounded())))
                    }), in: 100...300, step: 1)
                        .accessibilityLabel("Active word size").accessibilityValue("\(activeWordSizePercent)%")
                        .accessibilityIdentifier("activeWordSize")
                    Text("100% matches surrounding text.")
                        .font(.system(size: 12)).foregroundStyle(Palette.muted)
                }
                Divider()
                Toggle("Focus letter", isOn: $focusLetter).accessibilityIdentifier("focusLetter")
                Text("Highlight one letter and keep it centered.").font(.system(size: 12)).foregroundStyle(Palette.muted)
                Divider()
                Toggle("Animated scrolling", isOn: $animatedScrolling).accessibilityIdentifier("animatedScrolling")
                Text("Animate word transitions. Respects Reduce Motion.").font(.system(size: 12)).foregroundStyle(Palette.muted)
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
/// The starting zone owns a touch until release. Keeping one surface for both
/// roles prevents a speed adjustment from turning into a scrub at the boundary.
private struct ReadingTouchSurface: UIViewRepresentable {
    var enabled: Bool
    var onBegin: (Bool) -> Void
    var onMove: (Bool, CGFloat) -> Void
    var onEnd: () -> Void

    func makeUIView(context: Context) -> ReadingTouchView { ReadingTouchView() }
    func updateUIView(_ view: ReadingTouchView, context: Context) {
        view.onBegin = onBegin
        view.onMove = onMove
        view.onEnd = onEnd
        if !enabled { view.finish() }
        view.isUserInteractionEnabled = enabled
    }

    final class ReadingTouchView: UIView {
        var onBegin: ((Bool) -> Void)?
        var onMove: ((Bool, CGFloat) -> Void)?
        var onEnd: (() -> Void)?
        private var tracked: UITouch?
        private var activeTouches: Set<UITouch> = []
        private var reading = false
        private var startX: CGFloat = 0
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
            let wasIdle = activeTouches.isEmpty
            activeTouches.formUnion(touches)
            guard wasIdle, tracked == nil, let touch = touches.first else { return }
            tracked = touch
            let point = touch.location(in: self)
            reading = point.x >= bounds.midX && point.y >= bounds.midY
            startX = touch.location(in: window).x
            lastX = startX
            onBegin?(reading)
        }
        override func touchesMoved(_ touches: Set<UITouch>, with event: UIEvent?) {
            guard let tracked, touches.contains(tracked) else { return }
            let x = tracked.location(in: window).x
            onMove?(reading, reading ? x - lastX : x - startX)
            lastX = x
        }
        override func touchesEnded(_ touches: Set<UITouch>, with event: UIEvent?) {
            activeTouches.subtract(touches)
            if let tracked, touches.contains(tracked) { finish() }
        }
        override func touchesCancelled(_ touches: Set<UITouch>, with event: UIEvent?) {
            activeTouches.subtract(touches)
            if let tracked, touches.contains(tracked) { finish() }
        }
        override func layoutSubviews() {
            super.layoutSubviews()
            if bounds.size != lastSize { finish(); lastSize = bounds.size }
        }
        override func didMoveToWindow() {
            super.didMoveToWindow()
            if window == nil { finish(); activeTouches.removeAll() }
        }
        func finish() {
            guard tracked != nil else { return }
            tracked = nil
            onEnd?()
        }
    }
}
#endif

/// Anchors the selected word (or its focus letter) as its neighbors change length.
struct CenteredWordLayout: Layout {
    var selected: Int
    var focusAnchor: CGFloat?
    var expanded: Bool
    var breaks: Set<Int>
    var firstTokenID: Int
    var scrolling: Bool
    var time: Double
    var duration: Double
    private var spacing: CGFloat { expanded ? 10 : 18 }

    struct Cache { var motion = WordMotion() }
    func makeCache(subviews: Subviews) -> Cache { Cache() }
    // Token-window changes retain the positions of words with the same source ID.
    func updateCache(_ cache: inout Cache, subviews: Subviews) {}

    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout Cache) -> CGSize {
        CGSize(width: proposal.width ?? 600, height: expanded ? (proposal.height ?? 360) : 102)
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
        let leading = bounds.midX - currentWidth * anchor
        current.place(at: CGPoint(x: leading, y: bounds.midY), anchor: .leading,
                      proposal: ProposedViewSize(width: currentWidth, height: currentSize.height))
        let inset = expanded ? min(24, bounds.width / 8) : 0
        let neighborProposal = ProposedViewSize(width: expanded ? bounds.width - inset * 2 : nil, height: nil)
        let sizes = subviews.map { $0.sizeThatFits(neighborProposal) }
        let compact = expanded && bounds.height < 240
        let rowHeight = max(currentSize.height, sizes.map(\.height).max() ?? 0)
        let rowStep = rowHeight + (compact ? 4 : 8)
        func rowY(_ row: Int) -> CGFloat { bounds.midY + CGFloat(row) * rowStep }
        var positions = [Int: (x: CGFloat, row: Int)]()
        // Wrap outward from the fixed middle word while keeping source order.
        var leadingEdge = leading - spacing
        var row = 0
        for index in (0..<selected).reversed() {
            if expanded && (leadingEdge - sizes[index].width < bounds.minX + inset || breaks.contains(index)) {
                row -= 1; leadingEdge = bounds.maxX - inset
            }
            leadingEdge -= sizes[index].width
            positions[index] = (leadingEdge, row)
            leadingEdge -= spacing
        }
        var trailingEdge = leading + currentWidth + spacing
        row = 0
        for index in (selected + 1)..<subviews.count {
            if expanded && (trailingEdge + sizes[index].width > bounds.maxX - inset || breaks.contains(index - 1)) {
                row += 1; trailingEdge = bounds.minX + inset
            }
            positions[index] = (trailingEdge, row)
            trailingEdge += sizes[index].width + spacing
        }
        func spread(_ indices: [Int], row: Int, from left: CGFloat, to right: CGFloat) {
            guard indices.count > 1 else { return }
            let widths = indices.map { $0 == selected ? currentWidth : sizes[$0].width }
            let gap = max(spacing, (right - left - widths.reduce(0, +)) / CGFloat(indices.count - 1))
            var x = left
            for (index, width) in zip(indices, widths) {
                if index != selected { positions[index] = (x, row) }
                x += width + gap
            }
        }
        if expanded {
            let rows = Dictionary(grouping: positions.keys, by: { positions[$0]!.row })
            for (row, entries) in rows {
                let indices = entries.sorted()
                if row == 0 {
                    // Include the fixed word as an endpoint on each side.
                    let before = indices.filter { $0 < selected }
                    if let first = before.first, first > 0, !breaks.contains(first - 1) {
                        spread(before + [selected], row: row, from: bounds.minX + inset, to: leading + currentWidth)
                    }
                    let after = indices.filter { $0 > selected }
                    if let last = after.last, last + 1 < subviews.count, !breaks.contains(last) {
                        spread([selected] + after, row: row, from: leading, to: bounds.maxX - inset)
                    }
                } else if let first = indices.first, let last = indices.last,
                          first > 0, last + 1 < subviews.count, !breaks.contains(last) {
                    // Do not stretch paragraph endings or incomplete window edges.
                    spread(indices, row: row, from: bounds.minX + inset, to: bounds.maxX - inset)
                }
            }
        }
        var targets = Dictionary(uniqueKeysWithValues: positions.map { index, position in
            (firstTokenID + index, CGPoint(x: position.x - bounds.minX, y: rowY(position.row) - bounds.minY))
        })
        // Layout may probe placement at multiple origins; cache only local coordinates.
        targets[firstTokenID + selected] = CGPoint(x: leading - bounds.minX, y: bounds.height / 2)
        let displayed = cache.motion.positions(index: firstTokenID + selected, targets: targets,
            viewport: bounds.size, enabled: scrolling, time: time, duration: duration,
            widths: Dictionary(uniqueKeysWithValues: sizes.indices.map { (firstTokenID + $0, sizes[$0].width) }))
        for index in positions.keys {
            guard let point = displayed[firstTokenID + index] else { continue }
            // Keep offscreen rows at their real coordinates so they enter through the clip.
            subviews[index].place(at: CGPoint(x: bounds.minX + point.x, y: bounds.minY + point.y),
                                  anchor: .leading, proposal: ProposedViewSize(sizes[index]))
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
