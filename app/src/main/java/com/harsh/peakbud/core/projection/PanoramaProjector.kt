package com.harsh.peakbud.core.projection

import com.harsh.peakbud.core.geo.Geography

data class ScreenPoint(val x: Float, val y: Float)

object PanoramaProjector {
    /** Cylindrical panorama, intentionally independent of a camera's perspective. */
    fun project(bearing: Double, elevation: Double, heading: Double, pitch: Double,
                width: Float, height: Float, horizontalFov: Double = 100.0): ScreenPoint {
        require(width > 0 && height > 0 && horizontalFov in 10.0..180.0)
        val verticalFov = horizontalFov * height / width
        return ScreenPoint(
            (width / 2 + Geography.wrap180(bearing - heading) / horizontalFov * width).toFloat(),
            (height * 0.58 - (elevation - pitch) / verticalFov * height).toFloat(),
        )
    }
}
