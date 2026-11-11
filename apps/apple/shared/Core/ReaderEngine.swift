import SwiftUI

@MainActor
final class ReaderEngine: ObservableObject {
    let book: Book
    let tokens: [ReadingToken]
    let structure: ReadingStructure
    let passage: PassageDocument
    private var keepsProgress: Bool { structure.heading(at: index) != nil || structure.chapterEnds.contains(index) }
    @Published private(set) var index: Int
    @Published private(set) var isPlaying = false
    @Published private(set) var isFinished = false
    var isScrubbing: Bool { scrubOrigin != nil }
    // Speed changes take effect on the next word, so dragging never restarts its timer.
    @Published var wordsPerMinute: Double = 250
    @Published var naturalPauses = true
    @Published var passageMode = false {
        didSet {
            guard passageMode != oldValue else { return }
            pause()
        }
    }
    @Published private(set) var wordStartedAt: TimeInterval?
    private var wordDuration = 0.0
    private var heldWordProgress = 0.0
    var wordProgress: Double {
        guard let wordStartedAt, wordDuration > 0 else { return heldWordProgress }
        return min(1, heldWordProgress + (ProcessInfo.processInfo.systemUptime - wordStartedAt) / wordDuration)
    }
    private var playback: Task<Void, Never>?
    @Published private var scrubOrigin: Int?
    var onCheckpoint: ((ReadingPosition) -> Void)?

    init(book: Book) {
        self.book = book
        let document = book.readerCache.get(book)
        tokens = document.tokens
        structure = document.structure
        passage = document.passage
        index = document.index(book.position)
        if let position = book.position, !document.hasPage(position.pageID) {
            let startPage = max(book.readingStartPageIndex, book.pages.firstIndex { $0.id == position.pageID } ?? book.readingStartPageIndex)
            index = tokens.first(where: { $0.pageIndex >= startPage })?.id ?? max(0, tokens.count - 1)
        }
        isFinished = book.position?.finished ?? false
    }
    var current: ReadingToken? { tokens.indices.contains(index) ? tokens[index] : nil }
    var progress: Double { tokens.isEmpty ? 0 : (isFinished ? 1 : Double(index) / Double(tokens.count)) }
    var remainingMinutes: Int { Int(ceil(Double(max(0, tokens.count - index)) / wordsPerMinute)) }

    func toggle() { isPlaying ? pause() : play() }
    func play() {
        guard !passageMode else { return }
        endScrubbing()
        guard !tokens.isEmpty else { return }
        if isFinished { heldWordProgress = 0; index = 0; isFinished = false }
        isPlaying = true
        schedulePlayback()
    }
    private func schedulePlayback() {
        let progress = keepsProgress ? wordProgress : 0
        playback?.cancel()
        heldWordProgress = progress
        wordStartedAt = nil
        playback = Task { [weak self] in
            while let self, self.isPlaying, let token = self.current {
                let base = Tokenizer.duration(for: token.word, wordsPerMinute: self.wordsPerMinute, naturalPauses: self.naturalPauses && self.structure.heading(at: token.id) == nil)
                let duration = self.structure.duration(index: token.id, base: base)
                self.wordDuration = duration
                self.wordStartedAt = ProcessInfo.processInfo.systemUptime
                do { try await Task.sleep(for: .seconds(duration * (1 - self.heldWordProgress))) } catch { return }
                guard !Task.isCancelled, self.isPlaying else { return }
                if self.index + 1 >= self.tokens.count {
                    self.isFinished = true
                    self.pause()
                    return
                }
                self.heldWordProgress = 0
                self.wordStartedAt = nil
                self.index += 1
                if self.index % 20 == 0 { self.checkpoint() }
            }
        }
    }
    func pause() {
        stopPlayback()
        scrubOrigin = nil
        checkpoint()
    }
    private func stopPlayback() {
        heldWordProgress = keepsProgress ? wordProgress : 0
        wordStartedAt = nil
        isPlaying = false
        playback?.cancel()
        playback = nil
    }
    // Scroll updates are cheap; save when settling or leaving the reader.
    func browse(_ newIndex: Int) { if passageMode && newIndex != index { move(to: newIndex) } }
    func seekPosition(_ position: ReadingPosition) { seek(book.readerCache.get(book).index(position)) }

    func seek(_ newIndex: Int) {
        stopPlayback()
        scrubOrigin = nil
        move(to: newIndex)
        checkpoint()
    }
    // A drag previews many words but writes the selected source position only on release.
    func beginScrubbing() {
        guard !tokens.isEmpty, !isScrubbing else { return }
        stopPlayback()
        scrubOrigin = index
    }
    func scrub(by wordOffset: Int) {
        guard let scrubOrigin else { return }
        move(to: scrubOrigin + wordOffset)
    }
    func endScrubbing() {
        guard isScrubbing else { return }
        scrubOrigin = nil
        checkpoint()
    }
    private func move(to newIndex: Int) {
        guard !tokens.isEmpty else { return }
        heldWordProgress = 0
        index = min(max(0, newIndex), tokens.count - 1)
        isFinished = false
    }
    func seekPage(_ pageIndex: Int) {
        pause()
        guard !tokens.isEmpty else { return }
        let pageIndex = max(book.readingStartPageIndex, min(pageIndex, book.pages.count - 1))
        seek(tokens.first(where: { $0.pageIndex >= pageIndex })?.id ?? tokens.count - 1)
    }
    func checkpoint() {
        guard var position = current?.position else { return }
        position.finished = isFinished
        onCheckpoint?(position)
    }
}
