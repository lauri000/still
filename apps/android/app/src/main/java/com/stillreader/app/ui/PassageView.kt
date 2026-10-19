package com.stillreader.app.ui

import androidx.compose.animation.core.withInfiniteAnimationFrameNanos
import androidx.compose.foundation.layout.*
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.stillreader.app.core.PassageLayout
import com.stillreader.app.core.ReadingSession
import com.stillreader.app.core.ReadingState
import kotlinx.coroutines.currentCoroutineContext
import kotlin.math.ceil
import kotlin.math.roundToInt

@Composable
fun PassageView(session: ReadingSession, state: ReadingState) {
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    val window = remember(session, session.current?.pageID) { PassageLayout.window(session.tokens, state.index) }
    val nearby = remember(session, window) { session.tokens.subList(window.first, window.last + 1) }
    BoxWithConstraints(Modifier.widthIn(max = 850.dp).fillMaxSize().padding(horizontal = 24.dp)
        .clipToBounds().testTag("nearbyPassage")) {
        val fontSize = if (maxHeight < 240.dp) 20f else 24f
        val style = LocalTextStyle.current.copy(fontFamily = FontFamily.Serif, fontSize = fontSize.sp)
        val width = with(density) { maxWidth.toPx().toDouble() }
        val height = with(density) { maxHeight.toPx() }
        val rowHeight = with(density) { maxOf((if (maxHeight < 240.dp) 44.dp else 56.dp).toPx(), fontSize.sp.toPx() * 1.4f + 12.dp.toPx()) }
        val headingIDs = remember(session, nearby) { nearby.mapNotNull { token -> session.structure.headingAt(token.id)?.let { token.id to it.words.first } }.toMap() }
        val layout = remember(nearby, measurer, style, width) {
            val cache = mutableMapOf<String, Double>()
            fun measure(text: String, heading: Boolean = false) = cache.getOrPut("$heading:$text") {
                measurer.measure(AnnotatedString(text), if (heading) style.copy(fontSize = (fontSize * 1.2f).sp) else style, softWrap = false, maxLines = 1).size.width.toDouble()
            }
            PassageLayout(nearby, nearby.map { measure(it.word.text, it.id in headingIDs) }, width, measure(" "), headingIDs)
        }
        var frame by remember { mutableLongStateOf(0) }
        var durationScale by remember { mutableStateOf<MotionDurationScale?>(null) }
        LaunchedEffect(Unit) { durationScale = currentCoroutineContext()[MotionDurationScale] }
        val reduceMotion = (durationScale?.scaleFactor ?: 1f) == 0f
        LaunchedEffect(state.playing, reduceMotion) {
            if (state.playing && !reduceMotion) while (true) withInfiniteAnimationFrameNanos { frame = it }
        }
        val index = state.index - window.first
        val progress = remember(frame, state) { session.motionProgress }
        val currentRow = layout.rowContaining(index)
        val position = if (reduceMotion) layout.rows.getOrNull(currentRow)?.let { it.top + it.height / 2 } ?: 0.0 else layout.position(index, progress)
        val radius = ceil(height / rowHeight / 2).toInt() + 1
        val first = (currentRow - radius).coerceAtLeast(0)
        val end = (currentRow + radius + 1).coerceAtMost(layout.rows.size)
        if (nearby.isEmpty()) Text("Ready?", Modifier.align(Alignment.Center), style = style)
        for (rowIndex in first until end) {
            val row = layout.rows[rowIndex]
            key(window.first + row.words.first) {
                Text(row.text, color = Ink, maxLines = 1, softWrap = false,
                    style = style.copy(fontSize = (fontSize * (if (row.isHeading) 1.2 else 1.0) * (width / row.width).coerceAtMost(1.0)).sp),
                    modifier = Modifier.fillMaxWidth().offset {
                        IntOffset(0, (height / 2 + (row.top - position) * rowHeight).roundToInt())
                    }.height(with(density) { (rowHeight * row.height.toFloat()).toDp() }).wrapContentHeight()
                        .testTag(if (index in row.words) "currentPassageRow" else "passageRow-${window.first + row.words.first}")
                        .semantics {
                            if (row.isHeading) heading()
                            if (index in row.words) {
                                stateDescription = "Word ${state.index + 1} of ${session.tokens.size}"
                                customActions = listOf(
                                    CustomAccessibilityAction("Next word") { session.seek(state.index + 1); true },
                                    CustomAccessibilityAction("Previous word") { session.seek(state.index - 1); true })
                            }
                        })
            }
        }
    }
}
