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
    @AppStorage("activeWordSizePercent") private var savedActiveWordSizePercent = 100
    private var activeWordSizePercent: Int { min(300, max(100, savedActiveWordSizePercent)) }
    @AppStorage("animatedScrolling") private var animatedScrolling = false
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
        GeometryReader { geometry in
            VStack(spacing: 0) {
                header
                // Only the ribbon extends under horizontal system insets. Wrapped
                // passage text and controls keep their safe-area margins.
                focusStage
                    .padding(.leading, engine.passageMode ? 0 : -geometry.safeAreaInsets.leading)
                    .padding(.trailing, engine.passageMode ? 0 : -geometry.safeAreaInsets.trailing)
            }.background(Palette.paper).foregroundStyle(Palette.ink)
        }
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
                if !engine.passageMode { controls }
                #endif
                Button { engine.passageMode.toggle() } label: { Image(systemName: "text.alignleft").frame(width: 44, height: 44) }
                    .buttonStyle(.plain).accessibilityLabel(engine.passageMode ? "Single-line view" : "Passage view").accessibilityIdentifier("toggleContext")
                if !engine.passageMode {
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
        "\(engine.isFinished ? "Finished · " : "")\(engine.book.unitName) \(engine.current.map { engine.book.pages[$0.pageIndex].label } ?? "—") of \(engine.book.pages.count)"
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
                if engine.passageMode {
                    PassageView(engine: engine) { index in
                        engine.seek(index)
                        engine.passageMode = false
                    }
                        .frame(maxWidth: .infinity, maxHeight: .infinity)
                } else {
                    SingleLineView(engine: engine, focusLetter: focusLetter,
                        activeWordSizePercent: activeWordSizePercent, animatedScrolling: animatedScrolling)
                }
                #if os(iOS)
                if !engine.passageMode {
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
                }
                #endif
            }
            .contentShape(Rectangle())
            #if os(macOS)
            .gesture(scrubGesture, including: engine.passageMode ? .subviews : .all)
            #endif
            .overlay(alignment: .topTrailing) {
                if showSpeed && !engine.passageMode {
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
        .accessibilityValue(engine.passageMode ? "Passage view" : "Single-line view")
    }
    private func changeSpeed(by amount: Double) {
        let next = min(900, max(60, engine.wordsPerMinute + amount))
        if Int(next) != Int(engine.wordsPerMinute) { speedFeedbackID += 1 }
        engine.wordsPerMinute = next
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
            Text("\(engine.book.unitName) \(engine.book.pages[index].label) needs a check.").font(.system(size: 14, weight: .medium))
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
                Toggle("Pause at unchecked \(engine.book.unitPlural)", isOn: Binding(get: { pauseAtUncheckedPages }, set: {
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
