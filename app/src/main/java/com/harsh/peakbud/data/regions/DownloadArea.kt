package com.harsh.peakbud.data.regions

import com.harsh.peakbud.core.model.GeoBounds
import com.harsh.peakbud.core.model.GeoPoint
import com.harsh.peakbud.data.download.RegionDownloader
import com.harsh.peakbud.data.terrain.HgtTerrain
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID
import java.util.zip.GZIPInputStream
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.*

/** Small, user-selected downloads independent of the bundled catalogue. */
data class DownloadArea(val point: GeoPoint, val name: String, val observer: GeoBounds,
                        val terrain: GeoBounds, val search: GeoBounds, val tiles: List<String>) {
    val installedBytes: Long get() = tiles.size * CatalogRegion.HGT_BYTES
    companion object {
        const val RADIUS_M = 30_000.0
        fun plan(point: GeoPoint, name: String): DownloadArea {
            require(name.isNotBlank() && name.length <= 100)
            val observer = GeoBounds(point.latitude - .03, point.longitude - .03, point.latitude + .03, point.longitude + .03)
            require(observer.south > -55 && observer.north < 59 && observer.west > -180 && observer.east < 180)
            val angular = Math.toDegrees(RADIUS_M / 6371008.8) + 1.0 / 3600
            val longitude = angular / cos(Math.toRadians(max(abs(observer.south), abs(observer.north)) + angular))
            val search = GeoBounds(observer.south - angular, observer.west - longitude, observer.north + angular, observer.east + longitude)
            val lowLat = floor(search.south).toInt(); val highLat = floor(search.north).toInt()
            val lowLon = floor(search.west).toInt(); val highLon = floor(search.east).toInt()
            require(lowLat >= -56 && highLat < 60 && lowLon >= -180 && highLon < 180)
            val tiles = (lowLat..highLat).flatMap { lat -> (lowLon..highLon).map { lon -> HgtTerrain.tileName(lat, lon) } }
            return DownloadArea(point, name, observer, GeoBounds(lowLat.toDouble(), lowLon.toDouble(), highLat + 1.0, highLon + 1.0), search, tiles)
        }
        fun tileUrl(tile: String): String {
            require(tile.matches(Regex("[NS]\\d{2}[EW]\\d{3}\\.hgt")))
            return "https://s3.amazonaws.com/elevation-tiles-prod/skadi/${tile.take(3)}/$tile.gz"
        }
    }
}

data class AreaInstallResult(val pack: RegionPack, val peakNamesAvailable: Boolean)

/** Network content is received over HTTPS, bounded and validated before atomic installation.
 * Unlike curated areas, these hashes record freshly fetched bytes, not a bundled source checksum.
 */
class AreaInstaller(
    private val workRoot: File,
    private val store: RegionStore,
    private val downloader: RegionDownloader,
    private val fetchText: (String, Int, () -> Unit) -> String = ::areaText,
) {
    fun install(area: DownloadArea, checkpoint: () -> Unit = {}, progress: (InstallProgress) -> Unit = {}): AreaInstallResult {
        workRoot.mkdirs()
        require(workRoot.usableSpace > area.installedBytes * 2 + 64_000_000) { "Not enough space for this area" }
        val directory = workRoot.resolve("area-${UUID.randomUUID()}").apply { check(mkdir()) }
        try {
            checkpoint()
            progress(InstallProgress(InstallStage.PREPARE, area.name))
            val box = area.search
            val query = String.format(Locale.US, "[out:json][timeout:25];node[\"natural\"=\"peak\"][\"name\"](%.7f,%.7f,%.7f,%.7f);out body;", box.south, box.west, box.north, box.east)
            var namesAvailable = false
            var peaks = JSONArray()
            for (endpoint in listOf("https://overpass.private.coffee/api/interpreter", "https://overpass-api.de/api/interpreter")) {
                checkpoint()
                try {
                    peaks = parsePeaks(fetchText("$endpoint?data=${URLEncoder.encode(query, "UTF-8")}", 8_388_608, checkpoint), box)
                    namesAvailable = true
                    break
                } catch (cancelled: java.util.concurrent.CancellationException) { throw cancelled }
                catch (_: Exception) { /* Terrain remains useful when the public name service is unavailable. */ }
            }
            val terrainCredits = fetchText("https://raw.githubusercontent.com/tilezen/joerd/master/docs/attribution.md", 1_000_000, checkpoint)
            require(terrainCredits.isNotBlank())
            val credits = """${area.name}
                |User-selected offline area, Mapzen/Tilezen Skadi terrain (WGS84 / EGM96, 1 arc-second HGT).
                |Terrain: https://registry.opendata.aws/terrain-tiles/
                |Source files retrieved over HTTPS; local hashes recorded at installation (not catalogue-pinned).
                |Peak data © OpenStreetMap contributors, ODbL: https://www.openstreetmap.org/copyright
                |Named peak nodes selected from the area's bounding box; names and coordinates retained, optional metre elevations normalized.
                |OpenStreetMap peak coverage varies; missing names are not evidence that no mountains exist.
                |Peak-name service completed: $namesAvailable
                |
                |Terrain source attribution:
                |$terrainCredits
            """.trimMargin()
            directory.resolve("peaks.json").writeText(peaks.toString())
            directory.resolve("attribution.txt").writeText(credits)
            val files = JSONArray()
            fun record(path: String) {
                val file = directory.resolve(path)
                val digest = MessageDigest.getInstance("SHA-256")
                file.inputStream().use { input ->
                    val buffer = ByteArray(65536)
                    while (true) { checkpoint(); val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) }
                }
                files.put(JSONObject().put("path", path).put("size", file.length()).put("sha256", digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }))
            }
            area.tiles.forEachIndexed { index, tile ->
                checkpoint()
                val address = DownloadArea.tileUrl(tile)
                val compressed = downloader.download(address, checkpoint, maxBytes = CatalogRegion.HGT_BYTES + 65_536) { received, total ->
                    progress(InstallProgress(InstallStage.DOWNLOAD, area.name, index + 1, area.tiles.size, received, total ?: 0))
                }
                val output = directory.resolve("terrain/$tile").apply { parentFile!!.mkdirs() }
                try {
                    GZIPInputStream(compressed.inputStream()).use { input -> output.outputStream().use { target ->
                        var bytes = 0L; val buffer = ByteArray(65536)
                        while (true) {
                            checkpoint(); val n = input.read(buffer); if (n < 0) break
                            bytes += n; require(bytes <= CatalogRegion.HGT_BYTES) { "Invalid elevation tile" }; target.write(buffer, 0, n)
                        }
                        require(bytes == CatalogRegion.HGT_BYTES) { "Incomplete elevation tile" }
                    } }
                } catch (cancelled: java.util.concurrent.CancellationException) { throw cancelled }
                catch (badTile: Exception) { downloader.discard(address); throw badTile }
                record("terrain/$tile")
            }
            record("peaks.json"); record("attribution.txt")
            val id = "custom-" + UUID.nameUUIDFromBytes("${area.point.latitude},${area.point.longitude}".toByteArray()).toString()
            fun bounds(b: GeoBounds) = JSONObject().put("south", b.south).put("west", b.west).put("north", b.north).put("east", b.east)
            val manifest = JSONObject().put("schemaVersion", 1).put("id", id).put("name", area.name).put("version", "1")
                .put("horizontalCrs", "EPSG:4326").put("verticalDatum", "EGM96").put("radiusM", DownloadArea.RADIUS_M)
                .put("observerBounds", bounds(area.observer)).put("terrainBounds", bounds(area.terrain))
                .put("viewpoint", JSONObject().put("latitude", area.point.latitude).put("longitude", area.point.longitude))
                .put("source", "Mapzen/Tilezen Skadi; OpenStreetMap contributors (ODbL)").put("files", files)
            val pack = store.installPrepared(manifest.toString(), checkpoint, progress) { staging, _ ->
                directory.listFiles().orEmpty().forEach { file -> check(file.renameTo(staging.resolve(file.name))) }
            }
            // Retain completed tile transfers for retry after any earlier failure, then clear them on success.
            area.tiles.forEach { runCatching { downloader.discard(DownloadArea.tileUrl(it)) } }
            return AreaInstallResult(pack, namesAvailable)
        } finally { directory.deleteRecursively() }
    }

    companion object {
        fun parsePeaks(text: String, box: GeoBounds): JSONArray {
            val response = JSONObject(text)
            require(!response.has("remark")) { "Peak data service could not finish the request" }
            val rows = response.getJSONArray("elements")
            require(rows.length() <= 20_000)
            val out = JSONArray(); val seen = HashSet<Long>()
            for (i in 0 until rows.length()) {
                val row = rows.getJSONObject(i)
                if (row.optString("type") != "node") continue
                val tags = row.optJSONObject("tags") ?: continue
                val name = tags.optString("name").trim().take(200)
                if (tags.optString("natural") != "peak" || name.isBlank()) continue
                val point = GeoPoint(row.getDouble("lat"), row.getDouble("lon"))
                val id = row.getLong("id")
                if (!box.contains(point) || !seen.add(id)) continue
                val elevation = tags.optString("ele").trim().removeSuffix("m").trim().toDoubleOrNull()?.takeIf { it.isFinite() && it in -500.0..10_000.0 }
                out.put(JSONObject().put("id", "osm-node-$id").put("name", name).put("latitude", point.latitude).put("longitude", point.longitude)
                    .put("elevationM", elevation ?: JSONObject.NULL).put("source", "https://www.openstreetmap.org/node/$id"))
            }
            return out
        }
    }
}

private fun areaText(address: String, limit: Int, checkpoint: () -> Unit): String {
    val connection = URL(address).openConnection() as HttpURLConnection
    try {
        connection.connectTimeout = 15_000; connection.readTimeout = 45_000; connection.instanceFollowRedirects = false
        connection.setRequestProperty("User-Agent", "PeakBud/1.0 (Android offline terrain downloader)")
        require(connection.responseCode == 200) { "Area data service unavailable (${connection.responseCode})" }
        return connection.inputStream.use { input ->
            val output = java.io.ByteArrayOutputStream(); val buffer = ByteArray(16384)
            while (true) { checkpoint(); val n = input.read(buffer); if (n < 0) break; require(output.size() + n <= limit); output.write(buffer, 0, n) }
            output.toString("UTF-8")
        }
    } finally { connection.disconnect() }
}
