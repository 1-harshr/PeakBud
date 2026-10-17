package com.harsh.peakbud.core.projection

import kotlin.math.*

data class WorldRay(val bearing: Double, val elevation: Double)

/** Inverse of CameraProjector in the same calibrated, rotated preview coordinates. */
object CameraRays {
    fun world(x: Double, y: Double, lens: CameraLens, pose: CameraPose): WorldRay {
        val r = Math.toRadians(pose.roll)
        val p = Math.toRadians(pose.pitch)
        val screenRight = (x - lens.cx) / lens.fx
        val screenUp = (lens.cy - y) / lens.fy
        val right = screenRight * cos(r) - screenUp * sin(r)
        val up = screenRight * sin(r) + screenUp * cos(r)
        val horizontal = cos(p) - up * sin(p)
        val vertical = sin(p) + up * cos(p)
        return WorldRay(((pose.heading + Math.toDegrees(atan2(right, horizontal))) % 360 + 360) % 360,
            Math.toDegrees(atan2(vertical, hypot(right, horizontal))))
    }
}
