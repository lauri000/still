package com.stillreader.app.ui

import androidx.compose.ui.unit.IntSize

/** Presentation only: playback and saved positions never depend on animation progress. */
internal class WordMotion {
    private var index: Int? = null
    private var targets = emptyMap<Int, Float>()
    private var origins = emptyMap<Int, Float>()
    private var viewport = IntSize.Zero
    private var enabled = false
    private var lastProgress = 1f

    fun positions(index: Int, targets: Map<Int, Float>, viewport: IntSize,
                  enabled: Boolean, progress: Float): Map<Int, Float> {
        if (this.index != index || this.targets != targets || this.viewport != viewport) {
            val animate = enabled && this.enabled && this.index?.plus(1) == index && this.viewport == viewport
            origins = if (animate) sample(lastProgress) else emptyMap()
            this.targets = targets
            this.index = index
            this.viewport = viewport
        }
        // Pause, scrub, accessibility changes and resume cannot revive an old transition.
        if (!enabled) origins = emptyMap()
        this.enabled = enabled
        lastProgress = progress
        return sample(progress)
    }

    private fun sample(progress: Float) = targets.mapValues { (id, target) ->
        val origin = origins[id]
        val fraction = progress.coerceIn(0f, 1f)
        if (id == index || origin == null) target else origin + (target - origin) * fraction
    }

    companion object {
        fun durationMillis(wordsPerMinute: Double) = (minOf(0.18, 0.6 * 60 / wordsPerMinute.coerceAtLeast(60.0)) * 1000).toInt()
    }
}
