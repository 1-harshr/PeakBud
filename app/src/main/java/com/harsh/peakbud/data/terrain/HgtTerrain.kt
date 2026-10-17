package com.harsh.peakbud.data.terrain

import com.harsh.peakbud.core.model.*
import java.io.File
import java.io.RandomAccessFile
import kotlin.math.*

/** HGT access with at most four open tiles and 48 MiB of cached height blocks. */
class HgtTerrain(private val directory: File, private val bounds: GeoBounds) : Terrain {
    override val datum = VerticalDatum.EGM96
    override val resolutionM = 30.0
    override val heightErrorM = 15.0 // Conservative prototype estimate, not a dataset accuracy guarantee.
    private val tiles = object : LinkedHashMap<String, Tile>(4, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Tile>?): Boolean {
            if (size <= 4) return false
            eldest?.value?.close()
            return true
        }
    }
    private val absent = mutableSetOf<String>()
    private val corrupt = mutableSetOf<String>()

    @Synchronized
    override fun sample(point: GeoPoint): TerrainSample {
        if (!bounds.contains(point)) return TerrainSample.OutsideCoverage
        val latitude = floor(point.latitude).toInt().coerceAtMost(89)
        val normalizedLongitude = if (point.longitude == 180.0) -180.0 else point.longitude
        val longitude = floor(normalizedLongitude).toInt()
        val latitudes = if (point.latitude == latitude.toDouble() && latitude > -90) listOf(latitude, latitude - 1) else listOf(latitude)
        val longitudes = if (normalizedLongitude == longitude.toDouble()) listOf(longitude, if (longitude == -180) 179 else longitude - 1) else listOf(longitude)
        var invalid = false
        for (lat in latitudes) for (lon in longitudes) {
            val name = tileName(lat, lon)
            if (name in corrupt) { invalid = true; continue }
            if (name in absent) continue
            val tile = tiles[name] ?: run {
                val file = File(directory, name)
                if (!file.isFile) { absent += name; null }
                else try { Tile(file).also { tiles[name] = it } }
                catch (_: Exception) { corrupt += name; invalid = true; null }
            } ?: continue
            val longitudeDelta = ((normalizedLongitude - lon) + 360) % 360
            val x = longitudeDelta * (tile.side - 1)
            val y = (lat + 1 - point.latitude) * (tile.side - 1)
            return tile.interpolate(x.coerceIn(0.0, tile.side - 1.0), y.coerceIn(0.0, tile.side - 1.0))
        }
        return if (invalid) TerrainSample.NoData else TerrainSample.TileMissing
    }

    @Synchronized
    override fun close() { tiles.values.forEach { it.close() }; tiles.clear(); absent.clear(); corrupt.clear() }

    private class Tile(file: File) : AutoCloseable {
        val side = when (file.length()) {
            3601L * 3601 * 2 -> 3601
            else -> error("Invalid HGT length: ${file.name}")
        }
        private val input = RandomAccessFile(file, "r")
        private val blocks = object : LinkedHashMap<Int, ShortArray>(384, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, ShortArray>?): Boolean = size > 384
        }
        private fun value(row: Int, column: Int): Short {
            val blockRow = row / 128
            val blockColumn = column / 128
            val block = blocks.getOrPut(blockRow * 32 + blockColumn) {
                val top = blockRow * 128
                val left = blockColumn * 128
                val width = min(128, side - left)
                val bytes = ByteArray(width * 2)
                val values = ShortArray(128 * 128)
                for (y in 0 until min(128, side - top)) {
                    input.seek(((top + y).toLong() * side + left) * 2)
                    input.readFully(bytes)
                    for (x in 0 until width) values[y * 128 + x] = (((bytes[x * 2].toInt() and 255) shl 8) or (bytes[x * 2 + 1].toInt() and 255)).toShort()
                }
                values
            }
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
        override fun close() { input.close(); blocks.clear() }
    }

    companion object {
        fun tileName(latitude: Int, longitude: Int): String =
            "${if (latitude >= 0) "N" else "S"}${abs(latitude).toString().padStart(2, '0')}" +
                "${if (longitude >= 0) "E" else "W"}${abs(longitude).toString().padStart(3, '0')}.hgt"
    }
}
