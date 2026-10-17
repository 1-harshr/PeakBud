package com.harsh.peakbud.feature.camera

import android.graphics.Matrix
import com.harsh.peakbud.core.projection.CameraLens

/** One immutable publication of preview size, intrinsics and transform to the analysis worker. */
internal class CameraFrameGeometry(val lens: CameraLens, val width: Int, val height: Int, sensorToView: Matrix) {
    private val sensorToView = Matrix(sensorToView)

    /** Buffer -> sensor -> preview. Works even when the use cases have different crops/aspect ratios. */
    fun bufferToView(sensorToBuffer: Matrix): Matrix? {
        val inverse = Matrix()
        if (!sensorToBuffer.invert(inverse)) return null
        return Matrix().apply { setConcat(sensorToView, inverse) }
    }
}
