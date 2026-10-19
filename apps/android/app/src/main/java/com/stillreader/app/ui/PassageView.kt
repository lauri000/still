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
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.stillreader.app.core.PassageLayout
import com.stillreader.app.core.ReadingFocus
import com.stillreader.app.core.ReadingSession
import com.stillreader.app.core.ReadingState
import kotlinx.coroutines.currentCoroutineContext
import kotlin.math.ceil
import kotlin.math.roundToInt

@Composable
fun PassageView(session: ReadingSession, state: ReadingState, focusLetter: Boolean, activeWordSizePercent: Int) {
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    BoxWithConstraints(Modifier.widthIn(max = 850.dp).fillMaxSize().padding(horizontal = 24.dp)
        .clipToBounds().testTag("nearbyPassage")) {
        val fontSize = if (maxHeight < 240.dp) 20f else 24f
        val selectedSize = fontSize * activeWordSizePercent / 100f
        val style = LocalTextStyle.current.copy(fontFamily = FontFamily.Serif, fontSize = selectedSize.sp)
        val width = with(density) { maxWidth.toPx().toDouble() }
        val height = with(density) { maxHeight.toPx() }
        // Match the surrounding font's normal space instead of adding a fixed layout gap.
        val gap = remember(measurer, density, style) {
            measurer.measure(AnnotatedString(" "), style,
                softWrap = false, maxLines = 1).size.width.toDouble()
        }
        val rowHeight = with(density) { maxOf((if (maxHeight < 240.dp) 44.dp else 56.dp).toPx(), selectedSize.sp.toPx() * 1.4f + 12.dp.toPx()) }
        val measured = remember(session, measurer, density, style) {
            val cache = mutableMapOf<String, Double>()
            session.tokens.map { token -> cache.getOrPut(token.word.text) {
                measurer.measure(AnnotatedString(token.word.text), style, softWrap = false, maxLines = 1).size.width.toDouble()
            } }
        }
        val layout = remember(session, measured, width, gap, session.naturalPauses) {
            PassageLayout(session.tokens, measured, width, gap, session.naturalPauses)
        }
        var frame by remember { mutableLongStateOf(0) }
        var durationScale by remember { mutableStateOf<MotionDurationScale?>(null) }
        LaunchedEffect(Unit) { durationScale = currentCoroutineContext()[MotionDurationScale] }
        val reduceMotion = (durationScale?.scaleFactor ?: 1f) == 0f
        LaunchedEffect(state.playing, reduceMotion) {
            if (state.playing && !reduceMotion) while (true) withInfiniteAnimationFrameNanos { frame = it }
        }
        // Animation and token advancement share one monotonic clock, including natural pauses.
        val progress = remember(frame, state) { session.wordProgress }
        val position = if (reduceMotion) layout.rowContaining(state.index) + .5 else layout.position(state.index, progress)
        val radius = ceil(height / rowHeight / 2).toInt() + 1
        val first = (position.toInt() - radius).coerceAtLeast(0)
        val end = (position.toInt() + radius + 1).coerceAtMost(layout.rows.size)
        if (session.tokens.isEmpty()) Text("Ready?", Modifier.align(Alignment.Center).testTag("currentWord"), style = style)
        for (rowIndex in first until end) key(rowIndex) {
            Row(Modifier.fillMaxWidth().offset {
                IntOffset(0, (height / 2 + (rowIndex - position) * rowHeight).roundToInt())
            }.height(with(density) { rowHeight.toDp() }),
                horizontalArrangement = Arrangement.spacedBy(with(density) { gap.toFloat().toDp() }), verticalAlignment = Alignment.CenterVertically) {
                layout.rows[rowIndex].words.forEach { index -> key(index) {
                    val active = index == state.index
                    val word = session.tokens[index].word.text
                    val styled = remember(word, active, focusLetter) {
                        buildAnnotatedString {
                            append(word)
                            if (active && focusLetter) ReadingFocus.range(word)?.let {
                                addStyle(SpanStyle(color = FocusLetter), it.first, it.last + 1)
                            }
                        }
                    }
                    val scale = (layout.widths[index] / measured[index].coerceAtLeast(1.0)).coerceAtMost(1.0)
                    // Selection changes the highlight, so the text keeps its natural spacing.
                    Text(styled, maxLines = 1, softWrap = false,
                        style = style.copy(fontSize = (selectedSize * scale).sp),
                        color = if (active) Ink else Muted,
                        modifier = Modifier.width(with(density) { layout.widths[index].toFloat().toDp() })
                            .height(with(density) { rowHeight.toDp() - 8.dp })
                            .wrapContentHeight().testTag(if (active) "currentWord" else "passageWord-$index")
                            .semantics {
                                if (active) {
                                    stateDescription = "Word ${index + 1} of ${session.tokens.size}"
                                    customActions = listOf(
                                        CustomAccessibilityAction("Next word") { session.seek(index + 1); true },
                                        CustomAccessibilityAction("Previous word") { session.seek(index - 1); true })
                                }
                            })
                } }
            }
        }
    }
}
