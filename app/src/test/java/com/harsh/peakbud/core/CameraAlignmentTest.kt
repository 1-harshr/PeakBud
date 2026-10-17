package com.harsh.peakbud.core

import com.harsh.peakbud.core.alignment.*
import com.harsh.peakbud.core.model.HorizonSample
import com.harsh.peakbud.core.projection.*
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

class CameraAlignmentTest {
    private val lens = CameraLens(420.0, 420.0, 320.0, 240.0)
    private fun mountain(b: Double): Double = 5 + 2.5 * sin(b * .19) + 1.6 * sin(b * .47) + .9 * cos(b * .83)
    private fun terrain(shape: (Double) -> Double = ::mountain) = TerrainSkyline(List(3600) { i ->
        HorizonSample(i * .1, shape(i * .1), 30_000.0, true)
    })
    private fun observed(pose: CameraPose, shape: (Double) -> Double = ::mountain): List<BoundaryPoint> =
        (0..1000).mapNotNull { i ->
            val b = pose.heading - 45 + i * .09
            CameraProjector.project(b, shape((b + 360) % 360), pose, lens)?.takeIf { it.x in 10f..630f && it.y in 10f..470f }
                ?.let { BoundaryPoint(it.x.toDouble(), it.y.toDouble()) }
        }.filterIndexed { i, _ -> i % 4 == 0 }

    @Test fun inverseRaysRoundTripWithRollAndOffCenterCrop() {
        for (roll in listOf(-35.0, 0.0, 35.0)) for (heading in listOf(1.0, 179.0, 359.0)) {
            val pose = CameraPose(heading, 12.0, roll)
            val crop = CameraLens(712.0, 701.0, 212.0, 477.0)
            val projected = CameraProjector.project(heading + 10, 18.0, pose, crop)!!
            val ray = CameraRays.world(projected.x.toDouble(), projected.y.toDouble(), crop, pose)
            assertEquals((heading + 10) % 360, ray.bearing, .00001)
            assertEquals(18.0, ray.elevation, .00001)
        }
    }
    @Test fun recoversYawPitchAndRollWithoutChangingSensors() {
        for (roll in listOf(-17.0, 0.0, 23.0)) {
            val truth = CameraPose(81.3, 6.0, roll)
            val sensor = CameraPose(75.0, 8.0, roll)
            val result = SkylineMatcher.match(observed(truth), lens, sensor, terrain())
            assertNotNull("$roll: $result", result.match)
            assertEquals(6.3, result.match!!.correction.heading, .15)
            assertEquals(-2.0, result.match!!.correction.pitch, .15)
            assertEquals(75.0, sensor.heading, 0.0)
        }
    }
    @Test fun rejectsRepeatedTerrainEvenWithPerfectResidual() {
        val repeating: (Double) -> Double = { 5 + 2 * sin(Math.toRadians(it * 36)) }
        val result = SkylineMatcher.match(observed(CameraPose(80.0, 4.0, 0.0), repeating), lens, CameraPose(80.0, 4.0, 0.0), terrain(repeating))
        assertNull(result.match)
        assertEquals(MatchFailure.AMBIGUOUS, result.failure)
    }
    @Test fun rejectsFlatGroundMissingTerrainAndOutOfSearchHeading() {
        val truth = CameraPose(81.3, 6.0, 0.0)
        assertNull(SkylineMatcher.match(observed(truth), lens, truth, terrain { 5.0 }).match)
        assertNull(SkylineMatcher.match(observed(truth), lens, truth.copy(pitch = -80.0), terrain()).match)
        assertNull(SkylineMatcher.match(observed(truth), lens, truth, TerrainSkyline(emptyList())).match)
        assertNull(SkylineMatcher.match(observed(truth), lens, truth.copy(heading = 180.0), terrain()).match)
    }
    @Test fun toleratesSomeOccludedColumnsButNotAMajority() {
        val truth = CameraPose(81.3, 6.0, 0.0)
        val points = observed(truth)
        fun obstruct(fraction: Double) = points.mapIndexed { i, p -> if (i < points.size * fraction) p.copy(y = p.y - 60) else p }
        assertNotNull(SkylineMatcher.match(obstruct(.15), lens, truth, terrain()).match)
        assertNull(SkylineMatcher.match(obstruct(.6), lens, truth, terrain()).match)
    }
    @Test fun incompleteInterpolationNeverInventsDataIncludingNorthWrap() {
        val samples = List(720) { i -> HorizonSample(i * .5, 5.0, 1000.0, i != 0) }
        val profile = TerrainSkyline(samples)
        assertNull(profile.elevation(359.9)); assertNull(profile.elevation(.1))
        assertEquals(5.0, profile.elevation(10.0)!!, 0.0)
    }
    @Test fun alignmentNeedsTemporalEvidenceExpiresAndDoesNotJump() {
        val session = AlignmentSession()
        val match = SkylineMatch(AlignmentCorrection(3.0, -1.0), .1, .95, 1.0)
        session.start(1000)
        assertEquals(AlignmentPhase.ALIGNING, session.observe(match, 1100).phase)
        assertEquals(AlignmentPhase.ALIGNING, session.observe(match, 1400).phase)
        assertEquals(AlignmentPhase.ALIGNED, session.observe(match, 1700).phase)
        assertEquals(AlignmentPhase.LOST, session.tick(3800).phase)
        assertNull(session.state.correction)
        session.start(4000)
        for (t in listOf(4100L, 4400L, 4700L)) session.observe(match, t)
        assertEquals(AlignmentPhase.LOST, session.observe(match.copy(correction = AlignmentCorrection(9.0, 0.0)), 5000).phase)
        assertEquals(AlignmentPhase.SENSOR, session.reset().phase)
        session.start(6000)
        assertEquals(AlignmentPhase.LOST, session.tick(16001).phase)
    }
    @Test fun threeVisualMissesRevokeAcceptedCorrection() {
        val session = AlignmentSession()
        val match = SkylineMatch(AlignmentCorrection(3.0, -1.0), .1, .95, 1.0)
        session.start(0)
        for (t in listOf(100L, 400L, 700L)) session.observe(match, t)
        session.observe(null, 1000); session.observe(null, 1300)
        assertEquals(AlignmentPhase.ALIGNED, session.state.phase)
        assertEquals(AlignmentPhase.LOST, session.observe(null, 1600).phase)
    }
}
