import Foundation

/// Single-line presentation only: playback and saved positions never depend on animation time.
struct WordMotion {
    private var index: Int?
    private var targets = [Int: CGFloat]()
    private var origins = [Int: CGFloat]()
    private var viewportWidth: CGFloat = 0
    private var enabled = false
    private var started = 0.0
    private var duration = 0.0

    static func duration(wordsPerMinute: Double) -> Double {
        min(0.18, 0.6 * 60 / max(60, wordsPerMinute))
    }

    mutating func positions(index: Int, targets: [Int: CGFloat], viewportWidth: CGFloat,
                            enabled: Bool, time: Double, duration: Double) -> [Int: CGFloat] {
        if self.index != index || self.targets != targets || self.viewportWidth != viewportWidth {
            let consecutive = self.index.map { index == $0 + 1 } ?? false
            let animate = enabled && self.enabled && consecutive && self.viewportWidth == viewportWidth
            origins = animate ? sample(time: time) : [:]
            self.targets = targets
            self.index = index
            self.viewportWidth = viewportWidth
            started = time
            self.duration = duration
        }
        // Pause, scrub, accessibility changes and resume cannot revive an old transition.
        if !enabled { origins = [:] }
        self.enabled = enabled
        return sample(time: time)
    }

    private func sample(time: Double) -> [Int: CGFloat] {
        let fraction = duration > 0 ? min(1, max(0, (time - started) / duration)) : 1
        let eased = 1 - pow(1 - fraction, 3)
        return Dictionary(uniqueKeysWithValues: targets.map { id, target in
            guard id != index, let origin = origins[id] else { return (id, target) }
            return (id, origin + (target - origin) * eased)
        })
    }
}
