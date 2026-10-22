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
import kotlin.math.ceil
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
    var contents by remember { mutableStateOf(false) }
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
        if (it.type != KeyEventType.KeyDown || passage && it.key != Key.Escape) false else when (it.key) {
            Key.Spacebar -> { session.toggle(); true }
            Key.DirectionLeft -> { session.seek(state.index - 1); true }
            Key.DirectionRight -> { session.seek(state.index + 1); true }
            Key.Escape -> { onClose(); true }
            else -> false
        }
    }) {
        Column(Modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)).testTag("readerHeader")) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Default.ArrowBack, "Back") }
                Column(Modifier.weight(1f).heightIn(min = 48.dp)
                    .clickable(onClickLabel = "Contents and pages") { session.pause(); contents = true }
                    .testTag("readerContents")
                    .padding(horizontal = 4.dp), verticalArrangement = Arrangement.Center) {
                    Text(session.book.title, fontSize = 14.sp, fontWeight = FontWeight.Medium,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        session.structure.sectionAt(state.index)?.let { section ->
                            Text(section.title, Modifier.weight(1f).testTag("readerChapter"), color = Muted, fontSize = 11.sp,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Text("${if (state.finished) "Finished · " else ""}${session.book.unitName} ${session.current?.let { session.book.pages[it.pageIndex].label } ?: "—"} of ${session.book.pages.size}",
                            Modifier.testTag("readerPage"), color = Muted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                IconButton(onClick = { session.pause(); passage = !passage; session.passageMode = passage }, modifier = Modifier.testTag("toggleContext")) {
                    Icon(Icons.AutoMirrored.Default.Notes, if (passage) "Single-line view" else "Passage view")
                }
                if (!passage) IconButton(onClick = { session.pause(); options = true }, modifier = Modifier.testTag("readingOptions")) {
                    Icon(Icons.Default.MoreHoriz, "Reading options")
                }
            }
            LinearProgressIndicator(progress = { session.progress }, modifier = Modifier.fillMaxWidth().height(2.dp).testTag("readingProgress"),
                color = Forest, trackColor = Line, strokeCap = StrokeCap.Butt, gapSize = 0.dp, drawStopIndicator = {})
        }
        if (passage) {
            Box(Modifier.weight(1f).fillMaxWidth().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)).testTag("readingSurface")) {
                PassageView(session, state) { index ->
                    session.seek(index)
                    session.passageMode = false
                    passage = false
                }
            }
        } else ReadingSurface(session, wpm, state.playing, !options && !pagePicker && !contents,
            changeSpeed, Modifier.weight(1f).fillMaxWidth()) {
            if (session.structure.headingAt(state.index) != null) {
                HeadingCue(session, state.index)
            } else WordRibbon(session, state.index, focusLetter, animatedScrolling && state.playing && !state.scrubbing, activeWordSizePercent)
            androidx.compose.animation.AnimatedVisibility(showSpeed, modifier = Modifier.align(Alignment.TopEnd).windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)).padding(16.dp),
                enter = fadeIn(tween(80)), exit = fadeOut(tween(180))) {
                Text("${wpm.toInt()} wpm", modifier = Modifier.testTag("speedOverlay")
                    .background(Paper.copy(alpha = .94f), RoundedCornerShape(8.dp)).padding(horizontal = 10.dp, vertical = 6.dp),
                    color = Forest, fontSize = 14.sp)
            }
            state.blockedPage?.let { page ->
                Column(Modifier.align(Alignment.BottomCenter).windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)).padding(16.dp).widthIn(max = 500.dp)
                    .clip(RoundedCornerShape(12.dp)).background(Surface).padding(18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("${session.book.unitName} ${session.book.pages[page].label} needs a check.", textAlign = TextAlign.Center)
                    Text("Compare its text with the PDF. Blank or scanned pages can be entered manually or excluded.", color = Muted, fontSize = 12.sp, textAlign = TextAlign.Center)
                    TextButton(onClick = { onReview(page) }) { Text("Review this page") }
                }
            }
        }
    }
    if (options) {
        var natural by remember { mutableStateOf(session.naturalPauses) }
        var strict by remember { mutableStateOf(!session.allowUnreviewed) }
        AlertDialog(onDismissRequest = { options = false }, title = { Text("Single-line options") }, text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Active word size", Modifier.weight(1f), fontSize = 14.sp)
                    Text("$activeWordSizePercent%", Modifier.testTag("activeWordSizeValue"), color = Muted)
                }
                Slider(value = activeWordSizePercent.toFloat(), onValueChange = {
                    activeWordSizePercent = it.roundToInt().coerceIn(100, 300)
                    model.activeWordSizePercent = activeWordSizePercent
                }, valueRange = 100f..300f, steps = 199,
                    modifier = Modifier.testTag("activeWordSize").semantics {
                        contentDescription = "Active word size"
                        stateDescription = "$activeWordSizePercent%"
                    })
                Text("100% matches surrounding text.", fontSize = 12.sp, color = Muted)
                HorizontalDivider(color = Line)
                OptionToggle("Focus letter", focusLetter) { focusLetter = it; model.focusLetter = it }
                Text("Highlight one letter and keep it centered.", fontSize = 12.sp, color = Muted)
                HorizontalDivider(color = Line)
                OptionToggle("Animated scrolling", animatedScrolling) { animatedScrolling = it; model.animatedScrolling = it }
                Text("Animate word transitions. Respects the system animation setting.", fontSize = 12.sp, color = Muted)
                HorizontalDivider(color = Line)
                OptionToggle("Natural pauses", natural) { natural = it; model.options(natural, strict) }
                Text("Slow down at punctuation and paragraph breaks.", fontSize = 12.sp, color = Muted)
                HorizontalDivider(color = Line)
                OptionToggle("Pause at unchecked ${session.book.unitPlural}", strict) { strict = it; model.options(natural, strict) }
                Text("Require page approval before playback. Otherwise, unchecked text plays immediately and pages without text are skipped.", fontSize = 12.sp, color = Muted)
            }
        }, confirmButton = { TextButton(onClick = { options = false }) { Text("Done") } })
    }
    if (contents) ReaderContentsSheet(session, model, onClose = { contents = false }, onPage = { contents = false; pagePicker = true })
    if (pagePicker) {
        var page by remember { mutableIntStateOf(session.current?.pageIndex ?: session.book.readingStartPageIndex) }
        AlertDialog(onDismissRequest = { pagePicker = false }, title = { Text("Go to ${session.book.unitName.lowercase()}") },
            text = { PageSelector(page, session.book.pages.size, session.book.unitName) { page = it } },
            confirmButton = { TextButton(onClick = { session.seekPage(page); pagePicker = false }) { Text("Go") } },
            dismissButton = { TextButton(onClick = { pagePicker = false }) { Text("Cancel") } })
    }
}

@Composable
private fun HeadingCue(session: ReadingSession, index: Int) {
    val title = session.structure.headingAt(index)?.title ?: return
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    BoxWithConstraints(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)).padding(24.dp)) {
        val style = LocalTextStyle.current.copy(fontFamily = FontFamily.Serif, fontSize = 28.sp)
        val size = remember(title, maxWidth, maxHeight, density, style) {
            with(density) {
                (28 downTo 14 step 2).firstOrNull { points ->
                    measurer.measure(title, style.copy(fontSize = points.sp), constraints = Constraints(maxWidth = maxWidth.roundToPx()))
                        .size.height <= maxHeight.roundToPx()
                } ?: 14
            }
        }
        Text(title, color = Ink, style = style.copy(fontSize = size.sp), textAlign = TextAlign.Center,
            modifier = Modifier.align(Alignment.Center).testTag("currentHeading").semantics {
                heading()
                stateDescription = "Word ${index + 1} of ${session.tokens.size}"
                customActions = listOf(
                    CustomAccessibilityAction("Next word") { session.seek(index + 1); true },
                    CustomAccessibilityAction("Previous word") { session.seek(index - 1); true })
            })
    }
}

@Composable
private fun ReadingSurface(
    session: ReadingSession, wpm: Double, playing: Boolean, enabled: Boolean,
    change: (Double) -> Unit, modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit,
) {
    val changeSpeed by rememberUpdatedState(change)
    val density = LocalDensity.current.density
    val focused = LocalWindowInfo.current.isWindowFocused
    var size by remember { mutableStateOf(IntSize.Zero) }
    LaunchedEffect(focused, enabled) { if (!focused || !enabled) session.pause() }
    Box(modifier.onSizeChanged { size = it }.testTag("readingSurface")
        .pointerInput(session, density, focused, enabled, size) {
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
internal fun WordRibbon(session: ReadingSession, index: Int, focusLetter: Boolean, scrolling: Boolean, activeWordSizePercent: Int) {
    BoxWithConstraints(Modifier.fillMaxWidth().height(110.dp).clipToBounds().testTag("wordRibbon")) {
        val motion = remember(session, focusLetter, activeWordSizePercent) { WordMotion() }
        val progress = remember { Animatable(1f) }
        var animationIndex by remember { mutableIntStateOf(index) }
        var durationScale by remember { mutableStateOf<MotionDurationScale?>(null) }
        val focused = LocalWindowInfo.current.isWindowFocused
        val moving = scrolling && focused && (durationScale?.scaleFactor ?: 1f) > 0f
        LaunchedEffect(index, scrolling, focused, focusLetter, activeWordSizePercent, maxWidth, maxHeight) {
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
        val neighborSize = 22f
        val fontSize = neighborSize * activeWordSizePercent / 100f
        val neighborHeight = 48.dp
        val gap = with(LocalDensity.current) { 18.dp.roundToPx() }
        // Spacing bounds the number needed even for the shortest words. Size the
        // context from the viewport while retaining the existing heading boundaries.
        val radius = ceil(with(LocalDensity.current) { maxWidth.toPx() } / (2 * gap)).toInt() + 1
        var start = index.coerceAtMost(session.tokens.size)
        var end = (index + 1).coerceAtMost(session.tokens.size)
        while (start > maxOf(0, index - radius) && session.structure.headingAt(start - 1) == null) start--
        while (end < minOf(session.tokens.size, index + radius + 1) && session.structure.headingAt(end) == null) end++
        val tokens = session.tokens.subList(start, end)
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
                        .testTag(if (selected) "currentWord" else "nearbyWord-${token.id}")
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
            val placeables = measurables.map { it.measure(Constraints(maxHeight = constraints.maxHeight)) }
            val selected = tokens.indexOfFirst { it.id == index }
            layout(constraints.maxWidth, constraints.maxHeight) {
                if (selected !in placeables.indices) return@layout
                val focus = placeables[selected]
                val centerLeft = (constraints.maxWidth / 2f - (anchor ?: (focus.width / 2f))).roundToInt()
                val centerY = constraints.maxHeight / 2
                focus.place(centerLeft, centerY - focus.height / 2, zIndex = 1f)
                val targets = mutableMapOf(index to centerLeft.toFloat())
                var left = centerLeft - gap
                for (i in selected - 1 downTo 0) {
                    left -= placeables[i].width
                    targets[tokens[i].id] = left.toFloat()
                    left -= gap
                }
                var right = centerLeft + focus.width + gap
                for (i in selected + 1 until placeables.size) {
                    targets[tokens[i].id] = right.toFloat()
                    right += placeables[i].width + gap
                }
                val displayed = motion.positions(index, targets, IntSize(constraints.maxWidth, constraints.maxHeight),
                    moving, if (animationIndex == index) progress.value else 0f)
                placeables.forEachIndexed { i, item ->
                    if (i != selected) item.place(displayed.getValue(tokens[i].id).roundToInt(), centerY - item.height / 2)
                }
            }
        }
    }
}

private fun TextLayoutResult.focusCenter(range: IntRange): Float {
    val boxes = range.map { getBoundingBox(it) }
    return (boxes.minOf { it.left } + boxes.maxOf { it.right }) / 2f
}
