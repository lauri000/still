package com.stillreader.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Notes
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.stillreader.app.LibraryViewModel
import com.stillreader.app.core.ReadingSession
import com.stillreader.app.core.ReadingFocus
import kotlin.math.abs
import kotlin.math.sign
import kotlin.math.roundToInt

@Composable
fun ReaderScreen(session: ReadingSession, model: LibraryViewModel, onClose: () -> Unit, onReview: (Int) -> Unit) {
    val state by session.state.collectAsStateWithLifecycle()
    var passage by rememberSaveable(session.book.id) { mutableStateOf(false) }
    SideEffect { session.passageMode = passage }
    var options by remember { mutableStateOf(false) }
    var pagePicker by remember { mutableStateOf(false) }
    var wpm by remember(session) { mutableDoubleStateOf(session.wordsPerMinute) }
    var focusLetter by remember(model) { mutableStateOf(model.focusLetter) }
    var activeWordSizePercent by remember(model) { mutableIntStateOf(model.activeWordSizePercent) }
    var animatedScrolling by remember(model) { mutableStateOf(model.animatedScrolling) }
    val view = LocalView.current
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(session) { focus.clearFocus(force = true); keyboard?.hide() }
    DisposableEffect(state.playing, view) {
        view.keepScreenOn = state.playing
        onDispose { view.keepScreenOn = false }
    }
    DisposableEffect(session) { onDispose { session.pause() } }
    var speedChange by remember(session) { mutableIntStateOf(0) }
    var showSpeed by remember(session) { mutableStateOf(false) }
    val changeSpeed: (Double) -> Unit = { value ->
        val next = value.coerceIn(60.0, 900.0)
        if (next.toInt() != wpm.toInt()) speedChange++
        wpm = next
        model.speed(next)
    }
    LaunchedEffect(speedChange) {
        if (speedChange > 0) {
            showSpeed = true
            delay(650)
            showSpeed = false
        }
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
        Column(Modifier.testTag("readerHeader")) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Default.ArrowBack, "Back") }
                Column(Modifier.weight(1f).heightIn(min = 48.dp)
                    .clickable(onClickLabel = "Go to page") { session.pause(); pagePicker = true }
                    .padding(horizontal = 4.dp), verticalArrangement = Arrangement.Center) {
                    Text(session.book.title, fontSize = 14.sp, fontWeight = FontWeight.Medium,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${if (state.finished) "Finished · " else ""}Page ${session.current?.let { session.book.pages[it.pageIndex].label } ?: "—"} of ${session.book.pages.size}",
                        Modifier.testTag("readerPage"), color = Muted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                IconButton(onClick = { session.pause(); passage = !passage }, modifier = Modifier.testTag("toggleContext")) {
                    Icon(Icons.AutoMirrored.Default.Notes, if (passage) "Single-line view" else "Passage view")
                }
                IconButton(onClick = { session.pause(); options = true }, modifier = Modifier.testTag("readingOptions")) {
                    Icon(Icons.Default.MoreHoriz, "Reading options")
                }
            }
            LinearProgressIndicator(progress = { session.progress }, modifier = Modifier.fillMaxWidth().height(2.dp).testTag("readingProgress"),
                color = Forest, trackColor = Line, strokeCap = StrokeCap.Butt, gapSize = 0.dp, drawStopIndicator = {})
        }
        ReadingSurface(session, wpm, state.playing, !options && !pagePicker,
            passage, changeSpeed, Modifier.weight(1f).fillMaxWidth()) {
            if (passage) PassageView(session, state, focusLetter, activeWordSizePercent)
            else WordRibbon(session, state.index, focusLetter, false, animatedScrolling && state.playing && !state.scrubbing, activeWordSizePercent)
            androidx.compose.animation.AnimatedVisibility(showSpeed, modifier = Modifier.align(Alignment.TopEnd).padding(16.dp),
                enter = fadeIn(tween(80)), exit = fadeOut(tween(180))) {
                Text("${wpm.toInt()} wpm", modifier = Modifier.testTag("speedOverlay")
                    .background(Paper.copy(alpha = .94f), RoundedCornerShape(8.dp)).padding(horizontal = 10.dp, vertical = 6.dp),
                    color = Forest, fontSize = 14.sp)
            }
            state.blockedPage?.let { page ->
                Column(Modifier.align(Alignment.BottomCenter).padding(16.dp).widthIn(max = 500.dp)
                    .clip(RoundedCornerShape(12.dp)).background(Surface).padding(18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Page ${session.book.pages[page].label} needs a check.", textAlign = TextAlign.Center)
                    Text("Compare its text with the PDF. Blank or scanned pages can be entered manually or excluded.", color = Muted, fontSize = 12.sp, textAlign = TextAlign.Center)
                    TextButton(onClick = { onReview(page) }) { Text("Review this page") }
                }
            }
        }
    }
    if (options) {
        var natural by remember { mutableStateOf(session.naturalPauses) }
        var strict by remember { mutableStateOf(!session.allowUnreviewed) }
        AlertDialog(onDismissRequest = { options = false }, title = { Text("Reading options") }, text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(if (passage) "Passage text size" else "Active word size", Modifier.weight(1f), fontSize = 14.sp)
                    Text("$activeWordSizePercent%", Modifier.testTag("activeWordSizeValue"), color = Muted)
                }
                Slider(value = activeWordSizePercent.toFloat(), onValueChange = {
                    activeWordSizePercent = it.roundToInt().coerceIn(100, 300)
                    model.activeWordSizePercent = activeWordSizePercent
                }, valueRange = 100f..300f, steps = 199,
                    modifier = Modifier.testTag("activeWordSize").semantics {
                        contentDescription = if (passage) "Passage text size" else "Active word size"
                        stateDescription = "$activeWordSizePercent%"
                    })
                Text(if (passage) "Scales all passage text together to keep natural spacing." else "100% matches surrounding text. In passage view, this scales all text together.", fontSize = 12.sp, color = Muted)
                HorizontalDivider(color = Line)
                OptionToggle("Focus letter", focusLetter) { focusLetter = it; model.focusLetter = it }
                Text("Highlight one letter. In single-line view, keep that letter centered.", fontSize = 12.sp, color = Muted)
                HorizontalDivider(color = Line)
                OptionToggle("Animated scrolling", animatedScrolling) { animatedScrolling = it; model.animatedScrolling = it }
                Text("Animate single-line word transitions. Passage view scrolls continuously. Both respect the system animation setting.", fontSize = 12.sp, color = Muted)
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
private fun ReadingSurface(
    session: ReadingSession, wpm: Double, playing: Boolean, enabled: Boolean, passage: Boolean,
    change: (Double) -> Unit, modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit,
) {
    val changeSpeed by rememberUpdatedState(change)
    val density = LocalDensity.current.density
    val focused = LocalWindowInfo.current.isWindowFocused
    var size by remember { mutableStateOf(IntSize.Zero) }
    LaunchedEffect(focused, enabled) { if (!focused || !enabled) session.pause() }
    Box(modifier.onSizeChanged { size = it }.testTag("readingSurface")
        .pointerInput(session, density, focused, enabled, size, passage) {
            if (!focused || !enabled) return@pointerInput
            awaitEachGesture {
                val down = awaitFirstDown()
                // The initial position owns the entire gesture, even across the
                // boundary. Additional fingers cannot switch or take over its role.
                val reading = down.position.x >= size.width / 2f && down.position.y >= size.height / 2f
                down.consume()
                var distance = 0f
                var scrubbing = false
                try {
                    if (reading) session.play() else session.pause()
                    while (true) {
                        val event = awaitPointerEvent()
                        val pointer = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!pointer.pressed || pointer.isConsumed) break
                        val delta = pointer.position.x - pointer.previousPosition.x
                        if (reading) {
                            if (delta != 0f) changeSpeed(session.wordsPerMinute + delta / density * 2)
                        } else {
                            distance += delta
                            if (!scrubbing && abs(distance) > viewConfiguration.touchSlop) {
                                distance -= sign(distance) * viewConfiguration.touchSlop
                                session.beginScrubbing()
                                scrubbing = true
                            }
                            if (scrubbing) session.scrub((-distance / (32 * density)).toInt())
                        }
                        event.changes.forEach { it.consume() }
                    }
                } finally {
                    session.pause()
                }
            }
        }, contentAlignment = Alignment.Center) {
        Box(Modifier.align(Alignment.BottomEnd).fillMaxWidth(.5f).fillMaxHeight(.5f).testTag("holdToRead")
            .semantics {
                contentDescription = "Hold to read. Move right to read faster, left to read slower."
                stateDescription = "${wpm.toInt()} words per minute, ${if (playing) "reading" else "paused"}"
                if (enabled) {
                    // Screen readers can start and pause without sustaining a touch.
                    onClick(if (playing) "Pause reading" else "Start reading") { session.toggle(); true }
                    customActions = listOf(
                        CustomAccessibilityAction("Read faster") { changeSpeed(wpm + 10); true },
                        CustomAccessibilityAction("Read slower") { changeSpeed(wpm - 10); true })
                } else disabled()
            })
        content()
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
internal fun WordRibbon(session: ReadingSession, index: Int, focusLetter: Boolean, expanded: Boolean, scrolling: Boolean, activeWordSizePercent: Int) {
    BoxWithConstraints(Modifier.then(if (expanded) Modifier.widthIn(max = 850.dp) else Modifier).fillMaxWidth()
        .then(if (expanded) Modifier.fillMaxHeight() else Modifier.height(110.dp))
        .clipToBounds().testTag(if (expanded) "nearbyPassage" else "wordRibbon")) {
        val motion = remember(session, expanded, focusLetter, activeWordSizePercent) { WordMotion() }
        val progress = remember { Animatable(1f) }
        var animationIndex by remember { mutableIntStateOf(index) }
        var durationScale by remember { mutableStateOf<MotionDurationScale?>(null) }
        val focused = LocalWindowInfo.current.isWindowFocused
        val moving = scrolling && focused && (durationScale?.scaleFactor ?: 1f) > 0f
        LaunchedEffect(index, scrolling, focused, expanded, focusLetter, activeWordSizePercent, maxWidth, maxHeight) {
            durationScale = currentCoroutineContext()[MotionDurationScale]
            if (scrolling && focused) {
                progress.snapTo(0f)
                animationIndex = index
                progress.animateTo(1f, tween(WordMotion.durationMillis(session.wordsPerMinute),
                    easing = CubicBezierEasing(0.33f, 1f, 0.68f, 1f)))
            } else {
                progress.snapTo(1f)
                animationIndex = index
            }
        }
        val compact = expanded && maxHeight < 240.dp
        val passageSize = if (compact) 20f else 24f
        val neighborSize = if (expanded) passageSize else 22f
        val fontSize = neighborSize * activeWordSizePercent / 100f
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
                    style = if (selected) currentStyle else LocalTextStyle.current.copy(fontFamily = FontFamily.Serif, fontSize = neighborSize.sp),
                    color = if (selected) Ink else Muted,
                    modifier = Modifier.then(if (selected) Modifier.background(Paper) else Modifier)
                        .heightIn(min = neighborHeight).wrapContentHeight()
                        .testTag(if (selected) "currentWord" else if (expanded) "passageWord-${token.id}" else "nearbyWord-${token.id}")
                        .semantics {
                            if (selected) {
                                stateDescription = "Word ${index + 1} of ${session.tokens.size}"
                                customActions = listOf(
                                    CustomAccessibilityAction("Next word") { session.seek(index + 1); true },
                                    CustomAccessibilityAction("Previous word") { session.seek(index - 1); true })
                            }
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
                focus.place(centerLeft, centerY - focus.height / 2, zIndex = 1f)
                val rowHeight = maxOf(neighborHeight.roundToPx(), placeables.maxOfOrNull { it.height } ?: 0)
                val rowStep = rowHeight + (if (compact) 4.dp else 8.dp).roundToPx()
                fun rowY(row: Int) = centerY + row * rowStep
                fun breakAfter(i: Int) = tokens[i].word.endsParagraph || tokens[i].pageIndex != tokens[i + 1].pageIndex
                val positions = mutableMapOf<Int, Pair<Float, Int>>()
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
                    positions[i] = left.toFloat() to row
                    left -= gap
                }
                var right = centerLeft + focus.width + gap
                row = 0
                for (i in selected + 1 until placeables.size) {
                    val item = placeables[i]
                    if (expanded && (right + item.width > constraints.maxWidth - inset || breakAfter(i - 1))) {
                        row++; right = inset
                    }
                    positions[i] = right.toFloat() to row
                    right += item.width + gap
                }
                fun spread(indices: List<Int>, line: Int, left: Float, right: Float) {
                    if (indices.size < 2) return
                    val width = indices.sumOf { placeables[it].width }
                    val spacing = maxOf(gap.toFloat(), (right - left - width) / (indices.size - 1))
                    var x = left
                    indices.forEach { i ->
                        if (i != selected) positions[i] = x to line
                        x += placeables[i].width + spacing
                    }
                }
                if (expanded) {
                    positions.keys.groupBy { positions.getValue(it).second }.forEach { (line, entries) ->
                        val indices = entries.sorted()
                        if (line == 0) {
                            // Include the fixed word as an endpoint on each side.
                            val before = indices.filter { it < selected }
                            if (before.isNotEmpty() && before.first() > 0 && !breakAfter(before.first() - 1)) {
                                spread(before + selected, line, inset.toFloat(), (centerLeft + focus.width).toFloat())
                            }
                            val after = indices.filter { it > selected }
                            if (after.isNotEmpty() && after.last() < tokens.lastIndex && !breakAfter(after.last())) {
                                spread(listOf(selected) + after, line, centerLeft.toFloat(), (constraints.maxWidth - inset).toFloat())
                            }
                        } else if (indices.first() > 0 && indices.last() < tokens.lastIndex && !breakAfter(indices.last())) {
                            // Do not stretch paragraph endings or incomplete window edges.
                            spread(indices, line, inset.toFloat(), (constraints.maxWidth - inset).toFloat())
                        }
                    }
                }
                val targets = positions.mapKeys { tokens[it.key].id }.mapValues { (_, position) ->
                    Offset(position.first, rowY(position.second).toFloat())
                } + (index to Offset(centerLeft.toFloat(), centerY.toFloat()))
                val displayed = motion.positions(index, targets, IntSize(constraints.maxWidth, constraints.maxHeight),
                    moving, if (animationIndex == index) progress.value else 0f,
                    tokens.indices.associate { tokens[it].id to placeables[it].width })
                positions.keys.forEach { i ->
                    val item = placeables[i]
                    val point = displayed.getValue(tokens[i].id)
                    // Real offscreen coordinates let rows enter naturally through the clip.
                    item.place(point.x.roundToInt(), point.y.roundToInt() - item.height / 2)
                }
            }
        }
    }
}

private fun TextLayoutResult.focusCenter(range: IntRange): Float {
    val boxes = range.map { getBoundingBox(it) }
    return (boxes.minOf { it.left } + boxes.maxOf { it.right }) / 2f
}
