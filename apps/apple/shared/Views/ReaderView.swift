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
        GeometryReader { geometry in
            VStack(spacing: 0) {
                header.padding(.horizontal, 24).padding(.vertical, 16)
                Rectangle().fill(Palette.line).frame(height: 1)
                ScrollView {
                    VStack(spacing: 0) {
                        focusStage.frame(height: max(220, min(360, geometry.size.height * 0.43)))
                        if let blocked = engine.blockedPage { reviewNotice(blocked).padding(.bottom, 22) }
                        controls
                        speedControl.padding(.top, 25)
                        if showContext { contextView.padding(.top, 30) }
                    }
                    .padding(.top, readingTopInset(height: geometry.size.height))
                    .frame(maxWidth: 850).frame(maxWidth: .infinity).padding(.horizontal, 24).padding(.bottom, 30)
                }
                Spacer(minLength: 0)
                progressFooter.padding(.horizontal, 24).padding(.vertical, 18)
            }.background(Palette.paper).foregroundStyle(Palette.ink)
        }
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

    private func readingTopInset(height: CGFloat) -> CGFloat {
        let stageHeight = max(220, min(360, height * 0.43))
        // Center the word on tall iPads; preserve room for controls in shorter windows.
        let centeredInset = height / 2 - 95 - stageHeight / 2
        let availableInset = height - 175 - stageHeight - 280
        return max(0, min(centeredInset, availableInset))
    }

    private var header: some View {
        HStack(spacing: 14) {
            Button { engine.pause(); dismiss() } label: { Image(systemName: "chevron.left").frame(width: 40, height: 44) }
                .buttonStyle(.plain).accessibilityLabel("Close reader").keyboardShortcut(.escape, modifiers: [])
            Text(engine.book.title).font(.system(size: 14, weight: .medium)).lineLimit(1)
            Spacer()
            Button { engine.pause(); showContext.toggle() } label: { Image(systemName: "text.alignleft").frame(width: 44, height: 44) }
                .buttonStyle(.plain).accessibilityLabel(showContext ? "Hide passage" : "Nearby passage").accessibilityIdentifier("toggleContext")
            Button { engine.pause(); showingSettings = true } label: { Image(systemName: "ellipsis").frame(width: 44, height: 44) }
                .buttonStyle(.plain).accessibilityLabel("Reading options").accessibilityIdentifier("readingOptions")
        }
    }
    private var focusStage: some View {
        VStack(spacing: 0) {
            Spacer()
            wordRibbon
            Spacer()
        }
        .contentShape(Rectangle())
        .simultaneousGesture(scrubGesture)
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("readingSurface")
    }
    private var wordRibbon: some View {
        let focused = FocusWord(engine.current?.word.text ?? "", enabled: focusLetter)
        return CenteredWordLayout(selected: engine.index - engine.context.startIndex, focusAnchor: focused.anchor) {
            if engine.tokens.isEmpty {
                Text("Ready?").font(.custom("Georgia", size: 68)).accessibilityIdentifier("currentWord")
            } else {
                ForEach(Array(engine.context)) { token in
                    if token.id == engine.index {
                        Text(focused.text)
                            .font(focused.anchor == nil ? .custom("Georgia", size: 68) : .custom("Georgia", fixedSize: 68))
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
                            Text(token.word.text).font(.custom("Georgia", size: 22))
                                .foregroundStyle(Palette.muted).fixedSize().frame(minHeight: 44)
                        }
                        .buttonStyle(.plain)
                        .accessibilityLabel("Read from \(token.word.text)")
                        .accessibilityIdentifier("nearbyWord-\(token.id)")
                    }
                }
            }
        }
        .frame(height: 102).frame(maxWidth: .infinity)
        .contentShape(Rectangle())
        .clipped()
        .mask {
            LinearGradient(stops: [.init(color: .clear, location: 0), .init(color: .black, location: 0.06),
                                   .init(color: .black, location: 0.94), .init(color: .clear, location: 1)],
                           startPoint: .leading, endPoint: .trailing)
        }
    }
    private var scrubGesture: some Gesture {
        DragGesture(minimumDistance: 12)
            .updating($dragInProgress) { _, dragging, _ in dragging = true }
            .onChanged { value in
                let translation = value.translation
                // Leave vertical gestures to the reader's scroll view.
                guard engine.isScrubbing || abs(translation.width) > abs(translation.height) * 1.2 else { return }
                engine.beginScrubbing()
                engine.scrub(by: -Int((translation.width / 32).rounded(.towardZero)))
            }
            .onEnded { _ in engine.endScrubbing() }
    }
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
    private var contextView: some View {
        VStack(alignment: .leading, spacing: 14) {
            FlowLayout(spacing: 5) {
                ForEach(Array(engine.context)) { token in
                    Button { engine.seek(token.id) } label: {
                        Text(token.word.text).font(.custom("Georgia", size: 17)).padding(.horizontal, 2).padding(.vertical, 4)
                            .foregroundStyle(token.id == engine.index ? Palette.surface : Palette.muted)
                            .background(token.id == engine.index ? Palette.forest : .clear, in: RoundedRectangle(cornerRadius: 3))
                    }.buttonStyle(.plain)
                        .accessibilityIdentifier("passageWord-\(token.id)")
                        .accessibilityAddTraits(token.id == engine.index ? .isSelected : [])
                }
            }
        }.padding(22).frame(maxWidth: 650, alignment: .leading).background(Palette.surface, in: RoundedRectangle(cornerRadius: 15))
            .contentShape(Rectangle()).simultaneousGesture(scrubGesture)
            .accessibilityElement(children: .contain).accessibilityIdentifier("nearbyPassage")
    }
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
                Text(engine.isFinished ? "Finished" : "\(Int(engine.progress * 100))%")
            }.font(.system(size: 10)).foregroundStyle(Palette.muted)
        }
    }
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

/// Anchors the selected word (or its focus letter) as its neighbors change length.
private struct CenteredWordLayout: Layout {
    var selected: Int
    var focusAnchor: CGFloat?
    let spacing: CGFloat = 18

    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        CGSize(width: proposal.width ?? 600, height: 102)
    }

    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        guard subviews.indices.contains(selected) else { return }
        let current = subviews[selected]
        let anchor = focusAnchor ?? 0.5
        // Keep both sides of a focused word inside the ribbon's fading edges.
        let availableWidth = focusAnchor == nil ? bounds.width * 0.68
            : min(bounds.width * 0.68, bounds.width * 0.44 / max(anchor, 1 - anchor))
        let currentWidth = min(current.sizeThatFits(.unspecified).width, availableWidth)
        let leading = bounds.midX - currentWidth * anchor
        current.place(at: CGPoint(x: leading, y: bounds.midY), anchor: .leading,
                      proposal: ProposedViewSize(width: currentWidth, height: bounds.height))
        var leadingEdge = leading - spacing
        for index in (0..<selected).reversed() {
            let size = subviews[index].sizeThatFits(.unspecified)
            subviews[index].place(at: CGPoint(x: leadingEdge, y: bounds.midY), anchor: .trailing, proposal: .unspecified)
            leadingEdge -= size.width + spacing
        }
        var trailingEdge = leading + currentWidth + spacing
        for index in (selected + 1)..<subviews.count {
            let size = subviews[index].sizeThatFits(.unspecified)
            subviews[index].place(at: CGPoint(x: trailingEdge, y: bounds.midY), anchor: .leading, proposal: .unspecified)
            trailingEdge += size.width + spacing
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
