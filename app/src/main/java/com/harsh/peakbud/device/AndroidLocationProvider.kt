package com.harsh.peakbud.device

import com.harsh.peakbud.R
import com.harsh.peakbud.ui.text.TrailStrings
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
    private val strings = TrailStrings(context.resources)
    fun fixes() = callbackFlow<LocationStatus> {
        val manager = context.getSystemService(LocationManager::class.java)
        val precise = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!precise) {
            trySend(LocationStatus.Message(strings(R.string.turn_on_precise_location_so_peak_labels_line_up_you_can_also_choose_a_spot_o)))
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
            override fun onProviderDisabled(provider: String) { trySend(LocationStatus.Message(strings(R.string.location_is_off_turn_it_on_in_android_settings))) }
            override fun onProviderEnabled(provider: String) { trySend(LocationStatus.Message(strings(R.string.finding_your_location))) }
            @Deprecated("Legacy Android callback")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
        }
        try {
            if (!manager.isProviderEnabled(LocationManager.GPS_PROVIDER)) trySend(LocationStatus.Message(strings(R.string.location_is_off_turn_it_on_in_android_settings)))
            else trySend(LocationStatus.Message(strings(R.string.finding_your_location_open_sky_helps)))
            manager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 2000L, 0f, listener, Looper.getMainLooper())
        } catch (_: SecurityException) {
            trySend(LocationStatus.Message(strings(R.string.location_access_was_turned_off_you_can_still_choose_a_spot_on_home)))
        } catch (_: IllegalArgumentException) {
            trySend(LocationStatus.Message(strings(R.string.this_phone_has_no_gps_choose_a_spot_on_home_instead)))
        }
        awaitClose { manager.removeUpdates(listener) }
    }
}
