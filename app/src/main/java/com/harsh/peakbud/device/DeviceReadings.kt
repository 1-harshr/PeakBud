package com.harsh.peakbud.device

import android.content.Context
import kotlinx.coroutines.flow.Flow

/** Allows UI tests to supply stable observations without depending on emulator sensor noise. */
interface DeviceReadings {
    fun poses(displayRotation: Int): Flow<DevicePose>
    fun fixes(): Flow<LocationStatus>
}

class AndroidDeviceReadings(context: Context) : DeviceReadings {
    private val orientation = AndroidOrientationProvider(context)
    private val location = AndroidLocationProvider(context)
    override fun poses(displayRotation: Int) = orientation.poses(displayRotation)
    override fun fixes() = location.fixes()
}
