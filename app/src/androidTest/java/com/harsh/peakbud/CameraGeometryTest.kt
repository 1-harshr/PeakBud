package com.harsh.peakbud

import android.graphics.Matrix
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.harsh.peakbud.core.projection.CameraLens
import com.harsh.peakbud.feature.camera.CameraFrameGeometry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CameraGeometryTest {
    @Test fun analysisAndPreviewWithDifferentCropsMapThroughTheSameSensorPoint() {
        // A 4000x3000 sensor: analysis is 640x480, preview is a cropped 1000x1000 square.
        val sensorToBuffer = Matrix().apply { setScale(.16f, .16f) }
        val sensorToView = Matrix().apply { setValues(floatArrayOf(1f/3, 0f, -500f/3, 0f, 1f/3, 0f, 0f, 0f, 1f)) }
        val geometry = CameraFrameGeometry(CameraLens(700.0, 700.0, 500.0, 500.0), 1000, 1000, sensorToView)
        val points = floatArrayOf(80f, 0f, 320f, 240f, 560f, 480f)
        geometry.bufferToView(sensorToBuffer)!!.mapPoints(points)
        assertArrayEquals(floatArrayOf(0f, 0f, 500f, 500f, 1000f, 1000f), points, .001f)
        // A later mutable PreviewView matrix must not alter an already-published worker snapshot.
        sensorToView.reset()
        val centre = floatArrayOf(320f, 240f)
        geometry.bufferToView(sensorToBuffer)!!.mapPoints(centre)
        assertArrayEquals(floatArrayOf(500f, 500f), centre, .001f)
    }

    @Test fun rotatedPreviewMapsRawBufferCoordinatesOnce() {
        val sensorToBuffer = Matrix().apply { setScale(.16f, .16f) }
        // Clockwise quarter turn of the full sensor, then scale to a 600x800 portrait preview.
        val sensorToView = Matrix().apply { setValues(floatArrayOf(0f, -.2f, 600f, .2f, 0f, 0f, 0f, 0f, 1f)) }
        val geometry = CameraFrameGeometry(CameraLens(700.0, 700.0, 300.0, 400.0), 600, 800, sensorToView)
        val points = floatArrayOf(0f, 0f, 640f, 0f, 640f, 480f, 0f, 480f)
        geometry.bufferToView(sensorToBuffer)!!.mapPoints(points)
        assertArrayEquals(floatArrayOf(600f, 0f, 600f, 800f, 0f, 800f, 0f, 0f), points, .001f)
    }

    @Test fun unusableBufferTransformDoesNotProduceCoordinates() {
        val geometry = CameraFrameGeometry(CameraLens(700.0, 700.0, 300.0, 400.0), 600, 800, Matrix())
        assertNull(geometry.bufferToView(Matrix().apply { setScale(0f, 0f) }))
    }
}
