import SwiftUI
#if os(iOS)
import UIKit
#else
import AppKit
#endif

private enum PassageAnchor: Hashable { case block(Int), row(Int) }
private struct PassageRowFrame: Equatable {
    let words: Range<Int>
    let frame: CGRect
    let layoutWidth: CGFloat
    let fontSize: CGFloat
}
private struct PassageFrames: PreferenceKey {
    static var defaultValue: [PassageRowFrame] { [] }
    static func reduce(value: inout [PassageRowFrame], nextValue: () -> [PassageRowFrame]) { value += nextValue() }
}

struct PassageView: View {
    @ObservedObject var engine: ReaderEngine
    var textSize = 22
    let selectWord: (Int) -> Void
    @ScaledMetric private var baseSize: CGFloat = 22
    @State private var pendingIndex: Int?
    @State private var reportedIndex = -1
    @State private var positioned = false
    @State private var userScrolling = false
    @State private var visibleWords = 0..<0

    var body: some View {
        GeometryReader { geometry in
            let size = baseSize * CGFloat(min(32, max(16, textSize))) / 22
            let rowHeight = size * 1.6
            let width = max(1, geometry.size.width - 48)
            ScrollViewReader { proxy in
                ScrollView {
                    LazyVStack(alignment: .leading, spacing: 0) {
                        ForEach(engine.passage.blocks) { block in
                            PassageBlock(engine: engine, block: block, width: width, size: size, rowHeight: rowHeight)
                                .id(PassageAnchor.block(block.id))
                        }
                        if engine.tokens.isEmpty { Text("Ready?").font(.custom("Georgia", size: size)) }
                    }
                    .background(PassageScrollInput { userScrolling = true })
                    .padding(.horizontal, 24).padding(.top, 16).padding(.bottom, geometry.size.height)
                }
                .coordinateSpace(name: "passageScroll")
                .accessibilityIdentifier("passageScroll")
                .accessibilityScrollAction { edge in
                    switch edge {
                    case .top, .leading: engine.seek(visibleWords.upperBound)
                    case .bottom, .trailing: engine.seek(visibleWords.lowerBound - max(1, visibleWords.count))
                    }
                }
                .onChange(of: geometry.size, initial: true) { _, _ in navigate(engine.index, proxy: proxy) }
                .onChange(of: size) { _, _ in navigate(engine.index, proxy: proxy) }
                .onChange(of: engine.index) { _, index in
                    if index != reportedIndex { navigate(index, proxy: proxy) }
                }
                .onPreferenceChange(PassageFrames.self) { reportedFrames in
                    // A resize first reports frames from the previous wrapping. Never
                    // treat those intermediate frames as a user changing their place.
                    let frames = reportedFrames.filter { $0.layoutWidth == width && $0.fontSize == size }
                    let visible = frames.filter { $0.frame.maxY > 1 && $0.frame.minY < geometry.size.height }
                    if let first = visible.map(\.words.lowerBound).min(), let last = visible.map(\.words.upperBound).max() {
                        visibleWords = first..<last
                    }
                    if let target = pendingIndex {
                        if let row = frames.first(where: { $0.words.contains(target) }) {
                            if abs(row.frame.minY) < 1 {
                                reportedIndex = target; positioned = true; pendingIndex = nil
                            } else { proxy.scrollTo(PassageAnchor.row(row.words.lowerBound), anchor: .top) }
                        }
                    } else if positioned && userScrolling,
                              let top = visible.min(by: { $0.frame.minY < $1.frame.minY }),
                              !top.words.contains(engine.index) {
                        reportedIndex = top.words.lowerBound
                        engine.browse(reportedIndex)
                    }
                }
            }
        }
        .environment(\.openURL, OpenURLAction { url in
            guard url.scheme == "still-word", let host = url.host, let index = Int(host), engine.tokens.indices.contains(index) else { return .discarded }
            selectWord(index)
            return .handled
        })
        .task(id: engine.index) {
            guard engine.passageMode, pendingIndex == nil, positioned else { return }
            do { try await Task.sleep(for: .milliseconds(400)) } catch { return }
            engine.checkpoint()
        }
        .accessibilityElement(children: .contain).accessibilityIdentifier("nearbyPassage")
    }

    private func navigate(_ index: Int, proxy: ScrollViewProxy) {
        guard !engine.passage.blocks.isEmpty else { return }
        userScrolling = false
        pendingIndex = index
        reportedIndex = index
        let block = engine.passage.blocks[engine.passage.block(containing: index)]
        proxy.scrollTo(PassageAnchor.block(block.id), anchor: .top)
    }
}

private struct PassageBlock: View {
    @ObservedObject var engine: ReaderEngine
    let block: PassageDocument.Block
    let width: CGFloat
    let size: CGFloat
    let rowHeight: CGFloat
    @State private var layout: PassageLayout?
    @State private var arrangedWidth: CGFloat = 0
    @State private var arrangedSize: CGFloat = 0

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            if let layout {
                Color.clear.frame(height: (layout.rows.first?.top ?? 0) * rowHeight)
                ForEach(layout.rows.indices, id: \.self) { rowIndex in
                    let row = layout.rows[rowIndex]
                    let words = (block.id + row.words.lowerBound)..<(block.id + row.words.upperBound)
                    Text(linked(words)).font(.custom(row.isHeading ? "Georgia-Bold" : "Georgia", fixedSize: size * (row.isHeading ? 1.2 : 1)))
                        .foregroundStyle(Palette.ink).tint(Palette.ink)
                        .lineLimit(1).minimumScaleFactor(0.01)
                        .frame(width: width, height: rowHeight * row.height, alignment: .leading)
                        .id(PassageAnchor.row(words.lowerBound))
                        .background(GeometryReader { geometry in
                            Color.clear.preference(key: PassageFrames.self,
                                value: [PassageRowFrame(words: words, frame: geometry.frame(in: .named("passageScroll")), layoutWidth: arrangedWidth, fontSize: arrangedSize)])
                        })
                        .accessibilityAddTraits(row.isHeading ? .isHeader : [])
                        .accessibilityIdentifier(words.contains(engine.index) ? "currentPassageRow" : "passageRow-\(words.lowerBound)")
                        .accessibilityValue(words.contains(engine.index) ? "Word \(engine.index + 1) of \(engine.tokens.count)" : "")
                }
                Color.clear.frame(height: rowHeight * 0.6)
            } else { Color.clear.frame(height: rowHeight) }
        }
        .onChange(of: width, initial: true) { _, _ in arrange() }
        .onChange(of: size) { _, _ in arrange() }
    }

    private func linked(_ words: Range<Int>) -> AttributedString {
        var result = AttributedString()
        for index in words {
            if index != words.lowerBound { result += AttributedString(engine.tokens[index - 1].separator(to: engine.tokens[index])) }
            var word = AttributedString(engine.tokens[index].word.text)
            word.link = URL(string: "still-word://\(index)")
            word.foregroundColor = Palette.ink
            result += word
        }
        return result
    }

    private func arrange() {
        #if os(iOS)
        let font = UIFont(name: "Georgia", size: size) ?? UIFont.systemFont(ofSize: size)
        #else
        let font = NSFont(name: "Georgia", size: size) ?? NSFont.systemFont(ofSize: size)
        #endif
        let nearby = Array(engine.tokens[block.words])
        let ids = Dictionary(uniqueKeysWithValues: nearby.compactMap { token in engine.structure.heading(at: token.id).map { (token.id, $0.words.lowerBound) } })
        var measured: [String: Double] = [:]
        let widths = nearby.map { token in
            let heading = ids[token.id] != nil
            let key = "\(heading):\(token.word.text)"
            if let cached = measured[key] { return cached }
            #if os(iOS)
            let face = heading ? UIFont(name: "Georgia-Bold", size: size * 1.2) ?? font.withSize(size * 1.2) : font
            #else
            let face = heading ? NSFont(name: "Georgia-Bold", size: size * 1.2) ?? font : font
            #endif
            let value = ceil((token.word.text as NSString).size(withAttributes: [.font: face]).width)
            measured[key] = value
            return value
        }
        layout = PassageLayout(tokens: nearby, widths: widths, availableWidth: width,
            spacing: (" " as NSString).size(withAttributes: [.font: font]).width, headingIDs: ids)
        arrangedWidth = width; arrangedSize = size
    }
}

/// Observes native input without replacing the scroll view's gestures or delegate.
/// Reflow and programmatic scrollTo calls must never select a different word.
#if os(iOS)
private struct PassageScrollInput: UIViewRepresentable {
    let began: () -> Void
    func makeUIView(context: Context) -> Probe { Probe(began: began) }
    func updateUIView(_ view: Probe, context: Context) { view.began = began }
    final class Probe: UIView {
        var began: () -> Void
        private weak var scroll: UIScrollView?
        init(began: @escaping () -> Void) { self.began = began; super.init(frame: .zero); isUserInteractionEnabled = false }
        required init?(coder: NSCoder) { fatalError("init(coder:) has not been implemented") }
        override func didMoveToWindow() {
            super.didMoveToWindow()
            scroll?.panGestureRecognizer.removeTarget(self, action: #selector(panning))
            var ancestor = superview
            while let view = ancestor {
                if let found = view as? UIScrollView {
                    scroll = found
                    found.panGestureRecognizer.addTarget(self, action: #selector(panning))
                    break
                }
                ancestor = view.superview
            }
        }
        @objc private func panning() {
            if scroll?.panGestureRecognizer.state == .began { began() }
        }
    }
}
#else
private struct PassageScrollInput: NSViewRepresentable {
    let began: () -> Void
    func makeNSView(context: Context) -> Probe { Probe(began: began) }
    func updateNSView(_ view: Probe, context: Context) { view.began = began }
    final class Probe: NSView {
        var began: () -> Void
        private var monitor: Any?
        init(began: @escaping () -> Void) { self.began = began; super.init(frame: .zero) }
        required init?(coder: NSCoder) { fatalError("init(coder:) has not been implemented") }
        override func viewDidMoveToWindow() {
            super.viewDidMoveToWindow()
            if let monitor { NSEvent.removeMonitor(monitor); self.monitor = nil }
            guard window != nil else { return }
            monitor = NSEvent.addLocalMonitorForEvents(matching: [.scrollWheel, .leftMouseDown, .keyDown]) { [weak self] event in
                guard let self, event.window === self.window, let scroll = self.enclosingScrollView else { return event }
                let point = scroll.convert(event.locationInWindow, from: nil)
                if event.type == .keyDown || scroll.bounds.contains(point) { self.began() }
                return event
            }
        }
        deinit { if let monitor { NSEvent.removeMonitor(monitor) } }
    }
}
#endif
