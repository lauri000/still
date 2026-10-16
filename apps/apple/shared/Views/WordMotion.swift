import Foundation

/// Presentation only: playback and saved positions never depend on animation time.
struct WordMotion {
    private var index: Int?
    private var targets = [Int: CGPoint]()
    private var origins = [Int: CGPoint]()
    private var viewport = CGSize.zero
    private var widths = [Int: CGFloat]()
    private var enabled = false
    private var started = 0.0
    private var duration = 0.0

    static func duration(wordsPerMinute: Double) -> Double {
        min(0.18, 0.6 * 60 / max(60, wordsPerMinute))
    }

    mutating func positions(index: Int, targets: [Int: CGPoint], viewport: CGSize,
                            enabled: Bool, time: Double, duration: Double, widths: [Int: CGFloat] = [:]) -> [Int: CGPoint] {
        if self.index != index || self.targets != targets || self.viewport != viewport {
            let consecutive = self.index.map { index == $0 + 1 } ?? false
            let animate = enabled && self.enabled && consecutive && self.viewport == viewport
            origins = animate ? sample(time: time) : [:]
            self.targets = targets
            self.index = index
            self.viewport = viewport
            started = time
            self.duration = duration
        }
        self.widths = widths
        // Pause, scrub, accessibility changes and resume cannot revive an old transition.
        if !enabled { origins = [:] }
        self.enabled = enabled
        return sample(time: time)
    }

    private func sample(time: Double) -> [Int: CGPoint] {
        let fraction = duration > 0 ? min(1, max(0, (time - started) / duration)) : 1
        let eased = 1 - pow(1 - fraction, 3)
        return Dictionary(uniqueKeysWithValues: targets.map { id, target in
            guard id != index, let origin = origins[id] else { return (id, target) }
            // A word wrapping to the preceding row exits left and re-enters right.
            // Crossing the clip while invisible avoids sweeping over other words.
            if target.y < origin.y - 1, target.x > origin.x, let width = widths[id] {
                let exit = max(0, origin.x + width)
                let travel = (exit + max(0, viewport.width - target.x)) * eased
                return (id, travel < exit
                    ? CGPoint(x: origin.x - travel, y: origin.y)
                    : CGPoint(x: viewport.width - (travel - exit), y: target.y))
            }
            return (id, CGPoint(x: origin.x + (target.x - origin.x) * eased,
                                y: origin.y + (target.y - origin.y) * eased))
        })
    }
}
