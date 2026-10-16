package com.stillreader.app.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize

/** Presentation only: playback and saved positions never depend on animation progress. */
internal class WordMotion {
    private var index: Int? = null
    private var targets = emptyMap<Int, Offset>()
    private var origins = emptyMap<Int, Offset>()
    private var viewport = IntSize.Zero
    private var widths = emptyMap<Int, Int>()
    private var enabled = false
    private var lastProgress = 1f

    fun positions(index: Int, targets: Map<Int, Offset>, viewport: IntSize,
                  enabled: Boolean, progress: Float, widths: Map<Int, Int> = emptyMap()): Map<Int, Offset> {
        if (this.index != index || this.targets != targets || this.viewport != viewport) {
            val animate = enabled && this.enabled && this.index?.plus(1) == index && this.viewport == viewport
            origins = if (animate) sample(lastProgress) else emptyMap()
            this.targets = targets
            this.index = index
            this.viewport = viewport
        }
        this.widths = widths
        // Pause, scrub, accessibility changes and resume cannot revive an old transition.
        if (!enabled) origins = emptyMap()
        this.enabled = enabled
        lastProgress = progress
        return sample(progress)
    }

    private fun sample(progress: Float) = targets.mapValues { (id, target) ->
        val origin = origins[id]
        val fraction = progress.coerceIn(0f, 1f)
        val width = widths[id]
        if (id == index || origin == null) target
        else if (target.y < origin.y - 1 && target.x > origin.x && width != null) {
            // Wrap through the clipped edges instead of sweeping across a row.
            val exit = (origin.x + width).coerceAtLeast(0f)
            val travel = (exit + (viewport.width - target.x).coerceAtLeast(0f)) * fraction
            if (travel < exit) Offset(origin.x - travel, origin.y)
            else Offset(viewport.width - (travel - exit), target.y)
        } else origin + (target - origin) * fraction
    }

    companion object {
        fun durationMillis(wordsPerMinute: Double) = (minOf(0.18, 0.6 * 60 / wordsPerMinute.coerceAtLeast(60.0)) * 1000).toInt()
    }
}
