package com.harsh.peakbud.device

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Build
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.harsh.peakbud.core.model.GeoPoint
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow

data class PositionFix(val point: GeoPoint, val accuracyM: Float, val ageSeconds: Long,
                       val ellipsoidAltitudeM: Double? = null, val seaLevelAltitudeM: Double? = null,
                       val verticalAccuracyM: Float? = null)
sealed interface LocationStatus {
    data class Fix(val value: PositionFix) : LocationStatus
    data class Message(val text: String) : LocationStatus
}

class AndroidLocationProvider(private val context: Context) {
    fun fixes() = callbackFlow<LocationStatus> {
        val manager = context.getSystemService(LocationManager::class.java)
        val precise = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!precise) {
            trySend(LocationStatus.Message("Turn on precise location so peak labels line up. You can also choose a spot on Home."))
            close(); return@callbackFlow
        }
        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                val age = ((SystemClock.elapsedRealtimeNanos() - location.elapsedRealtimeNanos) / 1_000_000_000).coerceAtLeast(0)
                val accuracy = if (location.hasAccuracy() && location.accuracy.isFinite() && location.accuracy > 0) location.accuracy else 500f
                trySend(LocationStatus.Fix(PositionFix(GeoPoint(location.latitude, location.longitude), accuracy, age,
                    location.altitude.takeIf { location.hasAltitude() && it.isFinite() },
                    if (Build.VERSION.SDK_INT >= 34 && location.hasMslAltitude()) location.mslAltitudeMeters.takeIf { it.isFinite() } else null,
                    if (Build.VERSION.SDK_INT >= 34 && location.hasMslAltitude()) {
                        if (location.hasMslAltitudeAccuracy()) location.mslAltitudeAccuracyMeters.takeIf { it.isFinite() && it > 0 } else null
                    } else location.verticalAccuracyMeters.takeIf { location.hasVerticalAccuracy() && it.isFinite() && it > 0 })))
            }
            override fun onProviderDisabled(provider: String) { trySend(LocationStatus.Message("Location is off. Turn it on in Android settings.")) }
            override fun onProviderEnabled(provider: String) { trySend(LocationStatus.Message("Finding your location…")) }
            @Deprecated("Legacy Android callback")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
        }
        try {
            if (!manager.isProviderEnabled(LocationManager.GPS_PROVIDER)) trySend(LocationStatus.Message("Location is off. Turn it on in Android settings."))
            else trySend(LocationStatus.Message("Finding your location. Open sky helps."))
            manager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 2000L, 0f, listener, Looper.getMainLooper())
        } catch (_: SecurityException) {
            trySend(LocationStatus.Message("Location access was turned off. You can still choose a spot on Home."))
        } catch (_: IllegalArgumentException) {
            trySend(LocationStatus.Message("This phone has no GPS. Choose a spot on Home instead."))
        }
        awaitClose { manager.removeUpdates(listener) }
    }
}
