package com.stillreader.app

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import com.stillreader.app.ui.WordMotion
import org.junit.Assert.*
import org.junit.Test

class WordMotionTests {
    private val viewport = IntSize(600, 400)
    private val before = mapOf(0 to Offset(300f, 200f), 1 to Offset(380f, 200f), 2 to Offset(450f, 256f))
    private val after = mapOf(0 to Offset(220f, 200f), 1 to Offset(300f, 200f), 2 to Offset(370f, 200f))

    @Test fun neighborsMoveButActiveWordRemainsFixed() {
        val motion = WordMotion()
        motion.positions(0, before, viewport, true, 1f)
        val first = motion.positions(1, after, viewport, true, 0f)
        assertEquals(before[0], first[0])
        assertEquals(after[1], first[1])
        val middle = motion.positions(1, after, viewport, true, .5f)
        assertTrue(middle.getValue(2).x > after.getValue(2).x)
        assertTrue(middle.getValue(2).x < before.getValue(2).x)
        assertTrue(middle.getValue(2).y > after.getValue(2).y)
        assertTrue(middle.getValue(2).y < before.getValue(2).y)
        assertEquals(after[1], middle[1])
        assertEquals(after, motion.positions(1, after, viewport, true, 1f))
    }

    @Test fun wrappingWordUsesClippedEdgesInsteadOfCrossingTheRow() {
        val motion = WordMotion()
        val source = mapOf(2 to Offset(24f, 256f))
        val destination = mapOf(2 to Offset(500f, 200f))
        motion.positions(0, source, viewport, true, 1f, mapOf(2 to 80))
        for (step in 0..10) {
            val point = motion.positions(1, destination, viewport, true, step / 10f, mapOf(2 to 80)).getValue(2)
            assertTrue((point.y == 256f && point.x <= 24f) || (point.y == 200f && point.x >= 500f))
        }
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
