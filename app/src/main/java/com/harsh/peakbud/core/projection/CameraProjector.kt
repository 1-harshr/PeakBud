package com.harsh.peakbud.core.projection

import kotlin.math.*

/** Rear camera pose in true north coordinates. Roll is positive when screen-right tilts up. */
data class CameraPose(val heading: Double, val pitch: Double, val roll: Double)

data class ScreenLine(val start: ScreenPoint, val end: ScreenPoint)

/** Intrinsics in preview pixels, after CameraX rotation and center cropping. */
data class CameraLens(val fx: Double, val fy: Double, val cx: Double, val cy: Double) {
    init { require(listOf(fx, fy, cx, cy).all { it.isFinite() } && fx > 0 && fy > 0) }
    fun fieldOfView(width: Int, height: Int): Pair<Double, Double> {
        require(width > 0 && height > 0)
        return Math.toDegrees(atan(cx / fx) + atan((width - cx) / fx)) to
            Math.toDegrees(atan(cy / fy) + atan((height - cy) / fy))
    }
}

object CameraProjector {
    /** The world elevation=0 plane projected into the actual preview. Returns null when outside the view.
     * This is eye level, not the visible terrain boundary. Works through quarter-turn roll as well.
     */
    fun horizon(pose: CameraPose, lens: CameraLens, width: Int, height: Int): ScreenLine? {
        if (width <= 0 || height <= 0 || !listOf(pose.heading, pose.pitch, pose.roll).all { it.isFinite() }) return null
        val p = Math.toRadians(pose.pitch); val r = Math.toRadians(pose.roll)
        val a = sin(r) * cos(p) / lens.fx
        val b = -cos(r) * cos(p) / lens.fy
        val c = sin(p) - a * lens.cx - b * lens.cy
        val points = mutableListOf<ScreenPoint>()
        fun add(x: Double, y: Double) {
            if (!x.isFinite() || !y.isFinite() || x < -1e-5 || x > width + 1e-5 || y < -1e-5 || y > height + 1e-5) return
            val point = ScreenPoint(x.coerceIn(0.0, width.toDouble()).toFloat(), y.coerceIn(0.0, height.toDouble()).toFloat())
            if (points.none { hypot((it.x - point.x).toDouble(), (it.y - point.y).toDouble()) < .001 }) points += point
        }
        if (abs(b) > 1e-12) { add(0.0, -c / b); add(width.toDouble(), -(a * width + c) / b) }
        if (abs(a) > 1e-12) { add(-c / a, 0.0); add(-(b * height + c) / a, height.toDouble()) }
        return if (points.size >= 2) ScreenLine(points[0], points[1]) else null
    }

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
