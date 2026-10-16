import XCTest
import SwiftUI
@testable import Still

final class WordMotionTests: XCTestCase {
    private let viewport = CGSize(width: 600, height: 400)
    private let before = [0: CGPoint(x: 300, y: 200), 1: CGPoint(x: 380, y: 200), 2: CGPoint(x: 450, y: 256)]
    private let after = [0: CGPoint(x: 220, y: 200), 1: CGPoint(x: 300, y: 200), 2: CGPoint(x: 370, y: 200)]

    func testNeighborsMoveButActiveWordRemainsFixed() {
        var motion = WordMotion()
        _ = motion.positions(index: 0, targets: before, viewport: viewport, enabled: true, time: 0, duration: 0.18)
        let first = motion.positions(index: 1, targets: after, viewport: viewport, enabled: true, time: 1, duration: 0.18)
        XCTAssertEqual(first[0], before[0])
        XCTAssertEqual(first[1], after[1])
        let middle = motion.positions(index: 1, targets: after, viewport: viewport, enabled: true, time: 1.09, duration: 0.18)
        XCTAssertGreaterThan(middle[2]!.x, after[2]!.x)
        XCTAssertLessThan(middle[2]!.x, before[2]!.x)
        XCTAssertGreaterThan(middle[2]!.y, after[2]!.y)
        XCTAssertLessThan(middle[2]!.y, before[2]!.y)
        XCTAssertEqual(middle[1], after[1])
        XCTAssertEqual(motion.positions(index: 1, targets: after, viewport: viewport, enabled: true, time: 1.2, duration: 0.18), after)
    }

    func testWrappingWordUsesClippedEdgesInsteadOfCrossingTheRow() {
        var motion = WordMotion()
        let source = [2: CGPoint(x: 24, y: 256)]
        let destination = [2: CGPoint(x: 500, y: 200)]
        _ = motion.positions(index: 0, targets: source, viewport: viewport, enabled: true, time: 0, duration: 1, widths: [2: 80])
        _ = motion.positions(index: 1, targets: destination, viewport: viewport, enabled: true, time: 1, duration: 1, widths: [2: 80])
        for step in 1...9 {
            let point = motion.positions(index: 1, targets: destination, viewport: viewport, enabled: true,
                time: 1 + Double(step) / 10, duration: 1, widths: [2: 80])[2]!
            XCTAssertTrue((point.y == 256 && point.x <= 24) || (point.y == 200 && point.x >= 500))
        }
    }

    func testPauseOrReducedMotionSettlesAndResumeDoesNotReviveMotion() {
        var motion = WordMotion()
        _ = motion.positions(index: 0, targets: before, viewport: viewport, enabled: true, time: 0, duration: 0.18)
        _ = motion.positions(index: 1, targets: after, viewport: viewport, enabled: true, time: 1, duration: 0.18)
        XCTAssertEqual(motion.positions(index: 1, targets: after, viewport: viewport, enabled: false, time: 1.01, duration: 0.18), after)
        XCTAssertEqual(motion.positions(index: 1, targets: after, viewport: viewport, enabled: true, time: 1.02, duration: 0.18), after)
        // Manual scrubbing/stepping is immediate, even for a one-word forward seek.
        XCTAssertEqual(motion.positions(index: 2, targets: before, viewport: viewport, enabled: false, time: 1.03, duration: 0.18), before)
    }

    func testJumpsReplayAndViewportChangesAreImmediate() {
        for (index, size) in [(8, viewport), (0, viewport), (1, CGSize(width: 400, height: 600))] {
            var motion = WordMotion()
            _ = motion.positions(index: 0, targets: before, viewport: viewport, enabled: true, time: 0, duration: 0.18)
            XCTAssertEqual(motion.positions(index: index, targets: after, viewport: size, enabled: true, time: 1, duration: 0.18), after)
        }
        for speed in [60.0, 250, 900] {
            XCTAssertLessThan(WordMotion.duration(wordsPerMinute: speed), 60 / speed)
            XCTAssertLessThanOrEqual(WordMotion.duration(wordsPerMinute: speed), 0.18)
        }
    }
}

// Exercise SwiftUI's real Layout cache and token identity, not just interpolation math.
@MainActor
final class WordMotionLayoutTests: XCTestCase {
    private final class Model: ObservableObject {
        @Published var index = 20
        @Published var time = 0.0
        @Published var scrolling = true
        var frames = [Int: CGRect]()
    }
    private struct Frames: PreferenceKey {
        static var defaultValue = [Int: CGRect]()
        static func reduce(value: inout [Int: CGRect], nextValue: () -> [Int: CGRect]) {
            value.merge(nextValue(), uniquingKeysWith: { _, new in new })
        }
    }
    private struct Harness: View {
        @ObservedObject var model: Model
        var expanded: Bool
        var body: some View {
            let start = model.index - 6
            CenteredWordLayout(selected: 6, focusAnchor: nil, expanded: expanded, breaks: [],
                firstTokenID: start, scrolling: model.scrolling, time: model.time, duration: 0.18) {
                ForEach(start...(model.index + 6), id: \.self) { id in
                    Text("word\(id)").font(.custom("Georgia", size: expanded ? 24 : (id == model.index ? 68 : 22)))
                        .frame(minHeight: 48)
                        .background(GeometryReader { geometry in
                            Color.clear.preference(key: Frames.self, value: [id: geometry.frame(in: .named("stage"))])
                        })
                }
            }
            .frame(width: 600, height: expanded ? 400 : 102)
            .coordinateSpace(name: "stage")
            .onPreferenceChange(Frames.self) { model.frames = $0 }
        }
    }

    func testActualLayoutMovesNeighborsAndKeepsActiveWordCentered() async throws {
        for expanded in [false, true] {
            let model = Model()
            let host = UIHostingController(rootView: Harness(model: model, expanded: expanded))
            let window = UIWindow(frame: CGRect(x: 0, y: 0, width: 600, height: 400))
            window.rootViewController = host
            window.makeKeyAndVisible()
            func settle() async throws {
                host.view.setNeedsLayout()
                host.view.layoutIfNeeded()
                try await Task.sleep(for: .milliseconds(80))
            }
            try await settle()
            let before = try XCTUnwrap(model.frames[19]).midX
            model.index = 21; model.time = 1
            try await settle()
            XCTAssertEqual(try XCTUnwrap(model.frames[21]).midX, 300, accuracy: 1)
            model.time = 1.09
            try await settle()
            let middle = try XCTUnwrap(model.frames[19]).midX
            model.time = 1.2
            try await settle()
            let end = try XCTUnwrap(model.frames[19]).midX
            XCTAssertGreaterThan(abs(middle - before), 1)
            XCTAssertGreaterThan(abs(middle - end), 1)
            XCTAssertEqual(try XCTUnwrap(model.frames[21]).midX, 300, accuracy: 1)
            model.index = 22; model.time = 2
            try await settle()
            model.scrolling = false; model.time = 2.01
            try await settle()
            let paused = model.frames
            model.scrolling = true; model.time = 2.02
            try await settle()
            XCTAssertEqual(model.frames, paused)
            window.isHidden = true
        }
    }
}
