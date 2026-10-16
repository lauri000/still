import SwiftUI

@MainActor
final class ReaderEngine: ObservableObject {
    let book: Book
    let tokens: [ReadingToken]
    @Published private(set) var index: Int
    @Published private(set) var isPlaying = false
    @Published private(set) var isFinished = false
    @Published private(set) var isScrubbing = false
    @Published var blockedPage: Int?
    @Published var wordsPerMinute: Double = 250 { didSet { if isPlaying { schedulePlayback() } } }
    @Published var naturalPauses = true
    @Published var allowUnreviewed = true
    private var playback: Task<Void, Never>?
    private var startPage: Int
    private var scrubOrigin: Int?
    var onCheckpoint: ((ReadingPosition) -> Void)?

    init(book: Book) {
        self.book = book
        tokens = Tokenizer.tokens(for: book)
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
        if isFinished { index = 0; startPage = book.readingStartPageIndex; isFinished = false }
        guard checkPages(from: min(startPage, current!.pageIndex), through: max(startPage, current!.pageIndex)) else { return }
        blockedPage = nil
        isPlaying = true
        schedulePlayback()
    }
    private func checkPages(from: Int, through: Int) -> Bool {
        if !allowUnreviewed, let page = Tokenizer.blockingPage(in: book, from: from, through: through) {
            blockedPage = page
            pause()
            return false
        }
        return true
    }
    private func schedulePlayback() {
        playback?.cancel()
        playback = Task { [weak self] in
            while let self, self.isPlaying, let token = self.current {
                let duration = Tokenizer.duration(for: token.word, wordsPerMinute: self.wordsPerMinute, naturalPauses: self.naturalPauses)
                do { try await Task.sleep(for: .seconds(duration)) } catch { return }
                guard !Task.isCancelled, self.isPlaying else { return }
                if self.index + 1 >= self.tokens.count {
                    guard self.checkPages(from: token.pageIndex, through: self.book.pages.count - 1) else { return }
                    self.isFinished = true
                    self.pause()
                    return
                }
                let next = self.tokens[self.index + 1]
                guard self.checkPages(from: token.pageIndex, through: next.pageIndex) else { return }
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
