package com.harsh.peakbud.core.alignment

import com.harsh.peakbud.core.geo.Geography
import com.harsh.peakbud.core.model.*
import kotlin.math.*

/**
 * Skyline prediction used for camera registration, built only from the phone's position and the downloaded terrain.
 *
 * It differs from the visibility engine's full horizon in three deliberate ways:
 * - Terrain closer than [nearLimitM] is excluded. A few metres of GPS error or a 30 m terrain cell next to the phone
 *   swings the near-field angle by many degrees, so near ground cannot be predicted reliably. Bearings where that near
 *   ground would rise above the far skyline are marked incomplete: the camera sees foreground there, and the matcher must
 *   not use those columns.
 * - Standard atmospheric refraction (k = 0.13) is applied, which lifts a ridge 150 km away by about 0.1°.
 * - Only the sector the camera faces is traced, at a fine bearing step, so it is quick enough to rebuild when the
 *   phone turns to a new direction.
 */
object AlignmentHorizon {
    private const val REFRACTION = 0.13

    fun sector(observer: Observer, terrain: Terrain, centerDeg: Double, halfWidthDeg: Double, radiusM: Double,
               stepDeg: Double = .1, nearLimitM: Double = 1_000.0, checkpoint: () -> Unit = {}): List<HorizonSample> {
        require(halfWidthDeg in 1.0..180.0 && stepDeg in .02..1.0 && radiusM > nearLimitM)
        val count = (2 * halfWidthDeg / stepDeg).roundToInt() + 1
        val effectiveRadius = Geography.EARTH_RADIUS_M / (1 - REFRACTION)
        val lat0 = Math.toRadians(observer.point.latitude); val lon0 = Math.toRadians(observer.point.longitude)
        return List(count) { index ->
            checkpoint()
            val bearing = Geography.wrap360(centerDeg - halfWidthDeg + index * stepDeg)
            val sinB = sin(Math.toRadians(bearing)); val cosB = cos(Math.toRadians(bearing))
            var far = Double.NEGATIVE_INFINITY; var farDistance: Double? = null
            var near = Double.NEGATIVE_INFINITY
            var complete = true
            var d = terrain.resolutionM / 2
            while (d <= radiusM) {
                val arc = d / Geography.EARTH_RADIUS_M
                val lat = asin((sin(lat0) * cos(arc) + cos(lat0) * sin(arc) * cosB).coerceIn(-1.0, 1.0))
                val lon = lon0 + atan2(sinB * sin(arc) * cos(lat0), cos(arc) - sin(lat0) * sin(lat))
                when (val sample = terrain.sample(GeoPoint(Math.toDegrees(lat), Geography.wrap180(Math.toDegrees(lon))))) {
                    is TerrainSample.Valid -> {
                        // Height relative to the observer after curvature drop on the refraction-enlarged Earth.
                        val angle = atan2(sample.heightM - observer.heightM - d * d / (2 * effectiveRadius), d)
                        if (d < nearLimitM) near = max(near, angle)
                        else if (angle > far) { far = angle; farDistance = d }
                    }
                    else -> if (d >= nearLimitM) complete = false
                }
                // Fine steps nearby; beyond 30 km a 0.5% step keeps a crest's angular error well under the 0.2°
                // a phone analysis pixel covers, at roughly half the samples.
                d += max(terrain.resolutionM, d * if (d < 30_000) .003 else .005)
            }
            val usable = complete && far.isFinite() && near <= far
            HorizonSample(bearing, far.takeIf { it.isFinite() }?.let { Math.toDegrees(it) }, farDistance, usable)
        }
    }
}
