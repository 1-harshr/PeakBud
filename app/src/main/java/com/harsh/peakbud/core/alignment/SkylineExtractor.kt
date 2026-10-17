package com.harsh.peakbud.core.alignment

import kotlin.math.*

data class BoundaryPoint(val x: Double, val y: Double)
data class ImageSkyline(val width: Int, val height: Int, val points: List<BoundaryPoint>)
enum class ExtractionFailure { NO_CLEAR_SKY, NO_BOUNDARY, TOO_LITTLE_SUPPORT, FOREGROUND_OR_FLAT }
data class Extraction(val skyline: ImageSkyline?, val failure: ExtractionFailure?)

/**
 * Conservative mountain–sky boundary extractor for an upright frame (row 0 = top of the scene).
 *
 * The skyline is, by definition, the first place going down from a clear sky where the image stops
 * looking like that sky. Each column learns its own sky colour from the top of the frame and follows
 * the slow brightening towards the horizon, so pale, hazy distant ranges are not mistaken for sky (a
 * fixed "blue is sky" rule does exactly that and then latches onto the darker foreground ridge).
 *
 * Output is boundary evidence only: columns without a clear transition stay missing, bright smooth
 * cloud tops are dropped, and frames without a clear sky band, or whose outline looks like roofs or
 * foliage, are rejected. It is not a semantic mountain classifier; the terrain matcher is the real gate.
 */
object SkylineExtractor {
    /** Diagnostics of the last call, for tests and tuning only. */
    @Volatile var lastStats: String = ""
    fun extract(width: Int, height: Int, argb: IntArray): Extraction {
        require(width in 32..1024 && height in 32..1024 && argb.size == width * height)
        fun fail(reason: ExtractionFailure) = Extraction(null, reason)
        val r = FloatArray(argb.size) { (argb[it] shr 16 and 255).toFloat() }
        val g = FloatArray(argb.size) { (argb[it] shr 8 and 255).toFloat() }
        val b = FloatArray(argb.size) { (argb[it] and 255).toFloat() }
        fun lum(i: Int) = .299f * r[i] + .587f * g[i] + .114f * b[i]

        // 1. A clear sky must fill the top of the frame: bright, smooth and bluish (overcast/white skies,
        //    ground, foliage and buildings fail here, which is deliberately conservative).
        val band = max(3, height / 25)
        var skyLike = 0
        for (y in 0 until band) for (x in 1 until width - 1) {
            val i = y * width + x
            val texture = abs(lum(i + 1) - lum(i - 1))
            if (lum(i) > 85 && b[i] > r[i] + 12 && b[i] >= g[i] - 6 && texture < 10) skyLike++
        }
        if (skyLike < (width - 2) * band * .8) return fail(ExtractionFailure.NO_CLEAR_SKY)

        // 2. Per column, walk down from the sky band with a slowly adapting sky reference. The boundary is
        //    the first row that starts a sustained run of non-sky pixels which does not return to sky
        //    (a thin cloud wisp or contrail returns to sky below and is ignored).
        val step = max(1, width / 200)
        val columns = (step until width - step step step).toList()
        val run = max(3, height / 60)
        val confirm = max(6, height / 14)
        val raw = IntArray(columns.size) { -1 }
        val tolerance = 22f
        for ((c, x) in columns.withIndex()) {
            var rr = 0f; var gg = 0f; var bb = 0f
            for (y in 0 until band) { val i = y * width + x; rr += r[i]; gg += g[i]; bb += b[i] }
            rr /= band; gg /= band; bb /= band
            fun departs(y: Int): Boolean {
                val i = y * width + x
                val left = max(0, x - 1); val right = min(width - 1, x + 1)
                val texture = abs(lum(y * width + right) - lum(y * width + left))
                val dr = r[i] - rr; val dg = g[i] - gg; val db = b[i] - bb
                var distance = sqrt(dr * dr + dg * dg + db * db)
                // Near the horizon a clear sky turns paler and greyer (haze). A smooth shift towards white is
                // still sky; terrain is darker, more saturated or textured. Snow keeps texture at its edges.
                val brighter = dr + dg + db > 0
                val satNow = maxOf(r[i], g[i], b[i]) - minOf(r[i], g[i], b[i])
                val satRef = maxOf(rr, gg, bb) - minOf(rr, gg, bb)
                if (brighter && satNow <= satRef + 4 && texture < 8f) distance *= .35f
                return distance > tolerance || texture > 14f
            }
            var y = band
            while (y < height - confirm) {
                if (!departs(y)) {
                    val i = y * width + x
                    // The sky lightens and greys towards the horizon; follow it slowly.
                    rr += (r[i] - rr) * .12f; gg += (g[i] - gg) * .12f; bb += (b[i] - bb) * .12f
                    y++; continue
                }
                var sustained = true
                for (k in 1 until run) if (!departs(y + k)) { sustained = false; break }
                if (sustained) {
                    var below = 0
                    for (k in 0 until confirm) if (departs(y + k)) below++
                    if (below >= confirm * .85) { raw[c] = y; break }
                }
                y++
            }
        }

        // 3. Drop columns whose "ground" is a bright, smooth, colourless mass directly under the boundary:
        //    that is a cloud top, not terrain. Snow-covered rock keeps texture and is retained.
        for ((c, x) in columns.withIndex()) {
            val y0 = raw[c]; if (y0 < 0) continue
            var bright = 0; var smooth = 0; val depth = min(confirm, height - y0 - 1)
            for (k in 0 until depth) {
                val i = (y0 + k) * width + x
                val sat = maxOf(r[i], g[i], b[i]) - minOf(r[i], g[i], b[i])
                if (lum(i) > 200 && sat < 28) bright++
                val left = (y0 + k) * width + max(0, x - 2); val right = (y0 + k) * width + min(width - 1, x + 2)
                if (abs(lum(right) - lum(left)) < 6) smooth++
            }
            if (depth > 0 && bright > depth * .7 && smooth > depth * .7) raw[c] = -1
        }

        // 4. Remove isolated spikes with a running median over neighbouring supported columns.
        val cleaned = IntArray(raw.size) { -1 }
        for (c in raw.indices) {
            if (raw[c] < 0) continue
            val window = (max(0, c - 3)..min(raw.size - 1, c + 3)).map { raw[it] }.filter { it >= 0 }.sorted()
            val median = window[window.size / 2]
            if (abs(raw[c] - median) <= max(4, height / 40)) cleaned[c] = raw[c]
        }
        val points = columns.indices.filter { cleaned[it] >= 0 }.map { BoundaryPoint(columns[it].toDouble(), cleaned[it].toDouble()) }
        if (points.isEmpty()) return fail(ExtractionFailure.NO_BOUNDARY)
        if (points.size < columns.size * .6) return fail(ExtractionFailure.TOO_LITTLE_SUPPORT)
        // A ridge line is mostly continuous. Cloud edges and tree crowns break into many short pieces.
        var segments = 0; var longest = 0; var current = 0
        for (c in cleaned.indices) {
            val joined = cleaned[c] >= 0 && c > 0 && cleaned[c - 1] >= 0 && abs(cleaned[c] - cleaned[c - 1]) <= max(3, height / 50)
            if (cleaned[c] < 0) { current = 0; continue }
            if (!joined) { segments++; current = 0 }
            current++; longest = max(longest, current)
        }
        lastStats = "support=${"%.2f".format(points.size.toDouble() / columns.size)} segments=$segments longest=${"%.2f".format(longest.toDouble() / columns.size)}"
        if (segments > columns.size / 12 || longest < columns.size * .25) return fail(ExtractionFailure.TOO_LITTLE_SUPPORT)

        // 5. Shape gates: a near-flat line (horizon at sea, a wall), long perfectly level runs (roofs) or
        //    rapid small teeth (foliage) are not mountain evidence.
        val ys = points.map { it.y }.sorted()
        if (ys[ys.size * 9 / 10] - ys[ys.size / 10] < height * .02) return fail(ExtractionFailure.FOREGROUND_OR_FLAT)
        var longestFlat = 0; var flat = 0; var jagged = 0
        for (i in 1 until points.size) {
            val dy = abs(points[i].y - points[i - 1].y)
            flat = if (dy < .5) flat + 1 else 0
            longestFlat = max(longestFlat, flat)
            if (dy > max(4.0, height * .015)) jagged++
        }
        if (longestFlat > points.size * .3 || jagged > points.size * .18) return fail(ExtractionFailure.FOREGROUND_OR_FLAT)
        return Extraction(ImageSkyline(width, height, points), null)
    }
}
