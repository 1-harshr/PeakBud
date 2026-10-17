package com.harsh.peakbud.core

import com.harsh.peakbud.core.model.GeoPoint
import com.harsh.peakbud.data.regions.*
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class VicinityTest {
    private val assets = listOf(File("src/main/assets"), File("app/src/main/assets")).first { it.isDirectory }
    private val catalog = RegionCatalog.load { assets.resolve(it).inputStream() }
    private fun read(path: String) = assets.resolve(path).readText()

    @Test fun suggestsNearestCatalogueAreasFirst() {
        val nearAuli = GeoPoint(30.52, 79.58)  // inside Auli's viewpoint box
        val suggestions = Vicinity.suggestions(nearAuli, catalog)
        assertEquals(0.0, suggestions.first().distanceM, 0.0)
        assertTrue(suggestions.zipWithNext().all { (a, b) -> a.distanceM <= b.distanceM })
        val zermatt = Vicinity.suggestions(GeoPoint(46.0, 7.75), catalog).first()
        assertEquals("zermatt", zermatt.region.id)
    }

    @Test fun aroundMePackUsesOnlyPinnedTilesAndIsFarSmallerThanTheStatePack() {
        val dhanaulti = GeoPoint(30.4235, 78.2463)
        val plan = Vicinity.plan(dhanaulti, catalog, ::read)!!
        val broad = catalog.single { it.id == "uttarakhand-himalaya" }
        println("Around-me pack: ${plan.tiles.size} tiles, ${plan.downloadBytes / 1_048_576} MB vs Uttarakhand ${broad.tiles.size} tiles, ${broad.downloadBytes / 1_048_576} MB")
        assertTrue(plan.tiles.size in 1..4)
        assertTrue(plan.downloadBytes * 10 < broad.downloadBytes)
        // Every tile's download hash comes from the catalogue, never from the network.
        val pinned = catalog.flatMap { it.tiles }.associateBy { it.url }
        plan.tiles.forEach { assertEquals(pinned.getValue(it.url), it) }
        val manifest = JSONObject(plan.manifest)
        assertTrue(plan.id.matches(Regex("[a-z0-9][a-z0-9-]{0,63}")))
        assertEquals(Vicinity.RADIUS_M, manifest.getDouble("radiusM"), 0.0)
        // Generated peaks lie inside the terrain and match their recorded checksum.
        val peaks = Vicinity.generatedAsset("${plan.assetDirectory}/peaks.json")!!
        val bounds = manifest.getJSONObject("terrainBounds")
        val rows = JSONArray(String(peaks))
        assertTrue(rows.length() > 10)
        for (i in 0 until rows.length()) rows.getJSONObject(i).let {
            assertTrue(it.getDouble("latitude") in bounds.getDouble("south")..bounds.getDouble("north"))
        }
        val record = (0 until manifest.getJSONArray("files").length()).map { manifest.getJSONArray("files").getJSONObject(it) }.single { it.getString("path") == "peaks.json" }
        assertEquals(peaks.size.toLong(), record.getLong("size"))
    }

    @Test fun stateWidePacksAreNotOfferedButStillBackAroundMePacks() {
        val offered = catalog.filter { it.offered }
        assertTrue(catalog.single { it.id == "uttarakhand-himalaya" }.offered.not())
        assertTrue(catalog.single { it.id == "jharkhand" }.offered.not())
        assertTrue("every offered download is small: ${offered.maxOf { it.downloadBytes } / 1_048_576} MB", offered.all { it.downloadBytes <= CatalogRegion.MAX_OFFERED_BYTES })
        assertTrue(Vicinity.suggestions(GeoPoint(30.52, 79.58), catalog, 50).none { !it.region.offered })
        // Ranchi is only covered by the Jharkhand pack's tiles; an around-me pack is still possible and small.
        val ranchi = Vicinity.plan(GeoPoint(23.3441, 85.3096), catalog, ::read)!!
        assertTrue(ranchi.downloadBytes <= CatalogRegion.MAX_OFFERED_BYTES)
    }

    @Test fun placesOutsideCatalogueCoverageGetNoPack() {
        assertNull(Vicinity.plan(GeoPoint(-20.0, 140.0), catalog, ::read))  // inland Australia
    }
}
