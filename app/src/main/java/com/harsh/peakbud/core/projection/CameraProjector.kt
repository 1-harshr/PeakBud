package com.harsh.peakbud.core.projection

import kotlin.math.*

/** Rear camera pose in true north coordinates. Roll is positive when screen-right tilts up. */
data class CameraPose(val heading: Double, val pitch: Double, val roll: Double)

/** Intrinsics in preview pixels, after CameraX rotation and center cropping. */
data class CameraLens(val fx: Double, val fy: Double, val cx: Double, val cy: Double) {
    init { require(listOf(fx, fy, cx, cy).all { it.isFinite() } && fx > 0 && fy > 0) }
}

object CameraProjector {
    /** Pinhole projection. No cylindrical panorama assumptions or artificial horizon offset. */
    fun project(bearing: Double, elevation: Double, pose: CameraPose, lens: CameraLens): ScreenPoint? {
        if (!listOf(bearing, elevation, pose.heading, pose.pitch, pose.roll).all { it.isFinite() }) return null
        val delta = Math.toRadians(bearing - pose.heading)
        val e = Math.toRadians(elevation)
        val p = Math.toRadians(pose.pitch)
        val r = Math.toRadians(pose.roll)
        val right = cos(e) * sin(delta)
        val up = sin(e) * cos(p) - cos(e) * cos(delta) * sin(p)
        val forward = sin(e) * sin(p) + cos(e) * cos(delta) * cos(p)
        if (forward <= 0.001) return null
        val x = lens.cx + lens.fx * (right * cos(r) + up * sin(r)) / forward
        val y = lens.cy - lens.fy * (-right * sin(r) + up * cos(r)) / forward
        return ScreenPoint(x.toFloat(), y.toFloat()).takeIf { it.x.isFinite() && it.y.isFinite() }
    }
}
