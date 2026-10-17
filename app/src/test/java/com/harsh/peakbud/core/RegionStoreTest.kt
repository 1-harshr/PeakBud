package com.harsh.peakbud.core

import com.harsh.peakbud.data.regions.RegionStore
import com.harsh.peakbud.data.terrain.HgtTerrain
import com.harsh.peakbud.core.model.*
import java.io.*
import java.nio.file.Files
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.json.*
import org.junit.Assert.*
import org.junit.Test

class RegionStoreTest {
    private fun fixture(root: File, badHash: Boolean = false, badPath: Boolean = false, omitTile: Boolean = false): File {
        val tile = root.resolve("source.hgt")
        RandomAccessFile(tile, "rw").use { it.setLength(3601L * 3601 * 2) }
        val hash = MessageDigest.getInstance("SHA-256").run {
            tile.inputStream().use { input -> val buffer = ByteArray(65536); while (true) { val n = input.read(buffer); if (n < 0) break; update(buffer, 0, n) } }
            digest().joinToString("") { "%02x".format(it.toInt() and 255) }
        }
        val peaks = """[{"id":"fixture","name":"Test summit","latitude":28.51,"longitude":83.5,"elevationM":null,"source":"TEST ONLY"}]""".toByteArray()
        val attribution = "Synthetic test raster. Not geographical data.".toByteArray()
        fun record(path: String, data: ByteArray) = JSONObject().put("path", path).put("size", data.size).put("sha256", MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it.toInt() and 255) })
        val files = JSONArray().put(record("peaks.json", peaks)).put(record("attribution.txt", attribution))
        if (!omitTile) files.put(JSONObject().put("path", if (badPath) "../escaped.hgt" else "terrain/N28E083.hgt").put("size", tile.length()).put("sha256", if (badHash) "0".repeat(64) else hash))
        fun bounds(s: Double, w: Double, n: Double, e: Double) = JSONObject().put("south", s).put("west", w).put("north", n).put("east", e)
        val manifest = JSONObject().put("schemaVersion", 1).put("id", "fixture").put("name", "Test only").put("version", "1")
            .put("horizontalCrs", "EPSG:4326").put("verticalDatum", "EGM96").put("radiusM", 1000)
            .put("terrainBounds", bounds(28.0, 83.0, 29.0, 84.0)).put("observerBounds", bounds(28.49, 83.49, 28.51, 83.51))
            .put("viewpoint", JSONObject().put("latitude", 28.5).put("longitude", 83.5)).put("source", "TEST ONLY").put("files", files)
        val zip = root.resolve("fixture.zip")
        ZipOutputStream(zip.outputStream()).use { out ->
            fun bytes(name: String, data: ByteArray) { out.putNextEntry(ZipEntry(name)); out.write(data); out.closeEntry() }
            bytes("manifest.json", manifest.toString().toByteArray())
            bytes("peaks.json", peaks); bytes("attribution.txt", attribution)
            if (!omitTile) {
                out.putNextEntry(ZipEntry(if (badPath) "../escaped.hgt" else "terrain/N28E083.hgt"))
                tile.inputStream().use { it.copyTo(out) }; out.closeEntry()
            }
        }
        return zip
    }
    private fun withRoot(block: (File, RegionStore) -> Unit) {
        val root = Files.createTempDirectory("peakbud-region-test").toFile()
        try { block(root, RegionStore(root.resolve("installed"))) } finally { root.deleteRecursively() }
    }
    @Test fun installsAndReopensAnOfflinePack() = withRoot { root, store ->
        val pack = fixture(root).inputStream().use { store.install(it) }
        assertEquals(1, store.installed().size)
        assertEquals("Test summit", store.peaks(pack).single().name)
        HgtTerrain(pack.directory.resolve("terrain"), pack.bounds).use { assertEquals(TerrainSample.Valid(0.0), it.sample(pack.viewpoint)) }
        val reopened = RegionStore(root.resolve("installed"))
        assertEquals(pack.key, reopened.installed().single().key)
        store.delete(pack)
        assertTrue(store.installed().isEmpty())
    }
    @Test fun checksumMismatchCannotActivateAPartialPack() = withRoot { root, store ->
        val archive = fixture(root, badHash = true)
        assertTrue(runCatching { archive.inputStream().use { store.install(it) } }.isFailure)
        assertTrue(store.installed().isEmpty())
        assertTrue(root.resolve("installed").listFiles().orEmpty().isEmpty())
    }
    @Test fun traversalCannotWriteOutsideStaging() = withRoot { root, store ->
        assertTrue(runCatching { fixture(root, badPath = true).inputStream().use { store.install(it) } }.isFailure)
        assertFalse(root.resolve("escaped.hgt").exists())
        assertTrue(store.installed().isEmpty())
    }
    @Test fun missingTerrainCannotBeInstalledAsComplete() = withRoot { root, store ->
        assertTrue(runCatching { fixture(root, omitTile = true).inputStream().use { store.install(it) } }.isFailure)
        assertTrue(store.installed().isEmpty())
    }
    @Test fun cancelledImportPreservesPreviouslyInstalledPack() = withRoot { root, store ->
        val archive = fixture(root)
        val original = archive.inputStream().use { store.install(it) }
        var checkpoints = 0
        assertTrue(runCatching { archive.inputStream().use { store.install(it, { if (++checkpoints > 6) throw InterruptedException() }) } }.isFailure)
        assertEquals(original.key, store.installed().single().key)
    }
    @Test fun sameSizeCorruptionIsDetectedBeforeUseAfterRestart() = withRoot { root, store ->
        val archive = fixture(root)
        val pack = archive.inputStream().use { store.install(it) }
        store.verify(pack)
        RandomAccessFile(pack.directory.resolve("terrain/N28E083.hgt"), "rw").use { it.seek(500); it.writeShort(1234) }
        val restarted = RegionStore(root.resolve("installed"))
        val failure = runCatching { restarted.verify(restarted.installed().single()) }.exceptionOrNull()
        assertNotNull(failure)
        assertTrue(failure!!.message!!.contains("damaged"))
    }
}
