package com.harsh.peakbud.core

import com.harsh.peakbud.core.alignment.*
import com.harsh.peakbud.core.geo.Geography
import com.harsh.peakbud.core.model.*
import com.harsh.peakbud.core.projection.*
import com.harsh.peakbud.data.regions.RegionPack
import com.harsh.peakbud.feature.camera.CameraAlignmentController
import java.io.File
import kotlin.math.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

/**
 * End to end through the controller with rendered frames: extraction, frame-to-preview mapping, matching and the
 * session together. The rendered camera sees the true pose; the "sensor" is 7° and 2° off.
 */
class CameraAlignmentFlowTest {
    private val viewW = 640; private val viewH = 480
    private val lens = CameraLens(viewW / 2.0 / tan(Math.toRadians(33.0)), viewW / 2.0 / tan(Math.toRadians(33.0)), viewW / 2.0, viewH / 2.0)
    private fun skyline(bearing: Double) = 4 + 2.2 * sin(Math.toRadians(bearing * 7)) + 1.3 * sin(Math.toRadians(bearing * 17 + 40)) + .6 * cos(Math.toRadians(bearing * 41))
    private val pack = RegionPack(File("unused"), "test", "Test", "1", GeoBounds(0.0, 0.0, 1.0, 1.0), GeoBounds(0.0, 0.0, 1.0, 1.0),
        50_000.0, GeoPoint(.5, .5), 0.0, "test", emptyList())

    /** Render the view a camera at [truth] sees: graded blue sky above the skyline, textured rock below. */
    private fun mountainFrame(truth: CameraPose, foregroundColumns: IntRange? = null): UprightFrame =
        UprightFrame.sample(0, 0, viewW, viewH, 0, 320) { x, y ->
            val ray = CameraRays.world(x.toDouble(), y.toDouble(), lens, truth)
            val covered = foregroundColumns != null && x in foregroundColumns && y > viewH * .3
            if (!covered && ray.elevation > skyline(ray.bearing)) {
                val lift = (y * 60 / viewH)
                (0xff shl 24) or ((90 + lift) shl 16) or ((150 + lift) shl 8) or 225
            } else {
                val noise = ((x * 73856093) xor (y * 19349663)).mod(40)
                (0xff shl 24) or ((95 + noise) shl 16) or ((80 + noise) shl 8) or (60 + noise)
            }
        }
    private fun groundFrame() = UprightFrame.sample(0, 0, viewW, viewH, 0, 320) { x, y -> (0xff shl 24) or ((80 + (x * y).mod(30)) shl 16) or (70 shl 8) or 50 }

    private fun controller(now: () -> Long) = CameraAlignmentController(CoroutineScope(Dispatchers.Default), Dispatchers.Default, now,
        skylineSource = { _, heading, half, _ -> List((2 * half / .1).toInt() + 1) { i -> (heading - half + i * .1).let { b -> HorizonSample(Geography.wrap360(b), skyline(b), 30_000.0, true) } } })
    private val identity: (Double, Double) -> Pair<Double, Double>? = { x, y -> if (x in 0.0..viewW.toDouble() && y in 0.0..viewH.toDouble()) x to y else null }

    private fun awaitPrepared(c: CameraAlignmentController) = runBlocking { withTimeout(5_000) { while (c.ui.value.preparing) delay(10) } }

    @Test fun alignsFromWrongSensorHeadingThenLosesAlignmentWhenPointedAtTheGround() {
        var clock = 1_000L
        val c = controller { clock }
        val truth = CameraPose(120.0, 3.0, 4.0)
        val sensor = truth.copy(heading = 113.0, pitch = 5.0)
        c.start(CameraAlignmentController.Target(pack, pack.viewpoint, 1000.0, 1.6, 66.0), sensor, null)
        awaitPrepared(c)
        repeat(3) { clock += 350; assertTrue(c.wantsFrame()); c.process(mountainFrame(truth), identity, lens, sensor, emptyList()) }
        val aligned = c.ui.value
        assertEquals("$aligned", AlignmentPhase.ALIGNED, aligned.phase)
        val corrected = aligned.pose(sensor)!!
        assertEquals(truth.heading, corrected.heading, .3)
        assertEquals(truth.pitch, corrected.pitch, .3)

        // Pointing at the ground: no sky, no match. Three misses revoke the accepted correction.
        repeat(3) { clock += 350; assertTrue(c.wantsFrame()); c.process(groundFrame(), identity, lens, sensor, emptyList()) }
        assertEquals(AlignmentPhase.LOST, c.ui.value.phase)
        assertNull(c.ui.value.pose(sensor)?.takeIf { c.ui.value.aligned })
        c.reset()
    }

    @Test fun groundOnlyViewNeverAlignsAndExplainsWhy() {
        var clock = 1_000L
        val c = controller { clock }
        val sensor = CameraPose(120.0, -40.0, 0.0)
        c.start(CameraAlignmentController.Target(pack, pack.viewpoint, 1000.0, 1.6, 66.0), sensor, null)
        awaitPrepared(c)
        repeat(10) { clock += 350; if (c.wantsFrame()) c.process(groundFrame(), identity, lens, sensor, emptyList()) }
        clock += 13_000
        runBlocking { withTimeout(3_000) { while (c.ui.value.phase == AlignmentPhase.ALIGNING) delay(50) } }
        assertEquals(AlignmentPhase.FAILED, c.ui.value.phase)
        assertEquals(AlignmentProblem.NO_CLEAR_SKY, c.ui.value.problem)
        c.reset()
    }

    @Test fun foregroundCoveringASummitHidesItsLabelAndResetDiscardsAlignment() {
        var clock = 1_000L
        val c = controller { clock }
        val truth = CameraPose(200.0, 3.0, 0.0)
        // A peak just below the skyline at bearing 185°, which a tree on the left of the frame covers.
        val bearing = 185.0
        val peak = PeakView(Peak("p", "Hidden Summit", GeoPoint(.5, .5), 3000.0, "test"), 20_000.0, bearing, skyline(bearing) - .2, 3000.0,
            Visibility.TERRAIN_VISIBLE, reason = PeakReason.VISIBLE)
        val x = CameraProjector.project(bearing, skyline(bearing), truth, lens)!!.x.toInt()
        c.start(CameraAlignmentController.Target(pack, pack.viewpoint, 1000.0, 1.6, 66.0), truth.copy(heading = 196.0), null)
        awaitPrepared(c)
        repeat(4) { clock += 350; c.wantsFrame(); c.process(mountainFrame(truth, (x - 30)..(x + 30)), identity, lens, truth.copy(heading = 196.0), listOf(peak)) }
        assertEquals(AlignmentPhase.ALIGNED, c.ui.value.phase)
        assertEquals(setOf("p"), c.ui.value.hiddenByForeground)
        c.reset()
        assertEquals(AlignmentPhase.SENSOR, c.ui.value.phase)
        assertFalse(c.wantsFrame())
    }
}
