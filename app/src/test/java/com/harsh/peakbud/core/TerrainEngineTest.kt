package com.harsh.peakbud.core

import com.harsh.peakbud.core.geo.Geography
import com.harsh.peakbud.core.model.*
import com.harsh.peakbud.core.projection.PanoramaProjector
import com.harsh.peakbud.core.terrain.TerrainEngine
import com.harsh.peakbud.data.terrain.HgtTerrain
import com.harsh.peakbud.data.terrain.DemoTerrain
import org.junit.Assert.*
import org.junit.Test
import java.io.RandomAccessFile
import java.nio.file.Files
import kotlin.math.abs

class TerrainEngineTest {
    private val origin = GeoPoint(28.4, 83.69)
    private val observer = Observer(origin, 1000.0, heightUncertaintyM = 0.0)
    private val peak = Peak("test", "Test summit", Geography.destination(origin, 0.0, 10_000.0), 2000.0, "Test")
    private fun terrain(ridge: Boolean = false, missing: Boolean = false): Terrain = object : Terrain {
        override val datum = VerticalDatum.EGM96
        override val resolutionM = 30.0
        override val heightErrorM = 0.0
        override fun sample(point: GeoPoint): TerrainSample {
            val d = Geography.distance(origin, point)
            return when {
                missing && d in 3000.0..3500.0 -> TerrainSample.TileMissing
                ridge && d in 4500.0..5500.0 -> TerrainSample.Valid(2500.0)
                d in 9990.0..10010.0 -> TerrainSample.Valid(2000.0)
                d in 14000.0..16000.0 -> TerrainSample.Valid(6000.0)
                else -> TerrainSample.Valid(1000.0)
            }
        }
    }

    @Test fun destinationAndBearingRoundTripAcrossDateline() {
        val start = GeoPoint(-20.0, 179.99)
        val end = Geography.destination(start, 92.0, 25_000.0)
        assertTrue(end.longitude < 0)
        assertEquals(25_000.0, Geography.distance(start, end), 0.001)
        assertEquals(92.0, Geography.bearing(start, end), 0.001)
    }
    @Test fun panoramaWrapsNorthAndPositionsEastToRight() {
        val p = PanoramaProjector.project(1.0, 0.0, 359.0, 0.0, 1000f, 500f)
        assertEquals(520f, p.x, 0.001f)
    }
    @Test fun curvatureLowersADistantEqualHeightTarget() {
        assertTrue(Geography.elevationAngle(1000.0, 1000.0, 50_000.0) < -0.2)
    }
    @Test fun mountainBehindSummitDoesNotOccludeIt() {
        val view = TerrainEngine().visibility(observer, peak, terrain())
        assertEquals(Visibility.TERRAIN_VISIBLE, view.visibility)
    }
    @Test fun nearerRidgeOccludesFarSummitEvenWithMissingData() {
        assertEquals(Visibility.OCCLUDED, TerrainEngine().visibility(observer, peak, terrain(ridge = true, missing = true)).visibility)
    }
    @Test fun missingInterveningTerrainNeverClaimsVisibility() {
        assertEquals(Visibility.UNKNOWN, TerrainEngine().visibility(observer, peak, terrain(missing = true)).visibility)
    }
    @Test fun precisePeakRayFindsARidgeBetweenDraftBearings() {
        val target = peak.copy(point = Geography.destination(origin, 0.25, 10_000.0))
        val obstruction = Geography.destination(origin, 0.25, 5000.0)
        val narrow = object : Terrain {
            override val datum = VerticalDatum.EGM96
            override val resolutionM = 30.0
            override val heightErrorM = 0.0
            override fun sample(point: GeoPoint) = TerrainSample.Valid(when {
                Geography.distance(point, obstruction) < 10 -> 2500.0
                Geography.distance(point, target.point) < 10 -> 2000.0
                else -> 1000.0
            })
        }
        assertEquals(Visibility.OCCLUDED, TerrainEngine().visibility(observer, target, narrow).visibility)
    }
    // The ridge starts 300 m before the summit: outside the 250 m treated as the summit's own flank.
    @Test fun nearTangentSummitRemainsUncertain() {
        val tangent = object : Terrain {
            override val datum = VerticalDatum.EGM96
            override val resolutionM = 30.0
            override val heightErrorM = 0.0
            override fun sample(point: GeoPoint) = TerrainSample.Valid(Geography.distance(origin, point).let { d ->
                // Ridge 300 m in front of the summit, just under the sight line (2000 m summit at 10 km, 1970 m ridge at 9.7 km).
                if (d >= 9_995) 2000.0 else if (d >= 9_700) 1970.0 else 1000.0 })
        }
        assertEquals(Visibility.UNCERTAIN, TerrainEngine().visibility(observer, peak, tangent).visibility)
    }
    @Test fun conflictingPublishedHeightDoesNotInventATallerSummit() {
        val view = TerrainEngine().visibility(observer, peak.copy(publishedHeightM = 8000.0), terrain())
        assertEquals(Visibility.UNCERTAIN, view.visibility)
        assertEquals(2000.0, view.geometryHeightM!!, 0.0)
        assertEquals(PeakReason.HEIGHT_MISMATCH, view.reason)
        assertEquals(Geography.elevationAngle(observer.heightM, 2000.0, view.distanceM), view.elevationDeg!!, 0.0001)
    }
    @Test fun conflictingCatalogueHeightCannotMakeABlockedPeakDrawable() {
        val view = TerrainEngine().visibility(observer, peak.copy(publishedHeightM = 8000.0), terrain(ridge = true))
        assertEquals(Visibility.OCCLUDED, view.visibility)
        assertEquals(PeakReason.OCCLUDED, view.reason)
        assertEquals(6000, view.heightDifferenceM)
    }
    @Test fun smallPublishedHeightDifferencesDoNotHideClearTerrain() {
        assertEquals(Visibility.TERRAIN_VISIBLE, TerrainEngine().visibility(observer, peak.copy(publishedHeightM = 2030.0), terrain()).visibility)
    }
    @Test fun mismatchedHeightDatumsAreRejected() {
        try {
            TerrainEngine().visibility(observer.copy(datum = VerticalDatum.EGM2008), peak, terrain())
            fail("Expected datum rejection")
        } catch (_: IllegalArgumentException) { }
    }
    @Test fun calculationsHonourCancellationCheckpoints() {
        var checks = 0
        try {
            TerrainEngine().calculate(observer, terrain(), listOf(peak)) { if (++checks > 3) throw InterruptedException() }
            fail("Expected cancellation")
        } catch (_: InterruptedException) { assertEquals(4, checks) }
    }
    @Test fun missingSummitIsUnknown() {
        val missing = object : Terrain {
            override val datum = VerticalDatum.EGM96
            override val resolutionM = 30.0
            override val heightErrorM = 0.0
            override fun sample(point: GeoPoint) = TerrainSample.NoData
        }
        assertEquals(Visibility.UNKNOWN, TerrainEngine().visibility(observer, peak, missing).visibility)
    }
    @Test fun hgtReadsBigEndianInterpolatesAndPreservesNoData() {
        val dir = Files.createTempDirectory("peakbud-hgt").toFile()
        try {
            val file = dir.resolve("S01W001.hgt")
            RandomAccessFile(file, "rw").use { out ->
                out.setLength(3601L * 3601 * 2)
                fun set(row: Int, col: Int, value: Int) { out.seek((row.toLong() * 3601 + col) * 2); out.writeShort(value) }
                set(1800, 1800, 100); set(1800, 1801, 200)
                set(1801, 1800, 300); set(1801, 1801, 400)
                set(2000, 2000, -32768)
                set(3600, 3600, 925)
            }
            HgtTerrain(dir, GeoBounds(-1.0, -1.0, 0.0, 0.0)).use { hgt ->
                val point = GeoPoint(-1800.5 / 3600, -1 + 1800.5 / 3600)
                assertEquals(250.0, (hgt.sample(point) as TerrainSample.Valid).heightM, 0.0001)
                assertEquals(TerrainSample.NoData, hgt.sample(GeoPoint(-2000.0 / 3600, -1 + 2000.0 / 3600)))
                assertEquals(0.0, (hgt.sample(GeoPoint(-0.2, -0.2)) as TerrainSample.Valid).heightM, 0.0)
                assertEquals(TerrainSample.OutsideCoverage, hgt.sample(GeoPoint(1.0, 1.0)))
                // Shared north/east edge can be served by the southwest tile.
                assertEquals(TerrainSample.Valid(0.0), hgt.sample(GeoPoint(0.0, 0.0)))
                // The shortened final block spans the last row/column without reading past EOF.
                assertEquals(TerrainSample.Valid(925.0), hgt.sample(GeoPoint(-1.0, 0.0)))
            }
            assertEquals("S01W001.hgt", HgtTerrain.tileName(-1, -1))
        } finally { dir.deleteRecursively() }
    }
    @Test fun demoProducesACompletePanoramaAndNamedCandidates() {
        DemoTerrain().use { terrain ->
            val ground = (terrain.sample(DemoTerrain.VIEWPOINT) as TerrainSample.Valid).heightM
            val scene = TerrainEngine().calculate(Observer(DemoTerrain.VIEWPOINT, ground, datum = VerticalDatum.SYNTHETIC, heightUncertaintyM = 0.0), terrain, DemoTerrain.peaks)
            assertEquals(720, scene.horizon.size)
            assertTrue(scene.horizon.all { it.complete && it.elevationDeg?.isFinite() == true })
            assertEquals(8, scene.peaks.size)
            assertTrue(scene.peaks.any { it.visibility == Visibility.TERRAIN_VISIBLE || it.visibility == Visibility.UNCERTAIN })
            assertTrue(scene.peaks.all { it.peak.source == "Fictional demo" })
        }
    }
}
