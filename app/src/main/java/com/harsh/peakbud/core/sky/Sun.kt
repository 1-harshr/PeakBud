package com.harsh.peakbud.core.sky

import com.harsh.peakbud.core.geo.Geography
import com.harsh.peakbud.core.model.GeoPoint
import kotlin.math.*

/** Where the sun is, as seen from the ground: true-north azimuth and apparent elevation (with refraction), degrees. */
data class SunPosition(val azimuthDeg: Double, val elevationDeg: Double)

/** The sun next disappears behind ([SET]) or clears ([RISE]) the terrain skyline at [atMs]. */
data class SunEvent(val kind: Kind, val atMs: Long) { enum class Kind { SET, RISE } }

/** Offline solar position (NOAA equations, about 0.01° over this century) and terrain-aware sunset and sunrise. */
object Sun {
    private const val SEMI_DIAMETER = .27

    fun position(point: GeoPoint, epochMs: Long): SunPosition {
        val jd = epochMs / 86_400_000.0 + 2_440_587.5
        val t = (jd - 2_451_545.0) / 36_525
        val l0 = Geography.wrap360(280.46646 + t * (36_000.76983 + t * .0003032))
        val m = 357.52911 + t * (35_999.05029 - .0001537 * t)
        val e = .016708634 - t * (.000042037 + .0000001267 * t)
        val mr = Math.toRadians(m)
        val c = sin(mr) * (1.914602 - t * (.004817 + .000014 * t)) + sin(2 * mr) * (.019993 - .000101 * t) + sin(3 * mr) * .000289
        val omega = Math.toRadians(125.04 - 1_934.136 * t)
        val lambda = Math.toRadians(l0 + c - .00569 - .00478 * sin(omega))
        val eps0 = 23 + (26 + (21.448 - t * (46.815 + t * (.00059 - t * .001813))) / 60) / 60
        val eps = Math.toRadians(eps0 + .00256 * cos(omega))
        val declination = asin(sin(eps) * sin(lambda))
        val y = tan(eps / 2).pow(2); val l0r = Math.toRadians(l0)
        val equationOfTimeMin = 4 * Math.toDegrees(y * sin(2 * l0r) - 2 * e * sin(mr) + 4 * e * y * sin(mr) * cos(2 * l0r) -
            .5 * y * y * sin(4 * l0r) - 1.25 * e * e * sin(2 * mr))
        val utcMinutes = Math.floorMod(epochMs, 86_400_000L) / 60_000.0
        val solarMinutes = (utcMinutes + equationOfTimeMin + 4 * point.longitude).mod(1_440.0)
        val hourAngle = Math.toRadians(solarMinutes / 4 - 180)
        val lat = Math.toRadians(point.latitude)
        val cosZenith = (sin(lat) * sin(declination) + cos(lat) * cos(declination) * cos(hourAngle)).coerceIn(-1.0, 1.0)
        val elevation = 90 - Math.toDegrees(acos(cosZenith))
        val azimuth = Geography.wrap360(Math.toDegrees(atan2(sin(hourAngle), cos(hourAngle) * sin(lat) - tan(declination) * cos(lat))) + 180)
        return SunPosition(azimuth, elevation + refraction(elevation))
    }

    /** Bennett's formula, degrees; the sun is lifted by about half a degree at the horizon. */
    private fun refraction(elevationDeg: Double): Double =
        if (elevationDeg < -1) 0.0 else 1.02 / tan(Math.toRadians(elevationDeg + 10.3 / (elevationDeg + 5.11))) / 60

    /**
     * Next time the last of the sun's disc slips behind the skyline, or the first of it clears it, searching up to a day
     * ahead in one-minute steps. [skyline] gives the terrain's elevation at a bearing, or null where there is no map data.
     */
    fun nextTerrainEvent(point: GeoPoint, fromMs: Long, skyline: (Double) -> Double?, stepMs: Long = 60_000, limitMs: Long = 86_400_000): SunEvent? {
        fun clearance(ms: Long): Double {
            val sun = position(point, ms)
            // Without map data in that direction, fall back to the flat horizon.
            val ground = skyline(sun.azimuthDeg) ?: 0.0
            return sun.elevationDeg + SEMI_DIAMETER - max(ground, -2.0)
        }
        var previous = clearance(fromMs)
        var ms = fromMs + stepMs
        while (ms <= fromMs + limitMs) {
            val now = clearance(ms)
            if (previous > 0 && now <= 0) return SunEvent(SunEvent.Kind.SET, ms)
            if (previous <= 0 && now > 0) return SunEvent(SunEvent.Kind.RISE, ms)
            previous = now; ms += stepMs
        }
        return null
    }
}
