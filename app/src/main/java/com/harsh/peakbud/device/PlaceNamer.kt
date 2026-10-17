package com.harsh.peakbud.device

import android.content.Context
import android.location.Address
import android.location.Geocoder
import android.os.Build
import com.harsh.peakbud.core.model.GeoPoint
import java.util.Locale
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Turns a position into a short place name like "Yosemite Valley, California" using Android's geocoder.
 * Needs a network connection; returns null offline or when the device has no geocoder, so callers fall back
 * to coordinates.
 */
object PlaceNamer {
    suspend fun name(context: Context, point: GeoPoint): String? = withContext(Dispatchers.IO) {
        if (!Geocoder.isPresent()) return@withContext null
        val geocoder = Geocoder(context, Locale.getDefault())
        val address = withTimeoutOrNull(8_000) {
            runCatching {
                if (Build.VERSION.SDK_INT >= 33) suspendCancellableCoroutine<Address?> { done ->
                    geocoder.getFromLocation(point.latitude, point.longitude, 1, object : Geocoder.GeocodeListener {
                        override fun onGeocode(addresses: MutableList<Address>) { if (done.isActive) done.resume(addresses.firstOrNull()) }
                        override fun onError(message: String?) { if (done.isActive) done.resume(null) }
                    })
                } else @Suppress("DEPRECATION") geocoder.getFromLocation(point.latitude, point.longitude, 1)?.firstOrNull()
            }.getOrNull()
        } ?: return@withContext null
        val place = address.subLocality ?: address.locality ?: address.subAdminArea ?: address.featureName
        val region = address.adminArea ?: address.countryName
        listOfNotNull(place, region).map(::tidy).distinct().joinToString(", ").ifBlank { null }
    }

    /** Some geocoders return names in capitals ("TUOLUMNE MEADOWS"); show them in title case. */
    private fun tidy(name: String): String = if (name.any { it.isLowerCase() }) name
        else name.lowercase().split(" ").joinToString(" ") { word -> word.replaceFirstChar { it.titlecase() } }
}
