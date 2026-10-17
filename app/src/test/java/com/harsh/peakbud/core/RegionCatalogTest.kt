package com.harsh.peakbud.core

import com.harsh.peakbud.data.download.RegionDownloader
import com.harsh.peakbud.data.regions.*
import com.harsh.peakbud.data.terrain.HgtTerrain
import java.io.*
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.security.MessageDigest
import java.util.zip.GZIPOutputStream
import kotlin.math.*
import org.json.*
import org.junit.Assert.*
import org.junit.Test

class RegionCatalogTest {
    private val assets = listOf(File("src/main/assets"), File("app/src/main/assets")).first { it.isDirectory }
    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }

    @Test fun shippedCatalogHasPinnedSourcesValidPeaksAndFullViewingCoverage() {
        val regions = RegionCatalog.load { assets.resolve(it).inputStream() }
        assertTrue(regions.size >= 30)
        assertTrue(regions.map { it.id }.containsAll(listOf("poon-hill", "gokyo", "auli", "everest-base-camp", "zermatt", "yosemite-glacier-point", "machu-picchu")))
        regions.forEach { region ->
            val manifest = JSONObject(region.manifest)
            val records = manifest.getJSONArray("files")
            val expected = (0 until records.length()).associate { records.getJSONObject(it).let { r -> r.getString("path") to r } }
            listOf("peaks.json", "attribution.txt").forEach { name ->
                val bytes = assets.resolve("${region.assetDirectory}/$name").readBytes()
                assertEquals(expected.getValue(name).getLong("size"), bytes.size.toLong())
                assertEquals(expected.getValue(name).getString("sha256"), hash(bytes))
            }
            val peaks = JSONArray(assets.resolve("${region.assetDirectory}/peaks.json").readText())
            assertTrue("${region.id} has too few peaks", peaks.length() >= 3)
            val ids = (0 until peaks.length()).map { peaks.getJSONObject(it).getString("id") }
            assertEquals(ids.size, ids.distinct().size)
            region.tiles.forEach { tile ->
                assertTrue(tile.url.startsWith("https://s3.amazonaws.com/elevation-tiles-prod/skadi/"))
                assertTrue(tile.downloadSha256.matches(Regex("[a-f0-9]{64}")))
                assertEquals(CatalogRegion.HGT_BYTES, expected.getValue(tile.path).getLong("size"))
            }
            val b = manifest.getJSONObject("observerBounds")
            val angular = Math.toDegrees(manifest.getDouble("radiusM") / 6371008.8) + 1.0 / 3600
            val margin = angular / cos(Math.toRadians(max(abs(b.getDouble("south")), abs(b.getDouble("north"))) + angular))
            for (lat in floor(b.getDouble("south") - angular).toInt()..floor(b.getDouble("north") + angular).toInt())
                for (lon in floor(b.getDouble("west") - margin).toInt()..floor(b.getDouble("east") + margin).toInt())
                    assertTrue("${region.id} missing terrain", "terrain/${HgtTerrain.tileName(lat, lon)}" in expected)
        }
    }

    private fun fixture(block: (File, RegionStore, CatalogRegion, RegionDownloader, Map<String, ByteArray>, () -> Int) -> Unit) {
        val root = Files.createTempDirectory("peakbud-catalog-test").toFile()
        try {
            val raw = ByteArray(CatalogRegion.HGT_BYTES.toInt()) // Explicitly synthetic flat test terrain.
            val compressed = ByteArrayOutputStream().apply { GZIPOutputStream(this).use { it.write(raw) } }.toByteArray()
            val peakBytes = """[{"id":"test","name":"Synthetic summit","latitude":28.51,"longitude":83.5,"elevationM":null,"source":"TEST ONLY"}]""".toByteArray()
            val credits = "SYNTHETIC TEST ONLY".toByteArray()
            fun record(path: String, bytes: ByteArray) = JSONObject().put("path",path).put("size",bytes.size).put("sha256",hash(bytes))
            fun bounds(s: Double,w: Double,n: Double,e: Double) = JSONObject().put("south",s).put("west",w).put("north",n).put("east",e)
            val manifest = JSONObject().put("schemaVersion",1).put("id","test").put("name","Test region").put("version","1")
                .put("horizontalCrs","EPSG:4326").put("verticalDatum","EGM96").put("radiusM",1000)
                .put("observerBounds",bounds(28.49,83.49,28.51,83.51)).put("terrainBounds",bounds(28.0,83.0,29.0,84.0))
                .put("viewpoint",JSONObject().put("latitude",28.5).put("longitude",83.5)).put("source","TEST ONLY")
                .put("files",JSONArray().put(record("terrain/N28E083.hgt",raw)).put(record("peaks.json",peakBytes)).put(record("attribution.txt",credits)))
            val tile = CatalogTile("terrain/N28E083.hgt","https://example.test/tile.gz",compressed.size.toLong(),hash(compressed))
            val region = CatalogRegion("test","Test region","Test","Synthetic","regions/test",listOf(tile),manifest.toString())
            val assetBytes = mapOf("regions/test/peaks.json" to peakBytes,"regions/test/attribution.txt" to credits)
            var requests = 0
            val downloader = RegionDownloader(root.resolve("downloads")) { url ->
                requests++
                object : HttpURLConnection(url) {
                    override fun connect() = Unit
                    override fun disconnect() = Unit
                    override fun usingProxy() = false
                    override fun getResponseCode() = 200
                    override fun getHeaderField(name: String?) = when(name) { "Content-Length" -> compressed.size.toString(); "ETag" -> "\"fixture-v1\""; else -> null }
                    override fun getInputStream() = compressed.inputStream()
                }
            }
            block(root,RegionStore(root.resolve("installed")),region,downloader,assetBytes) { requests }
        } finally { root.deleteRecursively() }
    }
    @Test fun oneActionDownloadsVerifiesAndActivatesAnOfflineRegion() = fixture { root, store, region, downloader, assets, requests ->
        val installer = CatalogInstaller(root.resolve("work"),store,downloader) { assets.getValue(it).inputStream() }
        val pack = installer.install(region)
        assertEquals("test",pack.id)
        assertEquals("Synthetic summit",store.peaks(pack).single().name)
        assertEquals(1,requests())
        assertTrue(downloader.pending().isEmpty())
        assertTrue(root.resolve("work").listFiles().orEmpty().isEmpty())
        RegionStore(root.resolve("installed")).verify(pack)
    }
    @Test fun cancelledPreparationKeepsDownloadedTerrainForRetry() = fixture { root, store, region, downloader, assets, requests ->
        val installer = CatalogInstaller(root.resolve("work"),store,downloader) { assets.getValue(it).inputStream() }
        var cancel = false
        assertTrue(runCatching { installer.install(region, { if (cancel) throw InterruptedException() }) { if (it.startsWith("Getting")) cancel = true } }.isFailure)
        assertTrue(store.installed().isEmpty())
        assertEquals(1,downloader.pending().size)
        installer.install(region)
        assertEquals(1,requests()) // Uses the complete verified cached tile, no second transfer.
        assertEquals(1,store.installed().size)
    }
    @Test fun changedSourceHashCannotActivateARegion() = fixture { root, store, region, downloader, assets, _ ->
        val wrong = region.copy(tiles = region.tiles.map { it.copy(downloadSha256 = "0".repeat(64)) })
        val installer = CatalogInstaller(root.resolve("work"),store,downloader) { assets.getValue(it).inputStream() }
        assertTrue(runCatching { installer.install(wrong) }.isFailure)
        assertTrue(store.installed().isEmpty())
        assertTrue(downloader.pending().isEmpty())
    }
}
