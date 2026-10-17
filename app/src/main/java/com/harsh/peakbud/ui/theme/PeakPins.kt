package com.harsh.peakbud.ui.theme

import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import kotlin.math.*

/** Colours for one pin: the dot and stick, the name half and the height half of the pill. */
data class PinStyle(val stick: Color, val dot: Color, val nameSurface: Color, val nameText: Color,
                    val heightSurface: Color, val heightText: Color, val ring: Color? = null)

/** A peak that may get a pin: its summit on screen and the text of its pill. */
data class PinCandidate<T>(val item: T, val x: Float, val y: Float, val name: String, val height: String?)

/**
 * Still, simple peak markers: a dot on the summit, a vertical stick of fixed length, and a pill tilted up and to the
 * right carrying the name and height. Nothing slides between rows; when two pins would collide, the less important one
 * is simply not labelled. Every pill rises left to right; near the right edge a pill runs off the screen rather than
 * flipping.
 */
object PeakPins {
    private val stickLength = 56.dp
    private val pillHeight = 26.dp
    private val padding = 10.dp
    private const val TILT = 55f
    private val cosT = cos(Math.toRadians(TILT.toDouble())).toFloat()
    private val sinT = sin(Math.toRadians(TILT.toDouble())).toFloat()

    private class Shape(val stickTop: Float, val nameWidth: Float, val length: Float)

    private fun paint(textSizePx: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = textSizePx; typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL) }

    /** Pill size and stick top, shared by [place] and [draw] so collisions are tested on what is actually drawn. */
    private fun DrawScope.shape(paint: Paint, y: Float, name: String, height: String?, top: Float): Shape {
        val pad = padding.toPx(); val thick = pillHeight.toPx()
        val nameWidth = paint.measureText(name) + pad * 2
        val length = nameWidth + (height?.let { paint.measureText(it) + pad * 2 } ?: 0f)
        val rise = length * sinT + thick * cosT / 2
        // Fixed stick, shortened only if the pill would leave the top of the view; it always stays vertical.
        val stickTop = max(y - stickLength.toPx(), min(y - 8.dp.toPx(), top + rise))
        return Shape(stickTop, nameWidth, length)
    }

    /** Corners of the tilted pill starting at the stick top, grown by [grow] on every side. */
    private fun pill(x: Float, s: Shape, r: Float, grow: Float): FloatArray {
        // Along the pill (dx, dy) = (cos, -sin); across it (nx, ny) = (sin, cos).
        val a = -grow; val b = s.length + grow; val h = r + grow
        fun cx(u: Float, v: Float) = x + u * cosT + v * sinT
        fun cy(u: Float, v: Float) = s.stickTop - u * sinT + v * cosT
        return floatArrayOf(cx(a, -h), cy(a, -h), cx(b, -h), cy(b, -h), cx(b, h), cy(b, h), cx(a, h), cy(a, h))
    }

    /** The stick and dot as an upright box. */
    private fun stick(x: Float, y: Float, s: Shape, half: Float) = floatArrayOf(x - half, s.stickTop, x + half, s.stickTop, x + half, y + half, x - half, y + half)

    /** Separating-axis test for two convex quadrilaterals given as x,y corner pairs. */
    private fun overlaps(p: FloatArray, q: FloatArray): Boolean {
        for (poly in arrayOf(p, q)) for (i in 0 until 4) {
            val j = (i + 1) % 4
            val ax = -(poly[j * 2 + 1] - poly[i * 2 + 1]); val ay = poly[j * 2] - poly[i * 2]
            var pMin = Float.POSITIVE_INFINITY; var pMax = Float.NEGATIVE_INFINITY
            var qMin = Float.POSITIVE_INFINITY; var qMax = Float.NEGATIVE_INFINITY
            for (k in 0 until 4) {
                val pp = p[k * 2] * ax + p[k * 2 + 1] * ay; pMin = min(pMin, pp); pMax = max(pMax, pp)
                val qq = q[k * 2] * ax + q[k * 2 + 1] * ay; qMin = min(qMin, qq); qMax = max(qMax, qq)
            }
            if (pMax < qMin || qMax < pMin) return false
        }
        return true
    }

    /**
     * Keeps pins in the given priority order whose pill, stick and dot touch no pin already kept. Pills are parallel but
     * start at different heights, so a horizontal gap alone does not keep them apart.
     */
    fun <T> place(scope: DrawScope, candidates: List<PinCandidate<T>>, textSizePx: Float, top: Float, maxPins: Int = 12): List<PinCandidate<T>> = with(scope) {
        val paint = paint(textSizePx)
        val r = pillHeight.toPx() / 2; val gap = 2.dp.toPx(); val half = 5.5.dp.toPx()
        class Kept(val pill: FloatArray, val stick: FloatArray)
        val kept = ArrayList<Kept>(); val out = ArrayList<PinCandidate<T>>()
        for (c in candidates) {
            if (out.size >= maxPins) break
            val s = shape(paint, c.y, c.name, c.height, top)
            val pill = pill(c.x, s, r, gap); val stick = stick(c.x, c.y, s, half)
            if (kept.any { overlaps(it.pill, pill) || overlaps(it.pill, stick) || overlaps(pill, it.stick) || overlaps(stick, it.stick) }) continue
            kept += Kept(pill, stick); out += c
        }
        out
    }

    /** Draws one pin with its dot at ([x], [y]); [top] is the highest y the pill may reach. Returns the tap area. */
    fun draw(scope: DrawScope, x: Float, y: Float, name: String, height: String?, style: PinStyle, textSizePx: Float, top: Float): RectF = with(scope) {
        val paint = paint(textSizePx)
        val pad = padding.toPx(); val thick = pillHeight.toPx()
        val s = shape(paint, y, name, height, top)
        val stickTop = s.stickTop; val nameWidth = s.nameWidth; val length = s.length
        drawLine(style.stick, Offset(x, y), Offset(x, stickTop), 1.5.dp.toPx())
        drawCircle(Color.Black.copy(alpha = .25f), 5.5.dp.toPx(), Offset(x, y))
        drawCircle(style.dot, 4.dp.toPx(), Offset(x, y))
        style.ring?.let { drawCircle(it, 6.dp.toPx(), Offset(x, y), style = androidx.compose.ui.graphics.drawscope.Stroke(2.dp.toPx())) }

        val canvas = drawContext.canvas.nativeCanvas
        canvas.save()
        canvas.translate(x, stickTop); canvas.rotate(-TILT)
        val r = thick / 2
        val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        // Name half then height half, as one rounded pill.
        val path = android.graphics.Path().apply { addRoundRect(RectF(0f, -r, length, r), r, r, android.graphics.Path.Direction.CW) }
        canvas.save(); canvas.clipPath(path)
        fill.color = style.nameSurface.toArgb(); canvas.drawRect(0f, -r, nameWidth, r, fill)
        if (height != null) { fill.color = style.heightSurface.toArgb(); canvas.drawRect(nameWidth, -r, length, r, fill) }
        canvas.restore()
        val baseline = -(paint.ascent() + paint.descent()) / 2
        paint.color = style.nameText.toArgb(); canvas.drawText(name, pad, baseline, paint)
        if (height != null) { paint.color = style.heightText.toArgb(); canvas.drawText(height, nameWidth + pad, baseline, paint) }
        canvas.restore()

        // Axis-aligned bounds of the tilted pill plus the dot, for taps.
        val corners = pill(x, s, r, 0f)
        val xs = (0 until 4).map { corners[it * 2] }; val ys = (0 until 4).map { corners[it * 2 + 1] }
        return RectF(min(xs.min(), x - r), ys.min(), max(xs.max(), x + r), y + 8.dp.toPx())
    }
}
