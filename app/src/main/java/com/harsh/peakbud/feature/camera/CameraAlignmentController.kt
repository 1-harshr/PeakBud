package com.harsh.peakbud.feature.camera

import com.harsh.peakbud.core.alignment.*
import com.harsh.peakbud.core.geo.Geography
import com.harsh.peakbud.core.model.*
import com.harsh.peakbud.core.projection.*
import com.harsh.peakbud.data.regions.RegionPack
import com.harsh.peakbud.data.terrain.HgtTerrain
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlin.math.abs

/** What the camera screen shows about alignment. Kept apart from raw sensor readings in PanoramaState. */
data class AlignmentUi(
    val phase: AlignmentPhase = AlignmentPhase.SENSOR,
    val correction: AlignmentCorrection? = null,
    val problem: AlignmentProblem? = null,
    /** The terrain skyline for this direction is still being prepared. */
    val preparing: Boolean = false,
    /** Peaks whose summit is covered by something nearer (trees, buildings) in the latest aligned frame. */
    val hiddenByForeground: Set<String> = emptySet(),
) {
    val aligned get() = phase == AlignmentPhase.ALIGNED && correction != null
    fun pose(raw: CameraPose?): CameraPose? = if (aligned) raw?.let { correction!!.apply(it) } else raw
}

/**
 * Runs "Align with camera": predicts the skyline for the direction the phone faces from the downloaded terrain, then
 * matches camera frames against it.
 *
 * Threading: [start]/[reset] come from the main thread; [wantsFrame]/[process] run on the camera analysis thread.
 * Extraction and matching run outside the lock; a generation number discards results that finish after a reset or
 * restart. Terrain prediction runs on [io] and is cancellable.
 */
class CameraAlignmentController(
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
    private val log: (String) -> Unit = {},
    /** Predicts the far-field skyline for a sector; reads the region's terrain files by default. Replaceable in tests. */
    private val skylineSource: (Target, Double, Double, () -> Unit) -> List<HorizonSample>? = ::terrainSector,
) {
    /** Where and how to predict the skyline. [groundM] null means take it from the terrain at [point]. */
    data class Target(val pack: RegionPack, val point: GeoPoint, val groundM: Double?, val phoneHeightM: Double, val hfovDeg: Double)

    private class Sector(val target: Target, val centerDeg: Double, val halfWidthDeg: Double, val skyline: TerrainSkyline) {
        fun covers(target: Target, heading: Double) = this.target.pack.key == target.pack.key && Geography.distance(this.target.point, target.point) < 1.0 &&
            this.target.groundM == target.groundM && this.target.phoneHeightM == target.phoneHeightM &&
            abs(Geography.wrap180(heading - centerDeg)) <= halfWidthDeg - target.hfovDeg / 2 - 3
    }

    private val session = AlignmentSession(attemptMs = 12_000, staleMs = 4_000)
    private val mutable = MutableStateFlow(AlignmentUi())
    val ui: StateFlow<AlignmentUi> = mutable.asStateFlow()

    private var generation = 0L
    private var target: Target? = null
    @Volatile private var sector: Sector? = null
    /** Last prediction built, kept across Retry and prefetch so the same view is not traced twice. */
    @Volatile private var cached: Sector? = null
    private var sectorJob: Job? = null
    private var tickJob: Job? = null
    private var lastFrameMs = Long.MIN_VALUE / 2
    private var lastPose: CameraPose? = null
    private var hidden: Set<String> = emptySet()

    /** Begin aligning, or explain immediately why it cannot start. */
    fun start(target: Target?, pose: CameraPose?, blocker: AlignmentProblem?) = synchronized(this) {
        generation++
        sectorJob?.cancel(); sector = null; hidden = emptySet(); lastPose = null
        tickJob?.cancel(); tickJob = null
        cached?.takeIf { target != null && pose != null && it.covers(target, pose.heading) }?.let { sector = it }
        if (blocker != null || target == null || pose == null) {
            session.reset(); this.target = null
            mutable.value = AlignmentUi(AlignmentPhase.FAILED, problem = blocker ?: AlignmentProblem.HOLD_UPRIGHT)
            return@synchronized
        }
        this.target = target
        session.start(clock())
        if (sector == null) prepareSector(target, pose.heading, restartAttempt = true)
        publish()
        tickJob?.cancel()
        tickJob = scope.launch {
            while (isActive) {
                delay(500)
                synchronized(this@CameraAlignmentController) {
                    // Terrain preparation is not a failed camera match; start the attempt clock after it is ready.
                    if (sector != null || session.state.phase == AlignmentPhase.ALIGNED) session.tick(clock())
                    publish()
                    if (session.state.phase != AlignmentPhase.ALIGNING && session.state.phase != AlignmentPhase.ALIGNED) cancel()
                }
            }
        }
    }

    /** Back to sensor-estimated labels (manual adjustment, new place or region, leaving the camera). */
    fun reset() = synchronized(this) {
        generation++
        sectorJob?.cancel(); sectorJob = null; sector = null
        tickJob?.cancel(); tickJob = null
        target = null; hidden = emptySet(); lastPose = null
        session.reset()
        mutable.value = AlignmentUi()
    }

    /** The analysis thread asks before doing any image work: frames are skipped unless aligning, at most ~3 per second. */
    fun wantsFrame(): Boolean = synchronized(this) {
        val phase = session.state.phase
        if (phase != AlignmentPhase.ALIGNING && phase != AlignmentPhase.ALIGNED) return@synchronized false
        if (sector == null) return@synchronized false
        val now = clock()
        if (now - lastFrameMs < 300) return@synchronized false
        lastFrameMs = now
        true
    }

    /**
     * Process one frame. [toView] maps upright-frame points to preview pixels (the coordinates [lens] describes) and
     * returns null outside the visible preview. [pose] is the raw sensor estimate including manual trim.
     */
    fun process(frame: UprightFrame, toView: (Double, Double) -> Pair<Double, Double>?, lens: CameraLens?, pose: CameraPose?, peaks: List<PeakView>) {
        val (gen, sec, tgt, previousPose, accepted) = synchronized(this) {
            Snapshot(generation, sector, target, lastPose, session.state.correction.takeIf { session.state.phase == AlignmentPhase.ALIGNED })
        }
        if (sec == null || tgt == null) return
        val started = System.nanoTime()
        val outcome = analyse(frame, toView, lens, pose, previousPose, sec, tgt, accepted, peaks)
        log("alignment frame ${frame.width}x${frame.height} in ${(System.nanoTime() - started) / 1_000_000} ms: ${outcome.match?.let { "match %.2f°/%.2f° err %.3f°".format(it.correction.heading, it.correction.pitch, it.errorDeg) } ?: outcome.problem}")
        synchronized(this) {
            if (gen != generation) return  // reset or restarted while this frame was being analysed
            lastPose = pose
            val now = clock()
            when {
                outcome.recentre -> { prepareSector(tgt, pose!!.heading, restartAttempt = false); return }
                outcome.skip -> session.tick(now)
                outcome.match != null -> session.observe(outcome.match, now)
                else -> session.miss(outcome.problem!!, now)
            }
            if (session.state.phase == AlignmentPhase.ALIGNED) outcome.hidden?.let { hidden = it } else hidden = emptySet()
            publish()
        }
    }

    private data class Snapshot(val gen: Long, val sector: Sector?, val target: Target?, val lastPose: CameraPose?, val accepted: AlignmentCorrection?)
    private class Outcome(val match: SkylineMatch? = null, val problem: AlignmentProblem? = null, val skip: Boolean = false,
                          val recentre: Boolean = false, val hidden: Set<String>? = null)

    private fun analyse(frame: UprightFrame, toView: (Double, Double) -> Pair<Double, Double>?, lens: CameraLens?, pose: CameraPose?,
                        previousPose: CameraPose?, sec: Sector, tgt: Target, accepted: AlignmentCorrection?, peaks: List<PeakView>): Outcome {
        if (lens == null) return Outcome(problem = AlignmentProblem.NO_CAMERA)
        if (pose == null) return Outcome(problem = AlignmentProblem.HOLD_UPRIGHT)
        // Frames and sensor readings are not timestamp-synchronised; skip frames taken while the phone swings.
        if (previousPose != null && abs(Geography.wrap180(pose.heading - previousPose.heading)) > 4) return Outcome(skip = true, problem = AlignmentProblem.MOVING)
        if (abs(Geography.wrap180(pose.heading - sec.centerDeg)) > sec.halfWidthDeg - tgt.hfovDeg / 2 - 3) return Outcome(recentre = true)
        val extraction = SkylineExtractor.extract(frame.width, frame.height, frame.argb)
        val skyline = extraction.skyline ?: return Outcome(problem = when (extraction.failure) {
            ExtractionFailure.NO_CLEAR_SKY -> AlignmentProblem.NO_CLEAR_SKY
            else -> AlignmentProblem.NO_SKYLINE
        })
        val points = skyline.points.mapNotNull { p ->
            val (bx, by) = frame.toBuffer(p.x, p.y)
            toView(bx, by)?.let { (x, y) -> BoundaryPoint(x, y) }
        }
        if (points.size < 40) return Outcome(problem = AlignmentProblem.NO_SKYLINE)
        val result = SkylineMatcher.match(points, lens, pose, sec.skyline)
        val match = result.match ?: return Outcome(problem = when (result.failure) {
            MatchFailure.INVALID_VIEW -> AlignmentProblem.HOLD_UPRIGHT
            MatchFailure.INCOMPLETE_TERRAIN -> AlignmentProblem.NO_TERRAIN
            MatchFailure.INSUFFICIENT_SHAPE -> AlignmentProblem.NOT_ENOUGH_SHAPE
            else -> AlignmentProblem.NO_CLEAR_MATCH
        })
        val correction = accepted ?: match.correction
        return Outcome(match = match, hidden = foregroundCovered(points, lens, correction.apply(pose), sec.skyline, peaks))
    }

    /**
     * Image evidence of foreground occlusion: where the observed outline sits clearly above the predicted terrain skyline
     * (a tree, roof or nearby slope) and above a peak's summit, that peak's label is withheld. Matching the skyline does
     * not prove every summit is visible.
     */
    private fun foregroundCovered(points: List<BoundaryPoint>, lens: CameraLens, pose: CameraPose, predicted: TerrainSkyline, peaks: List<PeakView>): Set<String> {
        val rays = points.map { CameraRays.world(it.x, it.y, lens, pose) }
        return peaks.filter { peak ->
            val elevation = peak.elevationDeg ?: return@filter false
            val nearest = rays.minByOrNull { abs(Geography.wrap180(it.bearing - peak.bearingDeg)) } ?: return@filter false
            if (abs(Geography.wrap180(nearest.bearing - peak.bearingDeg)) > .5) return@filter false
            val terrain = predicted.elevation(nearest.bearing) ?: return@filter false
            nearest.elevation > terrain + .5 && elevation < nearest.elevation - .3
        }.map { it.peak.id }.toSet()
    }

    /** Build the prediction ahead of time (Camera tab opened) so "Align with camera" can start matching at once. */
    fun prefetch(target: Target, heading: Double) = synchronized(this) {
        if (cached?.covers(target, heading) == true || sectorJob?.isActive == true) return@synchronized
        if (session.state.phase == AlignmentPhase.ALIGNING || session.state.phase == AlignmentPhase.ALIGNED) return@synchronized
        prepareSector(target, heading, restartAttempt = false, prefetchOnly = true)
    }

    private fun prepareSector(target: Target, heading: Double, restartAttempt: Boolean, prefetchOnly: Boolean = false) {
        if (sectorJob?.isActive == true) return
        val gen = generation
        val halfWidth = (target.hfovDeg / 2 + 25).coerceAtMost(180.0)
        if (!prefetchOnly) mutable.value = mutable.value.copy(preparing = true)
        sectorJob = scope.launch(io) {
            val startMs = clock()
            val built = try {
                skylineSource(target, heading, halfWidth) { ensureActive() }?.let { Sector(target, heading, halfWidth, TerrainSkyline(it)) }
            } catch (cancelled: CancellationException) { throw cancelled } catch (error: Exception) { log("alignment terrain failed: ${error.javaClass.simpleName}"); null }
            log("alignment terrain sector ${halfWidth * 2}° to ${(target.pack.radiusM / 1000).toInt()} km in ${clock() - startMs} ms")
            synchronized(this@CameraAlignmentController) {
                if (gen != generation) return@launch
                if (built != null) cached = built
                if (prefetchOnly) return@launch
                if (built == null) {
                    session.reset(); tickJob?.cancel()
                    mutable.value = AlignmentUi(AlignmentPhase.FAILED, problem = AlignmentProblem.NO_TERRAIN)
                } else {
                    sector = built
                    // The attempt's time limit counts from when matching can begin, not from the tap.
                    if (restartAttempt && session.state.phase == AlignmentPhase.ALIGNING) session.start(clock())
                    publish()
                }
            }
        }
    }

    private fun publish() {
        val s = session.state
        mutable.value = AlignmentUi(s.phase, s.correction, s.problem, preparing = sector == null && (s.phase == AlignmentPhase.ALIGNING || s.phase == AlignmentPhase.ALIGNED),
            hiddenByForeground = if (s.phase == AlignmentPhase.ALIGNED) hidden else emptySet())
    }
}

/** Default skyline source: far-field sector traced through the downloaded region's terrain. */
private fun terrainSector(target: CameraAlignmentController.Target, heading: Double, halfWidth: Double, checkpoint: () -> Unit): List<HorizonSample>? =
    HgtTerrain(target.pack.directory.resolve("terrain"), target.pack.bounds).use { terrain ->
        val ground = target.groundM ?: (terrain.sample(target.point) as? TerrainSample.Valid)?.heightM ?: return@use null
        AlignmentHorizon.sector(Observer(target.point, ground, target.phoneHeightM), terrain, heading, halfWidth, target.pack.radiusM, .2, checkpoint = checkpoint)
    }
