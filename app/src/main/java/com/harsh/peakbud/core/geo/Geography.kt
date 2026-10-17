package com.harsh.peakbud.core.geo

import com.harsh.peakbud.core.model.GeoPoint
import kotlin.math.*

/** Spherical geometry for the bounded prototype. All external angles are degrees. */
object Geography {
    const val EARTH_RADIUS_M = 6_371_008.8
    fun wrap360(angle: Double): Double = ((angle % 360) + 360) % 360
    fun wrap180(angle: Double): Double = wrap360(angle + 180) - 180
    fun distance(a: GeoPoint, b: GeoPoint): Double {
        val p = Math.toRadians(a.latitude)
        val q = Math.toRadians(b.latitude)
        val dLat = q - p
        val dLon = Math.toRadians(b.longitude - a.longitude)
        val h = sin(dLat / 2).pow(2) + cos(p) * cos(q) * sin(dLon / 2).pow(2)
        return 2 * EARTH_RADIUS_M * asin(sqrt(h.coerceIn(0.0, 1.0)))
    }
    fun bearing(a: GeoPoint, b: GeoPoint): Double {
        val p = Math.toRadians(a.latitude)
        val q = Math.toRadians(b.latitude)
        val dLon = Math.toRadians(b.longitude - a.longitude)
        return wrap360(Math.toDegrees(atan2(sin(dLon) * cos(q), cos(p) * sin(q) - sin(p) * cos(q) * cos(dLon))))
    }
    fun destination(origin: GeoPoint, bearingDeg: Double, distanceM: Double): GeoPoint {
        val p = Math.toRadians(origin.latitude)
        val l = Math.toRadians(origin.longitude)
        val b = Math.toRadians(bearingDeg)
        val d = distanceM / EARTH_RADIUS_M
        val q = asin((sin(p) * cos(d) + cos(p) * sin(d) * cos(b)).coerceIn(-1.0, 1.0))
        val lon = l + atan2(sin(b) * sin(d) * cos(p), cos(d) - sin(p) * sin(q))
        return GeoPoint(Math.toDegrees(q), wrap180(Math.toDegrees(lon)))
    }
    /** Conservative search rectangle for supported regional packs, away from the poles/dateline. */
    fun viewingBounds(origin: GeoPoint, radiusM: Double): com.harsh.peakbud.core.model.GeoBounds {
        val angular = Math.toDegrees(radiusM / EARTH_RADIUS_M) + 1.0 / 3600
        val longitudeMargin = angular / cos(Math.toRadians(abs(origin.latitude) + angular))
        return com.harsh.peakbud.core.model.GeoBounds(origin.latitude - angular, origin.longitude - longitudeMargin,
            origin.latitude + angular, origin.longitude + longitudeMargin)
    }
    /** Spherical line of sight, including curvature once; no assumed atmospheric refraction. */
    fun elevationAngle(observerHeightM: Double, targetHeightM: Double, distanceM: Double): Double {
        val arc = distanceM / EARTH_RADIUS_M
        val targetRadius = EARTH_RADIUS_M + targetHeightM
        return Math.toDegrees(atan2(targetRadius * cos(arc) - (EARTH_RADIUS_M + observerHeightM), targetRadius * sin(arc)))
    }
}
