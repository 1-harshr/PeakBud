package com.harsh.peakbud.data.regions

import com.harsh.peakbud.core.geo.Geography
import com.harsh.peakbud.core.model.GeoBounds
import com.harsh.peakbud.core.model.GeoPoint
import com.harsh.peakbud.data.terrain.HgtTerrain
import java.security.MessageDigest
import java.time.LocalDate
import java.util.Locale
import kotlin.math.*
import org.json.JSONArray
import org.json.JSONObject

/**
 * Location-aware downloads: suggest catalogue areas near the user, and plan a small "around me" pack instead of a whole
 * state-sized one.
 *
 * An around-me pack only uses terrain tiles whose download and file checksums are already pinned in the shipped catalogue,
 * so it keeps the same integrity guarantees as every other built-in download. Its peaks are the catalogue's bundled peaks
 * that fall inside the pack. Places outside catalogue coverage get suggestions only.
 */
object Vicinity {
    const val RADIUS_M = 30_000.0
    private const val OBSERVER_HALF_DEG = .03  // roughly 3 km to walk around before the pack no longer covers you

    data class Suggestion(val region: CatalogRegion, val distanceM: Double)

    /** Catalogue areas ordered by distance from [point] to their supported viewpoints (0 when inside). */
    fun suggestions(point: GeoPoint, catalog: List<CatalogRegion>, limit: Int = 3): List<Suggestion> =
        catalog.map { Suggestion(it, distanceTo(point, observerBounds(it))) }.sortedBy { it.distanceM }.take(limit)

    /** A plan for a pack centred on [point], or null if any needed tile is not pinned in the catalogue. */
    /** [readAsset] reads a bundled text asset such as "regions/jharkhand/peaks.json". */
    fun plan(point: GeoPoint, catalog: List<CatalogRegion>, readAsset: (String) -> String, placeName: String? = null): CatalogRegion? {
        val observer = GeoBounds(point.latitude - OBSERVER_HALF_DEG, point.longitude - OBSERVER_HALF_DEG,
            point.latitude + OBSERVER_HALF_DEG, point.longitude + OBSERVER_HALF_DEG)
        if (observer.south <= -55 || observer.north >= 59) return null
        // Same coverage rule the store enforces: every tile touched by the observer box plus the viewing radius.
        val angular = Math.toDegrees(RADIUS_M / 6371008.8) + 1.0 / 3600
        val margin = angular / cos(Math.toRadians(max(abs(observer.south), abs(observer.north)) + angular))
        val lats = floor(observer.south - angular).toInt()..floor(observer.north + angular).toInt()
        val lons = floor(observer.west - margin).toInt()..floor(observer.east + margin).toInt()
        val tiles = ArrayList<CatalogTile>(); val records = JSONArray(); val sources = LinkedHashSet<CatalogRegion>()
        for (lat in lats) for (lon in lons) {
            val path = "terrain/${HgtTerrain.tileName(lat, lon)}"
            val source = catalog.firstOrNull { region -> region.tiles.any { it.path == path } } ?: return null
            tiles += source.tiles.first { it.path == path }
            records.put(fileRecord(source, path) ?: return null)
            sources += source
        }
        val terrain = GeoBounds(lats.first.toDouble(), lons.first.toDouble(), lats.last + 1.0, lons.last + 1.0)
        val peaks = peaksInside(terrain, sources) { readAsset("${it.assetDirectory}/peaks.json") }
        val id = "nearby-" + code(point.latitude, "n", "s", 2) + "-" + code(point.longitude, "e", "w", 3)
        val name = placeName?.let { "Around $it" } ?: String.format(Locale.US, "Around %.2f° %s, %.2f° %s",
            abs(point.latitude), if (point.latitude >= 0) "N" else "S", abs(point.longitude), if (point.longitude >= 0) "E" else "W")
        val attribution = buildString {
            append("PeakBud nearby area around ${String.format(Locale.US, "%.4f, %.4f", point.latitude, point.longitude)}\n")
            append("Built on this device from catalogue terrain tiles and peak snapshots of: ${sources.joinToString { it.name }}.\n")
            append("Peaks are the catalogue's bundled records inside this area. Credits of the source areas follow.\n\n")
            sources.forEach { append(readAsset("${it.assetDirectory}/attribution.txt")).append("\n") }
        }
        records.put(JSONObject().put("path", "peaks.json").put("size", peaks.size).put("sha256", sha256(peaks)))
        val credits = attribution.toByteArray()
        records.put(JSONObject().put("path", "attribution.txt").put("size", credits.size).put("sha256", sha256(credits)))
        val manifest = JSONObject()
            .put("schemaVersion", 1).put("id", id).put("name", name).put("version", "1")
            .put("horizontalCrs", "EPSG:4326").put("verticalDatum", "EGM96")
            .put("observerBounds", bounds(observer)).put("terrainBounds", bounds(terrain)).put("radiusM", RADIUS_M)
            .put("viewpoint", JSONObject().put("latitude", point.latitude).put("longitude", point.longitude).put("headingDeg", 0))
            .put("source", "Mapzen/Tilezen Skadi; GeoNames CC BY 4.0").put("preparedAt", LocalDate.now().toString()).put("files", records)
        return CatalogRegion(id, name, "Near you", "Terrain and peaks within ${(RADIUS_M / 1000).toInt()} km of where you are",
            "nearby/$id", tiles, manifest.toString()).also { generated[it.assetDirectory] = mapOf("peaks.json" to peaks, "attribution.txt" to credits) }
    }

    /** Generated peaks/credits for planned packs, served to the installer in place of bundled assets. */
    private val generated = java.util.concurrent.ConcurrentHashMap<String, Map<String, ByteArray>>()
    fun generatedAsset(path: String): ByteArray? = generated[path.substringBeforeLast('/')]?.get(path.substringAfterLast('/'))

    private fun peaksInside(bounds: GeoBounds, sources: Set<CatalogRegion>, peakJson: (CatalogRegion) -> String): ByteArray {
        val seen = HashSet<String>(); val out = JSONArray()
        for (source in sources) {
            val rows = JSONArray(peakJson(source))
            for (i in 0 until rows.length()) {
                val row = rows.getJSONObject(i)
                val lat = row.getDouble("latitude"); val lon = row.getDouble("longitude")
                if (lat in bounds.south..bounds.north && lon in bounds.west..bounds.east && seen.add(row.getString("id"))) out.put(row)
            }
        }
        return out.toString(2).toByteArray()
    }

    private fun fileRecord(source: CatalogRegion, path: String): JSONObject? {
        val files = JSONObject(source.manifest).getJSONArray("files")
        for (i in 0 until files.length()) if (files.getJSONObject(i).getString("path") == path) return JSONObject(files.getJSONObject(i).toString())
        return null
    }
    private fun observerBounds(region: CatalogRegion) = JSONObject(region.manifest).getJSONObject("observerBounds").let {
        GeoBounds(it.getDouble("south"), it.getDouble("west"), it.getDouble("north"), it.getDouble("east"))
    }
    private fun distanceTo(point: GeoPoint, box: GeoBounds): Double {
        if (box.contains(point)) return 0.0
        val nearest = GeoPoint(point.latitude.coerceIn(box.south, box.north), point.longitude.coerceIn(box.west, box.east))
        return Geography.distance(point, nearest)
    }
    private fun bounds(b: GeoBounds) = JSONObject().put("south", b.south).put("west", b.west).put("north", b.north).put("east", b.east)
    private fun code(value: Double, positive: String, negative: String, digits: Int) =
        (if (value >= 0) positive else negative) + String.format(Locale.US, "%0${digits + 3}.2f", abs(value)).replace(".", "")
    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
}
