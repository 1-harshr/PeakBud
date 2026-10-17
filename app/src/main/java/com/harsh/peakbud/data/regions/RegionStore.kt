package com.harsh.peakbud.data.regions

import com.harsh.peakbud.core.model.*
import com.harsh.peakbud.core.geo.Geography
import com.harsh.peakbud.data.terrain.HgtTerrain
import kotlin.math.*
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipFile
import org.json.JSONArray
import org.json.JSONObject

data class PackFile(val path: String, val size: Long, val sha256: String)
data class RegionPack(
    val directory: File,
    val id: String,
    val name: String,
    val version: String,
    val bounds: GeoBounds,
    val observerBounds: GeoBounds,
    val radiusM: Double,
    val viewpoint: GeoPoint,
    val headingDeg: Double,
    val source: String,
    val files: List<PackFile>,
) { val key: String get() = directory.name }

/** Complete, validated packs are activated by a same-filesystem directory rename. */
class RegionStore(private val root: File) {
    private val verifiedKeys = mutableSetOf<String>()
    init { check(root.isDirectory || root.mkdirs()) { "Cannot create offline region storage" } }

    fun installed(): List<RegionPack> = root.listFiles().orEmpty()
        .filter { it.isDirectory && !it.name.startsWith('.') }
        .mapNotNull { runCatching { parse(it.resolve("manifest.json").readText(), it) }.getOrNull() }
        .sortedBy { it.name }

    fun attribution(pack: RegionPack): String = pack.directory.resolve("attribution.txt").readText()

    /** Recheck bytes after process restart before cached database metadata is trusted. */
    @Synchronized
    fun verify(pack: RegionPack, checkpoint: () -> Unit = {}) {
        require(pack.directory.canonicalFile.parentFile == root.canonicalFile)
        if (pack.key in verifiedKeys) return
        pack.files.forEach { file ->
            checkpoint()
            val target = pack.directory.resolve(file.path)
            require(target.isFile && target.length() == file.size && sha256(target, checkpoint) == file.sha256) {
                "This hiking area's files are damaged. Delete it and download it again."
            }
        }
        verifiedKeys += pack.key
    }

    fun peaks(pack: RegionPack): List<Peak> {
        val array = JSONArray(pack.directory.resolve("peaks.json").readText())
        require(array.length() <= 20_000) { "Peak catalogue exceeds 20,000 records" }
        val ids = mutableSetOf<String>()
        return List(array.length()) { index ->
            val obj = array.getJSONObject(index)
            val id = obj.getString("id")
            require(id.isNotBlank() && ids.add(id)) { "Duplicate or empty peak identifier" }
            val name = obj.getString("name")
            require(name.isNotBlank() && name.length <= 200)
            val height = if (obj.isNull("elevationM")) null else obj.getDouble("elevationM")
            require(height == null || height.isFinite() && height in -500.0..10_000.0)
            Peak(id, name, GeoPoint(obj.getDouble("latitude"), obj.getDouble("longitude")), height, obj.optString("source", pack.source))
        }
    }

    fun install(stream: InputStream, checkpoint: () -> Unit = {}, progress: (String) -> Unit = {}): RegionPack {
        val token = UUID.randomUUID().toString()
        val archive = root.resolve(".$token.zip")
        val staging = root.resolve(".$token.staging")
        try {
            progress("Saving…")
            archive.outputStream().use { output -> copyBounded(stream, output, MAX_BYTES, checkpoint) }
            check(staging.mkdir())
            val parsed = ZipFile(archive).use { zip ->
                val entries = zip.entries().asSequence().toList()
                require(entries.size <= 256) { "Too many files in region archive" }
                require(entries.map { it.name }.distinct().size == entries.size) { "Duplicate archive paths" }
                val metadata = zip.getEntry("manifest.json") ?: error("Archive needs manifest.json at its root")
                val text = zip.getInputStream(metadata).use { input ->
                    val bytes = java.io.ByteArrayOutputStream()
                    copyBounded(input, bytes, 1_048_576, checkpoint)
                    bytes.toString("UTF-8")
                }
                val pack = parse(text, staging)
                require(pack.files.sumOf { it.size } <= MAX_BYTES) { "Region exceeds 512 MiB" }
                require(root.usableSpace > pack.files.sumOf { it.size } + 16_000_000) { "Not enough space on your phone. Free up some storage and try again." }
                val expected = pack.files.associateBy { it.path }
                require(entries.all { it.isDirectory || it.name == "manifest.json" || it.name in expected }) { "Archive contains unlisted files" }
                pack.files.forEachIndexed { index, file ->
                    checkpoint()
                    progress("Checking files (${index + 1} of ${pack.files.size})…")
                    val entry = zip.getEntry(file.path) ?: error("Missing ${file.path}")
                    require(!entry.isDirectory && entry.size == file.size) { "Size mismatch: ${file.path}" }
                    val target = staging.resolve(file.path)
                    target.parentFile?.mkdirs()
                    zip.getInputStream(entry).use { input -> target.outputStream().use { output -> copyBounded(input, output, file.size, checkpoint) } }
                    require(target.length() == file.size && sha256(target, checkpoint) == file.sha256) { "Checksum mismatch: ${file.path}" }
                    if (file.path.endsWith(".hgt")) require(file.size == 3601L * 3601 * 2) { "Only 1 arc-second HGT tiles are supported" }
                }
                staging.resolve("manifest.json").writeText(text)
                require(staging.resolve("attribution.txt").length() <= 1_048_576)
                require(staging.resolve("peaks.json").length() <= 8_388_608)
                peaks(pack) // Validate catalogue before activation.
                pack
            }
            checkpoint()
            val final = root.resolve("${parsed.id}-$token")
            check(staging.renameTo(final)) { "Could not activate region" }
            return parsed.copy(directory = final)
        } finally {
            archive.delete()
            staging.deleteRecursively()
        }
    }

    @Synchronized
    fun delete(pack: RegionPack) {
        require(pack.directory.canonicalFile.parentFile == root.canonicalFile)
        check(pack.directory.deleteRecursively()) { "Unable to delete region" }
        verifiedKeys.remove(pack.key)
    }

    private fun parse(text: String, directory: File): RegionPack {
        val obj = JSONObject(text)
        require(obj.getInt("schemaVersion") == 1) { "Unsupported region format" }
        require(obj.getString("verticalDatum") == "EGM96" && obj.getString("horizontalCrs") == "EPSG:4326") { "Region must use WGS84 / EGM96" }
        val id = obj.getString("id")
        require(id.matches(Regex("[a-z0-9][a-z0-9-]{0,63}"))) { "Invalid region identifier" }
        fun bounds(key: String): GeoBounds = obj.getJSONObject(key).let {
            GeoBounds(it.getDouble("south"), it.getDouble("west"), it.getDouble("north"), it.getDouble("east"))
        }
        val terrainBounds = bounds("terrainBounds")
        val observerBounds = bounds("observerBounds")
        // Initial pack format uses rectangular regions away from the dateline/poles.
        require(terrainBounds.west < terrainBounds.east && observerBounds.west < observerBounds.east)
        require(observerBounds.south > -55 && observerBounds.north < 59)
        val radius = obj.getDouble("radiusM")
        require(radius.isFinite() && radius in 100.0..50_000.0)
        val view = obj.getJSONObject("viewpoint")
        val viewpoint = GeoPoint(view.getDouble("latitude"), view.getDouble("longitude"))
        require(observerBounds.contains(viewpoint))
        val files = obj.getJSONArray("files")
        require(files.length() in 3..250)
        val paths = mutableSetOf<String>()
        val records = List(files.length()) { i ->
            val f = files.getJSONObject(i)
            val path = f.getString("path")
            require(path.matches(Regex("(?:terrain/[NS]\\d{2}[EW]\\d{3}\\.hgt|licences/[a-zA-Z0-9_.-]+|peaks\\.json|attribution\\.txt)")) && !path.contains("..") && paths.add(path)) { "Invalid or duplicate file path" }
            val size = f.getLong("size")
            val hash = f.getString("sha256")
            require(size in 1..MAX_BYTES && hash.matches(Regex("[a-f0-9]{64}")))
            PackFile(path, size, hash)
        }
        require("peaks.json" in paths && "attribution.txt" in paths && paths.any { it.endsWith(".hgt") })
        val angular = Math.toDegrees(radius / Geography.EARTH_RADIUS_M) + 1.0 / 3600
        val lonMargin = angular / cos(Math.toRadians(max(abs(observerBounds.south), abs(observerBounds.north)) + angular))
        val lowLat = floor(observerBounds.south - angular).toInt()
        val highLat = floor(observerBounds.north + angular).toInt()
        val lowLon = floor(observerBounds.west - lonMargin).toInt()
        val highLon = floor(observerBounds.east + lonMargin).toInt()
        require(lowLat >= -56 && highLat < 60 && lowLon >= -180 && highLon < 180)
        require(terrainBounds.south <= lowLat && terrainBounds.north >= highLat + 1 && terrainBounds.west <= lowLon && terrainBounds.east >= highLon + 1) { "Terrain does not cover the supported viewpoints plus viewing radius" }
        for (latitude in lowLat..highLat) for (longitude in lowLon..highLon) {
            require("terrain/${HgtTerrain.tileName(latitude, longitude)}" in paths) { "A tile needed by the viewing radius is missing" }
        }
        val name = obj.getString("name")
        require(name.isNotBlank() && name.length <= 100)
        return RegionPack(directory, id, name, obj.getString("version"), terrainBounds, observerBounds,
            radius, viewpoint, view.optDouble("headingDeg", 0.0).also { require(it.isFinite()) }, obj.getString("source"), records)
    }

    companion object {
        const val MAX_BYTES = 512L * 1024 * 1024
        private fun copyBounded(input: InputStream, output: java.io.OutputStream, limit: Long, checkpoint: () -> Unit) {
            val buffer = ByteArray(65536)
            var total = 0L
            while (true) {
                checkpoint()
                val count = input.read(buffer)
                if (count == -1) break
                total += count
                require(total <= limit) { "File exceeds its permitted size" }
                output.write(buffer, 0, count)
            }
        }
        private fun sha256(file: File, checkpoint: () -> Unit): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(65536)
                while (true) { checkpoint(); val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
            }
            return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
        }
    }
}
