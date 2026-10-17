package com.harsh.peakbud.core

import com.harsh.peakbud.core.alignment.*
import org.junit.Assert.*
import org.junit.Test
import javax.imageio.ImageIO
import java.awt.image.BufferedImage
import kotlin.math.*

class SkylineExtractionTest {
    private fun photo(name: String): BufferedImage = ImageIO.read(javaClass.getResourceAsStream("/camera-alignment/$name"))
    private fun extract(image: BufferedImage): Extraction {
        val scale = min(1.0, 640.0 / max(image.width, image.height))
        val w = (image.width * scale).toInt(); val h = (image.height * scale).toInt()
        val pixels = IntArray(w * h) { i -> image.getRGB((i % w / scale).toInt(), (i / w / scale).toInt()) }
        return SkylineExtractor.extract(w, h, pixels)
    }
    @Test fun realClearRidgeFollowsIndependentHumanAnnotations() {
        val image = photo("dhanaulti-himalaya.jpg")
        val result = extract(image)
        assertNotNull("$result", result.skyline)
        val scale = result.skyline!!.width.toDouble() / image.width
        val annotations = javaClass.getResourceAsStream("/camera-alignment/dhanaulti-skyline.csv")!!.bufferedReader().readLines()
            .filter { it.isNotBlank() && !it.startsWith("#") && !it.startsWith("x,") }.map { line -> line.split(',').map { it.toDouble() } }
        val errors = annotations.map { (x, y) ->
            val found = result.skyline!!.points.minBy { abs(it.x - x * scale) }
            abs(found.y / scale - y)
        }.sorted()
        println("Dhanaulti boundary median=${errors[errors.size / 2]}px p90=${errors[errors.size * 9 / 10]}px; independent sparse manual annotations")
        assertTrue("median=$errors", errors[errors.size / 2] <= 10)
        assertTrue("p90=$errors", errors[errors.size * 9 / 10] <= 15)
    }
    @Test fun realNegativeViewsDoNotSupplyAlignmentBoundary() {
        for (name in listOf("sky-only.png", "ground-only.png", "trees-only.png", "ranchi-temple.jpg")) {
            val result = extract(photo(name))
            assertNull("$name: $result", result.skyline)
        }
    }
    @Test fun cloudChallengeViewsAreConservativelyRejected() {
        for (name in listOf("deoria-tal.jpg", "hemkund-clouds.jpg")) {
            val result = extract(photo(name))
            assertNull("$name: $result", result.skyline)
        }
    }
    /** Opt-in visual check: set PEAKBUD_DEBUG_DIR to write each fixture with the extracted boundary drawn in red. */
    @Test fun writeDebugOverlaysWhenRequested() {
        val dir = System.getenv("PEAKBUD_DEBUG_DIR") ?: return
        for (name in listOf("dhanaulti-himalaya.jpg", "deoria-tal.jpg", "hemkund-clouds.jpg", "ranchi-temple.jpg", "sky-only.png", "ground-only.png", "trees-only.png")) {
            val image = photo(name)
            val result = extract(image)
            val scale = min(1.0, 640.0 / max(image.width, image.height))
            val out = BufferedImage(image.width, image.height, BufferedImage.TYPE_INT_RGB)
            out.graphics.drawImage(image, 0, 0, null)
            val g = out.createGraphics(); g.color = java.awt.Color.RED
            result.skyline?.points?.forEach { g.fillRect((it.x / scale).toInt() - 2, (it.y / scale).toInt() - 2, 5, 5) }
            ImageIO.write(out, "png", java.io.File(dir, "overlay-$name.png"))
            println("DEBUG $name -> ${result.failure ?: "${result.skyline!!.points.size} points"} ${SkylineExtractor.lastStats}")
        }
    }
    @Test fun syntheticBlueSkyRoofIsRejectedDespiteStrongEdge() {
        val w = 320; val h = 240
        val pixels = IntArray(w * h) { i ->
            val x = i % w; val y = i / w
            if (y < if (x in 80..240) 95 else 155) 0xff65b7ee.toInt() else 0xff775555.toInt()
        }
        assertNull(SkylineExtractor.extract(w, h, pixels).skyline)
    }
}
