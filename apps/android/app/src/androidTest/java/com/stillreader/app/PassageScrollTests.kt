package com.stillreader.app

import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.stillreader.app.core.*
import com.stillreader.app.ui.PassageView
import com.stillreader.app.ui.StillTheme
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PassageScrollTests {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun passageKeepsFlingingAfterFingerLifts() = checkMomentum(false)
    @Test fun passageKeepsFlingingWithLivePositionUpdates() = checkMomentum(true)

    @Test fun passageKeepsFlingingAcrossParagraphs() = checkMomentum(true, paragraphRepeats = 1)

    @Test fun rowTapSelectsExactWordAndIgnoresBlankSpace() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        lateinit var session: ReadingSession
        var selected: Int? = null
        compose.runOnUiThread {
            session = ReadingSession(Book(title = "Tap test", originalFilename = "test.pdf", pages = listOf(
                BookPage(number = 1, extractedText = "one two three.", isReviewed = true))), scope) { }
            session.passageMode = true
        }
        compose.setContent {
            val state by session.state.collectAsState()
            StillTheme { PassageView(session, state) { selected = it } }
        }
        try {
            val row = compose.onNodeWithTag("currentPassageRow")
            val results = mutableListOf<TextLayoutResult>()
            row.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
            val layout = results.single()
            row.performTouchInput { click(layout.getBoundingBox(3).center) }
            compose.runOnIdle { assertNull("A space must not select a word", selected) }
            row.performTouchInput { click(Offset(width - 1f, layout.getBoundingBox(0).center.y)) }
            compose.runOnIdle { assertNull("The blank end of a row must not select its last word", selected) }
            row.performTouchInput { click(layout.getBoundingBox(5).center) }
            compose.runOnIdle { assertEquals("Tap on two", 1, selected) }
            val action = row.fetchSemanticsNode().config[SemanticsActions.CustomActions].single { it.label == "Read from three." }
            compose.runOnIdle { assertTrue(action.action()) }
            compose.runOnIdle { assertEquals("Accessibility word action", 2, selected) }
        } finally { scope.cancel() }
    }

    private fun checkMomentum(liveDrag: Boolean, paragraphRepeats: Int = 4) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val text = (1..100).joinToString("\n\n") { paragraph ->
            "Paragraph $paragraph. " + "The river runs through the quiet valley and the trees move in the breeze. ".repeat(paragraphRepeats)
        }
        lateinit var session: ReadingSession
        compose.runOnUiThread {
            session = ReadingSession(Book(title = "Momentum", originalFilename = "test.pdf",
                pages = listOf(BookPage(number = 1, extractedText = text, isReviewed = true))), scope) { }
            session.passageMode = true
        }
        compose.setContent {
            val state by session.state.collectAsState()
            StillTheme { PassageView(session, state) { session.seek(it) } }
        }
        try {
            compose.waitForIdle()
            compose.mainClock.autoAdvance = false
            val scroll = compose.onNodeWithTag("passageScroll")
            fun position() = scroll.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
            if (liveDrag) {
                scroll.performTouchInput { down(Offset(width * .5f, height * .8f)) }
                repeat(16) { step ->
                    scroll.performTouchInput {
                        moveTo(Offset(width * .5f, height * (.8f - .5f * (step + 1) / 16)), delayMillis = 16)
                    }
                    compose.mainClock.advanceTimeByFrame()
                }
                scroll.performTouchInput { up() }
            } else scroll.performTouchInput {
                swipeWithVelocity(Offset(width * .5f, height * .8f), Offset(width * .5f, height * .3f),
                    endVelocity = 2500f, durationMillis = 250)
            }
            compose.mainClock.advanceTimeByFrame()
            val released = position()
            compose.mainClock.advanceTimeBy(160)
            val coasting = position()
            compose.mainClock.advanceTimeBy(160)
            val later = position()
            assertTrue("The page must keep moving after release: $released -> $coasting", coasting > released)
            assertTrue("Momentum must continue rather than stop at the next row: $coasting -> $later", later > coasting)
        } finally {
            compose.mainClock.autoAdvance = true
            scope.cancel()
        }
    }
}
