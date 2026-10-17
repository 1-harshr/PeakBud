package com.harsh.peakbud.core.alignment

import kotlin.math.abs

enum class AlignmentPhase { SENSOR, ALIGNING, ALIGNED, LOST }
data class AlignmentState(val phase: AlignmentPhase = AlignmentPhase.SENSOR,
                          val correction: AlignmentCorrection? = null,
                          val verifiedAtMs: Long? = null)

/** Owned by one analysis worker. UI receives immutable snapshots. Uses monotonic time.
 * Reset externally when location/scene/intrinsics/lifecycle/manual trim changes.
 */
class AlignmentSession {
    var state = AlignmentState()
        private set
    private var startedMs = 0L
    private var previous: SkylineMatch? = null
    private var previousMs = Long.MIN_VALUE
    private var agreements = 0
    private var misses = 0

    fun start(nowMs: Long): AlignmentState {
        reset()
        startedMs = nowMs
        state = AlignmentState(AlignmentPhase.ALIGNING)
        return state
    }
    fun reset(): AlignmentState {
        previous = null; agreements = 0; misses = 0; previousMs = Long.MIN_VALUE
        state = AlignmentState()
        return state
    }
    fun tick(nowMs: Long): AlignmentState {
        if (state.phase == AlignmentPhase.ALIGNING && nowMs - startedMs > 10_000 ||
            state.phase == AlignmentPhase.ALIGNED && nowMs - (state.verifiedAtMs ?: 0L) > 2_000) lose()
        return state
    }
    fun observe(match: SkylineMatch?, nowMs: Long): AlignmentState {
        tick(nowMs)
        if (state.phase != AlignmentPhase.ALIGNING && state.phase != AlignmentPhase.ALIGNED) return state
        if (nowMs <= previousMs) return state
        val oldTime = previousMs
        previousMs = nowMs
        if (match == null) {
            previous = null; agreements = 0; misses++
            if (state.phase == AlignmentPhase.ALIGNED && misses >= 3) lose()
            return state
        }
        misses = 0
        val old = previous
        val accepted = state.correction
        fun agrees(a: AlignmentCorrection, b: AlignmentCorrection) = abs(a.heading - b.heading) < .8 && abs(a.pitch - b.pitch) < .6
        if (state.phase == AlignmentPhase.ALIGNED && accepted != null && !agrees(accepted, match.correction)) {
            // A new, different best fit is not permission to jump labels to another ridge.
            lose()
            return state
        }
        agreements = if (old != null && nowMs - oldTime in 150..1_000 && agrees(old.correction, match.correction)) agreements + 1 else 1
        previous = match
        if (agreements >= 3) state = AlignmentState(AlignmentPhase.ALIGNED, match.correction, nowMs)
        else if (state.phase == AlignmentPhase.ALIGNED) state = state.copy(verifiedAtMs = nowMs)
        return state
    }
    private fun lose() {
        state = AlignmentState(AlignmentPhase.LOST)
        previous = null; agreements = 0; misses = 0
    }
}
