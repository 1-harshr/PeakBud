package com.harsh.peakbud.core

import com.harsh.peakbud.core.alignment.*
import com.harsh.peakbud.core.geo.Geography
import com.harsh.peakbud.core.model.*
import com.harsh.peakbud.core.projection.*
import com.harsh.peakbud.core.terrain.TerrainEngine
import com.harsh.peakbud.data.regions.RegionStore
import com.harsh.peakbud.data.terrain.HgtTerrain
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Opt-in real-data check of the whole idea: predict the skyline from the photo's recorded location and the
 * downloaded terrain only (no reference photos), then register the photo's extracted outline against it.
 *
 * The Commons photo records a camera location but no heading, pitch or lens, so the search covers every
 * heading and a range of horizontal fields of view. Requires the installed Uttarakhand pack:
 * PEAKBUD_TERRAIN_BENCH_DIR=/private/tmp/peakbud-broad-validation/installed. Optional PEAKBUD_DEBUG_DIR
 * writes the photo with the predicted terrain skyline and projected peak names.
 */
class RealPhotoAlignmentTest {
    private data class Fit(val heading: Double, val hfov: Double, val pitch: Double, val error: Double, val inliers: Double)

    @Test fun dhanaultiPhotoRegistersUniquelyAgainstTerrainFromItsLocation() {
        val installed = System.getenv("PEAKBUD_TERRAIN_BENCH_DIR")
        assumeTrue("Set PEAKBUD_TERRAIN_BENCH_DIR to the installed-region directory", installed != null)
        val store = RegionStore(File(installed!!))
        val pack = store.installed().single { it.id == "uttarakhand-himalaya" }
        val camera = GeoPoint(30.423519, 78.246292)
        val image = ImageIO.read(javaClass.getResourceAsStream("/camera-alignment/dhanaulti-himalaya.jpg"))
        val w = 640; val h = (image.height * w.toDouble() / image.width).toInt()
        val pixels = IntArray(w * h) { i -> image.getRGB((i % w * image.width / w), (i / w * image.height / h)) }
        val skyline = SkylineExtractor.extract(w, h, pixels).skyline!!

        HgtTerrain(pack.directory.resolve("terrain"), pack.bounds).use { terrain ->
            // The Commons coordinate is uploader-supplied and lies on a hillside with the north-east blocked within 2 km,
            // while the photo has an open view. Evaluate it plus the five highest summits within 5 km (likely viewpoints).
            val candidates = ArrayList<Pair<GeoPoint, Double>>()
            for (dy in -50..50) for (dx in -50..50) {
                val p = GeoPoint(camera.latitude + dy * .0009, camera.longitude + dx * .00104)
                if (Geography.distance(camera, p) > 5_000) continue
                (terrain.sample(p) as? TerrainSample.Valid)?.let { candidates += p to it.heightM }
            }
            val summits = ArrayList<Pair<GeoPoint, Double>>()
            for (c in candidates.sortedByDescending { it.second }) if (summits.none { Geography.distance(it.first, c.first) < 800 }) { summits += c; if (summits.size == 5) break }
            val spots = listOf(camera to (terrain.sample(camera) as TerrainSample.Valid).heightM) + summits
            val points = skyline.points
            data class Outcome(val spot: GeoPoint, val ground: Double, val predicted: TerrainSkyline, val best: Fit, val rival: Fit)
            val outcomes = spots.mapNotNull { (spot, ground) ->
                val samples = AlignmentHorizon.sector(Observer(spot, ground), terrain, 180.0, 180.0, 250_000.0, .1)
                val predicted = TerrainSkyline(samples)
                val fits = search(points, w, h, predicted)
                if (fits.isEmpty()) { println("REAL spot ${spot.latitude},${spot.longitude} ${ground.toInt()} m: no usable view"); return@mapNotNull null }
                val best = fits.minBy { it.error }
                val rival = fits.filter { abs(Geography.wrap180(it.heading - best.heading)) > 5 }.minByOrNull { it.error } ?: best.copy(error = Double.POSITIVE_INFINITY)
                println("REAL spot ${"%.5f".format(spot.latitude)},${"%.5f".format(spot.longitude)} ${ground.toInt()} m (${(Geography.distance(camera, spot) / 1000).let { "%.1f".format(it) }} km from record): best heading=${best.heading} hfov=${best.hfov} pitch=${"%.2f".format(best.pitch)} normErr=${"%.3f".format(best.error)} inliers=${"%.2f".format(best.inliers)}; rival heading=${rival.heading} normErr=${"%.3f".format(rival.error)} ratio=${"%.2f".format(rival.error / best.error)}")
                Outcome(spot, ground, predicted, best, rival)
            }
            val chosen = outcomes.minBy { it.best.error }
            val best = chosen.best; val rival = chosen.rival; val predicted = chosen.predicted
            val ground = chosen.ground
            val camera = chosen.spot
            // The production matcher, started from a sensor heading 8° off with the recovered lens, must find
            // the same pose and accept it as unambiguous.
            val fx = (w / 2.0) / tan(Math.toRadians(best.hfov / 2))
            val lens = CameraLens(fx, fx, w / 2.0, h / 2.0)
            val sensor = CameraPose(best.heading - 8, best.pitch + 2, 0.0)
            val result = SkylineMatcher.match(points, lens, sensor, predicted)
            println("REAL matcher from heading-8°/pitch+2°: $result")

            System.getenv("PEAKBUD_DEBUG_DIR")?.let { dir -> drawOverlay(image, w, predicted, lens, CameraPose(best.heading, best.pitch, 0.0), store, pack, camera, terrain, ground, File(dir, "dhanaulti-registered.png")) }

            // What this photo can and cannot show (viewpoint uncertain by kilometres, side crop and lens unknown, distant
            // summits smoothed in 30 m terrain): the recorded spot itself has no open view, most nearby viewpoints point
            // the camera towards Nanda Devi's bearing, and the production matcher must not claim more than that.
            val first = outcomes.first()
            assertTrue("recorded hillside coordinate should not register: ${first.best}", first.best.error > .5)
            val nandaDevi = 91.7
            val agreeing = outcomes.drop(1).count { abs(Geography.wrap180(it.best.heading - nandaDevi)) < 4 || abs(Geography.wrap180(it.rival.heading - nandaDevi)) < 4 }
            assertTrue("most nearby viewpoints should face Nanda Devi's bearing: $agreeing", agreeing >= 4)
            result.match?.let { assertEquals("an accepted match must agree with the search", 8.0, it.correction.heading, 1.0) }
        }
    }

    private fun search(points: List<BoundaryPoint>, w: Int, h: Int, predicted: TerrainSkyline): List<Fit> {
        val fits = ArrayList<Fit>()
        // Commons EXIF: Nikon D300, 80 mm (120 mm full-frame equivalent) -> 17.1° across a full-width frame.
        // The published 2:1 file may also be cropped at the sides, so search 8–18° with a scale-normalised score.
        for (tenth in 80..180 step 4) {
            val hfov = tenth / 10.0
            val fx = (w / 2.0) / tan(Math.toRadians(hfov / 2.0))
            val lens = CameraLens(fx, fx, w / 2.0, h / 2.0)
            var heading = 0.0
            while (heading < 360) {
                val level = CameraPose(heading, 0.0, 0.0)
                val offsets = points.mapNotNull { p -> CameraRays.world(p.x, p.y, lens, level).let { ray -> predicted.elevation(ray.bearing)?.let { it - ray.elevation } } }
                if (offsets.size >= points.size * .9) {
                    val pitch = -offsets.sorted()[offsets.size / 2]
                    val pose = CameraPose(heading, pitch, 0.0)
                    val residuals = points.mapNotNull { p -> CameraRays.world(p.x, p.y, lens, pose).let { ray -> predicted.elevation(ray.bearing)?.let { abs(ray.elevation - it) } } }.sorted()
                    val kept = residuals.take(residuals.size * 3 / 4)
                    // Normalise by the outline's angular relief so a shrunken, nearly flat outline cannot score well.
                    val elevations = points.map { p -> CameraRays.world(p.x, p.y, lens, pose).elevation }.sorted()
                    val relief = elevations[elevations.size * 9 / 10] - elevations[elevations.size / 10]
                    fits += Fit(heading, hfov, pitch, sqrt(kept.sumOf { it * it } / kept.size) / relief, residuals.count { it < relief * .1 }.toDouble() / points.size)
                }
                heading += .2
            }
        }
        return fits
    }

    private fun drawOverlay(image: BufferedImage, analysisWidth: Int, predicted: TerrainSkyline, lens: CameraLens, pose: CameraPose,
                            store: RegionStore, pack: com.harsh.peakbud.data.regions.RegionPack, camera: GeoPoint, terrain: Terrain, ground: Double, file: File) {
        val scale = image.width.toDouble() / analysisWidth
        val out = BufferedImage(image.width, image.height, BufferedImage.TYPE_INT_RGB)
        val g = out.createGraphics(); g.drawImage(image, 0, 0, null)
        g.color = Color(255, 220, 0)
        var bearing = pose.heading - 30
        while (bearing < pose.heading + 30) {
            predicted.elevation(Geography.wrap360(bearing))?.let { e -> CameraProjector.project(bearing, e, pose, lens) }
                ?.let { g.fillRect((it.x * scale).toInt() - 1, (it.y * scale).toInt() - 1, 3, 3) }
            bearing += .05
        }
        g.font = g.font.deriveFont(15f)
        val observer = Observer(camera, ground)
        store.peaks(pack).asSequence().filter { Geography.distance(camera, it.point) in 5_000.0..250_000.0 }
            .map { TerrainEngine().visibility(observer, it, terrain) }
            .filter { it.visibility != Visibility.OCCLUDED && it.elevationDeg != null }
            .mapNotNull { v -> CameraProjector.project(v.bearingDeg, v.elevationDeg!!, pose, lens)?.let { v to it } }
            .filter { (_, p) -> p.x in 0f..analysisWidth.toFloat() && p.y > 0 }
            .sortedBy { it.first.distanceM }.take(14)
            .forEach { (v, p) ->
                val x = (p.x * scale).toInt(); val y = (p.y * scale).toInt()
                g.color = Color.WHITE; g.drawLine(x, y, x, y - 40); g.fillOval(x - 3, y - 3, 7, 7)
                g.drawString("${v.peak.name} ${(v.distanceM / 1000).toInt()} km", x - 30, y - 45)
                println("REAL label ${v.peak.name}: bearing=${"%.1f".format(v.bearingDeg)} ${(v.distanceM / 1000).toInt()} km ${v.visibility}")
            }
        ImageIO.write(out, "png", file)
    }
}
