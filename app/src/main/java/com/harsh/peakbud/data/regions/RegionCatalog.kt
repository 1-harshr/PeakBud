package com.harsh.peakbud.data.regions

import com.harsh.peakbud.data.download.RegionDownloader
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.zip.GZIPInputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.json.JSONObject

data class CatalogTile(val path: String, val url: String, val downloadBytes: Long, val downloadSha256: String)
data class CatalogRegion(val id: String, val name: String, val country: String, val description: String,
                         val assetDirectory: String, val tiles: List<CatalogTile>, val manifest: String) {
    val downloadBytes get() = tiles.sumOf { it.downloadBytes }
    val installedBytes get() = tiles.size * HGT_BYTES
    val radiusKm get() = JSONObject(manifest).getDouble("radiusM").toInt() / 1000
    val version get() = JSONObject(manifest).getString("version")
    companion object { const val HGT_BYTES = 3601L * 3601 * 2 }
}

/** Small peak snapshots and trusted download recipes ship inside the APK. */
object RegionCatalog {
    fun load(openAsset: (String) -> InputStream): List<CatalogRegion> {
        val obj = openAsset("regions/catalog.json").bufferedReader().use { JSONObject(it.readText()) }
        require(obj.getInt("schemaVersion") == 1)
        val rows = obj.getJSONArray("regions")
        return List(rows.length()) { i ->
            val row = rows.getJSONObject(i)
            val manifest = row.getJSONObject("manifest")
            val tiles = row.getJSONArray("tiles")
            val id = row.getString("id")
            require(id.matches(Regex("[a-z0-9-]+")) && manifest.getString("id") == id)
            CatalogRegion(id, manifest.getString("name"), row.getString("country"), row.getString("description"),
                row.getString("assetDirectory").also { require(it == "regions/$id") },
                List(tiles.length()) { t -> tiles.getJSONObject(t).let { tile ->
                    CatalogTile(tile.getString("path").also { require(it.matches(Regex("terrain/[NS]\\d{2}[EW]\\d{3}\\.hgt"))) },
                        tile.getString("url").also { require(it.startsWith("https://s3.amazonaws.com/elevation-tiles-prod/skadi/")) },
                        tile.getLong("downloadBytes"), tile.getString("downloadSha256"))
                } }, manifest.toString())
        }.also { require(it.map { r -> r.id }.distinct().size == it.size) }
    }
}

/** Downloads only pinned catalog sources, then uses the existing validated atomic installer. */
class CatalogInstaller(private val workRoot: File, private val store: RegionStore, private val downloader: RegionDownloader,
                       private val openAsset: (String) -> InputStream) {
    fun install(region: CatalogRegion, checkpoint: () -> Unit = {}, progress: (String) -> Unit = {}): RegionPack {
        workRoot.mkdirs()
        require(workRoot.usableSpace > region.installedBytes * 4 + region.downloadBytes + 16_000_000) { "Not enough storage to prepare this region. Free some space and retry." }
        val work = workRoot.resolve(region.id)
        work.deleteRecursively(); check(work.mkdirs())
        try {
            val records = JSONObject(region.manifest).getJSONArray("files")
            val expected = (0 until records.length()).associate { i -> records.getJSONObject(i).let { it.getString("path") to it } }
            region.tiles.forEachIndexed { index, tile ->
                checkpoint()
                val compressed = downloader.download(tile.url, checkpoint) { received, total ->
                    progress("Downloading ${region.name} · ${index + 1}/${region.tiles.size} · ${received / 1_048_576}/${(total ?: tile.downloadBytes) / 1_048_576} MB")
                }
                progress("Checking terrain ${index + 1}/${region.tiles.size}…")
                if (compressed.length() != tile.downloadBytes || hash(compressed, checkpoint) != tile.downloadSha256) {
                    downloader.discard(tile.url)
                    error("The terrain source has changed or was damaged. Please try again later.")
                }
                val output = work.resolve(tile.path)
                output.parentFile!!.mkdirs()
                GZIPInputStream(compressed.inputStream()).use { input -> output.outputStream().use { target ->
                    val buffer = ByteArray(65536); var total = 0L
                    while (true) {
                        checkpoint(); val n = input.read(buffer); if (n < 0) break
                        total += n; require(total <= CatalogRegion.HGT_BYTES) { "Terrain exceeds expected size" }
                        target.write(buffer, 0, n)
                    }
                    require(total == CatalogRegion.HGT_BYTES) { "Incomplete terrain file" }
                } }
                require(hash(output, checkpoint) == expected.getValue(tile.path).getString("sha256")) { "Terrain checksum failed" }
            }
            listOf("peaks.json", "attribution.txt").forEach { name ->
                checkpoint()
                openAsset("${region.assetDirectory}/$name").use { input -> work.resolve(name).outputStream().use { input.copyTo(it) } }
            }
            progress("Preparing ${region.name} for offline use…")
            val archive = work.resolve("region.zip")
            ZipOutputStream(archive.outputStream().buffered()).use { zip ->
                zip.setLevel(0) // Already compressed on the wire; avoid expensive mobile recompression.
                zip.putNextEntry(ZipEntry("manifest.json")); zip.write(region.manifest.toByteArray()); zip.closeEntry()
                expected.keys.forEach { path ->
                    checkpoint(); zip.putNextEntry(ZipEntry(path))
                    work.resolve(path).inputStream().use { input ->
                        val buffer = ByteArray(65536)
                        while (true) { checkpoint(); val n = input.read(buffer); if (n < 0) break; zip.write(buffer, 0, n) }
                    }
                    zip.closeEntry()
                }
            }
            val pack = archive.inputStream().use { store.install(it, checkpoint, progress) }
            region.tiles.forEach { downloader.discard(it.url) }
            return pack
        } finally { work.deleteRecursively() }
    }
    private fun hash(file: File, checkpoint: () -> Unit): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(65536)
            while (true) { checkpoint(); val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}
