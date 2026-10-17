package com.harsh.peakbud.core

import com.harsh.peakbud.core.model.*
import com.harsh.peakbud.core.terrain.TerrainEngine
import com.harsh.peakbud.data.regions.RegionStore
import com.harsh.peakbud.data.terrain.HgtTerrain
import java.io.File
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Opt-in real-pack benchmark. No network, installs, or hardware-dependent timing assertions. */
class TerrainPerformanceTest {
    @Test fun reportFullSceneLatencyAndRequireIdenticalResultsAcrossColdApplicationCaches() {
        val installed = System.getenv("PEAKBUD_TERRAIN_BENCH_DIR")
        assumeTrue("Set PEAKBUD_TERRAIN_BENCH_DIR to the installed-region directory", installed != null)
        val store = RegionStore(File(installed!!))
        val packs = store.installed().filter { it.id in setOf("jharkhand", "uttarakhand-himalaya") }
        assertEquals("Both real regional packs are required", 2, packs.size)
        for (pack in packs.sortedBy { it.id }) {
            val peaks = store.peaks(pack)
            var baseline: MountainScene? = null
            var baselineCalls: Long? = null
            repeat(3) { iteration ->
                HgtTerrain(pack.directory.resolve("terrain"), pack.bounds).use { terrain ->
                    var calls = 0L
                    val counted = object : Terrain by terrain {
                        override fun sample(point: GeoPoint): TerrainSample {
                            calls++
                            return terrain.sample(point)
                        }
                    }
                    val ground = (terrain.sample(pack.viewpoint) as TerrainSample.Valid).heightM
                    val start = System.nanoTime()
                    val scene = TerrainEngine().calculate(Observer(pack.viewpoint, ground), counted, peaks, CalculationSettings(pack.radiusM))
                    val seconds = (System.nanoTime() - start) / 1e9
                    println("TERRAIN BENCH ${pack.id} run=${iteration + 1} seconds=$seconds samples=$calls peaks=${scene.peaks.size}")
                    assertEquals(720, scene.horizon.size)
                    assertTrue(scene.peaks.any { it.distanceM > 50_000 })
                    if (baseline == null) { baseline = scene; baselineCalls = calls }
                    else { assertEquals(baseline, scene); assertEquals(baselineCalls, calls) }
                }
            }
        }
    }
}
