package com.harsh.peakbud.core

import com.harsh.peakbud.core.model.*
import com.harsh.peakbud.core.terrain.TerrainEngine
import com.harsh.peakbud.data.terrain.DemoTerrain
import org.junit.Assert.*
import org.junit.Test

class ProgressiveSceneTest {
    @Test fun partialScenesGrowNearestFirstAndTheFinalSceneIsUnchanged() {
        val observer = Observer(DemoTerrain.VIEWPOINT, (DemoTerrain().sample(DemoTerrain.VIEWPOINT) as TerrainSample.Valid).heightM, datum = VerticalDatum.SYNTHETIC)
        val plain = DemoTerrain().use { TerrainEngine().calculate(observer, it, DemoTerrain.peaks) }
        val partials = ArrayList<MountainScene>()
        val progressive = DemoTerrain().use { TerrainEngine().calculate(observer, it, DemoTerrain.peaks, onProgress = { partials += it }, progressMs = 0) }
        assertEquals(plain, progressive)
        assertTrue(partials.isNotEmpty())
        var previous = emptySet<String>()
        for (partial in partials) {
            assertEquals(plain.horizon, partial.horizon)
            val ids = partial.peaks.map { it.peak.id }.toSet()
            assertTrue("partial scenes only grow", ids.containsAll(previous))
            assertTrue("every partial result matches the final one", partial.peaks.all { it in plain.peaks })
            // Everything already finished is no farther than anything still missing.
            val missing = plain.peaks.filter { it.peak.id !in ids }
            if (missing.isNotEmpty() && partial.peaks.isNotEmpty()) assertTrue(partial.peaks.maxOf { it.distanceM } <= missing.minOf { it.distanceM })
            previous = ids
        }
    }
}
