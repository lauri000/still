package com.stillreader.app

import androidx.compose.ui.unit.IntSize
import com.stillreader.app.ui.WordMotion
import org.junit.Assert.*
import org.junit.Test

class WordMotionTests {
    private val viewport = IntSize(600, 400)
    private val before = mapOf(0 to 300f, 1 to 380f, 2 to 450f)
    private val after = mapOf(0 to 220f, 1 to 300f, 2 to 370f)

    @Test fun neighborsMoveButActiveWordRemainsFixed() {
        val motion = WordMotion()
        motion.positions(0, before, viewport, true, 1f)
        val first = motion.positions(1, after, viewport, true, 0f)
        assertEquals(before[0], first[0])
        assertEquals(after[1], first[1])
        val middle = motion.positions(1, after, viewport, true, .5f)
        assertTrue(middle.getValue(2) > after.getValue(2))
        assertTrue(middle.getValue(2) < before.getValue(2))
        assertEquals(after[1], middle[1])
        assertEquals(after, motion.positions(1, after, viewport, true, 1f))
    }

    @Test fun interruptedTransitionContinuesFromItsDisplayedPosition() {
        val motion = WordMotion()
        motion.positions(0, before, viewport, true, 1f)
        val middle = motion.positions(1, after, viewport, true, .5f)
        val next = mapOf(0 to 140f, 1 to 220f, 2 to 300f)
        val resumed = motion.positions(2, next, viewport, true, 0f)
        assertEquals(middle[0], resumed[0])
        assertEquals(middle[1], resumed[1])
        assertEquals(next[2], resumed[2])
        assertEquals(next, motion.positions(2, next, viewport, true, 1f))
    }

    @Test fun pauseOrReducedMotionSettlesAndResumeDoesNotReviveMotion() {
        val motion = WordMotion()
        motion.positions(0, before, viewport, true, 1f)
        motion.positions(1, after, viewport, true, 0f)
        assertEquals(after, motion.positions(1, after, viewport, false, .1f))
        assertEquals(after, motion.positions(1, after, viewport, true, .2f))
        assertEquals(before, motion.positions(2, before, viewport, false, 0f))
    }

    @Test fun jumpsReplayAndViewportChangesAreImmediate() {
        listOf(8 to viewport, 0 to viewport, 1 to IntSize(400, 600)).forEach { (index, size) ->
            val motion = WordMotion()
            motion.positions(0, before, viewport, true, 1f)
            assertEquals(after, motion.positions(index, after, size, true, 0f))
        }
        listOf(60.0, 250.0, 900.0).forEach { speed ->
            assertTrue(WordMotion.durationMillis(speed) < 60_000 / speed)
            assertTrue(WordMotion.durationMillis(speed) <= 180)
        }
    }
}
