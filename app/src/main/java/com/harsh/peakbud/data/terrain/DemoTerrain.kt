package com.harsh.peakbud.data.terrain

import com.harsh.peakbud.core.geo.Geography
import com.harsh.peakbud.core.model.*
import kotlin.math.*

/** Deliberately fictional geography for exploring the app without a downloaded region. */
class DemoTerrain : Terrain {
    override val datum = VerticalDatum.SYNTHETIC
    override val resolutionM = 60.0
    override val heightErrorM = 2.0
    override fun sample(point: GeoPoint): TerrainSample {
        val distance = Geography.distance(VIEWPOINT, point)
        if (distance > 52_000) return TerrainSample.OutsideCoverage
        val bearing = Geography.bearing(VIEWPOINT, point)
        var height = 1800.0 + 120 * sin(distance / 1500) * (1 - exp(-distance / 2000))
        for (mountain in mountains) {
            val dx = Geography.wrap180(bearing - mountain.bearing) * Math.PI / 180 * max(distance, 100.0)
            val dy = distance - mountain.distance
            height += mountain.height * exp(-(dx * dx + dy * dy) / (2 * mountain.width.pow(2)))
        }
        return TerrainSample.Valid(height)
    }
    private data class Mountain(val bearing: Double, val distance: Double, val height: Double, val width: Double)
    companion object {
        val VIEWPOINT = GeoPoint(28.4, 83.69)
        private val mountains = listOf(
            Mountain(310.0, 25000.0, 3400.0, 2700.0), Mountain(333.0, 33000.0, 4400.0, 3300.0),
            Mountain(2.0, 29000.0, 3900.0, 2400.0), Mountain(25.0, 22000.0, 2900.0, 2400.0),
            Mountain(51.0, 38000.0, 4100.0, 3200.0), Mountain(94.0, 18000.0, 1500.0, 3000.0),
            Mountain(155.0, 30000.0, 2200.0, 4500.0), Mountain(225.0, 18000.0, 1800.0, 3500.0),
        )
        val peaks: List<Peak> = mountains.mapIndexed { i, m ->
            Peak("demo-$i", listOf("West Sentinel", "Silver Crown", "North Spire", "Twin Ridge", "Dawn Summit", "Pine Crest", "South Lookout", "Cloud Ridge")[i],
                Geography.destination(VIEWPOINT, m.bearing, m.distance), 1800.0 + m.height, "Fictional demo")
        }
    }
}
