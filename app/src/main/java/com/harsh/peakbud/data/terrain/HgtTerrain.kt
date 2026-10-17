package com.harsh.peakbud.data.terrain

import com.harsh.peakbud.core.model.*
import java.io.File
import java.io.RandomAccessFile
import kotlin.math.*

/** HGT access with four open files, 48 MiB of height blocks and 4 MiB of read buffers.
 * Numeric tile keys and cursors for the last tile/block avoid repeated lookup and
 * string allocation on the millions of adjacent samples along sightlines.
 */
class HgtTerrain(private val directory: File, private val bounds: GeoBounds) : Terrain {
    override val datum = VerticalDatum.EGM96
    override val resolutionM = 30.0
    override val heightErrorM = 15.0 // Conservative prototype estimate, not a dataset accuracy guarantee.
    private val tiles = object : LinkedHashMap<Int, Tile>(4, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, Tile>?): Boolean {
            if (size <= 4) return false
            eldest?.value?.close()
            return true
        }
    }
    private val absent = mutableSetOf<Int>()
    private val corrupt = mutableSetOf<Int>()

    private var lastKey: Int = Int.MIN_VALUE
    private var lastTile: Tile? = null

    @Synchronized
    override fun sample(point: GeoPoint): TerrainSample {
        if (!bounds.contains(point)) return TerrainSample.OutsideCoverage
        val latitude = floor(point.latitude).toInt().coerceAtMost(89)
        val normalizedLongitude = if (point.longitude == 180.0) -180.0 else point.longitude
        val longitude = floor(normalizedLongitude).toInt()
        // Integer-coordinate borders may be served by the neighbouring tile.
        // Keep the previous fallback order, including the ±180° seam.
        val latitudeCount = if (point.latitude == latitude.toDouble() && latitude > -90) 2 else 1
        val longitudeCount = if (normalizedLongitude == longitude.toDouble()) 2 else 1
        var invalid = false
        for (latIndex in 0 until latitudeCount) for (lonIndex in 0 until longitudeCount) {
            val lat = latitude - latIndex
            val lon = if (lonIndex == 0) longitude else if (longitude == -180) 179 else longitude - 1
            val key = (lat + 90) * 360 + lon + 180
            val tile = if (lastKey == key) lastTile else {
                if (key in corrupt) { invalid = true; continue }
                if (key in absent) continue
                tiles[key] ?: run {
                    val file = File(directory, tileName(lat, lon))
                    if (!file.isFile) { absent += key; null }
                    else try { Tile(file).also { tiles[key] = it } }
                    catch (_: Exception) { corrupt += key; invalid = true; null }
                }
            }
            if (tile == null) continue
            lastKey = key
            lastTile = tile
            val longitudeDelta = ((normalizedLongitude - lon) + 360) % 360
            val x = longitudeDelta * (tile.side - 1)
            val y = (lat + 1 - point.latitude) * (tile.side - 1)
            return tile.interpolate(x.coerceIn(0.0, tile.side - 1.0), y.coerceIn(0.0, tile.side - 1.0))
        }
        return if (invalid) TerrainSample.NoData else TerrainSample.TileMissing
    }

    @Synchronized
    override fun close() { tiles.values.forEach { it.close() }; tiles.clear(); absent.clear(); corrupt.clear(); lastKey = Int.MIN_VALUE; lastTile = null }

    private class Tile(file: File) : AutoCloseable {
        val side = when (file.length()) {
            3601L * 3601 * 2 -> 3601
            else -> error("Invalid HGT length: ${file.name}")
        }
        private val input = RandomAccessFile(file, "r")
        // Fetch a block's row span in one read. On Android, 128 seek/read pairs per
        // block dominated long-range panoramas even when the file was already cached.
        private val readBuffer = ByteArray(128 * side * 2)
        private val blocks = object : LinkedHashMap<Int, ShortArray>(384, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, ShortArray>?): Boolean = size > 384
        }
        private var lastBlockKey = -1
        private var lastBlock: ShortArray? = null
        private fun value(row: Int, column: Int): Short {
            val blockRow = row / 128
            val blockColumn = column / 128
            val key = blockRow * 32 + blockColumn
            val block = if (key == lastBlockKey) lastBlock!! else blocks.getOrPut(key) {
                val top = blockRow * 128
                val left = blockColumn * 128
                val width = min(128, side - left)
                val values = ShortArray(128 * 128)
                val rows = min(128, side - top)
                input.seek((top.toLong() * side + left) * 2)
                input.readFully(readBuffer, 0, ((rows - 1) * side + width) * 2)
                for (y in 0 until rows) {
                    for (x in 0 until width) {
                        val offset = (y * side + x) * 2
                        values[y * 128 + x] = (((readBuffer[offset].toInt() and 255) shl 8) or (readBuffer[offset + 1].toInt() and 255)).toShort()
                    }
                }
                values
            }
            lastBlockKey = key
            lastBlock = block
            return block[(row % 128) * 128 + column % 128]
        }
        fun interpolate(x: Double, y: Double): TerrainSample {
            val left = floor(x).toInt()
            val top = floor(y).toInt()
            val right = min(left + 1, side - 1)
            val bottom = min(top + 1, side - 1)
            val a = value(top, left); val b = value(top, right); val c = value(bottom, left); val d = value(bottom, right)
            if (a == Short.MIN_VALUE || b == Short.MIN_VALUE || c == Short.MIN_VALUE || d == Short.MIN_VALUE) return TerrainSample.NoData
            val fx = x - left
            val fy = y - top
            return TerrainSample.Valid((a * (1 - fx) + b * fx) * (1 - fy) + (c * (1 - fx) + d * fx) * fy)
        }
        override fun close() { input.close(); blocks.clear(); lastBlock = null; lastBlockKey = -1 }
    }

    companion object {
        fun tileName(latitude: Int, longitude: Int): String =
            "${if (latitude >= 0) "N" else "S"}${abs(latitude).toString().padStart(2, '0')}" +
                "${if (longitude >= 0) "E" else "W"}${abs(longitude).toString().padStart(3, '0')}.hgt"
    }
}
