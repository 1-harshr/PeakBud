package com.harsh.peakbud.core

import com.harsh.peakbud.core.model.*
import com.harsh.peakbud.data.download.RegionDownloader
import com.harsh.peakbud.data.regions.*
import com.harsh.peakbud.data.terrain.HgtTerrain
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.util.zip.GZIPOutputStream
import org.junit.Assert.*
import org.junit.Test

class DownloadAreaTest {
    private val area = DownloadArea.plan(GeoPoint(28.5, 83.5), "My trek")
    private val peakResponse = """{"elements":[{"type":"node","id":42,"lat":28.51,"lon":83.52,"tags":{"natural":"peak","name":"Test summit","ele":"1234 m"}}]}"""
    private class Response(private val bytes: ByteArray) : HttpURLConnection(URL("https://example.test/tile")) {
        override fun connect() = Unit
        override fun disconnect() = Unit
        override fun usingProxy() = false
        override fun getResponseCode() = 200
        override fun getHeaderField(name: String?) = if (name == "Content-Length") bytes.size.toString() else null
        override fun getInputStream() = ByteArrayInputStream(bytes)
    }
    private fun withRoot(block: (File, RegionStore) -> Unit) {
        val root = Files.createTempDirectory("peakbud-custom-area").toFile()
        try { block(root, RegionStore(root.resolve("installed"))) } finally { root.deleteRecursively() }
    }
    private fun compressedTile(size: Long = CatalogRegion.HGT_BYTES): ByteArray {
        val bytes = ByteArrayOutputStream()
        GZIPOutputStream(bytes).use { output ->
            val buffer = ByteArray(65536)
            var left = size
            while (left > 0) { val count = minOf(left, buffer.size.toLong()).toInt(); output.write(buffer, 0, count); left -= count }
        }
        return bytes.toByteArray()
    }
    private fun installer(root: File, store: RegionStore, bytes: ByteArray = compressedTile(), text: String = peakResponse) =
        AreaInstaller(root.resolve("work"), store, RegionDownloader(root.resolve("downloads")) { Response(bytes) }) { address, _, _ ->
            if (address.contains("overpass")) text else "Synthetic terrain, test attribution only."
        }

    @Test fun plansOutsideBundledCatalogueAndCoversTheViewingRadius() {
        val remote = DownloadArea.plan(GeoPoint(-20.5, 140.5), "Outback")
        assertEquals(listOf("S21E140.hgt"), remote.tiles)
        assertTrue(remote.terrain.contains(remote.point))
        assertTrue(remote.terrain.contains(GeoPoint(remote.search.south, remote.search.west)))
        assertTrue(remote.terrain.contains(GeoPoint(remote.search.north, remote.search.east)))
        assertEquals("https://s3.amazonaws.com/elevation-tiles-prod/skadi/S21/S21E140.hgt.gz", DownloadArea.tileUrl(remote.tiles.single()))
    }
    @Test fun rejectsUnsupportedPolarAndDateLineAreas() {
        listOf(GeoPoint(70.0, 20.0), GeoPoint(-56.0, 20.0), GeoPoint(0.0, 179.99)).forEach {
            assertTrue(runCatching { DownloadArea.plan(it, "Unsupported") }.isFailure)
        }
    }
    @Test fun downloadsAndInstallsAUsablePackWithoutACatalogueEntry() = withRoot { root, store ->
        val result = installer(root, store).install(area)
        assertTrue(result.peakNamesAvailable)
        val pack = result.pack
        assertEquals("My trek", pack.name)
        assertTrue(pack.id.startsWith("custom-"))
        assertEquals(1, store.installed().size)
        val peak = store.peaks(pack).single()
        assertEquals("Test summit", peak.name)
        assertEquals(1234.0, peak.publishedHeightM!!, 0.0)
        assertTrue(store.attribution(pack).contains("OpenStreetMap"))
        HgtTerrain(pack.directory.resolve("terrain"), pack.bounds).use { assertEquals(TerrainSample.Valid(0.0), it.sample(area.point)) }
        assertTrue(root.resolve("work").listFiles().orEmpty().isEmpty())
        assertTrue(root.resolve("downloads").listFiles().orEmpty().isEmpty())
    }
    @Test fun unavailablePeakServiceStillAllowsAnExplicitTerrainOnlyResult() = withRoot { root, store ->
        val result = installer(root, store, text = "not JSON").install(area)
        assertFalse(result.peakNamesAvailable)
        assertTrue(store.peaks(result.pack).isEmpty())
        assertEquals(1, store.installed().size)
    }
    @Test fun truncatedTileCannotActivateAnArea() = withRoot { root, store ->
        assertTrue(runCatching { installer(root, store, compressedTile(100)).install(area) }.isFailure)
        assertTrue(store.installed().isEmpty())
        assertTrue(root.resolve("work").listFiles().orEmpty().isEmpty())
    }
    @Test fun cancelledInstallDoesNotReplaceAnExistingPack() = withRoot { root, store ->
        val source = installer(root, store)
        val existing = source.install(area).pack
        var cancelled = false
        assertTrue(runCatching {
            source.install(area, { if (cancelled) throw java.util.concurrent.CancellationException() }) {
                if (it.stage == InstallStage.CHECK_FILES) cancelled = true
            }
        }.isFailure)
        assertEquals(existing.key, store.installed().single().key)
        assertTrue(root.resolve("work").listFiles().orEmpty().isEmpty())
    }
    @Test fun providerTimeoutIsNotMistakenForAnEmptyPeakCatalogue() {
        assertTrue(runCatching { AreaInstaller.parsePeaks("""{"remark":"runtime error: timeout","elements":[]}""", area.search) }.isFailure)
        assertEquals(0, AreaInstaller.parsePeaks("""{"elements":[]}""", area.search).length())
    }
    @Test fun filtersOutsideAndDuplicatePeaksAndPreservesUnknownHeights() {
        val response = """{"elements":[
            {"type":"node","id":1,"lat":28.5,"lon":83.5,"tags":{"natural":"peak","name":"A","ele":"unknown"}},
            {"type":"node","id":1,"lat":28.5,"lon":83.5,"tags":{"natural":"peak","name":"A"}},
            {"type":"node","id":2,"lat":0,"lon":0,"tags":{"natural":"peak","name":"Outside"}},
            {"type":"node","id":3,"lat":28.5,"lon":83.5,"tags":{"natural":"peak"}}
        ]}"""
        val peaks = AreaInstaller.parsePeaks(response, area.search)
        assertEquals(1, peaks.length())
        assertTrue(peaks.getJSONObject(0).isNull("elevationM"))
    }
}
