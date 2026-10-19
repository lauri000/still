import SwiftUI

@MainActor
final class ReaderEngine: ObservableObject {
    let book: Book
    let tokens: [ReadingToken]
    let structure: ReadingStructure
    private var keepsProgress: Bool { passageMode || structure.heading(at: index) != nil || structure.chapterEnds.contains(index) }
    @Published private(set) var index: Int
    @Published private(set) var isPlaying = false
    @Published private(set) var isFinished = false
    @Published private(set) var isScrubbing = false
    @Published var blockedPage: Int?
    // Speed changes take effect on the next word, so dragging never restarts its timer.
    @Published var wordsPerMinute: Double = 250
    @Published var naturalPauses = true
    @Published var allowUnreviewed = true
    // The passage interpolates within the same word clock used by playback.
    var passageMode = false {
        didSet { if passageMode && !tokens.isEmpty { blockedPage = nil } }
    }
    @Published private(set) var wordStartedAt: TimeInterval?
    private var wordDuration = 0.0
    private var motionDuration = 0.0
    var motionProgress: Double { motionDuration > 0 ? min(1, wordProgress * wordDuration / motionDuration) : wordProgress }
    private var heldWordProgress = 0.0
    var wordProgress: Double {
        guard let wordStartedAt, wordDuration > 0 else { return heldWordProgress }
        return min(1, heldWordProgress + (ProcessInfo.processInfo.systemUptime - wordStartedAt) / wordDuration)
    }
    private var playback: Task<Void, Never>?
    private var startPage: Int
    private var scrubOrigin: Int?
    var onCheckpoint: ((ReadingPosition) -> Void)?

    init(book: Book) {
        self.book = book
        tokens = Tokenizer.tokens(for: book)
        structure = ReadingStructure(book: book, tokens: tokens)
        index = Tokenizer.index(for: book.position, in: tokens)
        startPage = max(book.readingStartPageIndex, book.position.flatMap { position in book.pages.firstIndex { $0.id == position.pageID } } ?? book.readingStartPageIndex)
        if let position = book.position, !tokens.contains(where: { $0.pageID == position.pageID }) {
            index = tokens.first(where: { $0.pageIndex >= startPage })?.id ?? max(0, tokens.count - 1)
        }
        isFinished = book.position?.finished ?? false
    }
    var current: ReadingToken? { tokens.indices.contains(index) ? tokens[index] : nil }
    var progress: Double { tokens.isEmpty ? 0 : (isFinished ? 1 : Double(index) / Double(tokens.count)) }
    var remainingMinutes: Int { Int(ceil(Double(max(0, tokens.count - index)) / wordsPerMinute)) }
    var context: ArraySlice<ReadingToken> { tokens[max(0, index - 12)..<min(tokens.count, index + 25)] }

    func toggle() { isPlaying ? pause() : play() }
    func play() {
        endScrubbing()
        guard !tokens.isEmpty else {
            blockedPage = book.pages.indices.first { book.includesPage(at: $0) }
            return
        }
        if isFinished { heldWordProgress = 0; index = 0; startPage = book.readingStartPageIndex; isFinished = false }
        guard checkPages(from: min(startPage, current!.pageIndex), through: max(startPage, current!.pageIndex)) else { return }
        blockedPage = nil
        isPlaying = true
        schedulePlayback()
    }
    private func checkPages(from: Int, through: Int) -> Bool {
        if !allowUnreviewed && !passageMode, let page = Tokenizer.blockingPage(in: book, from: from, through: through) {
            blockedPage = page
            pause()
            return false
        }
        return true
    }
    private func schedulePlayback() {
        let progress = keepsProgress ? wordProgress : 0
        playback?.cancel()
        heldWordProgress = progress
        wordStartedAt = nil
        playback = Task { [weak self] in
            while let self, self.isPlaying, let token = self.current {
                let base = Tokenizer.duration(for: token.word, wordsPerMinute: self.wordsPerMinute, naturalPauses: self.naturalPauses && !self.passageMode && self.structure.heading(at: token.id) == nil)
                let duration = self.structure.duration(index: token.id, base: base, passage: self.passageMode)
                self.motionDuration = self.structure.chapterEnds.contains(token.id) ? base : duration
                self.wordDuration = duration
                self.wordStartedAt = ProcessInfo.processInfo.systemUptime
                do { try await Task.sleep(for: .seconds(duration * (1 - self.heldWordProgress))) } catch { return }
                guard !Task.isCancelled, self.isPlaying else { return }
                if self.index + 1 >= self.tokens.count {
                    guard self.checkPages(from: token.pageIndex, through: self.book.pages.count - 1) else { return }
                    self.isFinished = true
                    self.pause()
                    return
                }
                let next = self.tokens[self.index + 1]
                guard self.checkPages(from: token.pageIndex, through: next.pageIndex) else { return }
                self.heldWordProgress = 0
                self.wordStartedAt = nil
                self.index += 1
                self.startPage = next.pageIndex
                if self.index % 20 == 0 { self.checkpoint() }
            }
        }
    }
    func pause() {
        stopPlayback()
        scrubOrigin = nil
        isScrubbing = false
        checkpoint()
    }
    private func stopPlayback() {
        heldWordProgress = keepsProgress ? wordProgress : 0
        wordStartedAt = nil
        isPlaying = false
        playback?.cancel()
        playback = nil
    }
    func seek(_ newIndex: Int) {
        stopPlayback()
        scrubOrigin = nil
        isScrubbing = false
        move(to: newIndex)
        checkpoint()
    }
    // A drag previews many words but writes the selected source position only on release.
    func beginScrubbing() {
        guard !tokens.isEmpty, !isScrubbing else { return }
        stopPlayback()
        scrubOrigin = index
        isScrubbing = true
    }
    func scrub(by wordOffset: Int) {
        guard let scrubOrigin else { return }
        move(to: scrubOrigin + wordOffset)
    }
    func endScrubbing() {
        guard isScrubbing else { return }
        scrubOrigin = nil
        isScrubbing = false
        checkpoint()
    }
    private func move(to newIndex: Int) {
        guard !tokens.isEmpty else { return }
        heldWordProgress = 0
        index = min(max(0, newIndex), tokens.count - 1)
        startPage = tokens[index].pageIndex
        isFinished = false
        blockedPage = nil
    }
    func seekPage(_ pageIndex: Int) {
        pause()
        let pageIndex = max(book.readingStartPageIndex, min(pageIndex, book.pages.count - 1))
        if let token = tokens.first(where: { $0.pageIndex >= pageIndex }) {
            seek(token.id)
            startPage = pageIndex
        } else { blockedPage = pageIndex }
    }
    func checkpoint() {
        if let blockedPage {
            onCheckpoint?(ReadingPosition(pageID: book.pages[blockedPage].id, utf16Offset: 0))
            return
        }
        guard var position = current?.position else { return }
        position.finished = isFinished
        onCheckpoint?(position)
    }
}
