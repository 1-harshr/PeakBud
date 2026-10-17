package com.harsh.peakbud.device

import android.content.Context
import android.hardware.*
import android.view.Surface
import com.harsh.peakbud.core.geo.Geography
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlin.math.*

data class DevicePose(val magneticHeading: Double, val pitch: Double, val reliable: Boolean, val message: String? = null, val available: Boolean = true, val roll: Double = 0.0, val cameraUsable: Boolean = true, val source: String = "phone sensors")

class AndroidOrientationProvider(context: Context) {
    private val manager = context.getSystemService(SensorManager::class.java)
    fun poses(displayRotation: Int) = callbackFlow<DevicePose> {
        val rotation = manager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        val acceleration = manager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        val magnetic = manager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
        if (rotation == null && (acceleration == null || magnetic == null)) {
            trySend(DevicePose(0.0, 0.0, false, "This phone has no compass. Drag the panorama to look around.", available = false))
            close(); return@callbackFlow
        }
        var gravity: FloatArray? = null
        var field: FloatArray? = null
        var accuracy = SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM
        val listener = object : SensorEventListener {
            override fun onAccuracyChanged(sensor: Sensor?, value: Int) {
                if (sensor?.type == Sensor.TYPE_ROTATION_VECTOR || sensor?.type == Sensor.TYPE_MAGNETIC_FIELD) accuracy = value
            }
            override fun onSensorChanged(event: SensorEvent) {
                val matrix = FloatArray(9)
                if (event.sensor.type == Sensor.TYPE_ROTATION_VECTOR) SensorManager.getRotationMatrixFromVector(matrix, event.values)
                else {
                    if (event.sensor.type == Sensor.TYPE_ACCELEROMETER) gravity = event.values.clone()
                    if (event.sensor.type == Sensor.TYPE_MAGNETIC_FIELD) field = event.values.clone()
                    val g = gravity ?: return
                    val f = field ?: return
                    if (!SensorManager.getRotationMatrix(matrix, null, g, f)) return
                }
                val remapped = FloatArray(9)
                val axes = when (displayRotation) {
                    Surface.ROTATION_90 -> SensorManager.AXIS_Y to SensorManager.AXIS_MINUS_X
                    Surface.ROTATION_180 -> SensorManager.AXIS_MINUS_X to SensorManager.AXIS_MINUS_Y
                    Surface.ROTATION_270 -> SensorManager.AXIS_MINUS_Y to SensorManager.AXIS_X
                    else -> SensorManager.AXIS_X to SensorManager.AXIS_Y
                }
                SensorManager.remapCoordinateSystem(matrix, axes.first, axes.second, remapped)
                // Rear-camera direction is -Z. When held flat, use screen-top instead.
                var east = -remapped[2].toDouble()
                var north = -remapped[5].toDouble()
                val flat = hypot(east, north) < 0.2
                if (flat) { east = remapped[1].toDouble(); north = remapped[4].toDouble() }
                val heading = Geography.wrap360(Math.toDegrees(atan2(east, north)))
                val pitch = if (flat) 0.0 else Math.toDegrees(asin((-remapped[8]).toDouble().coerceIn(-1.0, 1.0)))
                trySend(DevicePose(heading, pitch, accuracy >= SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM,
                    if (accuracy < SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM) "Compass is unsteady. Wave your phone in a figure eight, away from metal." else null, roll = Math.toDegrees(atan2(remapped[6].toDouble(), remapped[7].toDouble())), cameraUsable = !flat, source = if (rotation != null) "compass and motion sensors" else "compass and tilt sensor"))
            }
        }
        val registered = if (rotation != null) manager.registerListener(listener, rotation, SensorManager.SENSOR_DELAY_GAME)
        else {
            val accelerometerRegistered = manager.registerListener(listener, acceleration, SensorManager.SENSOR_DELAY_GAME)
            val magnetometerRegistered = manager.registerListener(listener, magnetic, SensorManager.SENSOR_DELAY_GAME)
            accelerometerRegistered && magnetometerRegistered
        }
        if (!registered) {
            trySend(DevicePose(0.0, 0.0, false, "Couldn't start the compass. Drag the panorama to look around.", available = false))
            manager.unregisterListener(listener)
            close(); return@callbackFlow
        }
        awaitClose { manager.unregisterListener(listener) }
    }
}
