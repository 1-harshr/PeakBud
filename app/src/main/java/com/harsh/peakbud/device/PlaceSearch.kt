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

data class AreaPlace(val name: String, val detail: String, val point: GeoPoint)

object PlaceSearch {
    /** Explicit search only, no requests while typing. Coordinates also work without a geocoder. */
    suspend fun find(context: Context, query: String): List<AreaPlace> = withContext(Dispatchers.IO) {
        val coordinates = query.trim().split(Regex("[,\\s]+"))
        if (coordinates.size == 2) {
            val point = runCatching { GeoPoint(coordinates[0].toDouble(), coordinates[1].toDouble()) }.getOrNull()
            if (point != null) return@withContext listOf(AreaPlace(query.trim().take(100), "", point))
        }
        check(Geocoder.isPresent())
        val geocoder = Geocoder(context, Locale.getDefault())
        val addresses: List<Address> = withTimeoutOrNull(12_000) {
            if (Build.VERSION.SDK_INT >= 33) suspendCancellableCoroutine { done ->
                geocoder.getFromLocationName(query, 5, object : Geocoder.GeocodeListener {
                    override fun onGeocode(addresses: MutableList<Address>) { if (done.isActive) done.resume(addresses.toList()) }
                    override fun onError(errorMessage: String?) { if (done.isActive) done.resume(emptyList()) }
                })
            } else @Suppress("DEPRECATION") geocoder.getFromLocationName(query, 5).orEmpty()
        } ?: emptyList()
        addresses.filter { it.hasLatitude() && it.hasLongitude() }.map { address ->
            val name = address.featureName ?: address.locality ?: query
            AreaPlace(name.take(100), listOfNotNull(address.locality, address.adminArea, address.countryName).distinct().filter { it != name }.joinToString(", "),
                GeoPoint(address.latitude, address.longitude))
        }.distinctBy { it.point }
    }
}
