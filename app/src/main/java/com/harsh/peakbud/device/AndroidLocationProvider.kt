package com.harsh.peakbud.device

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.harsh.peakbud.core.model.GeoPoint
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow

data class PositionFix(val point: GeoPoint, val accuracyM: Float, val ageSeconds: Long)
sealed interface LocationStatus {
    data class Fix(val value: PositionFix) : LocationStatus
    data class Message(val text: String) : LocationStatus
}

class AndroidLocationProvider(private val context: Context) {
    fun fixes() = callbackFlow<LocationStatus> {
        val manager = context.getSystemService(LocationManager::class.java)
        val precise = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!precise) {
            trySend(LocationStatus.Message("Precise location is needed for peak alignment. You can still choose a manual viewpoint."))
            close(); return@callbackFlow
        }
        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                val age = ((SystemClock.elapsedRealtimeNanos() - location.elapsedRealtimeNanos) / 1_000_000_000).coerceAtLeast(0)
                val accuracy = if (location.hasAccuracy() && location.accuracy.isFinite() && location.accuracy > 0) location.accuracy else 500f
                trySend(LocationStatus.Fix(PositionFix(GeoPoint(location.latitude, location.longitude), accuracy, age)))
            }
            override fun onProviderDisabled(provider: String) { trySend(LocationStatus.Message("GPS is turned off. Enable location in Android settings.")) }
            override fun onProviderEnabled(provider: String) { trySend(LocationStatus.Message("Waiting for a satellite fix…")) }
            @Deprecated("Legacy Android callback")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
        }
        try {
            if (!manager.isProviderEnabled(LocationManager.GPS_PROVIDER)) trySend(LocationStatus.Message("GPS is turned off. Enable location in Android settings."))
            else trySend(LocationStatus.Message("Waiting for a satellite fix. Keep a clear view of the sky."))
            manager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 2000L, 10f, listener, Looper.getMainLooper())
        } catch (_: SecurityException) {
            trySend(LocationStatus.Message("Location permission was revoked. Choose a manual viewpoint."))
        } catch (_: IllegalArgumentException) {
            trySend(LocationStatus.Message("This device has no GPS provider. Choose a manual viewpoint."))
        }
        awaitClose { manager.removeUpdates(listener) }
    }
}
