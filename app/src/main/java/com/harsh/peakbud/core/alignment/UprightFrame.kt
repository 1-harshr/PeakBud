package com.harsh.peakbud.core.alignment

import kotlin.math.*

/**
 * Turns a camera buffer region into a small upright ARGB image for [SkylineExtractor], and maps points found in it back
 * to buffer coordinates. Platform-free so rotation and crop handling are unit tested; the Android side then maps buffer
 * coordinates to the preview with CameraX's own transforms.
 *
 * [rotationDegrees] is CameraX's ImageInfo.rotationDegrees: how far the buffer must turn clockwise to be upright.
 */
class UprightFrame private constructor(
    val width: Int, val height: Int, val argb: IntArray,
    private val cropLeft: Int, private val cropTop: Int, private val cropWidth: Int, private val cropHeight: Int,
    private val rotationDegrees: Int, private val scale: Double,
) {
    /** Upright-image point to buffer coordinates (pixel centres preserved). */
    fun toBuffer(x: Double, y: Double): Pair<Double, Double> {
        val u = (x + .5) * scale - .5
        val v = (y + .5) * scale - .5
        return when (rotationDegrees) {
            0 -> cropLeft + u to cropTop + v
            90 -> cropLeft + v to cropTop + (cropHeight - 1 - u)
            180 -> cropLeft + (cropWidth - 1 - u) to cropTop + (cropHeight - 1 - v)
            else -> cropLeft + (cropWidth - 1 - v) to cropTop + u
        }
    }

    companion object {
        /**
         * @param pixel reads one buffer pixel as ARGB at integer buffer coordinates.
         * @param maxWidth bound on the upright width; work stays constant regardless of camera resolution.
         */
        fun sample(cropLeft: Int, cropTop: Int, cropWidth: Int, cropHeight: Int, rotationDegrees: Int, maxWidth: Int,
                   pixel: (x: Int, y: Int) -> Int): UprightFrame {
            require(rotationDegrees in setOf(0, 90, 180, 270) && cropWidth > 0 && cropHeight > 0 && maxWidth >= 32)
            val uprightW = if (rotationDegrees % 180 == 0) cropWidth else cropHeight
            val uprightH = if (rotationDegrees % 180 == 0) cropHeight else cropWidth
            val scale = max(1.0, uprightW.toDouble() / maxWidth)
            val w = max(1, (uprightW / scale).toInt()); val h = max(1, (uprightH / scale).toInt())
            val frame = UprightFrame(w, h, IntArray(w * h), cropLeft, cropTop, cropWidth, cropHeight, rotationDegrees, scale)
            for (y in 0 until h) for (x in 0 until w) {
                val (bx, by) = frame.toBuffer(x.toDouble(), y.toDouble())
                frame.argb[y * w + x] = pixel(bx.roundToInt().coerceIn(cropLeft, cropLeft + cropWidth - 1),
                    by.roundToInt().coerceIn(cropTop, cropTop + cropHeight - 1))
            }
            return frame
        }
    }
}
