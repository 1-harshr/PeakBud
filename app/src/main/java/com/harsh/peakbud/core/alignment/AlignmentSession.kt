package com.harsh.peakbud.core.alignment

import kotlin.math.abs

/**
 * SENSOR: labels use the compass/tilt estimate. ALIGNING: looking for a confident camera match.
 * ALIGNED: an accepted correction is applied and re-verified by recent frames.
 * FAILED: no confident match before the attempt timed out. LOST: an accepted alignment stopped verifying.
 */
enum class AlignmentPhase { SENSOR, ALIGNING, ALIGNED, FAILED, LOST }

/** Why the last frames did not produce a usable match; drives the short explanation shown to the user. */
enum class AlignmentProblem { NO_CLEAR_SKY, NO_SKYLINE, NOT_ENOUGH_SHAPE, NO_TERRAIN, NO_CLEAR_MATCH, HOLD_UPRIGHT, MOVING, ROUGH_LOCATION, NO_CAMERA }

data class AlignmentState(val phase: AlignmentPhase = AlignmentPhase.SENSOR,
                          val correction: AlignmentCorrection? = null,
                          val verifiedAtMs: Long? = null,
                          val problem: AlignmentProblem? = null)

/** Owned by one analysis worker; callers synchronise. UI receives immutable snapshots. Uses monotonic time.
 * Reset externally when location/scene/intrinsics/lifecycle/manual trim changes.
 */
class AlignmentSession(private val attemptMs: Long = 10_000, private val staleMs: Long = 2_000) {
    var state = AlignmentState()
        private set
    private var startedMs = 0L
    private var previous: SkylineMatch? = null
    private var previousMs = Long.MIN_VALUE
    private var agreements = 0
    private var misses = 0
    private val problems = HashMap<AlignmentProblem, Int>()

    fun start(nowMs: Long): AlignmentState {
        reset()
        startedMs = nowMs
        state = AlignmentState(AlignmentPhase.ALIGNING)
        return state
    }
    fun reset(): AlignmentState {
        previous = null; agreements = 0; misses = 0; previousMs = Long.MIN_VALUE; problems.clear()
        state = AlignmentState()
        return state
    }
    fun tick(nowMs: Long): AlignmentState {
        if (state.phase == AlignmentPhase.ALIGNING && nowMs - startedMs > attemptMs) {
            state = AlignmentState(AlignmentPhase.FAILED, problem = problems.maxByOrNull { it.value }?.key ?: AlignmentProblem.NO_SKYLINE)
            previous = null; agreements = 0
        } else if (state.phase == AlignmentPhase.ALIGNED && nowMs - (state.verifiedAtMs ?: 0L) > staleMs) lose()
        return state
    }
    /** A frame that could not be matched, with the reason. */
    fun miss(problem: AlignmentProblem, nowMs: Long): AlignmentState {
        problems.merge(problem, 1, Int::plus)
        return observe(null, nowMs)
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
        if (state.phase == AlignmentPhase.ALIGNED && accepted != null) {
            // A different best fit is not permission to jump labels to another ridge.
            if (!agrees(accepted, match.correction)) { lose(); return state }
            // Small agreeing changes follow slow compass drift without visible jumps.
            val followed = AlignmentCorrection(accepted.heading + (match.correction.heading - accepted.heading) * .3,
                accepted.pitch + (match.correction.pitch - accepted.pitch) * .3)
            state = state.copy(correction = followed, verifiedAtMs = nowMs)
            return state
        }
        agreements = if (old != null && nowMs - oldTime in 150..1_000 && agrees(old.correction, match.correction)) agreements + 1 else 1
        previous = match
        if (agreements >= 3) state = AlignmentState(AlignmentPhase.ALIGNED, match.correction, nowMs)
        return state
    }
    private fun lose() {
        state = AlignmentState(AlignmentPhase.LOST)
        previous = null; agreements = 0; misses = 0
    }
}
