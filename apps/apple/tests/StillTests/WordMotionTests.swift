import XCTest
import SwiftUI
@testable import Still

final class WordMotionTests: XCTestCase {
    private let viewport: CGFloat = 600
    private let before: [Int: CGFloat] = [0: 300, 1: 380, 2: 450]
    private let after: [Int: CGFloat] = [0: 220, 1: 300, 2: 370]

    func testNeighborsMoveButActiveWordRemainsFixed() {
        var motion = WordMotion()
        _ = motion.positions(index: 0, targets: before, viewportWidth: viewport, enabled: true, time: 0, duration: 0.18)
        let first = motion.positions(index: 1, targets: after, viewportWidth: viewport, enabled: true, time: 1, duration: 0.18)
        XCTAssertEqual(first[0], before[0])
        XCTAssertEqual(first[1], after[1])
        let middle = motion.positions(index: 1, targets: after, viewportWidth: viewport, enabled: true, time: 1.09, duration: 0.18)
        XCTAssertGreaterThan(middle[2]!, after[2]!)
        XCTAssertLessThan(middle[2]!, before[2]!)
        XCTAssertEqual(middle[1], after[1])
        XCTAssertEqual(motion.positions(index: 1, targets: after, viewportWidth: viewport, enabled: true, time: 1.2, duration: 0.18), after)
    }

    func testNewWordWindowKeepsExistingMotionAndDropsOldWords() {
        var motion = WordMotion()
        let source = before.merging([3: CGFloat(530)]) { _, new in new }
        let destination = after.merging([3: CGFloat(450)]) { _, new in new }
        _ = motion.positions(index: 0, targets: source, viewportWidth: viewport, enabled: true, time: 0, duration: 1)
        _ = motion.positions(index: 1, targets: destination, viewportWidth: viewport, enabled: true, time: 1, duration: 1)
        let interrupted = motion.positions(index: 1, targets: destination, viewportWidth: viewport, enabled: true, time: 1.1, duration: 1)
        let next: [Int: CGFloat] = [1: 220, 2: 300, 3: 370, 4: 450]
        let resumed = motion.positions(index: 2, targets: next, viewportWidth: viewport, enabled: true, time: 1.1, duration: 1)
        XCTAssertNil(resumed[0])
        XCTAssertEqual(resumed[1], interrupted[1])
        XCTAssertEqual(resumed[2], next[2]) // Selected word snaps to its focus anchor.
        XCTAssertEqual(resumed[3], interrupted[3]) // A moving neighbor keeps its sampled position.
        XCTAssertEqual(resumed[4], next[4]) // A newly visible word has no stale origin.
    }

    func testPauseOrReducedMotionSettlesAndResumeDoesNotReviveMotion() {
        var motion = WordMotion()
        _ = motion.positions(index: 0, targets: before, viewportWidth: viewport, enabled: true, time: 0, duration: 0.18)
        _ = motion.positions(index: 1, targets: after, viewportWidth: viewport, enabled: true, time: 1, duration: 0.18)
        XCTAssertEqual(motion.positions(index: 1, targets: after, viewportWidth: viewport, enabled: false, time: 1.01, duration: 0.18), after)
        XCTAssertEqual(motion.positions(index: 1, targets: after, viewportWidth: viewport, enabled: true, time: 1.02, duration: 0.18), after)
        // Manual scrubbing/stepping is immediate, even for a one-word forward seek.
        XCTAssertEqual(motion.positions(index: 2, targets: before, viewportWidth: viewport, enabled: false, time: 1.03, duration: 0.18), before)
    }

    func testJumpsReplayAndViewportChangesAreImmediate() {
        for (index, size) in [(8, viewport), (0, viewport), (1, CGFloat(400))] {
            var motion = WordMotion()
            _ = motion.positions(index: 0, targets: before, viewportWidth: viewport, enabled: true, time: 0, duration: 0.18)
            XCTAssertEqual(motion.positions(index: index, targets: after, viewportWidth: size, enabled: true, time: 1, duration: 0.18), after)
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
        var focusAnchor: CGFloat?
        var sizePercent: CGFloat
        var body: some View {
            let start = model.index - 6
            CenteredWordLayout(selected: 6, focusAnchor: focusAnchor,
                firstTokenID: start, scrolling: model.scrolling, time: model.time, duration: 0.18) {
                ForEach(start...(model.index + 6), id: \.self) { id in
                    Text("word\(id)").font(.custom("Georgia", size: 22 * (id == model.index ? sizePercent / 100 : 1)))
                        .frame(minHeight: 48)
                        .background(GeometryReader { geometry in
                            Color.clear.preference(key: Frames.self, value: [id: geometry.frame(in: .named("stage"))])
                        })
                }
            }
            .frame(width: 600, height: 102)
            .coordinateSpace(name: "stage")
            .onPreferenceChange(Frames.self) { model.frames = $0 }
        }
    }

    func testActualLayoutMovesNeighborsAndKeepsActiveWordCentered() async throws {
        for (focusAnchor, sizePercent) in [(nil, CGFloat(100)), (CGFloat(0.3), CGFloat(100)), (nil, CGFloat(300)), (CGFloat(0.3), CGFloat(300))] {
            let model = Model()
            let host = UIHostingController(rootView: Harness(model: model, focusAnchor: focusAnchor, sizePercent: sizePercent))
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
            let active = try XCTUnwrap(model.frames[21])
            XCTAssertEqual(active.minX + active.width * (focusAnchor ?? 0.5), 300, accuracy: 1)
            model.time = 1.09
            try await settle()
            let middle = try XCTUnwrap(model.frames[19]).midX
            model.time = 1.2
            try await settle()
            let end = try XCTUnwrap(model.frames[19]).midX
            XCTAssertGreaterThan(abs(middle - before), 1)
            XCTAssertGreaterThan(abs(middle - end), 1)
            let settled = try XCTUnwrap(model.frames[21])
            XCTAssertEqual(settled.minX + settled.width * (focusAnchor ?? 0.5), 300, accuracy: 1)
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
