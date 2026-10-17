package com.harsh.peakbud.core

import com.harsh.peakbud.core.model.*
import com.harsh.peakbud.data.terrain.HgtTerrain
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

/** Exercise cursor invalidation, rather than timing-dependent performance assertions. */
class HgtTerrainCacheTest {
    private fun fixture(block: (File) -> Unit) {
        val root = Files.createTempDirectory("peakbud-hgt-cache").toFile()
        try { block(root) } finally { root.deleteRecursively() }
    }
    private fun tile(root: File, latitude: Int, longitude: Int, edit: (RandomAccessFile) -> Unit = {}) {
        RandomAccessFile(root.resolve(HgtTerrain.tileName(latitude, longitude)), "rw").use {
            it.setLength(3601L * 3601 * 2)
            edit(it)
        }
    }
    private fun set(file: RandomAccessFile, row: Int, col: Int, height: Int) {
        file.seek((row.toLong() * 3601 + col) * 2)
        file.writeShort(height)
    }
    private fun patch(file: RandomAccessFile, row: Int, col: Int, height: Int) {
        // Include cells adjacent to integer positions rounded by latitude conversion.
        for (r in row - 1..row + 1) for (c in col - 1..col + 1) set(file, r, c, height)
    }
    private fun point(latitude: Int, longitude: Int, row: Double, col: Double) =
        GeoPoint(latitude + 1 - row / 3600.0, longitude + col / 3600.0)

    @Test fun revisitingEvictedTilesNeverUsesAClosedLastTile() = fixture { root ->
        for (longitude in 0..5) tile(root, 0, longitude) { patch(it, 1800, 1800, 100 + longitude) }
        HgtTerrain(root, GeoBounds(0.0, 0.0, 1.0, 6.0)).use { terrain ->
            repeat(3) {
                for (longitude in listOf(0, 1, 2, 3, 4, 5, 0, 5, 2)) {
                    repeat(4) {
                        assertEquals(TerrainSample.Valid(100.0 + longitude), terrain.sample(point(0, longitude, 1800.0, 1800.0)))
                    }
                }
            }
        }
    }

    @Test fun lastBlockCursorSurvivesMoreThanTheBlockCacheCapacity() = fixture { root ->
        val positions = (0..28).flatMap { row -> (0..28).map { col -> row * 128 + 10 to col * 128 + 10 } }
        tile(root, 0, 0) { file ->
            positions.forEachIndexed { index, (row, col) -> patch(file, row, col, index + 100) }
        }
        HgtTerrain(root, GeoBounds(0.0, 0.0, 1.0, 1.0)).use { terrain ->
            for (order in listOf(positions.indices.toList(), positions.indices.reversed().toList())) {
                for (index in order) {
                    val (row, col) = positions[index]
                    assertEquals((100 + index).toDouble(),
                        (terrain.sample(point(0, 0, row.toDouble(), col.toDouble())) as TerrainSample.Valid).heightM, 0.00001)
                }
            }
        }
    }

    @Test fun seamsCorruptFallbackAndNoDataRemainDistinctFromMissingTiles() = fixture { root ->
        tile(root, 0, 179) { file -> set(file, 1800, 3600, 321) }
        tile(root, 0, 0) { file ->
            patch(file, 1800, 3600 - 1, 456)
            set(file, 127, 127, 100); set(file, 127, 128, 200)
            set(file, 128, 127, 300); set(file, 128, 128, -32768)
        }
        root.resolve("N00E001.hgt").writeBytes(byteArrayOf(1))
        HgtTerrain(root, GeoBounds(0.0, -180.0, 1.0, 180.0)).use { terrain ->
            assertEquals(TerrainSample.Valid(321.0), terrain.sample(GeoPoint(0.5, -180.0)))
            assertEquals(TerrainSample.Valid(321.0), terrain.sample(GeoPoint(0.5, 180.0)))
            // Bad primary tile at an exact edge must still try the valid west tile.
            assertEquals(TerrainSample.Valid(456.0), terrain.sample(GeoPoint(0.5, 1.0)))
            assertEquals(TerrainSample.NoData, terrain.sample(point(0, 0, 127.5, 127.5)))
            assertEquals(TerrainSample.NoData, terrain.sample(GeoPoint(0.5, 1.5)))
            assertEquals(TerrainSample.TileMissing, terrain.sample(GeoPoint(0.5, 2.5)))
            assertEquals(TerrainSample.OutsideCoverage, terrain.sample(GeoPoint(-0.5, 0.5)))
        }
    }
}
