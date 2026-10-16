package com.stillreader.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Notes
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.stillreader.app.LibraryViewModel
import com.stillreader.app.core.ReadingSession
import com.stillreader.app.core.ReadingFocus
import kotlin.math.roundToInt

@Composable
fun ReaderScreen(session: ReadingSession, model: LibraryViewModel, onClose: () -> Unit, onReview: (Int) -> Unit) {
    val state by session.state.collectAsStateWithLifecycle()
    var passage by rememberSaveable(session.book.id) { mutableStateOf(false) }
    var options by remember { mutableStateOf(false) }
    var pagePicker by remember { mutableStateOf(false) }
    var wpm by remember(session) { mutableDoubleStateOf(session.wordsPerMinute) }
    var focusLetter by remember(model) { mutableStateOf(model.focusLetter) }
    val view = LocalView.current
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(session) { focus.clearFocus(force = true); keyboard?.hide() }
    DisposableEffect(state.playing, view) {
        view.keepScreenOn = state.playing
        onDispose { view.keepScreenOn = false }
    }
    DisposableEffect(session) { onDispose { session.pause() } }
    val stepPixels = with(LocalDensity.current) { 32.dp.toPx() }
    val swipe = Modifier.pointerInput(session, stepPixels) {
        var distance = 0f
        detectHorizontalDragGestures(
            onDragStart = { distance = 0f; session.beginScrubbing() },
            onDragEnd = { session.endScrubbing() },
            onDragCancel = { session.pause() },
            onHorizontalDrag = { change, delta -> change.consume(); distance += delta; session.scrub((-distance / stepPixels).toInt()) },
        )
    }
    Column(Modifier.fillMaxSize().onPreviewKeyEvent {
        if (it.type != KeyEventType.KeyDown) false else when (it.key) {
            Key.Spacebar -> { session.toggle(); true }
            Key.DirectionLeft -> { session.seek(state.index - 1); true }
            Key.DirectionRight -> { session.seek(state.index + 1); true }
            Key.Escape -> { onClose(); true }
            else -> false
        }
    }) {
        Header(session.book.title, onClose) {
            IconButton(onClick = { session.pause(); passage = !passage }, modifier = Modifier.testTag("toggleContext")) {
                Icon(Icons.AutoMirrored.Default.Notes, if (passage) "Single-line view" else "Passage view")
            }
            IconButton(onClick = { session.pause(); options = true }, modifier = Modifier.testTag("readingOptions")) {
                Icon(Icons.Default.MoreHoriz, "Reading options")
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth().then(swipe).testTag("readingSurface"), contentAlignment = Alignment.Center) {
            WordRibbon(session, state.index, focusLetter, passage)
        }
        state.blockedPage?.let { page ->
            Column(Modifier.padding(horizontal = 24.dp).widthIn(max = 500.dp).clip(RoundedCornerShape(12.dp)).background(Surface).padding(18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Page ${session.book.pages[page].label} needs a check.", textAlign = TextAlign.Center)
                Text("Compare its text with the PDF. Blank or scanned pages can be entered manually or excluded.", color = Muted, fontSize = 12.sp, textAlign = TextAlign.Center)
                TextButton(onClick = { onReview(page) }) { Text("Review this page") }
            }
        }
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp)) {
            LinearProgressIndicator(progress = { session.progress }, modifier = Modifier.fillMaxWidth())
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { session.pause(); pagePicker = true }, modifier = Modifier.weight(1f)) {
                    Text("Page ${session.current?.let { session.book.pages[it.pageIndex].label } ?: "—"} of ${session.book.pages.size}", color = Muted, fontSize = 12.sp)
                }
                HoldReadingCorner(session, wpm, state.playing, !options && !pagePicker) {
                    wpm = it; model.speed(it)
                }
            }
            if (state.finished) Text("Finished", color = Muted, fontSize = 12.sp)
        }
    }
    if (options) {
        var natural by remember { mutableStateOf(session.naturalPauses) }
        var strict by remember { mutableStateOf(!session.allowUnreviewed) }
        AlertDialog(onDismissRequest = { options = false }, title = { Text("Reading options") }, text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OptionToggle("Focus letter", focusLetter) { focusLetter = it; model.focusLetter = it }
                Text("Highlight one letter and keep it centered as words advance.", fontSize = 12.sp, color = Muted)
                HorizontalDivider(color = Line)
                OptionToggle("Natural pauses", natural) { natural = it; model.options(natural, strict) }
                Text("Slow down at punctuation and paragraph breaks.", fontSize = 12.sp, color = Muted)
                HorizontalDivider(color = Line)
                OptionToggle("Pause at unchecked pages", strict) { strict = it; model.options(natural, strict) }
                Text("Require page approval before playback. Otherwise, unchecked text plays immediately and pages without text are skipped.", fontSize = 12.sp, color = Muted)
            }
        }, confirmButton = { TextButton(onClick = { options = false }) { Text("Done") } })
    }
    if (pagePicker) {
        var page by remember { mutableIntStateOf(session.current?.pageIndex ?: session.book.readingStartPageIndex) }
        AlertDialog(onDismissRequest = { pagePicker = false }, title = { Text("Go to page") },
            text = { PageSelector(page, session.book.pages.size) { page = it } },
            confirmButton = { TextButton(onClick = { session.seekPage(page); pagePicker = false }) { Text("Go") } },
            dismissButton = { TextButton(onClick = { pagePicker = false }) { Text("Cancel") } })
    }
}

@Composable
private fun HoldReadingCorner(session: ReadingSession, wpm: Double, playing: Boolean, enabled: Boolean, change: (Double) -> Unit) {
    val changeSpeed by rememberUpdatedState(change)
    val density = LocalDensity.current.density
    val focused = LocalWindowInfo.current.isWindowFocused
    var size by remember { mutableStateOf(IntSize.Zero) }
    Column(Modifier.width(176.dp).height(96.dp).onSizeChanged { size = it }
        .background(if (playing) Surface else Paper, RoundedCornerShape(18.dp))
        .testTag("holdToRead")
        .pointerInput(session, density, focused, enabled, size) {
            if (!focused || !enabled) return@pointerInput
            awaitEachGesture {
                val down = awaitFirstDown()
                down.consume()
                try {
                    session.play()
                    // Only the finger that started here owns this gesture. It can
                    // leave the corner without turning into a text scrub.
                    while (true) {
                        val pointer = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                        if (!pointer.pressed || pointer.isConsumed) break
                        val delta = (pointer.position.x - pointer.previousPosition.x) / density
                        if (delta != 0f) changeSpeed((session.wordsPerMinute + delta * 2).coerceIn(60.0, 900.0))
                        pointer.consume()
                    }
                } finally {
                    session.pause()
                }
            }
        }
        .semantics(mergeDescendants = true) {
            contentDescription = "Hold to read. Move right to read faster, left to read slower."
            stateDescription = "${wpm.toInt()} words per minute, ${if (playing) "reading" else "paused"}"
            // Screen-reader users can activate without sustaining a touch.
            onClick(if (playing) "Pause reading" else "Start reading") { session.toggle(); true }
            customActions = listOf(
                CustomAccessibilityAction("Read faster") { changeSpeed((wpm + 10).coerceAtMost(900.0)); true },
                CustomAccessibilityAction("Read slower") { changeSpeed((wpm - 10).coerceAtLeast(60.0)); true })
        }, verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text("${wpm.toInt()} wpm", color = Forest, fontSize = 22.sp)
        Text(if (playing) "Release to pause" else "Hold to read", color = Muted, fontSize = 12.sp)
        Text("← slower · faster →", color = Muted, fontSize = 10.sp)
    }
}

@Composable
fun OptionToggle(label: String, checked: Boolean, change: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), fontSize = 14.sp)
        Switch(checked, change, modifier = Modifier.semantics { contentDescription = label })
    }
}

@Composable
private fun WordRibbon(session: ReadingSession, index: Int, focusLetter: Boolean, expanded: Boolean) {
    BoxWithConstraints(Modifier.fillMaxWidth().then(if (expanded) Modifier.fillMaxHeight() else Modifier.height(110.dp))
        .clipToBounds().testTag(if (expanded) "nearbyPassage" else "wordRibbon")) {
        val compact = expanded && maxHeight < 240.dp
        val fontSize = if (compact) 48f else 68f
        val neighborHeight = if (compact) 36.dp else 48.dp
        val radius = if (expanded) 80 else 6
        val tokens = session.tokens.subList((index - radius).coerceAtLeast(0), (index + radius + 1).coerceAtMost(session.tokens.size))
        val current = session.current?.word?.text ?: "Ready?"
        val measurer = rememberTextMeasurer()
        val focusRange = remember(current, focusLetter) { if (focusLetter) ReadingFocus.range(current) else null }
        val styled = remember(current, focusRange) {
            buildAnnotatedString {
                append(current)
                focusRange?.let { addStyle(SpanStyle(color = FocusLetter), it.first, it.last + 1) }
            }
        }
        val baseStyle = LocalTextStyle.current.merge(TextStyle(fontFamily = FontFamily.Serif, fontSize = fontSize.sp))
        val measured = measurer.measure(styled, baseStyle, softWrap = false, maxLines = 1)
        val width = with(LocalDensity.current) { maxWidth.toPx() }
        val baseAnchor = focusRange?.let { measured.focusCenter(it) }
        val availableScale = baseAnchor?.let {
            width * .44f / maxOf(it, measured.size.width - it).coerceAtLeast(1f)
        } ?: 1f
        val scale = minOf(1f, width * .68f / measured.size.width.coerceAtLeast(1), availableScale)
        val currentStyle = baseStyle.copy(fontSize = (fontSize * scale).coerceAtLeast(1f).sp,
            letterSpacing = baseStyle.letterSpacing * scale)
        val fitted = measurer.measure(styled, currentStyle, softWrap = false, maxLines = 1)
        val anchor = focusRange?.let { fitted.focusCenter(it) }
        if (tokens.isEmpty()) {
            Text(current, style = currentStyle, modifier = Modifier.align(Alignment.Center).testTag("currentWord"))
        } else Layout(content = {
            tokens.forEach { token -> key(token.id) {
                val selected = token.id == index
                Text(if (selected) styled else AnnotatedString(token.word.text), maxLines = 1, softWrap = false,
                    style = if (selected) currentStyle else LocalTextStyle.current.copy(fontFamily = FontFamily.Serif, fontSize = if (compact) 20.sp else 22.sp),
                    color = if (selected) Ink else Muted,
                    modifier = Modifier.clickable { session.seek(token.id) }.heightIn(min = neighborHeight).wrapContentHeight()
                        .testTag(if (selected) "currentWord" else if (expanded) "passageWord-${token.id}" else "nearbyWord-${token.id}")
                        .semantics {
                            if (selected) {
                                stateDescription = "Word ${index + 1} of ${session.tokens.size}"
                                customActions = listOf(
                                    CustomAccessibilityAction("Next word") { session.seek(index + 1); true },
                                    CustomAccessibilityAction("Previous word") { session.seek(index - 1); true })
                            } else contentDescription = "Read from ${token.word.text}"
                        })
            } }
        }, modifier = Modifier.fillMaxSize()) { measurables, constraints ->
            val inset = if (expanded) 24.dp.roundToPx().coerceAtMost(constraints.maxWidth / 8) else 0
            val placeables = measurables.map { it.measure(Constraints(maxWidth = if (expanded) constraints.maxWidth - inset * 2 else Constraints.Infinity, maxHeight = constraints.maxHeight)) }
            val selected = tokens.indexOfFirst { it.id == index }
            val gap = (if (expanded) 10.dp else 18.dp).roundToPx()
            layout(constraints.maxWidth, constraints.maxHeight) {
                if (selected !in placeables.indices) return@layout
                val focus = placeables[selected]
                val centerLeft = (constraints.maxWidth / 2f - (anchor ?: (focus.width / 2f))).roundToInt()
                val centerY = constraints.maxHeight / 2
                focus.place(centerLeft, centerY - focus.height / 2)
                val rowHeight = maxOf(neighborHeight.roundToPx(), placeables.filterIndexed { i, _ -> i != selected }.maxOfOrNull { it.height } ?: 0)
                val firstRow = (focus.height + rowHeight) / 2 + (if (compact) 4.dp else 12.dp).roundToPx()
                val rowStep = rowHeight + (if (compact) 4.dp else 8.dp).roundToPx()
                fun rowY(row: Int) = if (row == 0) centerY else centerY + (if (row > 0) 1 else -1) * (firstRow + (kotlin.math.abs(row) - 1) * rowStep)
                fun breakAfter(i: Int) = tokens[i].word.endsParagraph || tokens[i].pageIndex != tokens[i + 1].pageIndex
                var left = centerLeft - gap
                var row = 0
                // Fill the middle row around the fixed word, then wrap overflow
                // into earlier rows above and later rows below in reading order.
                for (i in selected - 1 downTo 0) {
                    val item = placeables[i]
                    if (expanded && (left - item.width < inset || breakAfter(i))) {
                        row--; left = constraints.maxWidth - inset
                    }
                    left -= item.width
                    val y = rowY(row)
                    if (y - item.height / 2 >= 0) item.place(left, y - item.height / 2)
                    left -= gap
                }
                var right = centerLeft + focus.width + gap
                row = 0
                for (i in selected + 1 until placeables.size) {
                    val item = placeables[i]
                    if (expanded && (right + item.width > constraints.maxWidth - inset || breakAfter(i - 1))) {
                        row++; right = inset
                    }
                    val y = rowY(row)
                    if (y + item.height / 2 <= constraints.maxHeight) item.place(right, y - item.height / 2)
                    right += item.width + gap
                }
            }
        }
    }
}

private fun TextLayoutResult.focusCenter(range: IntRange): Float {
    val boxes = range.map { getBoundingBox(it) }
    return (boxes.minOf { it.left } + boxes.maxOf { it.right }) / 2f
}
