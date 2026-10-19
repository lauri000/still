package com.stillreader.app

import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.stillreader.app.core.*
import com.stillreader.app.ui.StillTheme
import com.stillreader.app.ui.WordRibbon
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalTestApi::class)
class WordMotionLayoutTests {
    private val scale = object : MotionDurationScale {
        var value = mutableStateOf(1f)
        override val scaleFactor get() = value.value
    }
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>(effectContext = scale)

    @Test fun singleLineWordsMoveAtEachSizeAndPauseOrReducedMotionSettles() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val book = Book(title = "Motion", originalFilename = "motion.pdf",
            pages = listOf(BookPage(number = 1, label = "1", extractedText = (0..100).joinToString(" ") { "word$it" })))
        val session = ReadingSession(book, scope) {}
        val index = mutableIntStateOf(20)
        val scrolling = mutableStateOf(true)
        val sizePercent = mutableIntStateOf(100)
        session.seek(index.intValue)
        session.wordsPerMinute = 60.0
        compose.setContent { StillTheme { WordRibbon(session, index.intValue, false, scrolling.value, activeWordSizePercent = sizePercent.intValue) } }
        try {
            for (percent in listOf(100, 200, 300)) {
                compose.runOnIdle { sizePercent.intValue = percent; scrolling.value = true; scale.value.value = 1f }
                compose.waitForIdle()
                compose.mainClock.autoAdvance = false
                val neighbor = index.intValue - 1
                fun point() = compose.onNodeWithTag("nearbyWord-$neighbor").fetchSemanticsNode().boundsInRoot.center
                val before = point()
                val center = compose.onNodeWithTag("currentWord").fetchSemanticsNode().boundsInRoot.center
                compose.runOnUiThread { session.seek(index.intValue + 1); index.intValue++ }
                compose.mainClock.advanceTimeByFrame()
                compose.mainClock.advanceTimeBy(48)
                val middle = point()
                assertEquals(center, compose.onNodeWithTag("currentWord").fetchSemanticsNode().boundsInRoot.center)
                compose.mainClock.advanceTimeBy(240)
                val end = point()
                assertTrue("A real neighboring word must move across multiple frames ($percent%): $before, $middle, $end",
                    (middle - before).getDistance() > 1f && (middle - end).getDistance() > 1f)
                compose.runOnUiThread { session.seek(index.intValue + 1); index.intValue++ }
                compose.mainClock.advanceTimeBy(48)
                compose.runOnUiThread { scrolling.value = false }
                compose.mainClock.advanceTimeByFrame()
                val paused = point()
                compose.mainClock.advanceTimeBy(240)
                assertEquals(paused, point())
                // System animation scale zero must bypass motion even with the preference on.
                compose.runOnUiThread { scale.value.value = 0f; scrolling.value = true }
                compose.mainClock.advanceTimeByFrame()
                compose.runOnUiThread { session.seek(index.intValue + 1); index.intValue++ }
                compose.mainClock.advanceTimeBy(48)
                val reduced = point()
                compose.mainClock.advanceTimeBy(240)
                assertEquals(reduced, point())
                compose.mainClock.autoAdvance = true
            }
        } finally { compose.mainClock.autoAdvance = true; scope.cancel() }
    }
}
