package com.stillreader.app.ui

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.stillreader.app.core.*
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlin.math.roundToInt

/** Native scrolling; neither the word clock nor the hold-to-read surface runs here. */
@Composable
fun PassageView(session: ReadingSession, state: ReadingState, selectWord: (Int) -> Unit) {
    val document = session.passage
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    val onSelectWord by rememberUpdatedState(selectWord)
    val list = rememberLazyListState(initialFirstVisibleItemIndex = document.blockContaining(state.index))
    BoxWithConstraints(Modifier.fillMaxSize().testTag("nearbyPassage")) {
        val fontSize = if (maxHeight < 240.dp) 20f else 24f
        val style = LocalTextStyle.current.copy(fontFamily = FontFamily.Serif, fontSize = fontSize.sp)
        val width = with(density) { (maxWidth - 48.dp).toPx().toDouble().coerceAtLeast(1.0) }
        val rowHeight = with(density) { maxOf((if (maxHeight < 240.dp) 44.dp else 56.dp).toPx(), fontSize.sp.toPx() * 1.4f + 12.dp.toPx()) }
        val layouts = remember(width, style, density) { mutableStateMapOf<Int, PassageLayout>() }
        val widths = remember(style, density) { android.util.LruCache<String, Double>(2048) }
        var positioned by remember(width, style, density) { mutableStateOf(false) }
        var anchoring by remember { mutableStateOf(true) }
        var reportedIndex by remember { mutableIntStateOf(state.index) }

        LaunchedEffect(state.index, width, style, density) {
            if (document.blocks.isEmpty() || positioned && state.index == reportedIndex) return@LaunchedEffect
            anchoring = true
            val target = state.index
            val blockIndex = document.blockContaining(target)
            val block = document.blocks[blockIndex]
            list.scrollToItem(blockIndex)
            val layout = snapshotFlow { layouts[block.id] }.filterNotNull().first()
            val row = layout.rows[layout.rowContaining(target - block.id)]
            list.scrollToItem(blockIndex, (row.top * rowHeight).roundToInt())
            reportedIndex = target
            positioned = true
            anchoring = false
        }
        LaunchedEffect(list, layouts, rowHeight) {
            var wasScrolling = false
            snapshotFlow { Triple(list.firstVisibleItemIndex, list.firstVisibleItemScrollOffset, list.isScrollInProgress) }
                .collect { (blockIndex, offset, scrolling) ->
                    if (positioned && !anchoring && (scrolling || wasScrolling)) {
                        val block = document.blocks.getOrNull(blockIndex)
                        val row = block?.let { layouts[it.id]?.rowAtOffset(offset / rowHeight.toDouble()) }
                        if (block != null && row != null) {
                            val range = (block.id + row.words.first)..(block.id + row.words.last)
                            if (session.state.value.index !in range) {
                                reportedIndex = range.first
                                session.browse(reportedIndex)
                            }
                        }
                        if (!scrolling && wasScrolling) session.checkpoint()
                    }
                    wasScrolling = scrolling
                }
        }
        if (document.blocks.isEmpty()) Text("Ready?", Modifier.align(Alignment.Center), style = style)
        LazyColumn(state = list, modifier = Modifier.fillMaxSize().testTag("passageScroll"),
            contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 16.dp, bottom = maxHeight)) {
            itemsIndexed(document.blocks, key = { _, block -> block.id }) { _, block ->
                val nearby = remember(session, block) { session.tokens.subList(block.words.first, block.words.last + 1) }
                val layout = remember(nearby, style, width, density) {
                    val ids = nearby.mapNotNull { token -> session.structure.headingAt(token.id)?.let { token.id to it.words.first } }.toMap()
                    fun measure(text: String, heading: Boolean = false): Double {
                        val key = "$heading:$text"
                        return widths.get(key) ?: measurer.measure(AnnotatedString(text),
                            if (heading) style.copy(fontSize = (fontSize * 1.2f).sp) else style,
                            softWrap = false, maxLines = 1).size.width.toDouble().also { widths.put(key, it) }
                    }
                    PassageLayout(nearby, nearby.map { measure(it.word.text, it.id in ids) }, width, measure(" "), ids)
                }
                DisposableEffect(layout) {
                    layouts[block.id] = layout
                    onDispose { layouts.remove(block.id) }
                }
                Column {
                    Spacer(Modifier.height(with(density) { ((layout.rows.firstOrNull()?.top ?: 0.0) * rowHeight).toFloat().toDp() }))
                    layout.rows.forEach { row ->
                        PassageRow(row, nearby, style, fontSize, width, rowHeight,
                            state.index.takeIf { it - block.id in row.words }, session.tokens.size) { onSelectWord(it) }
                    }
                    Spacer(Modifier.height(with(density) { (rowHeight * if (layout.rows.lastOrNull()?.isHeading == true) .45f else .2f).toDp() }))
                }
            }
        }
    }
}

/** One text/tap target per row; words are character ranges, not separate link layouts. */
@Composable
private fun PassageRow(row: PassageLayout.Row, tokens: List<ReadingToken>, style: TextStyle,
                       fontSize: Float, width: Double, rowHeight: Float, activeIndex: Int?,
                       wordCount: Int, selectWord: (Int) -> Unit) {
    val density = LocalDensity.current
    val onSelectWord by rememberUpdatedState(selectWord)
    val text = remember(row, tokens) {
        buildAnnotatedString {
            row.words.forEach { local ->
                if (local != row.words.first) append(" ")
                val token = tokens[local]
                withAnnotation("word", token.id.toString()) { append(token.word.text) }
            }
        }
    }
    var layout by remember(text) { mutableStateOf<TextLayoutResult?>(null) }
    Text(text, color = Ink, maxLines = 1, softWrap = false,
        style = style.copy(fontSize = (fontSize * (if (row.isHeading) 1.2 else 1.0) * (width / row.width).coerceAtMost(1.0)).sp),
        onTextLayout = { layout = it },
        modifier = Modifier.fillMaxWidth().height(with(density) { (rowHeight * row.height).toFloat().toDp() }).wrapContentHeight()
            .testTag(if (activeIndex != null) "currentPassageRow" else "passageRow-${tokens[row.words.first].id}")
            .pointerInput(text) {
                detectTapGestures { position ->
                    val result = layout ?: return@detectTapGestures
                    val offset = result.getOffsetForPosition(position)
                    // Ignore empty space beside/between words instead of selecting a nearby one.
                    if (offset < text.length && result.getBoundingBox(offset).contains(position)) {
                        text.getStringAnnotations("word", offset, offset).firstOrNull()?.let { onSelectWord(it.item.toInt()) }
                    }
                }
            }
            .semantics {
                if (row.isHeading) heading()
                if (activeIndex != null) stateDescription = "Word ${activeIndex + 1} of $wordCount"
                onClick("Continue in single-line view") { onSelectWord(activeIndex ?: tokens[row.words.first].id); true }
                customActions = row.words.map { local ->
                    val token = tokens[local]
                    CustomAccessibilityAction("Read from ${token.word.text}") { onSelectWord(token.id); true }
                }
            })
}
