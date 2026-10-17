package com.harsh.peakbud.core

import com.harsh.peakbud.core.alignment.*
import com.harsh.peakbud.core.geo.Geography
import com.harsh.peakbud.core.model.*
import com.harsh.peakbud.core.projection.*
import com.harsh.peakbud.data.regions.RegionStore
import com.harsh.peakbud.data.terrain.HgtTerrain
import java.io.File
import kotlin.math.*
import kotlin.random.Random
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Opt-in accuracy measurement on real terrain geometry: render what a phone camera would see from random lookouts (highest point within ~1 km of a random spot) in the
 * installed Uttarakhand and Jharkhand packs (true pose known), corrupt it like a real frame (sensor heading/pitch error,
 * pixel noise, missing columns, a foreground blob), and check the matcher either recovers the true correction or refuses.
 * A wrong accepted correction is the failure that matters. Requires PEAKBUD_TERRAIN_BENCH_DIR.
 *
 * This isolates registration from image extraction; it is not a measure of outdoor camera accuracy.
 */
class RealTerrainMatchingTest {
    @Test fun matcherRecoversOrRefusesOnRealTerrainAndNeverAcceptsAWrongPose() {
        val installed = System.getenv("PEAKBUD_TERRAIN_BENCH_DIR")
        assumeTrue("Set PEAKBUD_TERRAIN_BENCH_DIR to the installed-region directory", installed != null)
        val store = RegionStore(File(installed!!))
        val random = Random(20261017)
        val w = 320; val h = 240
        val hfov = 66.0  // typical phone main camera
        val fx = (w / 2.0) / tan(Math.toRadians(hfov / 2))
        val lens = CameraLens(fx, fx, w / 2.0, h / 2.0)
        var totalWrong = 0
        for (id in listOf("uttarakhand-himalaya", "jharkhand")) {
            val pack = store.installed().single { it.id == id }
            HgtTerrain(pack.directory.resolve("terrain"), pack.bounds).use { terrain ->
                var trials = 0; var accepted = 0; var wrong = 0; val errors = ArrayList<Double>(); val failures = HashMap<MatchFailure, Int>()
                var horizonMs = 0L; var matchMs = 0L
                while (trials < 60) {
                    val b = pack.observerBounds
                    val start = GeoPoint(b.south + random.nextDouble() * (b.north - b.south), b.west + random.nextDouble() * (b.east - b.west))
                    // Hikers align from lookouts, not valley floors: move to the highest terrain within about 1 km.
                    var spot = start; var ground = Double.NEGATIVE_INFINITY
                    for (dy in -10..10) for (dx in -10..10) {
                        val p = GeoPoint(start.latitude + dy * .0009, start.longitude + dx * .00104)
                        (terrain.sample(p) as? TerrainSample.Valid)?.let { if (it.heightM > ground) { ground = it.heightM; spot = p } }
                    }
                    if (!ground.isFinite()) continue
                    trials++
                    val heading = random.nextDouble() * 360
                    val truth = CameraPose(heading, random.nextDouble(-4.0, 6.0), random.nextDouble(-8.0, 8.0))
                    val t0 = System.nanoTime()
                    val samples = AlignmentHorizon.sector(Observer(spot, ground), terrain, heading, hfov / 2 + 25, pack.radiusM, .1)
                    horizonMs += (System.nanoTime() - t0) / 1_000_000
                    val predicted = TerrainSkyline(samples)
                    // Observed outline: true far-field skyline projected through the true pose, with noise and gaps.
                    val points = ArrayList<BoundaryPoint>()
                    var bearing = heading - hfov / 2 - 5
                    while (bearing < heading + hfov / 2 + 5) {
                        val e = predicted.elevation(Geography.wrap360(bearing))
                        val p = e?.let { CameraProjector.project(bearing, it, truth, lens) }
                        if (p != null && p.x in 2f..(w - 2f) && p.y in 2f..(h - 2f) && random.nextDouble() > .1)
                            points += BoundaryPoint(p.x + random.nextDouble(-1.0, 1.0), p.y + random.nextDouble(-1.0, 1.0))
                        bearing += hfov / 160
                    }
                    // A foreground object (tree/building) raises the outline over a random tenth of the frame.
                    val blob = random.nextInt(0, max(1, points.size))
                    val raised = points.mapIndexed { i, p -> if (i in blob until blob + points.size / 10) p.copy(y = p.y - 25) else p }
                    val sensor = truth.copy(heading = truth.heading + random.nextDouble(-15.0, 15.0), pitch = truth.pitch + random.nextDouble(-4.0, 4.0))
                    val t1 = System.nanoTime()
                    val result = if (raised.size >= 40) SkylineMatcher.match(raised, lens, sensor, predicted) else MatchResult(null, MatchFailure.INSUFFICIENT_SHAPE)
                    matchMs += (System.nanoTime() - t1) / 1_000_000
                    val m = result.match
                    if (m == null) { failures.merge(result.failure!!, 1, Int::plus); continue }
                    accepted++
                    val corrected = m.correction.apply(sensor)
                    val error = abs(Geography.wrap180(corrected.heading - truth.heading))
                    errors += error
                    if (error > 1.0 || abs(corrected.pitch - truth.pitch) > 1.0) wrong++
                }
                errors.sort()
                println("REAL MATCH $id: trials=$trials accepted=$accepted wrong=$wrong refused=$failures " +
                    "headingErr median=${errors.getOrNull(errors.size / 2)?.let { "%.3f".format(it) }}° p90=${errors.getOrNull(errors.size * 9 / 10)?.let { "%.3f".format(it) }}° " +
                    "horizon=${horizonMs / trials} ms/view match=${matchMs / trials} ms/view (desktop JVM)")
                totalWrong += wrong
            }
        }
        assertEquals("a wrong correction must never be accepted", 0, totalWrong)
    }
}
