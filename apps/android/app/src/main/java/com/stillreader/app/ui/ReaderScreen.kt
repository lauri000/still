package com.stillreader.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Notes
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.stillreader.app.LibraryViewModel
import com.stillreader.app.core.ReadingSession
import com.stillreader.app.core.ReadingState
import com.stillreader.app.core.ReadingFocus
import kotlin.math.roundToInt

@OptIn(ExperimentalLayoutApi::class)
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
                Icon(Icons.AutoMirrored.Default.Notes, if (passage) "Hide passage" else "Nearby passage")
            }
            IconButton(onClick = { session.pause(); options = true }, modifier = Modifier.testTag("readingOptions")) {
                Icon(Icons.Default.MoreHoriz, "Reading options")
            }
        }
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val availableHeight = maxHeight
            val compact = availableHeight < 360.dp
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = if (compact) 12.dp else 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Spacer(Modifier.height(((availableHeight - 620.dp) / 2).coerceIn(0.dp, 160.dp)))
                Box(Modifier.fillMaxWidth().height(if (compact) 100.dp else (availableHeight * .46f).coerceIn(200.dp, 360.dp)).then(swipe).testTag("readingSurface"), contentAlignment = Alignment.Center) {
                    WordRibbon(session, state.index, focusLetter)
                }
                state.blockedPage?.let { page ->
                    Column(Modifier.padding(horizontal = 24.dp).widthIn(max = 500.dp).clip(RoundedCornerShape(12.dp)).background(Surface).padding(18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Page ${session.book.pages[page].label} needs a check.", textAlign = TextAlign.Center)
                        Text("Compare its text with the PDF. Blank or scanned pages can be entered manually or excluded.", color = Muted, fontSize = 12.sp, textAlign = TextAlign.Center)
                        TextButton(onClick = { onReview(page) }) { Text("Review this page") }
                    }
                    Spacer(Modifier.height(20.dp))
                }
                val changeSpeed: (Double) -> Unit = { wpm = it; model.speed(it) }
                if (compact) {
                    Row(Modifier.widthIn(max = 720.dp).fillMaxWidth().padding(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.CenterVertically) {
                        PlaybackControls(session, state)
                        ReadingSpeed(wpm, changeSpeed, Modifier.weight(1f))
                    }
                } else {
                    PlaybackControls(session, state)
                    Spacer(Modifier.height(26.dp))
                    ReadingSpeed(wpm, changeSpeed, Modifier.widthIn(max = 330.dp).fillMaxWidth())
                }
                if (passage) {
                    FlowRow(Modifier.padding(24.dp).widthIn(max = 650.dp).clip(RoundedCornerShape(14.dp)).background(Surface).padding(18.dp).then(swipe).testTag("nearbyPassage"),
                        horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        session.tokens.subList((state.index - 12).coerceAtLeast(0), (state.index + 25).coerceAtMost(session.tokens.size)).forEach { token ->
                            Text(token.word.text, fontSize = 18.sp, fontFamily = FontFamily.Serif,
                                color = if (token.id == state.index) Surface else Muted,
                                modifier = Modifier.clip(RoundedCornerShape(4.dp)).background(if (token.id == state.index) Forest else Surface)
                                    .clickable { session.seek(token.id) }.padding(horizontal = 3.dp, vertical = 8.dp)
                                    .testTag("passageWord-${token.id}").semantics { selected = token.id == state.index })
                        }
                    }
                }
            }
        }
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp)) {
            LinearProgressIndicator(progress = { session.progress }, modifier = Modifier.fillMaxWidth())
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { session.pause(); pagePicker = true }) {
                    Text("Page ${session.current?.let { session.book.pages[it.pageIndex].label } ?: "—"} of ${session.book.pages.size}", color = Muted, fontSize = 12.sp)
                }
                Spacer(Modifier.weight(1f))
                Text(if (state.finished) "Finished" else "${(session.progress * 100).toInt()}%", color = Muted, fontSize = 12.sp)
            }
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
private fun PlaybackControls(session: ReadingSession, state: ReadingState) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(24.dp)) {
        IconButton(onClick = { session.seek(state.index - 1) }, enabled = session.tokens.isNotEmpty()) { Icon(Icons.Default.SkipPrevious, "Previous word", tint = Forest) }
        FilledIconButton(onClick = session::toggle, enabled = session.tokens.isNotEmpty(), modifier = Modifier.size(76.dp).testTag("playPause"), shape = CircleShape) {
            Icon(if (state.playing) Icons.Default.Pause else if (state.finished) Icons.Default.Replay else Icons.Default.PlayArrow,
                if (state.playing) "Pause reading" else "Play reading", Modifier.size(30.dp))
        }
        IconButton(onClick = { session.seek(state.index + 1) }, enabled = session.tokens.isNotEmpty()) { Icon(Icons.Default.SkipNext, "Next word", tint = Forest) }
    }
}

@Composable
private fun ReadingSpeed(wpm: Double, change: (Double) -> Unit, modifier: Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(wpm.toInt().toString(), fontSize = 24.sp)
            Text("words / min", fontSize = 12.sp, color = Muted)
        }
        Slider(value = wpm.toFloat(), onValueChange = { change((it / 10).roundToInt() * 10.0) },
            valueRange = 60f..900f, colors = SliderDefaults.colors(inactiveTrackColor = Line),
            modifier = Modifier.widthIn(max = 330.dp).fillMaxWidth().padding(horizontal = 18.dp).semantics { contentDescription = "Words per minute" })
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
private fun WordRibbon(session: ReadingSession, index: Int, focusLetter: Boolean) {
    BoxWithConstraints(Modifier.fillMaxWidth().height(110.dp).clipToBounds()) {
        val tokens = session.tokens.subList((index - 6).coerceAtLeast(0), (index + 7).coerceAtMost(session.tokens.size))
        val current = session.current?.word?.text ?: "Ready?"
        val measurer = rememberTextMeasurer()
        val focusRange = remember(current, focusLetter) { if (focusLetter) ReadingFocus.range(current) else null }
        val styled = remember(current, focusRange) {
            buildAnnotatedString {
                append(current)
                focusRange?.let { addStyle(SpanStyle(color = FocusLetter), it.first, it.last + 1) }
            }
        }
        val baseStyle = LocalTextStyle.current.merge(TextStyle(fontFamily = FontFamily.Serif, fontSize = 68.sp))
        val measured = measurer.measure(styled, baseStyle, softWrap = false, maxLines = 1)
        val width = with(LocalDensity.current) { maxWidth.toPx() }
        val baseAnchor = focusRange?.let { measured.focusCenter(it) }
        val availableScale = baseAnchor?.let {
            width * .44f / maxOf(it, measured.size.width - it).coerceAtLeast(1f)
        } ?: 1f
        val scale = minOf(1f, width * .68f / measured.size.width.coerceAtLeast(1), availableScale)
        val currentStyle = baseStyle.copy(fontSize = (68f * scale).coerceAtLeast(1f).sp,
            letterSpacing = baseStyle.letterSpacing * scale)
        val fitted = measurer.measure(styled, currentStyle, softWrap = false, maxLines = 1)
        val anchor = focusRange?.let { fitted.focusCenter(it) }
        if (tokens.isEmpty()) {
            Text(current, style = currentStyle, modifier = Modifier.align(Alignment.Center).testTag("currentWord"))
        } else Layout(content = {
            tokens.forEach { token ->
                val selected = token.id == index
                Text(if (selected) styled else AnnotatedString(token.word.text), maxLines = 1, softWrap = false,
                    style = if (selected) currentStyle else LocalTextStyle.current.copy(fontFamily = FontFamily.Serif, fontSize = 22.sp),
                    color = if (selected) Ink else Muted,
                    modifier = Modifier.clickable { session.seek(token.id) }.heightIn(min = 48.dp).wrapContentHeight()
                        .testTag(if (selected) "currentWord" else "nearbyWord-${token.id}")
                        .semantics {
                            if (selected) {
                                stateDescription = "Word ${index + 1} of ${session.tokens.size}"
                                customActions = listOf(
                                    CustomAccessibilityAction("Next word") { session.seek(index + 1); true },
                                    CustomAccessibilityAction("Previous word") { session.seek(index - 1); true })
                            } else contentDescription = "Read from ${token.word.text}"
                        })
            }
        }, modifier = Modifier.fillMaxSize()) { measurables, constraints ->
            val placeables = measurables.map { it.measure(Constraints(maxHeight = constraints.maxHeight)) }
            val selected = tokens.indexOfFirst { it.id == index }
            val gap = 18.dp.roundToPx()
            layout(constraints.maxWidth, constraints.maxHeight) {
                val focus = placeables[selected]
                val centerLeft = (constraints.maxWidth / 2f - (anchor ?: (focus.width / 2f))).roundToInt()
                focus.place(centerLeft, (constraints.maxHeight - focus.height) / 2)
                var left = centerLeft - gap
                for (i in selected - 1 downTo 0) {
                    val item = placeables[i]; left -= item.width
                    item.place(left, (constraints.maxHeight - item.height) / 2); left -= gap
                }
                var right = centerLeft + focus.width + gap
                for (i in selected + 1 until placeables.size) {
                    val item = placeables[i]
                    item.place(right, (constraints.maxHeight - item.height) / 2); right += item.width + gap
                }
            }
        }
    }
}

private fun TextLayoutResult.focusCenter(range: IntRange): Float {
    val boxes = range.map { getBoundingBox(it) }
    return (boxes.minOf { it.left } + boxes.maxOf { it.right }) / 2f
}
