package com.harsh.peakbud.core

import com.harsh.peakbud.core.geo.Geography
import com.harsh.peakbud.core.model.*
import com.harsh.peakbud.core.terrain.TerrainEngine
import com.harsh.peakbud.data.regions.*
import com.harsh.peakbud.data.download.RegionDownloader
import com.harsh.peakbud.data.terrain.HgtTerrain
import java.io.File
import java.net.HttpURLConnection
import org.junit.Assume.assumeTrue
import org.junit.Assert.*
import org.junit.Test

/** Opt-in real-data verification. Reads developer-cached provider bytes; never downloads during tests. */
class RealRegionalDataTest {
    @Test fun installAndCalculateBothBroadPacksFromPinnedProviderData() {
        val cache = System.getenv("PEAKBUD_SKADI_DIR")
        assumeTrue("Set PEAKBUD_SKADI_DIR to the verified Skadi gzip directory", cache != null)
        val assets = listOf(File("src/main/assets"), File("app/src/main/assets")).first { it.isDirectory }
        val root = File(System.getenv("PEAKBUD_REGIONAL_QA_DIR") ?: (System.getProperty("java.io.tmpdir") ?: ".") + "/peakbud-broad-validation").apply { mkdirs() }
        val store = RegionStore(root.resolve("installed"))
        val catalog = RegionCatalog.load { assets.resolve(it).inputStream() }
        val downloader = RegionDownloader(root.resolve("downloads")) { url ->
            val file = File(cache, url.path.substringAfterLast('/'))
            object : HttpURLConnection(url) {
                override fun connect() = Unit
                override fun disconnect() = Unit
                override fun usingProxy() = false
                override fun getResponseCode() = 200
                override fun getHeaderField(name: String?) = when (name) { "Content-Length" -> file.length().toString(); "ETag" -> "\"verified-local-provider-copy\""; else -> null }
                override fun getInputStream() = file.inputStream()
            }
        }
        for (id in listOf("uttarakhand-himalaya", "jharkhand")) {
            val region = catalog.single { it.id == id }
            val installStart = System.nanoTime()
            val pack = store.installed().firstOrNull { it.id == id && it.directory.resolve("manifest.json").readText() == region.manifest }
                ?: CatalogInstaller(root.resolve("work"),store,downloader) { assets.resolve(it).inputStream() }.install(region)
            store.verify(pack)
            println("REGIONAL QA $id: ${pack.files.size} files, ${region.downloadBytes} wire bytes; install/verify ${(System.nanoTime()-installStart)/1e9}s")
            val peaks = store.peaks(pack)
            HgtTerrain(pack.directory.resolve("terrain"), pack.bounds).use { terrain ->
                val ground = (terrain.sample(pack.viewpoint) as TerrainSample.Valid).heightM
                val start = System.nanoTime()
                val scene = TerrainEngine().calculate(Observer(pack.viewpoint, ground), terrain, peaks, CalculationSettings(pack.radiusM))
                assertTrue(scene.peaks.any { it.distanceM > 50_000.0 })
                assertEquals(720, scene.horizon.size)
                println("REGIONAL QA $id: ${scene.peaks.size} candidates, ${scene.peaks.groupingBy { it.visibility }.eachCount()}, horizon gaps ${scene.horizon.count { !it.complete }}, calculate ${(System.nanoTime()-start)/1e9}s")
            }
        }
        assertTrue(store.installed().map { it.id }.containsAll(listOf("uttarakhand-himalaya", "jharkhand")))
    }
}
