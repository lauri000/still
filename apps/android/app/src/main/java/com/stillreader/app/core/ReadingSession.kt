package com.stillreader.app.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.roundToLong

data class ReadingState(
    val index: Int = 0,
    val playing: Boolean = false,
    val finished: Boolean = false,
    val scrubbing: Boolean = false,
    val blockedPage: Int? = null,
)

/** One owner, on the UI thread. The coroutine scope supplies native scheduling; tests supply virtual time. */
class ReadingSession(val book: Book, private val scope: CoroutineScope, private val save: (ReadingPosition) -> Unit) {
    private val document = book.readerCache.get(book)
    val tokens = document.tokens
    val structure = document.structure
    val passage get() = document.passage
    private val keepsProgress get() = structure.headingAt(state.value.index) != null || state.value.index in structure.chapterEnds
    private var startPage = maxOf(book.readingStartPageIndex, book.pages.indexOfFirst { it.id == book.position?.pageID }.coerceAtLeast(0))
    private val mutable = MutableStateFlow(ReadingState(
        index = if (book.position != null && !document.hasPage(book.position.pageID)) {
            tokens.firstOrNull { it.pageIndex >= startPage }?.id ?: (tokens.size - 1).coerceAtLeast(0)
        } else document.index(book.position),
        finished = book.position?.finished ?: false,
    ))
    val state = mutable.asStateFlow()
    val current get() = tokens.getOrNull(state.value.index)
    val progress get() = if (tokens.isEmpty()) 0f else if (state.value.finished) 1f else state.value.index.toFloat() / tokens.size
    var wordsPerMinute: Double = 250.0
        // Speed changes take effect on the next word, so dragging never restarts its timer.
        set(value) { field = value.coerceIn(60.0, 900.0) }
    var naturalPauses = true
    var allowUnreviewed = true
    var passageMode = false
        set(value) {
            if (field == value) return
            pause()
            field = value
            if (value) mutable.value = state.value.copy(blockedPage = null)
        }
    // Injectable monotonic clock keeps animation and the word timer in agreement in tests.
    var clockNanos: () -> Long = System::nanoTime
    private var wordStartedAt: Long? = null
    private var wordDuration = 0.0
    private var heldWordProgress = 0.0
    val wordProgress: Double get() = wordStartedAt?.let {
        (heldWordProgress + (clockNanos() - it) / 1_000_000_000.0 / wordDuration).coerceIn(0.0, 1.0)
    } ?: heldWordProgress
    private var playback: Job? = null
    private var scrubOrigin: Int? = null

    fun toggle() { if (state.value.playing) pause() else play() }
    fun play() {
        if (passageMode) return
        endScrubbing()
        if (tokens.isEmpty()) {
            mutable.value = state.value.copy(blockedPage = book.pages.indices.firstOrNull { book.includesPage(it) })
            return
        }
        if (state.value.finished) {
            heldWordProgress = 0.0
            mutable.value = state.value.copy(index = 0, finished = false)
            startPage = book.readingStartPageIndex
        }
        if (!checkPages(minOf(startPage, current!!.pageIndex), maxOf(startPage, current!!.pageIndex))) return
        mutable.value = state.value.copy(playing = true, blockedPage = null)
        schedule()
    }

    private fun schedule() {
        val progress = if (keepsProgress) wordProgress else 0.0
        playback?.cancel()
        heldWordProgress = progress; wordStartedAt = null
        playback = scope.launch {
            while (isActive && state.value.playing) {
                val token = current ?: break
                val base = Tokenizer.duration(token.word, wordsPerMinute, naturalPauses && structure.headingAt(token.id) == null)
                wordDuration = structure.duration(token.id, base)
                wordStartedAt = clockNanos()
                delay((wordDuration * (1 - heldWordProgress) * 1000).roundToLong())
                if (!isActive || !state.value.playing) break
                if (state.value.index + 1 >= tokens.size) {
                    if (!checkPages(token.pageIndex, book.pages.lastIndex)) break
                    mutable.value = state.value.copy(finished = true)
                    pause()
                    break
                }
                val next = tokens[state.value.index + 1]
                if (!checkPages(token.pageIndex, next.pageIndex)) break
                heldWordProgress = 0.0; wordStartedAt = null
                startPage = next.pageIndex
                mutable.value = state.value.copy(index = next.id)
                if (next.id % 20 == 0) checkpoint()
            }
        }
    }

    private fun checkPages(from: Int, through: Int): Boolean {
        val blocked = if (allowUnreviewed) null else Tokenizer.blockingPage(book, from, through)
        if (blocked != null) { mutable.value = state.value.copy(blockedPage = blocked); pause(); return false }
        return true
    }

    private fun stop() {
        heldWordProgress = if (keepsProgress) wordProgress else 0.0
        wordStartedAt = null
        playback?.cancel(); playback = null; mutable.value = state.value.copy(playing = false)
    }
    fun pause() {
        stop(); scrubOrigin = null
        mutable.value = state.value.copy(scrubbing = false)
        checkpoint()
    }
    // Scrolling updates the shared cursor; persist after settling or leaving the reader.
    fun browse(index: Int) { if (passageMode && index != state.value.index) move(index) }
    fun seekPosition(position: ReadingPosition) = seek(document.index(position))

    fun seek(index: Int) {
        stop(); scrubOrigin = null
        mutable.value = state.value.copy(scrubbing = false)
        move(index); checkpoint()
    }
    private fun move(index: Int) {
        if (tokens.isEmpty()) return
        heldWordProgress = 0.0
        val clamped = index.coerceIn(0, tokens.lastIndex)
        startPage = tokens[clamped].pageIndex
        mutable.value = state.value.copy(index = clamped, finished = false, blockedPage = null)
    }
    fun seekPage(index: Int) {
        pause()
        if (book.pages.isEmpty()) return
        val page = index.coerceIn(book.readingStartPageIndex, book.pages.lastIndex)
        val token = tokens.firstOrNull { it.pageIndex >= page }
        if (token != null) { seek(token.id); startPage = page }
        else mutable.value = state.value.copy(blockedPage = page)
    }
    fun beginScrubbing() {
        if (tokens.isEmpty() || state.value.scrubbing) return
        stop(); scrubOrigin = state.value.index
        mutable.value = state.value.copy(scrubbing = true)
    }
    fun scrub(wordOffset: Int) { scrubOrigin?.let { move(it + wordOffset) } }
    fun endScrubbing() {
        if (!state.value.scrubbing) return
        scrubOrigin = null; mutable.value = state.value.copy(scrubbing = false); checkpoint()
    }
    fun checkpoint() {
        state.value.blockedPage?.let { save(ReadingPosition(book.pages[it].id, 0)); return }
        current?.let { save(it.position.copy(finished = state.value.finished)) }
    }
}
